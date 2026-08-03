package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.loan.dto.LeadDtos.CreateLeadRequest;
import com.navix.loan.dto.LeadDtos.DayCount;
import com.navix.loan.dto.LeadDtos.DispositionRequest;
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
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
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

    private final LeadRepository leadRepository;
    private final StaffDirectory staffDirectory;
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
            Integer maxRating) {
        requireLeadWriter();
        Instant fromInst = from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInst = to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<Lead> rows = leadRepository.search(
                blankToNull(q),
                blankToNull(callStatus),
                blankToNull(source),
                createdBy,
                fromInst,
                toInst,
                minRating,
                maxRating);
        Map<Long, String> names = new HashMap<>();
        return rows.stream().map(l -> toView(l, names)).toList();
    }

    @Transactional(readOnly = true)
    public LeadView get(Long id) {
        requireLeadWriter();
        return toView(requireLead(id));
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

        String staffClause = createdBy == null ? "" : " AND created_by_staff_id = ?";
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

        String staffClause = createdBy == null ? "" : " AND created_by_staff_id = ?";
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

    private Lead requireLead(Long id) {
        return leadRepository.findById(id)
                .orElseThrow(() -> new BusinessException("LEAD_NOT_FOUND", "Lead not found: " + id));
    }

    private LeadView toView(Lead l) {
        return toView(l, new HashMap<>());
    }

    private LeadView toView(Lead l, Map<Long, String> names) {
        String name = names.computeIfAbsent(
                l.getCreatedByStaffId(),
                id -> staffDirectory.findStaff(id).map(StaffSummary::name).orElse(null));
        return LeadView.of(l, name);
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
