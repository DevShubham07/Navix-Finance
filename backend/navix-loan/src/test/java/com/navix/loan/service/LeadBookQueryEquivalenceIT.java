package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.common.query.ColumnFilter;
import com.navix.common.query.FilterSpec;
import com.navix.common.query.SqlFacets;
import com.navix.loan.entity.Lead;
import com.navix.loan.repository.LeadRepository;
import com.navix.loan.service.LeadBookQuery.LeadFilter;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.criteria.Predicate;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;

/**
 * The test the {@code LeadService.list} rewrite stands or falls on: the JPA {@code Specification} it
 * replaced and the native {@link LeadBookQuery} return the SAME ids, in the SAME order, with the same
 * count, for the same params — across the DSA exclusion, the date-window edges and the rating bounds.
 *
 * <p>The Specification below is the pre-rewrite predicate copied verbatim, run through real Hibernate
 * and Spring Data against real PostgreSQL. Opt-in (needs a database) and isolated: it works in its own
 * schema, {@value #SCHEMA}, which it drops and recreates — nothing else in the database is touched.
 * <pre>
 * ./mvnw test -pl navix-loan -Dtest=LeadBookQueryEquivalenceIT \
 *     -Dlead.book.it.url=jdbc:postgresql://localhost:5433/navix -Dlead.book.it.password=navix
 * </pre>
 */
@EnabledIfSystemProperty(named = "lead.book.it.url", matches = ".+")
class LeadBookQueryEquivalenceIT {

    private static final String SCHEMA = "lead_book_it";
    /** A source only DSA-owned rows carry: it must never surface through the register or a facet. */
    private static final String DSA_ONLY_SOURCE = "ZZ_DSA_ONLY";

    private static JdbcTemplate jdbc;
    private static LocalContainerEntityManagerFactoryBean emfBean;
    private static LeadRepository repo;
    private static LeadBookQuery query;
    private static final List<Seed> SEEDS = new ArrayList<>();

    private record Seed(long id, String name, String mobile, String source, String callStatus, Integer rating,
            String outcome, long createdBy, Long ownerDsa, Instant createdAt) {
    }

    @BeforeAll
    static void setUp() throws Exception {
        String url = System.getProperty("lead.book.it.url");
        String user = System.getProperty("lead.book.it.user", "navix");
        String pw = System.getProperty("lead.book.it.password", "");
        DriverManagerDataSource admin = new DriverManagerDataSource(url, user, pw);
        JdbcTemplate adminJdbc = new JdbcTemplate((DataSource) admin);
        adminJdbc.execute("drop schema if exists " + SCHEMA + " cascade");
        adminJdbc.execute("create schema " + SCHEMA);

        DriverManagerDataSource ds = new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA, user, pw);
        jdbc = new JdbcTemplate((DataSource) ds);
        jdbc.execute("""
                create table lead (
                    id bigserial primary key,
                    name varchar(160) not null,
                    mobile varchar(10) not null,
                    email varchar(160), city varchar(120), pincode varchar(6), employer varchar(160),
                    monthly_salary_paise bigint, loan_amount_interested_paise bigint,
                    source varchar(24), source_detail varchar(240),
                    call_status varchar(32) not null default 'NOT_CALLED',
                    quality_rating smallint,
                    notes text, remarks text,
                    lead_outcome varchar(16) not null default 'NEW',
                    dsa_note text,
                    created_by_staff_id bigint not null,
                    owner_dsa_id bigint,
                    pan varchar(10),
                    created_at timestamptz not null, created_by varchar(160),
                    updated_at timestamptz, updated_by varchar(160)
                )""");

        emfBean = new LocalContainerEntityManagerFactoryBean();
        emfBean.setDataSource(ds);
        emfBean.setManagedTypes(PersistenceManagedTypes.of(Lead.class.getName()));
        emfBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Properties props = new Properties();
        props.put("hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        emfBean.setJpaProperties(props);
        emfBean.afterPropertiesSet();
        EntityManagerFactory emf = emfBean.getObject();
        repo = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf))
                .getRepository(LeadRepository.class);
        query = new LeadBookQuery(jdbc);
        seed();
    }

    @AfterAll
    static void tearDown() {
        if (emfBean != null) {
            emfBean.destroy();
        }
        if (jdbc != null) {
            jdbc.execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }

    /**
     * Rows planted on every edge: a lead exactly at the window's from, one microsecond before it, one
     * microsecond before the (exclusive) upper bound and exactly at it; stars 1..5 and unrated;
     * DSA-owned rows spread across every other attribute so an excluded one can hide anywhere.
     */
    private static void seed() {
        Random rnd = new Random(42);
        String[] names = {"Ravi Kumar", "RAVI SHARMA", "asha ravi", "Meera", "Sunil Rao", "50%_Off Co"};
        String[] sources = {"DSA", "REFERRAL", "WALK_IN", "OTHER", null};
        String[] statuses = {"NOT_CALLED", "CALLED", "CALLBACK", "NO_ANSWER", "NOT_INTERESTED", "WRONG_NUMBER", "CONNECTED"};
        String[] outcomes = {"NEW", "OUTREACHED", "REJECTED"};
        Instant[] edges = {
                Instant.parse("2026-07-12T23:59:59.999999Z"), Instant.parse("2026-07-13T00:00:00Z"),
                Instant.parse("2026-07-13T00:00:00.000001Z"), Instant.parse("2026-08-12T23:59:59.999999Z"),
                Instant.parse("2026-08-13T00:00:00Z"), Instant.parse("2026-08-13T00:00:00.000001Z")};
        for (int i = 0; i < 700; i++) {
            Instant at = i < 60
                    ? edges[i % edges.length]
                    : Instant.parse("2026-06-01T00:00:00Z").plusSeconds(rnd.nextInt(90 * 86400));
            Long dsa = rnd.nextInt(10) < 3 ? (long) (1 + rnd.nextInt(3)) : null;
            String source = dsa != null && i % 5 == 0 ? DSA_ONLY_SOURCE : sources[rnd.nextInt(sources.length)];
            Integer rating = rnd.nextInt(6) == 0 ? null : 1 + rnd.nextInt(5);
            Seed s = new Seed(0, names[rnd.nextInt(names.length)], String.format("98%08d", rnd.nextInt(100_000_000)),
                    source, statuses[rnd.nextInt(statuses.length)], rating, outcomes[rnd.nextInt(outcomes.length)],
                    1 + rnd.nextInt(4), dsa, at);
            Long id = jdbc.queryForObject("""
                    insert into lead (name, mobile, source, call_status, quality_rating, lead_outcome,
                                      created_by_staff_id, owner_dsa_id, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
                    s.name, s.mobile, s.source, s.callStatus, s.rating, s.outcome, s.createdBy, s.ownerDsa,
                    Timestamp.from(s.createdAt));
            SEEDS.add(new Seed(id, s.name, s.mobile, s.source, s.callStatus, s.rating, s.outcome, s.createdBy,
                    s.ownerDsa, s.createdAt));
        }
    }

    /** The pre-rewrite {@code LeadService.list} Specification, verbatim apart from taking Instants. */
    private static Specification<Lead> legacy(String query, String status, String leadSource, Long createdBy,
            Instant fromInst, Instant toInst, Integer minRating, Integer maxRating, String outcome) {
        return (root, ignored, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isNull(root.get("ownerDsaId")));
            if (query != null) {
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), "%" + query.toLowerCase(Locale.ROOT) + "%"),
                        cb.like(root.get("mobile"), "%" + query + "%")));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("callStatus"), status));
            }
            if (leadSource != null) {
                predicates.add(cb.equal(root.get("source"), leadSource));
            }
            if (createdBy != null) {
                predicates.add(cb.equal(root.get("createdByStaffId"), createdBy));
            }
            if (outcome != null) {
                predicates.add(cb.equal(root.get("leadOutcome"), outcome));
            }
            if (fromInst != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromInst));
            }
            if (toInst != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), toInst));
            }
            if (minRating != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("qualityRating"), minRating));
            }
            if (maxRating != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("qualityRating"), maxRating));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    @Test
    void specificationAndNativeQueryReturnTheSameIdsInTheSameOrder() {
        // No "%" / "_" terms: the native query escapes LIKE wildcards (as CustomerBookQuery does) where the
        // Specification let them act as patterns — the one deliberate divergence, pinned in LeadBookQueryTest.
        String[] qs = {null, "ravi", "RAVI", "98"};
        String[] statuses = {null, "CALLED"};
        String[] sources = {null, "DSA", DSA_ONLY_SOURCE};
        Long[] creators = {null, 2L};
        LocalDate[][] windows = {
                {null, null},
                {LocalDate.of(2026, 7, 13), null},
                {null, LocalDate.of(2026, 8, 12)},
                {LocalDate.of(2026, 7, 13), LocalDate.of(2026, 8, 12)},
                {LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 12)}};
        Integer[][] ratings = {{null, null}, {2, 4}, {3, 3}};
        int compared = 0;
        long nonEmpty = 0;
        for (String q : qs) for (String st : statuses) for (String src : sources) for (Long by : creators)
        for (LocalDate[] w : windows) for (Integer[] r : ratings) {
            String oc = compared % 2 == 0 ? null : "NEW";
            // The service's own normalisation, so the window arithmetic under test is the real one.
            LeadFilter f = LeadService.bookFilter(q, st, src, by, w[0], w[1], r[0], r[1], oc);
            List<Long> expected = repo.findAll(
                            legacy(f.needle(), f.callStatus(), f.source(), f.createdBy(), f.from(), f.to(),
                                    f.minRating(), f.maxRating(), f.outcome()),
                            Sort.by(Sort.Direction.DESC, "id"))
                    .stream().map(Lead::getId).toList();
            List<Long> actual = query.pageIds(f, FilterSpec.empty(), 0, 100_000);
            assertThat(actual).as("ids for %s", f).isEqualTo(expected);
            assertThat(query.count(f, FilterSpec.empty())).as("count for %s", f).isEqualTo(expected.size());
            compared++;
            nonEmpty += expected.isEmpty() ? 0 : 1;
        }
        assertThat(compared).isGreaterThan(500);
        assertThat(nonEmpty).as("the grid must actually select rows").isGreaterThan(100);
    }

    /** The window edges, asserted against the planted rows rather than only against the old query. */
    @Test
    void windowIsHalfOpenAtTheExactInstants() {
        LeadFilter f = LeadService.bookFilter(null, null, null, null,
                LocalDate.of(2026, 7, 13), LocalDate.of(2026, 8, 12), null, null, null);
        List<Long> ids = query.pageIds(f, FilterSpec.empty(), 0, 100_000);
        Instant lo = Instant.parse("2026-07-13T00:00:00Z");
        Instant hi = Instant.parse("2026-08-13T00:00:00Z");
        List<Long> byHand = SEEDS.stream()
                .filter(s -> s.ownerDsa == null && !s.createdAt.isBefore(lo) && s.createdAt.isBefore(hi))
                .map(Seed::id).sorted(java.util.Comparator.reverseOrder()).toList();
        assertThat(ids).isEqualTo(byHand);
        assertThat(SEEDS.stream().filter(s -> s.createdAt.equals(hi) && s.ownerDsa == null).map(Seed::id))
                .as("a lead exactly at the exclusive bound exists and is out").isNotEmpty().doesNotContainAnyElementsOf(ids);
    }

    /** DSA-owned leads are unreachable: by the list, the count, a column filter that names them, and a facet. */
    @Test
    void dsaOwnedLeadsNeverAppear() {
        List<Long> dsaIds = SEEDS.stream().filter(s -> s.ownerDsa != null).map(Seed::id).toList();
        assertThat(dsaIds).isNotEmpty();
        LeadFilter none = LeadService.bookFilter(null, null, null, null, null, null, null, null, null);

        assertThat(query.pageIds(none, FilterSpec.empty(), 0, 100_000)).doesNotContainAnyElementsOf(dsaIds);
        assertThat(query.count(none, FilterSpec.empty()))
                .isEqualTo(SEEDS.stream().filter(s -> s.ownerDsa == null).count());

        // Asking for the DSA-only source via the view param or a column filter still finds nothing.
        assertThat(query.count(LeadService.bookFilter(null, null, DSA_ONLY_SOURCE, null, null, null, null, null, null),
                FilterSpec.empty())).isZero();
        FilterSpec byColumn = FilterSpec.parse(Map.of("f.source", "in:" + DSA_ONLY_SOURCE), LeadBookQuery.FILTER_COLUMNS);
        assertThat(query.count(none, byColumn)).isZero();
        // A forged scope filter is dropped, not honoured.
        FilterSpec forged = FilterSpec.parse(Map.of("f.ownerDsaId", "in:1,2,3"), LeadBookQuery.FILTER_COLUMNS);
        assertThat(query.count(none, forged)).isEqualTo(query.count(none, FilterSpec.empty()));

        SqlFacets.Facet source = query.facet(none, FilterSpec.empty(), "source");
        assertThat(source.values()).doesNotContain(DSA_ONLY_SOURCE).contains("REFERRAL", ColumnFilter.BLANK);
    }

    /** Column filters narrow exactly what a plain reading of the rows says they should. */
    @Test
    void columnFiltersMatchTheRows() {
        LeadFilter none = LeadService.bookFilter(null, null, null, null, null, null, null, null, null);
        List<Seed> visible = SEEDS.stream().filter(s -> s.ownerDsa == null).toList();

        assertThat(count("f.source", "in:DSA,__NULL__", none)).isEqualTo(
                visible.stream().filter(s -> s.source == null || s.source.equals("DSA")).count());
        assertThat(count("f.rating", "num:2..4", none)).isEqualTo(
                visible.stream().filter(s -> s.rating != null && s.rating >= 2 && s.rating <= 4).count());
        assertThat(count("f.name", "text:contains:rav", none)).isEqualTo(
                visible.stream().filter(s -> s.name.toLowerCase(Locale.ROOT).contains("rav")).count());
        assertThat(count("f.callStatus", "in:CALLED,CONNECTED", none)).isEqualTo(
                visible.stream().filter(s -> s.callStatus.equals("CALLED") || s.callStatus.equals("CONNECTED")).count());

        // A facet follows the OTHER columns' filters, and ignores its own so the dropdown can switch.
        FilterSpec rated = FilterSpec.parse(Map.of("f.rating", "num:5..5", "f.source", "in:DSA"), LeadBookQuery.FILTER_COLUMNS);
        assertThat(query.facet(none, rated, "source").values()).contains("REFERRAL");
        assertThat(query.facet(none, rated, "callStatus").values())
                .containsExactlyInAnyOrderElementsOf(visible.stream()
                        .filter(s -> s.rating != null && s.rating == 5 && "DSA".equals(s.source))
                        .map(Seed::callStatus).distinct().toList());
        // Identity columns have no facet at all.
        assertThat(query.facet(none, FilterSpec.empty(), "name").values()).isEmpty();
        assertThat(query.facet(none, FilterSpec.empty(), "mobile").values()).isEmpty();
    }

    private static long count(String key, String value, LeadFilter f) {
        return query.count(f, FilterSpec.parse(Map.of(key, value), LeadBookQuery.FILTER_COLUMNS));
    }
}
