package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.dto.LeadDtos.ImportIssue;
import com.navix.loan.dto.LeadDtos.ImportRow;
import com.navix.loan.entity.Lead;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LeadRepository;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk lead import — loads an unattributed lead list from an uploaded .csv/.xlsx. Imported leads
 * carry no {@code owner_dsa_id} and land with {@code source = "OTHER"}, so they surface on
 * {@code /staff/admin/leads} and the telecalling queue, never the DSA register
 * ({@link DsaAdminService#leads} filters {@code ownerDsaId is not null}).
 *
 * <p><b>Chunked by design.</b> {@link LeadImportJobService} streams the file and calls
 * {@link #processChunk} with at most {@link #CHUNK_ROWS} rows at a time, each in its own
 * transaction. That is not an optimisation, it is what makes a 200k-row list possible at all:
 * <ul>
 *   <li>the dedup lookups are {@code where mobile in (...)}, and PostgreSQL's wire protocol caps a
 *       statement at 65535 bind parameters — one whole file in one {@code IN} list fails outright;
 *   <li>classifying the whole file at once held ~10 concurrent 200k-element collections, which does
 *       not fit the 2 GB task;
 *   <li>a single transaction spanning the whole import pinned one of the default 10 pool
 *       connections for minutes and left RDS with a long {@code idle in transaction}.
 * </ul>
 * A crash now costs at most one chunk, and re-uploading the same file is naturally idempotent
 * because the dedup rules skip everything already landed.
 *
 * <p><b>Row rules are unchanged</b> from the original 2000-row importer and live in exactly one
 * place ({@link #normalizeRow}); {@link LeadFileParser} does structural work only, so CSV and XLSX
 * cannot drift apart.
 */
@Service
@RequiredArgsConstructor
public class LeadImportService {

    /** Rows per transaction. Well under the 65535 bind-parameter ceiling on the dedup lookups. */
    public static final int CHUNK_ROWS = 1000;
    /** Issues retained for the operator. The rest are counted, never stored — see V68. */
    public static final int MAX_ISSUES = 50;

    private static final Pattern MOBILE_PATTERN = Pattern.compile("^[6-9][0-9]{9}$");
    private static final Pattern PAN_PATTERN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    private static final Pattern PINCODE_PATTERN = Pattern.compile("^[1-9][0-9]{5}$");

    private static final String INSERT_LEAD = """
            insert into lead (name, mobile, pan, pincode, email, source, source_detail,
                              call_status, created_by_staff_id, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final LeadRepository leadRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * One import run's accumulating state: the counters the job reports, and the whole-file dedup
     * memory that a chunk on its own cannot have.
     *
     * <p>The seen-sets are what stop the same mobile appearing twice in a 200k file when its twin is
     * 50,000 rows away in a different chunk. ~40 MB at 200k rows, which the task can afford; the
     * alternative (a query per row) cannot.
     */
    @Getter
    public static final class ImportRun {

        private final Long staffId;
        private final String sourceDetail;
        private final boolean merge;

        private final Set<String> seenMobiles = new HashSet<>();
        private final Set<String> seenPans = new HashSet<>();
        private final List<ImportIssue> issues = new ArrayList<>();

        private int processedRows;
        private int insertedCount;
        private int mergedCount;
        private int skippedDuplicates;
        private int skippedCustomers;
        private int issueCount;

        public ImportRun(Long staffId, String sourceDetail, boolean merge) {
            this.staffId = staffId;
            this.sourceDetail = sourceDetail;
            this.merge = merge;
        }

        private void addIssue(ImportIssue issue) {
            issueCount++;
            if (issues.size() < MAX_ISSUES) {
                issues.add(issue);
            }
        }

        /**
         * A row the parser could not even hand over (wrong column count). It still counts as read,
         * so the progress bar reaches the end of the file.
         */
        public void recordParseIssue(ImportIssue issue) {
            processedRows++;
            addIssue(issue);
        }
    }

    /** A parsed row with the 1-based data-row number the operator sees in an issue. */
    public record NumberedRow(int rowNumber, ImportRow row) {
    }

    /**
     * Classify and persist one chunk, in its own transaction.
     *
     * <p>{@code REQUIRES_NEW} so the caller (an async worker, not a request thread) never holds a
     * transaction across the whole file, and so a failure in a later chunk cannot roll back rows
     * already committed and already counted in the job's progress.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processChunk(List<NumberedRow> chunk, ImportRun run) {
        List<NormalizedRow> normalized = new ArrayList<>(chunk.size());
        for (NumberedRow numbered : chunk) {
            run.processedRows++;
            int before = run.issueCount;
            NormalizedRow nr = normalizeRow(numbered.rowNumber(), numbered.row(), run);
            // A bad row is reported and skipped. The original importer rejected the entire file on
            // the first bad line, which is unusable when the file has 200k of them.
            if (run.issueCount == before) {
                normalized.add(nr);
            }
        }

        // In-file de-dup across the WHOLE run: mobile OR pan already seen -> later row dropped. Only
        // remember a pan when non-null, so blank PANs never collide.
        List<NormalizedRow> deduped = new ArrayList<>(normalized.size());
        for (NormalizedRow nr : normalized) {
            boolean seen = run.seenMobiles.contains(nr.mobile())
                    || (nr.pan() != null && run.seenPans.contains(nr.pan()));
            if (seen) {
                run.skippedDuplicates++;
                continue;
            }
            run.seenMobiles.add(nr.mobile());
            if (nr.pan() != null) {
                run.seenPans.add(nr.pan());
            }
            deduped.add(nr);
        }
        if (deduped.isEmpty()) {
            return;
        }

        Set<String> mobiles = new LinkedHashSet<>();
        Set<String> pans = new LinkedHashSet<>();
        for (NormalizedRow nr : deduped) {
            mobiles.add(nr.mobile());
            if (nr.pan() != null) {
                pans.add(nr.pan());
            }
        }

        List<Lead> leadsByMobile = leadRepository.findByMobileIn(mobiles);
        List<Lead> leadsByPan = pans.isEmpty() ? List.of() : leadRepository.findByPanIn(pans);
        List<String> customerPans = pans.isEmpty() ? List.of() : customerProfileRepository.findPansIn(pans);
        List<String> customerMobiles = customerProfileRepository.findMobilesIn(mobiles);

        Map<String, Lead> leadByMobileMap = new HashMap<>();
        for (Lead l : leadsByMobile) {
            leadByMobileMap.putIfAbsent(l.getMobile(), l);
        }
        Map<String, Lead> leadByPanMap = new HashMap<>();
        for (Lead l : leadsByPan) {
            leadByPanMap.putIfAbsent(l.getPan(), l);
        }
        Set<String> customerPanSet = new HashSet<>(customerPans);
        Set<String> customerMobileSet = new HashSet<>(customerMobiles);

        List<NormalizedRow> freshRows = new ArrayList<>();
        List<Lead> toMerge = new ArrayList<>();

        for (NormalizedRow nr : deduped) {
            boolean isCustomer = customerMobileSet.contains(nr.mobile())
                    || (nr.pan() != null && customerPanSet.contains(nr.pan()));
            if (isCustomer) {
                run.skippedCustomers++;
                continue;
            }

            Lead byMobile = leadByMobileMap.get(nr.mobile());
            Lead byPan = nr.pan() == null ? null : leadByPanMap.get(nr.pan());
            Lead existing = byMobile != null ? byMobile : byPan;
            if (existing != null) {
                List<String> fillable = run.merge ? fillableFields(existing, nr, leadByPanMap) : List.of();
                if (fillable.isEmpty()) {
                    run.skippedDuplicates++;
                    continue;
                }
                for (String field : fillable) {
                    if ("email".equals(field)) {
                        existing.setEmail(nr.email());
                    } else if ("pincode".equals(field)) {
                        existing.setPincode(nr.pincode());
                    } else if ("pan".equals(field)) {
                        existing.setPan(nr.pan());
                    }
                }
                toMerge.add(existing);
                continue;
            }

            freshRows.add(nr);
        }

        if (!freshRows.isEmpty()) {
            batchInsert(freshRows, run);
            run.insertedCount += freshRows.size();
        }
        if (!toMerge.isEmpty()) {
            leadRepository.saveAll(toMerge);
            run.mergedCount += toMerge.size();
        }
    }

    /**
     * Insert through {@link JdbcTemplate}, not {@code saveAll}.
     *
     * <p>{@code BaseAuditEntity} generates ids with {@link jakarta.persistence.GenerationType#IDENTITY}
     * against a {@code bigserial}, and Hibernate silently disables JDBC batching for inserts under
     * IDENTITY because it must read each generated key back — {@code saveAll} of 1000 rows is 1000
     * round-trips. Moving the whole app to a sequence generator would touch every table; bypassing
     * JPA for this one hot insert keeps the change to this method. {@code created_at} is set here
     * because JPA auditing does not run on a raw JDBC insert.
     */
    private void batchInsert(List<NormalizedRow> rows, ImportRun run) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.batchUpdate(INSERT_LEAD, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                NormalizedRow nr = rows.get(i);
                ps.setString(1, nr.name());
                ps.setString(2, nr.mobile());
                setNullable(ps, 3, nr.pan());
                setNullable(ps, 4, nr.pincode());
                setNullable(ps, 5, nr.email());
                ps.setString(6, "OTHER");
                ps.setString(7, run.sourceDetail);
                ps.setString(8, "NOT_CALLED");
                ps.setLong(9, run.staffId);
                ps.setTimestamp(10, now);
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    private static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    // ---- row rules (unchanged from the original importer) -----------------------------

    private NormalizedRow normalizeRow(int rowNum, ImportRow r, ImportRun run) {
        String name = r.name() == null ? "" : r.name().trim();
        if (name.isBlank()) {
            run.addIssue(new ImportIssue(rowNum, "name", "is required"));
        } else if (name.length() > 160) {
            run.addIssue(new ImportIssue(rowNum, "name", "must be at most 160 characters"));
        }

        String mobile = normalizeMobile(r.mobile());
        if (!MOBILE_PATTERN.matcher(mobile).matches()) {
            run.addIssue(new ImportIssue(rowNum, "mobile", "must be a valid 10-digit mobile number"));
        }

        String pan = normalizeBlank(r.pan());
        if (pan != null) {
            pan = pan.toUpperCase(Locale.ROOT);
            if (!PAN_PATTERN.matcher(pan).matches()) {
                run.addIssue(new ImportIssue(rowNum, "pan", "must be a valid PAN, e.g. ABCDE1234F"));
            }
        }

        String pincode = normalizeBlank(r.pincode());
        if (pincode != null && !PINCODE_PATTERN.matcher(pincode).matches()) {
            run.addIssue(new ImportIssue(rowNum, "pincode", "must be a valid 6-digit pincode"));
        }

        String email = normalizeBlank(r.email());
        if (email != null) {
            if (email.length() > 160) {
                run.addIssue(new ImportIssue(rowNum, "email", "must be at most 160 characters"));
            } else if (!isValidEmail(email)) {
                run.addIssue(new ImportIssue(rowNum, "email", "must be a valid email address"));
            }
        }

        return new NormalizedRow(rowNum, name, mobile, pan, pincode, email);
    }

    /**
     * The mobile rule — ONE definition. Deliberately NOT {@code normalizeMobile} from elsewhere in
     * the codebase: that helper truncates with a length cap and would silently accept an 11-digit
     * number.
     */
    private static String normalizeMobile(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("[^0-9]", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        }
        digits = digits.replaceFirst("^0+", "");
        return digits;
    }

    private static boolean isValidEmail(String email) {
        int at = email.indexOf('@');
        return at > 0 && email.indexOf('.', at) > at;
    }

    private static String normalizeBlank(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isBlank() ? null : t;
    }

    /**
     * FILL BLANKS ONLY: email/pincode/pan on the existing lead. {@code pan} is fillable only when
     * the existing lead is unattributed ({@code ownerDsaId == null}) and no other lead in this
     * batch already holds that PAN — keeps {@code uq_lead_dsa_pan} (V55) untouched and never lets
     * two leads share a PAN.
     */
    private static List<String> fillableFields(Lead existing, NormalizedRow nr, Map<String, Lead> leadByPanMap) {
        List<String> fillable = new ArrayList<>();
        if (isBlank(existing.getEmail()) && nr.email() != null) {
            fillable.add("email");
        }
        if (isBlank(existing.getPincode()) && nr.pincode() != null) {
            fillable.add("pincode");
        }
        if (isBlank(existing.getPan()) && nr.pan() != null
                && existing.getOwnerDsaId() == null
                && leadByPanMap.get(nr.pan()) == null) {
            fillable.add("pan");
        }
        return fillable;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    static String sourceDetailFor(String fileName) {
        String detail = "Bulk import: " + fileName;
        return detail.length() > 240 ? detail.substring(0, 240) : detail;
    }

    /**
     * Any staff member may upload a lead list — a deliberate product decision, and deliberately the
     * OPPOSITE of the DSA-rejecting {@code requireStaff()} in {@code CustomerController} and friends.
     * A DSA may upload; two guards make that safe and must stay: imported rows are always
     * unattributed (so a bulk upload can never earn commission), and the job result is redacted for
     * anyone without {@code customer:view} (see {@code LeadImportJobService#toView}).
     */
    static Long requireStaffId() {
        CurrentActor actor = ActorContext.get();
        String role = actor == null ? null : actor.role();
        if (role == null || "BORROWER".equals(role) || "ANONYMOUS".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "Staff sign-in required");
        }
        try {
            return Long.valueOf(actor.id());
        } catch (NumberFormatException e) {
            throw new BusinessException("FORBIDDEN_ROLE", "Staff identity required");
        }
    }

    // ---- internal shapes --------------------------------------------------------------

    private record NormalizedRow(int row, String name, String mobile, String pan, String pincode, String email) {
    }
}
