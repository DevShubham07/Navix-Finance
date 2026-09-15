package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.loan.dto.LeadDtos.CreateLeadRequest;
import com.navix.loan.dto.LeadDtos.DayCount;
import com.navix.loan.dto.LeadDtos.DispositionRequest;
import com.navix.loan.dto.LeadDtos.LeadOutcomeRequest;
import com.navix.loan.service.DsaAttributionService.AttributedApplication;
import com.navix.loan.dto.LeadDtos.LeadStats;
import com.navix.loan.dto.LeadDtos.LeadView;
import com.navix.loan.dto.LeadDtos.RatingCount;
import com.navix.loan.dto.LeadDtos.SourceCount;
import com.navix.loan.dto.LeadDtos.StaffCount;
import com.navix.loan.dto.LeadDtos.StatusCount;
import com.navix.loan.dto.LeadDtos.UpdateLeadRequest;
import com.navix.loan.entity.Lead;
import com.navix.loan.repository.LeadRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Telecaller lead intake + disposition. CRUD for TELECALLER/ADMIN; stats tracker for ADMIN.
 */
@Service
@RequiredArgsConstructor
public class LeadService {

    private static final Set<String> SOURCES = Set.of("DSA", "REFERRAL", "WALK_IN", "OTHER");
    private static final Set<String> CALL_STATUSES = Set.of(
            "NOT_CALLED", "CALLED", "CALLBACK", "NO_ANSWER",
            "NOT_INTERESTED", "WRONG_NUMBER", "CONNECTED");
    /**
     * The outcome values a human may SET. {@code CONFIRMED} is absent on purpose — it is resolved on
     * read from the attributed application, never stored (see {@code Lead.leadOutcome} and V70).
     */
    private static final Set<String> LEAD_OUTCOMES = Set.of("NEW", "OUTREACHED", "REJECTED");

    private final LeadRepository leadRepository;
    private final StaffDirectory staffDirectory;
    // Resolves the derived CONFIRMED outcome. Read-only; this service never writes DSA state.
    private final DsaAttributionService attributionService;
    private final JdbcTemplate jdbc;

    @Transactional
    public LeadView create(CreateLeadRequest req) {
        requireLeadWriter();
        Lead l = new Lead();
        l.setName(req.name().trim());
        l.setMobile(req.mobile().trim());
        applyOptionalProfile(
                l,
                req.email(),
                req.city(),
                req.employer(),
                req.monthlySalaryPaise(),
                req.loanAmountInterestedPaise(),
                req.source(),
                req.sourceDetail(),
                req.notes());
        l.setCallStatus("NOT_CALLED");
        l.setCreatedByStaffId(staffId());
        return toView(leadRepository.save(l));
    }

    @Transactional(readOnly = true)
    public List<LeadView> list(
            String q,
            String callStatus,
            String source,
            Long createdBy,
            LocalDate from,
            LocalDate to,
            Integer minRating,
            Integer maxRating,
            String leadOutcome) {
        requireLeadWriter();
        Instant fromInst = from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInst = to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        String query = blankToNull(q);
        String status = blankToNull(callStatus);
        String leadSource = blankToNull(source);
        String outcome = blankToNull(leadOutcome) == null
                ? null : leadOutcome.trim().toUpperCase(Locale.ROOT);
        Specification<Lead> spec = (root, ignored, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            // DSA-owned leads never appear to a telecaller/ADMIN here — they stay out of the
            // telecalling queue entirely; ADMIN reaches them only through /api/admin/dsa/**.
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
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(Predicate[]::new));
        };
        List<Lead> rows = leadRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "id"));
        Map<Long, String> names = new HashMap<>();
        // ONE attribution query for the whole result set, not one per row — this list is unpaged.
        Map<Long, AttributedApplication> attributions = attributionService.attributedApplications(rows);
        return rows.stream()
                .map(l -> toView(l, names, attributions.get(l.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public LeadView get(Long id) {
        requireLeadWriter();
        return toView(requireLead(id));
    }

    /**
     * The outcome a reader should see: the stored value, unless an attributed application exists, in
     * which case {@code CONFIRMED} overlays it.
     *
     * <p>{@code CONFIRMED} is never persisted — attribution is a PAN match resolved on read (V70), so
     * a lead with no PAN can never reach it however it was worked.
     */
    private static String effectiveOutcome(Lead l, AttributedApplication attribution) {
        if (attribution != null && attribution.application() != null) {
            return "CONFIRMED";
        }
        return l.getLeadOutcome();
    }

    @Transactional
    public LeadView update(Long id, UpdateLeadRequest req) {
        requireLeadWriter();
        Lead l = requireLead(id);
        if (req.name() != null && !req.name().isBlank()) {
            l.setName(req.name().trim());
        }
        if (req.mobile() != null && !req.mobile().isBlank()) {
            l.setMobile(req.mobile().trim());
        }
        applyOptionalProfile(
                l,
                req.email(),
                req.city(),
                req.employer(),
                req.monthlySalaryPaise(),
                req.loanAmountInterestedPaise(),
                req.source(),
                req.sourceDetail(),
                req.notes());
        return toView(leadRepository.save(l));
    }

    @Transactional
    public LeadView disposition(Long id, DispositionRequest req) {
        requireLeadWriter();
        String status = req.callStatus() == null ? null : req.callStatus().trim().toUpperCase();
        if (status == null || !CALL_STATUSES.contains(status)) {
            throw new BusinessException("INVALID_CALL_STATUS",
                    "callStatus must be one of " + CALL_STATUSES);
        }
        if (req.qualityRating() != null && (req.qualityRating() < 1 || req.qualityRating() > 5)) {
            throw new BusinessException("INVALID_QUALITY_RATING", "qualityRating must be 1–5");
        }
        Lead l = requireLead(id);
        l.setCallStatus(status);
        l.setQualityRating(req.qualityRating());
        l.setRemarks(req.remarks() == null || req.remarks().isBlank() ? null : req.remarks().trim());
        return toView(leadRepository.save(l));
    }

    @Transactional(readOnly = true)
    public LeadStats stats(LocalDate from, LocalDate to, Long createdBy) {
        requireAdmin();
        Instant fromInst = from == null
                ? LocalDate.now(ZoneOffset.UTC).minusDays(30).atStartOfDay(ZoneOffset.UTC).toInstant()
                : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInst = to == null
                ? LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
                : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        // DSA-owned leads are excluded from every tracker bucket, same as list() — ADMIN sees them
        // only via /api/admin/dsa/**.
        String staffClause = " AND owner_dsa_id IS NULL" + (createdBy == null ? "" : " AND created_by_staff_id = ?");
        List<Object> baseArgs = new ArrayList<>();
        baseArgs.add(java.sql.Timestamp.from(fromInst));
        baseArgs.add(java.sql.Timestamp.from(toInst));
        if (createdBy != null) {
            baseArgs.add(createdBy);
        }

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lead WHERE created_at >= ? AND created_at < ?" + staffClause,
                Long.class,
                baseArgs.toArray());
        long totalCount = total == null ? 0L : total;

        List<StatusCount> byStatus = jdbc.query(
                "SELECT call_status, COUNT(*) FROM lead WHERE created_at >= ? AND created_at < ?"
                        + staffClause + " GROUP BY call_status ORDER BY call_status",
                (rs, i) -> new StatusCount(rs.getString(1), rs.getLong(2)),
                baseArgs.toArray());

        List<SourceCount> bySource = jdbc.query(
                "SELECT COALESCE(source, 'UNKNOWN'), COUNT(*) FROM lead WHERE created_at >= ? AND created_at < ?"
                        + staffClause + " GROUP BY source ORDER BY 1",
                (rs, i) -> new SourceCount(rs.getString(1), rs.getLong(2)),
                baseArgs.toArray());

        List<RatingCount> byRating = jdbc.query(
                "SELECT quality_rating, COUNT(*) FROM lead WHERE created_at >= ? AND created_at < ?"
                        + staffClause + " AND quality_rating IS NOT NULL GROUP BY quality_rating ORDER BY 1",
                (rs, i) -> new RatingCount(rs.getInt(1), rs.getLong(2)),
                baseArgs.toArray());

        Long unrated = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lead WHERE created_at >= ? AND created_at < ?"
                        + staffClause + " AND quality_rating IS NULL",
                Long.class,
                baseArgs.toArray());

        Double avg = jdbc.queryForObject(
                "SELECT AVG(quality_rating) FROM lead WHERE created_at >= ? AND created_at < ?"
                        + staffClause + " AND quality_rating IS NOT NULL",
                Double.class,
                baseArgs.toArray());

        List<DayCount> byDay = buildByDay(fromInst, toInst, createdBy);

        List<StaffCount> byStaffRows = jdbc.query(
                "SELECT created_by_staff_id, COUNT(*) FROM lead WHERE created_at >= ? AND created_at < ?"
                        + staffClause + " GROUP BY created_by_staff_id ORDER BY COUNT(*) DESC",
                (rs, i) -> {
                    Long sid = rs.getLong(1);
                    String name = staffDirectory.findStaff(sid).map(StaffSummary::name).orElse(null);
                    return new StaffCount(sid, name, rs.getLong(2));
                },
                baseArgs.toArray());

        return new LeadStats(
                totalCount,
                byStatus,
                bySource,
                byRating,
                unrated == null ? 0L : unrated,
                byDay,
                byStaffRows,
                avg);
    }

    private List<DayCount> buildByDay(Instant fromInst, Instant toInst, Long createdBy) {
        LocalDate start = fromInst.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate endExclusive = toInst.atZone(ZoneOffset.UTC).toLocalDate();
        if (!endExclusive.isAfter(start)) {
            return List.of();
        }

        String staffClause = " AND owner_dsa_id IS NULL" + (createdBy == null ? "" : " AND created_by_staff_id = ?");
        List<Object> createdArgs = new ArrayList<>();
        createdArgs.add(java.sql.Timestamp.from(fromInst));
        createdArgs.add(java.sql.Timestamp.from(toInst));
        if (createdBy != null) {
            createdArgs.add(createdBy);
        }

        Map<LocalDate, long[]> buckets = new HashMap<>();
        for (LocalDate d = start; d.isBefore(endExclusive); d = d.plusDays(1)) {
            buckets.put(d, new long[] {0L, 0L});
        }

        jdbc.query(
                "SELECT created_at::date, COUNT(*) FROM lead WHERE created_at >= ? AND created_at < ?"
                        + staffClause + " GROUP BY 1",
                rs -> {
                    LocalDate d = rs.getDate(1).toLocalDate();
                    long[] b = buckets.get(d);
                    if (b != null) {
                        b[0] = rs.getLong(2);
                    }
                },
                createdArgs.toArray());

        jdbc.query(
                "SELECT updated_at::date, COUNT(*) FROM lead WHERE updated_at >= ? AND updated_at < ?"
                        + staffClause
                        + " AND call_status <> 'NOT_CALLED' AND updated_at IS NOT NULL GROUP BY 1",
                rs -> {
                    LocalDate d = rs.getDate(1).toLocalDate();
                    long[] b = buckets.get(d);
                    if (b != null) {
                        b[1] = rs.getLong(2);
                    }
                },
                createdArgs.toArray());

        List<DayCount> out = new ArrayList<>();
        for (LocalDate d = start; d.isBefore(endExclusive); d = d.plusDays(1)) {
            long[] b = buckets.get(d);
            out.add(new DayCount(d.toString(), b[0], b[1]));
        }
        return out;
    }

    private void applyOptionalProfile(
            Lead l,
            String email,
            String city,
            String employer,
            Long monthlySalaryPaise,
            Long loanAmountInterestedPaise,
            String source,
            String sourceDetail,
            String notes) {
        if (email != null) {
            l.setEmail(email.isBlank() ? null : email.trim());
        }
        if (city != null) {
            l.setCity(city.isBlank() ? null : city.trim());
        }
        if (employer != null) {
            l.setEmployer(employer.isBlank() ? null : employer.trim());
        }
        if (monthlySalaryPaise != null) {
            if (monthlySalaryPaise < 0) {
                throw new BusinessException("INVALID_AMOUNT", "monthlySalaryPaise must be >= 0");
            }
            l.setMonthlySalaryPaise(monthlySalaryPaise);
        }
        if (loanAmountInterestedPaise != null) {
            if (loanAmountInterestedPaise < 0) {
                throw new BusinessException("INVALID_AMOUNT", "loanAmountInterestedPaise must be >= 0");
            }
            l.setLoanAmountInterestedPaise(loanAmountInterestedPaise);
        }
        if (source != null) {
            String s = source.isBlank() ? null : source.trim().toUpperCase();
            if (s != null && !SOURCES.contains(s)) {
                throw new BusinessException("INVALID_SOURCE", "source must be one of " + SOURCES);
            }
            l.setSource(s);
        }
        if (sourceDetail != null) {
            l.setSourceDetail(sourceDetail.isBlank() ? null : sourceDetail.trim());
        }
        if (notes != null) {
            l.setNotes(notes.isBlank() ? null : notes.trim());
        }
    }

    /**
     * Set the outreach outcome and/or the DSA-visible note on a lead.
     *
     * <p>Deliberately a SEPARATE endpoint from {@link #disposition}, which is replace-semantics: it
     * overwrites {@code qualityRating} and {@code remarks} from whatever the request carries. Folding
     * these two fields into {@code DispositionRequest} would mean the existing telecaller panel —
     * which does not send them — nulled them on every save.
     *
     * <p>This one is PATCH semantics: a null field is left alone, so the two screens can write
     * independently. Passing a blank note clears it.
     */
    @Transactional
    public LeadView outcome(Long id, LeadOutcomeRequest req) {
        requireLeadWriter();
        // Validate before loading, as disposition() does — a malformed request costs no query.
        String outcome = null;
        if (req.leadOutcome() != null) {
            outcome = req.leadOutcome().trim().toUpperCase(Locale.ROOT);
            if (!LEAD_OUTCOMES.contains(outcome)) {
                throw new BusinessException("INVALID_LEAD_OUTCOME",
                        "leadOutcome must be one of " + LEAD_OUTCOMES
                                + " (CONFIRMED is derived from the application, not set here)");
            }
        }
        Lead l = requireTelecallerLead(id);
        if (outcome != null) {
            l.setLeadOutcome(outcome);
        }
        if (req.dsaNote() != null) {
            String note = req.dsaNote().trim();
            l.setDsaNote(note.isEmpty() ? null : note);
        }
        return toView(leadRepository.save(l));
    }

    /**
     * A lead this side of the partition may write to.
     *
     * <p>{@link #requireLead} is a bare {@code findById} with no ownership predicate, while
     * {@link #list} filters {@code ownerDsaId IS NULL}. A telecaller therefore cannot FIND a
     * DSA-owned lead but could still write to one by id. The existing methods inherit that gap;
     * this one does not.
     */
    private Lead requireTelecallerLead(Long id) {
        Lead l = requireLead(id);
        if (l.getOwnerDsaId() != null) {
            throw new BusinessException("LEAD_NOT_FOUND", "Lead not found: " + id);
        }
        return l;
    }

    private Lead requireLead(Long id) {
        return leadRepository.findById(id)
                .orElseThrow(() -> new BusinessException("LEAD_NOT_FOUND", "Lead not found: " + id));
    }

    private LeadView toView(Lead l) {
        return toView(l, new HashMap<>(), attributionService.attributedApplication(l));
    }

    private LeadView toView(Lead l, Map<Long, String> names, AttributedApplication attribution) {
        String name = names.computeIfAbsent(
                l.getCreatedByStaffId(),
                id -> staffDirectory.findStaff(id).map(StaffSummary::name).orElse(null));
        return LeadView.of(l, name, effectiveOutcome(l, attribution));
    }

    private void requireLeadWriter() {
        String role = ActorContext.get().role();
        if (!"TELECALLER".equals(role) && !"ADMIN".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "TELECALLER or ADMIN required");
        }
    }

    private void requireAdmin() {
        if (!"ADMIN".equals(ActorContext.get().role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "ADMIN required");
        }
    }

    private Long staffId() {
        CurrentActor actor = ActorContext.get();
        try {
            return Long.valueOf(actor.id());
        } catch (NumberFormatException e) {
            throw new BusinessException("FORBIDDEN_ROLE", "Staff identity required");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
