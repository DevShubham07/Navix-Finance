package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.loan.domain.DsaCommissionStatus;
import com.navix.loan.dto.DsaDtos.AdminDsaCommissionView;
import com.navix.loan.dto.DsaDtos.AdminDsaLeadView;
import com.navix.loan.dto.DsaDtos.AdminDsaRosterView;
import com.navix.loan.dto.DsaDtos.AdminOutreachView;
import com.navix.loan.dto.DsaDtos.AdminUpdateLeadRequest;
import com.navix.loan.dto.DsaDtos.CreateCommissionRequest;
import com.navix.loan.entity.DsaCommission;
import com.navix.loan.entity.DsaCommissionEvent;
import com.navix.loan.entity.Lead;
import com.navix.loan.entity.Loan;
import com.navix.loan.repository.DsaCommissionEventRepository;
import com.navix.loan.repository.DsaCommissionRepository;
import com.navix.loan.repository.LeadOutreachRepository;
import com.navix.loan.repository.LeadRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.service.DsaAttributionService.AttributedApplication;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN administration of the DSA program: the roster, the company-wide lead register, the
 * commission ledger, and its overrides (pay / void / reassign / manual create), plus the outreach
 * audit log. Separate from {@link DsaService} — ADMIN reaches DSA data exclusively through here,
 * never through the DSA-only {@code /api/dsa/**} surface. Every mutating action here also appends
 * a {@link DsaCommissionEvent} row.
 */
@Service
@RequiredArgsConstructor
public class DsaAdminService {

    private final LeadRepository leadRepository;
    private final DsaCommissionRepository commissionRepository;
    private final DsaCommissionEventRepository commissionEventRepository;
    private final LeadOutreachRepository outreachRepository;
    private final LoanRepository loanRepository;
    private final StaffDirectory staffDirectory;
    private final DsaAttributionService attributionService;

    @Transactional(readOnly = true)
    public List<AdminDsaRosterView> roster() {
        requireAdmin();
        List<StaffSummary> dsas = staffDirectory.listAll("DSA");
        return dsas.stream().map(dsa -> {
            List<Lead> leads = leadRepository.findByOwnerDsaIdOrderByIdDesc(dsa.id());
            long converted = leads.stream()
                    .filter(l -> attributionService.attributedApplication(l).application() != null)
                    .count();
            List<DsaCommission> commissions = commissionRepository.findByDsaStaffIdOrderByIdDesc(dsa.id());
            long accrued = sumByStatus(commissions, DsaCommissionStatus.ACCRUED);
            long payable = sumByStatus(commissions, DsaCommissionStatus.PAYABLE);
            long paid = sumByStatus(commissions, DsaCommissionStatus.PAID);
            return new AdminDsaRosterView(dsa.id(), dsa.name(), dsa.active(),
                    leads.size(), converted, accrued, payable, paid);
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<AdminDsaLeadView> leads(Long dsaId, LocalDate from, LocalDate to, String q) {
        requireAdmin();
        Instant fromInst = from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInst = to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        String query = blankToNull(q);
        Specification<Lead> spec = (root, ignored, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            // DSA leads only — this register is the ADMIN-only counterpart of /staff/leads.
            predicates.add(cb.isNotNull(root.get("ownerDsaId")));
            if (dsaId != null) {
                predicates.add(cb.equal(root.get("ownerDsaId"), dsaId));
            }
            if (fromInst != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromInst));
            }
            if (toInst != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), toInst));
            }
            if (query != null) {
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), "%" + query.toLowerCase(Locale.ROOT) + "%"),
                        cb.like(root.get("pan"), "%" + query.toUpperCase(Locale.ROOT) + "%"),
                        cb.like(root.get("mobile"), "%" + query + "%")));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Map<Long, String> names = new HashMap<>();
        return leadRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "id")).stream()
                .map(l -> AdminDsaLeadView.of(l,
                        names.computeIfAbsent(l.getOwnerDsaId(), this::nameOf)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdminDsaCommissionView> commissions(DsaCommissionStatus status, Long dsaId) {
        requireAdmin();
        List<DsaCommission> rows = commissionRepository.findAll();
        Map<Long, String> dsaNames = new HashMap<>();
        Map<Long, String> pans = new HashMap<>();
        return rows.stream()
                .filter(c -> status == null || c.getStatus() == status)
                .filter(c -> dsaId == null || dsaId.equals(c.getDsaStaffId()))
                .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                .map(c -> AdminDsaCommissionView.of(c,
                        dsaNames.computeIfAbsent(c.getDsaStaffId(), this::nameOf),
                        pans.computeIfAbsent(c.getLeadId(), id -> leadRepository.findById(id).map(Lead::getPan).orElse(null))))
                .toList();
    }

    @Transactional
    public AdminDsaCommissionView pay(Long id, String txnRef) {
        requireAdmin();
        if (txnRef == null || txnRef.isBlank()) {
            throw new BusinessException("MISSING_TXN_REF", "A transaction id is required to mark a commission paid");
        }
        DsaCommission c = requireCommission(id);
        if (c.getStatus() != DsaCommissionStatus.PAYABLE) {
            throw new BusinessException("NOT_PAYABLE", "Only a PAYABLE commission can be marked paid");
        }
        c.setStatus(DsaCommissionStatus.PAID);
        c.setTxnRef(txnRef.trim());
        c.setPaidAt(Instant.now());
        c.setPaidBy(ActorContext.get().name());
        commissionRepository.save(c);
        appendEvent(c.getId(), "PAID", null, null, "txnRef=" + txnRef.trim());
        return viewOf(c);
    }

    @Transactional
    public AdminDsaCommissionView voidCommission(Long id, String reason) {
        requireAdmin();
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("VOID_REASON_REQUIRED", "A reason is required to void a commission");
        }
        DsaCommission c = requireCommission(id);
        if (c.getStatus() == DsaCommissionStatus.PAID) {
            throw new BusinessException("ALREADY_PAID", "A paid commission cannot be voided");
        }
        c.setStatus(DsaCommissionStatus.VOID);
        c.setVoidReason(reason.trim());
        c.setVoidedAt(Instant.now());
        commissionRepository.save(c);
        appendEvent(c.getId(), "VOIDED", null, null, reason.trim());
        return viewOf(c);
    }

    @Transactional
    public AdminDsaCommissionView reassign(Long id, Long toDsaStaffId, String reason) {
        requireAdmin();
        if (toDsaStaffId == null) {
            throw new BusinessException("MISSING_DSA", "toDsaStaffId is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("REASSIGN_REASON_REQUIRED", "A reason is required to reassign a commission");
        }
        DsaCommission c = requireCommission(id);
        if (c.getStatus() == DsaCommissionStatus.PAID) {
            throw new BusinessException("ALREADY_PAID", "A paid commission cannot be reassigned");
        }
        Long from = c.getDsaStaffId();
        c.setDsaStaffId(toDsaStaffId);
        commissionRepository.save(c);
        appendEvent(c.getId(), "REASSIGNED", from, toDsaStaffId, reason.trim());
        return viewOf(c);
    }

    /**
     * Manually create a commission for {@code (dsaId, leadId)} where automatic attribution missed —
     * recomputes the amount from the lead's currently attributed loan. Refuses if a row already
     * exists (the unique-per-lead invariant) or if the lead has no disbursed loan to attribute yet.
     */
    @Transactional
    public AdminDsaCommissionView createManual(CreateCommissionRequest req) {
        requireAdmin();
        if (req.dsaId() == null || req.leadId() == null) {
            throw new BusinessException("MISSING_FIELDS", "dsaId and leadId are required");
        }
        if (commissionRepository.findByLeadId(req.leadId()).isPresent()) {
            throw new BusinessException("COMMISSION_EXISTS", "A commission already exists for this lead");
        }
        Lead lead = leadRepository.findById(req.leadId())
                .orElseThrow(() -> new ResourceNotFoundException("Lead", String.valueOf(req.leadId())));
        AttributedApplication attributed = attributionService.attributedApplication(lead);
        if (attributed.application() == null || attributed.application().getLoanId() == null) {
            throw new BusinessException("NO_DISBURSED_LOAN", "This lead has no disbursed loan to attribute yet");
        }
        Loan loan = loanRepository.findById(attributed.application().getLoanId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan", String.valueOf(attributed.application().getLoanId())));
        long netDisbursed = loan.getNetDisbursed() != null ? loan.getNetDisbursed() : 0L;
        long amount = java.math.BigDecimal.valueOf(netDisbursed)
                .multiply(java.math.BigDecimal.valueOf(DsaCommissionService.DSA_COMMISSION_RATE_BPS))
                .divide(java.math.BigDecimal.valueOf(10_000))
                .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();

        DsaCommission c = new DsaCommission();
        c.setDsaStaffId(req.dsaId());
        c.setLeadId(lead.getId());
        c.setCustomerId(attributed.application().getCustomerId());
        c.setApplicationId(attributed.application().getId());
        c.setLoanId(loan.getId());
        c.setNetDisbursedPaise(netDisbursed);
        c.setRateBps(DsaCommissionService.DSA_COMMISSION_RATE_BPS);
        c.setAmountPaise(amount);
        c.setStatus(DsaCommissionStatus.ACCRUED);
        c.setAccruedAt(Instant.now());
        commissionRepository.save(c);
        appendEvent(c.getId(), "CREATED_MANUALLY", null, req.dsaId(), "manual creation by ADMIN");
        return viewOf(c);
    }

    @Transactional
    public AdminDsaLeadView correctLead(Long id, AdminUpdateLeadRequest req) {
        requireAdmin();
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lead", String.valueOf(id)));
        if (req.pan() != null && !req.pan().isBlank()) {
            lead.setPan(req.pan().trim().toUpperCase(Locale.ROOT));
        }
        if (req.ownerDsaId() != null) {
            lead.setOwnerDsaId(req.ownerDsaId());
        }
        if (req.name() != null && !req.name().isBlank()) {
            lead.setName(req.name().trim());
        }
        if (req.mobile() != null && !req.mobile().isBlank()) {
            lead.setMobile(req.mobile().trim());
        }
        if (req.email() != null) {
            lead.setEmail(req.email().isBlank() ? null : req.email().trim());
        }
        Lead saved = leadRepository.save(lead);
        return AdminDsaLeadView.of(saved, nameOf(saved.getOwnerDsaId()));
    }

    @Transactional(readOnly = true)
    public List<AdminOutreachView> outreach(Long dsaId, Long leadId) {
        requireAdmin();
        List<com.navix.loan.entity.LeadOutreach> rows;
        if (leadId != null && dsaId != null) {
            rows = outreachRepository.findByDsaStaffIdAndLeadIdOrderByIdDesc(dsaId, leadId);
        } else if (leadId != null) {
            rows = outreachRepository.findByLeadIdOrderByIdDesc(leadId);
        } else if (dsaId != null) {
            rows = outreachRepository.findByDsaStaffIdOrderByIdDesc(dsaId);
        } else {
            rows = outreachRepository.findAll();
        }
        return rows.stream().map(AdminOutreachView::of).toList();
    }

    // ---- internals -----------------------------------------------------------------

    private DsaCommission requireCommission(Long id) {
        return commissionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DsaCommission", String.valueOf(id)));
    }

    private void appendEvent(Long commissionId, String action, Long fromDsa, Long toDsa, String notes) {
        DsaCommissionEvent event = new DsaCommissionEvent();
        event.setCommissionId(commissionId);
        event.setAction(action);
        event.setFromDsaStaffId(fromDsa);
        event.setToDsaStaffId(toDsa);
        event.setActorId(actorStaffId());
        event.setNotes(notes);
        event.setAt(Instant.now());
        commissionEventRepository.save(event);
    }

    private AdminDsaCommissionView viewOf(DsaCommission c) {
        String pan = leadRepository.findById(c.getLeadId()).map(Lead::getPan).orElse(null);
        return AdminDsaCommissionView.of(c, nameOf(c.getDsaStaffId()), pan);
    }

    private String nameOf(Long staffId) {
        if (staffId == null) {
            return null;
        }
        return staffDirectory.findStaff(staffId).map(StaffSummary::name).orElse(null);
    }

    private static long sumByStatus(List<DsaCommission> commissions, DsaCommissionStatus status) {
        return commissions.stream().filter(c -> c.getStatus() == status).mapToLong(DsaCommission::getAmountPaise).sum();
    }

    private static Long actorStaffId() {
        try {
            return Long.valueOf(ActorContext.get().id());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void requireAdmin() {
        CurrentActor actor = ActorContext.get();
        if (!"ADMIN".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "ADMIN required");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
