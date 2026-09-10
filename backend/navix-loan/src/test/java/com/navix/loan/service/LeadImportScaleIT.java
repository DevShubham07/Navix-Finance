package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.loan.dto.LeadDtos.ImportIssue;
import com.navix.loan.dto.LeadDtos.ImportRow;
import com.navix.loan.entity.Lead;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LeadRepository;
import com.navix.loan.service.LeadImportService.ImportRun;
import com.navix.loan.service.LeadImportService.NumberedRow;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The claim this change exists to make good on: a 1-2 lakh row list actually imports.
 *
 * <p>Runs the REAL insert path — {@link LeadImportService} driving a {@link JdbcTemplate} against a
 * live PostgreSQL — because the things that broke at scale can only be observed against a real
 * database: the 65535 bind-parameter ceiling on the dedup {@code IN} lookups, the cost of the 200k
 * individual round-trips that {@code GenerationType.IDENTITY} forces on {@code saveAll}, and heap.
 *
 * <p><b>Opt-in, and never points anywhere by default.</b> Run it against a disposable database only:
 * <pre>
 * ./mvnw test -pl navix-loan -am -Dtest=LeadImportScaleIT \
 *     -Dlead.import.it.url=jdbc:postgresql://localhost:5433/navix
 * </pre>
 * It DROPS and recreates the {@code lead} table, so pointing it at anything real would destroy data.
 */
@EnabledIfSystemProperty(named = "lead.import.it.url", matches = ".+")
class LeadImportScaleIT {

    private static final int ROWS = 200_000;

    private JdbcTemplate jdbc;
    private LeadImportService service;

    @BeforeEach
    void freshSchema() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(System.getProperty("lead.import.it.url"));
        ds.setUsername(System.getProperty("lead.import.it.user", "navix"));
        ds.setPassword(System.getProperty("lead.import.it.password", ""));
        jdbc = new JdbcTemplate((DataSource) ds);

        jdbc.execute("drop table if exists lead");
        jdbc.execute("""
                create table lead (
                    id bigserial primary key,
                    name varchar(160) not null,
                    mobile varchar(10) not null,
                    email varchar(160),
                    source varchar(24),
                    source_detail varchar(240),
                    call_status varchar(32) not null default 'NOT_CALLED',
                    pincode varchar(6),
                    pan varchar(10),
                    owner_dsa_id bigint,
                    created_by_staff_id bigint not null,
                    created_at timestamptz not null,
                    created_by varchar(160),
                    updated_at timestamptz,
                    updated_by varchar(160)
                )""");
        jdbc.execute("create index idx_lead_mobile on lead (mobile)");
        jdbc.execute("create index idx_lead_pan on lead (pan) where pan is not null");

        service = new LeadImportService(leadRepository(jdbc), customerRepository(), jdbc);
    }

    @Test
    void importsTwoLakhRows() {
        ImportRun run = importFile(csvOf(ROWS));

        Integer stored = jdbc.queryForObject("select count(*) from lead", Integer.class);
        assertThat(run.getProcessedRows()).isEqualTo(ROWS);
        assertThat(run.getInsertedCount()).isEqualTo(ROWS);
        assertThat(run.getIssueCount()).isZero();
        assertThat(stored).isEqualTo(ROWS);
        // Every row accounted for exactly once.
        assertThat(run.getInsertedCount() + run.getSkippedDuplicates() + run.getSkippedCustomers())
                .isEqualTo(ROWS);
    }

    /** Re-uploading the same list inserts nothing — the property that makes a retry safe. */
    @Test
    void reimportingTheSameFileInsertsNothing() {
        importFile(csvOf(ROWS));
        ImportRun second = importFile(csvOf(ROWS));

        Integer stored = jdbc.queryForObject("select count(*) from lead", Integer.class);
        assertThat(second.getInsertedCount()).isZero();
        assertThat(second.getSkippedDuplicates()).isEqualTo(ROWS);
        assertThat(stored).isEqualTo(ROWS);
    }

    /** A bad row deep in the file must not cost the other 199,999. */
    @Test
    void oneBadRowNearTheEndDoesNotLoseTheFile() {
        String csv = csvOf(ROWS).replace(
                "Person 199998," + mobileFor(199_998),
                "Person 199998,not-a-mobile");

        ImportRun run = importFile(csv);

        assertThat(run.getIssueCount()).isEqualTo(1);
        assertThat(run.getInsertedCount()).isEqualTo(ROWS - 1);
        assertThat(jdbc.queryForObject("select count(*) from lead", Integer.class)).isEqualTo(ROWS - 1);
    }

    private ImportRun importFile(String csv) {
        ImportRun run = new ImportRun(77L, "Bulk import: big.csv", false);
        long startedAt = System.currentTimeMillis();
        Chunker chunker = new Chunker(service, run);
        LeadFileParser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "big.csv", chunker);
        chunker.flush();
        long elapsedMs = Math.max(1, System.currentTimeMillis() - startedAt);
        Runtime runtime = Runtime.getRuntime();
        System.out.printf("imported %,d of %,d rows in %,d ms (%,d rows/s), heap in use %d MB%n",
                run.getInsertedCount(), run.getProcessedRows(), elapsedMs,
                run.getInsertedCount() * 1000L / elapsedMs,
                (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
        return run;
    }

    private static String csvOf(int rows) {
        StringBuilder csv = new StringBuilder("name,contact number,pan card,pincode,emailid\n");
        for (int i = 0; i < rows; i++) {
            csv.append("Person ").append(i).append(',').append(mobileFor(i))
                    .append(",,560001,p").append(i).append("@example.com\n");
        }
        return csv.toString();
    }

    /** 6000000000 + i stays a valid Indian mobile (leading 6-9) and unique across 200k rows. */
    private static String mobileFor(int i) {
        return String.valueOf(6_000_000_000L + i);
    }

    private static final class Chunker implements LeadFileParser.Sink {
        private final LeadImportService service;
        private final ImportRun run;
        private final List<NumberedRow> buffer = new ArrayList<>(LeadImportService.CHUNK_ROWS);

        Chunker(LeadImportService service, ImportRun run) {
            this.service = service;
            this.run = run;
        }

        @Override
        public void row(int rowNumber, ImportRow row) {
            buffer.add(new NumberedRow(rowNumber, row));
            if (buffer.size() >= LeadImportService.CHUNK_ROWS) {
                flush();
            }
        }

        @Override
        public void issue(ImportIssue issue) {
            run.recordParseIssue(issue);
        }

        void flush() {
            if (buffer.isEmpty()) {
                return;
            }
            service.processChunk(List.copyOf(buffer), run);
            buffer.clear();
        }
    }

    // ---- live-SQL stand-ins for the two Spring Data repositories the import reads through ----

    @SuppressWarnings("unchecked")
    private static LeadRepository leadRepository(JdbcTemplate jdbc) {
        return (LeadRepository) Proxy.newProxyInstance(
                LeadRepository.class.getClassLoader(),
                new Class<?>[] {LeadRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByMobileIn" -> selectLeads(jdbc, "mobile", (Collection<String>) args[0]);
                    case "findByPanIn" -> selectLeads(jdbc, "pan", (Collection<String>) args[0]);
                    case "saveAll" -> args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static List<Lead> selectLeads(JdbcTemplate jdbc, String column, Collection<String> values) {
        if (values.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(values.size(), "?"));
        return jdbc.query(
                "select id, name, mobile, pan, pincode, email, owner_dsa_id, source from lead where "
                        + column + " in (" + placeholders + ")",
                values.toArray(),
                (rs, i) -> {
                    Lead lead = new Lead();
                    lead.setId(rs.getLong("id"));
                    lead.setName(rs.getString("name"));
                    lead.setMobile(rs.getString("mobile"));
                    lead.setPan(rs.getString("pan"));
                    lead.setPincode(rs.getString("pincode"));
                    lead.setEmail(rs.getString("email"));
                    lead.setOwnerDsaId((Long) rs.getObject("owner_dsa_id"));
                    lead.setSource(rs.getString("source"));
                    return lead;
                });
    }

    /** No customer_profile table here — the import only asks it "is this mobile/PAN already ours". */
    private static CustomerProfileRepository customerRepository() {
        return (CustomerProfileRepository) Proxy.newProxyInstance(
                CustomerProfileRepository.class.getClassLoader(),
                new Class<?>[] {CustomerProfileRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findPansIn", "findMobilesIn" -> List.of();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
