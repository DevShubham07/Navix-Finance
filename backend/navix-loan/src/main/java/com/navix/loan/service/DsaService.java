package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.domain.DsaCommissionStatus;
import com.navix.loan.dto.DsaDtos.CreateDsaLeadRequest;
import com.navix.loan.dto.DsaDtos.DsaCommissionView;
import com.navix.loan.dto.DsaDtos.DsaEarningsSummary;
import com.navix.loan.dto.DsaDtos.DsaLeadView;
import com.navix.loan.dto.DsaDtos.OutreachRequest;
import com.navix.loan.dto.DsaDtos.OutreachResultView;
import com.navix.loan.dto.DsaDtos.UpdateDsaLeadRequest;
import com.navix.loan.entity.DsaCommission;
import com.navix.loan.entity.DsaLeadRejection;
import com.navix.loan.entity.Lead;
import com.navix.loan.entity.Loan;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.DsaCommissionRepository;
import com.navix.loan.repository.DsaLeadRejectionRepository;
import com.navix.loan.repository.LeadRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.service.DsaAttributionService.AttributedApplication;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The DSA-facing portal ({@code /api/dsa/**}): lead intake, own-lead reads/edits, outreach, and the
 * DSA's own commission/earnings view. Deliberately a separate service from {@code LeadService} — a
 * DSA never shares an endpoint with a telecaller, so there is no code path for one to reach the
 * other's data. Every read/write is scoped by {@code ActorContext.get().id()}; nothing here ever
 * trusts a client-supplied DSA/owner id.
 *
 * <p>Strictly DSA-only (no ADMIN bypass): ADMIN reaches DSA leads/commissions only through the
 * separate {@code DsaAdminService} / {@code /api/admin/dsa/**} endpoints, so the isolation boundary
 * here has no exception to reason about.
 */
@Service
@RequiredArgsConstructor
public class DsaService {

    /** Lead-creation rate limit — keeps PAN-enumeration bulk attempts bounded and visible. */
    private static final int MAX_LEADS_PER_DAY = 30;

    private final LeadRepository leadRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final DsaLeadRejectionRepository rejectionRepository;
    private final DsaCommissionRepository commissionRepository;
    private final LoanRepository loanRepository;
    private final DsaAttributionService attributionService;
    private final LeadOutreachService outreachService;

    @Transactional
    public DsaLeadView createLead(CreateDsaLeadRequest req) {
        Long dsaId = requireDsaId();
        String pan = req.pan().trim().toUpperCase(Locale.ROOT);

        Instant since = Instant.now().minus(1, ChronoUnit.DAYS);
        if (leadRepository.countByOwnerDsaIdAndCreatedAtAfter(dsaId, since) >= MAX_LEADS_PER_DAY) {
            throw new BusinessException("LEAD_RATE_LIMITED", "You've reached today's lead-creation limit");
        }

        if (leadRepository.findByPanAndOwnerDsaIdNotNull(pan).isPresent()) {
            recordRejection(dsaId, pan, "OTHER_DSA");
            throw alreadyKnown();
        }
        if (customerProfileRepository.existsByPan(pan)) {
            recordRejection(dsaId, pan, "EXISTING_CUSTOMER");
            throw alreadyKnown();
        }

        Lead lead = new Lead();
        lead.setPan(pan);
        lead.setName(req.name().trim());
        lead.setMobile(req.mobile().trim());
        lead.setEmail(blankToNull(req.email()));
        lead.setCity(blankToNull(req.city()));
        lead.setEmployer(blankToNull(req.employer()));
        lead.setMonthlySalaryPaise(nonNegative(req.monthlySalaryPaise()));
        lead.setLoanAmountInterestedPaise(nonNegative(req.loanAmountInterestedPaise()));
        lead.setNotes(blankToNull(req.notes()));
        lead.setSource("DSA");
        lead.setCallStatus("NOT_CALLED");
        lead.setCreatedByStaffId(dsaId);
        lead.setOwnerDsaId(dsaId);
        Lead saved = leadRepository.save(lead);
        return toView(saved);
    }

    /**
     * Everything this DSA may see: leads they entered AND leads they uploaded (V70). Scoped, as
     * everywhere in this service, by {@code ActorContext.get().id()} — never a client-supplied id.
     */
    @Transactional(readOnly = true)
    public List<DsaLeadView> list(String q, String status) {
        Long dsaId = requireDsaId();
        String query = blankToNull(q) == null ? null : q.trim().toLowerCase(Locale.ROOT);
        String statusFilter = blankToNull(status);
        List<Lead> leads =
                leadRepository.findByOwnerDsaIdOrCreatedByStaffIdOrderByIdDesc(dsaId, dsaId);
        // One attribution query for the lot. Per-lead was fine while this was only hand-entered leads
        // (30/day rate limit); an uploaded list is unbounded.
        Map<Long, AttributedApplication> attributions =
                attributionService.attributedApplications(leads);
        return leads.stream()
                .map(l -> toView(l, attributions.get(l.getId())))
                .filter(v -> query == null
                        || contains(v.name(), query)
                        || contains(v.pan(), query)
                        || contains(v.mobile(), query))
                .filter(v -> statusFilter == null || v.status().name().equalsIgnoreCase(statusFilter))
                .toList();
    }

    /**
     * Null-safe substring match. PAN is mandatory on a DSA-entered lead but OPTIONAL on an uploaded
     * one, so searching over uploaded leads used to NPE on {@code v.pan().toLowerCase()} the moment
     * they became visible here.
     */
    private static boolean contains(String haystack, String lowercaseNeedle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowercaseNeedle);
    }

    @Transactional(readOnly = true)
    public DsaLeadView get(Long id) {
        Long dsaId = requireDsaId();
        return toView(requireVisibleLead(id, dsaId));
    }

    @Transactional
    public DsaLeadView update(Long id, UpdateDsaLeadRequest req) {
        Long dsaId = requireDsaId();
        Lead lead = requireOwnLead(id, dsaId);
        if (req.name() != null && !req.name().isBlank()) {
            lead.setName(req.name().trim());
        }
        if (req.mobile() != null && !req.mobile().isBlank()) {
            lead.setMobile(req.mobile().trim());
        }
        if (req.email() != null) {
            lead.setEmail(blankToNull(req.email()));
        }
        if (req.city() != null) {
            lead.setCity(blankToNull(req.city()));
        }
        if (req.employer() != null) {
            lead.setEmployer(blankToNull(req.employer()));
        }
        if (req.monthlySalaryPaise() != null) {
            lead.setMonthlySalaryPaise(nonNegative(req.monthlySalaryPaise()));
        }
        if (req.loanAmountInterestedPaise() != null) {
            lead.setLoanAmountInterestedPaise(nonNegative(req.loanAmountInterestedPaise()));
        }
        if (req.notes() != null) {
            lead.setNotes(blankToNull(req.notes()));
        }
        return toView(leadRepository.save(lead));
    }

    @Transactional
    public OutreachResultView outreach(Long id, OutreachRequest req) {
        Long dsaId = requireDsaId();
        Lead lead = requireOwnLead(id, dsaId);
        return outreachService.send(lead, dsaId, req);
    }

    @Transactional(readOnly = true)
    public List<DsaCommissionView> commissions() {
        Long dsaId = requireDsaId();
        return commissionRepository.findByDsaStaffIdOrderByIdDesc(dsaId).stream()
                .map(c -> DsaCommissionView.of(c, leadRepository.findById(c.getLeadId()).orElse(null)))
                .toList();
    }

    /**
     * Deliberately still OWNER-scoped, unlike {@link #list}: this is the commission page, and only a
     * lead the DSA entered can ever earn. Counting uploaded leads under "Leads added" here would show
     * a large number next to zero earnings and read as money owed.
     */
    @Transactional(readOnly = true)
    public DsaEarningsSummary earnings() {
        Long dsaId = requireDsaId();
        List<Lead> leads = leadRepository.findByOwnerDsaIdOrderByIdDesc(dsaId);
        Map<Long, AttributedApplication> attributions =
                attributionService.attributedApplications(leads);
        long converted = leads.stream()
                .filter(l -> {
                    AttributedApplication a = attributions.get(l.getId());
                    return a != null && a.application() != null;
                })
                .count();
        List<DsaCommission> commissions = commissionRepository.findByDsaStaffIdOrderByIdDesc(dsaId);
        long accrued = sumByStatus(commissions, DsaCommissionStatus.ACCRUED);
        long payable = sumByStatus(commissions, DsaCommissionStatus.PAYABLE);
        long paid = sumByStatus(commissions, DsaCommissionStatus.PAID);
        return new DsaEarningsSummary(leads.size(), converted, accrued, payable, paid);
    }

    // ---- internals -----------------------------------------------------------------

    private DsaLeadView toView(Lead lead) {
        return toView(lead, attributionService.attributedApplication(lead));
    }

    private DsaLeadView toView(Lead lead, AttributedApplication resolved) {
        AttributedApplication attributed =
                resolved == null ? AttributedApplication.notApplied() : resolved;
        Long netDisbursedPaise = null;
        Long commissionPaise = null;
        if (attributed.application() != null && attributed.application().getLoanId() != null) {
            netDisbursedPaise = loanRepository.findById(attributed.application().getLoanId())
                    .map(Loan::getNetDisbursed).orElse(null);
        }
        var commission = commissionRepository.findByLeadId(lead.getId()).orElse(null);
        if (commission != null) {
            commissionPaise = commission.getAmountPaise();
        }
        // CONFIRMED overlays the stored outcome once an application is attributed (V70).
        String outcome = attributed.application() != null ? "CONFIRMED" : lead.getLeadOutcome();
        return DsaLeadView.of(lead, attributed.status(), netDisbursedPaise, commissionPaise, outcome);
    }

    /**
     * A lead this DSA may WRITE to — strictly one they own.
     *
     * <p>Deliberately narrower than {@link #requireVisibleLead}: an uploaded lead is view-only. It
     * never passed the PAN and cross-DSA duplicate guards a hand-entered lead does, and
     * {@code outreach} sends real billable SMS/email — so a bulk upload must not become a bulk send
     * capability, nor a way to rewrite a mobile or PAN after import.
     */
    private Lead requireOwnLead(Long id, Long dsaId) {
        return leadRepository.findByIdAndOwnerDsaId(id, dsaId)
                .orElseThrow(() -> new BusinessException("LEAD_NOT_FOUND", "Lead not found: " + id));
    }

    /** A lead this DSA may READ — one they own or one they uploaded. Same opaque miss on a foreign id. */
    private Lead requireVisibleLead(Long id, Long dsaId) {
        return leadRepository.findVisibleToDsa(id, dsaId)
                .orElseThrow(() -> new BusinessException("LEAD_NOT_FOUND", "Lead not found: " + id));
    }

    private void recordRejection(Long dsaId, String pan, String reason) {
        DsaLeadRejection rejection = new DsaLeadRejection();
        rejection.setDsaStaffId(dsaId);
        rejection.setPan(pan);
        rejection.setReason(reason);
        rejection.setCreatedAt(Instant.now());
        rejectionRepository.save(rejection);
    }

    private static BusinessException alreadyKnown() {
        return new BusinessException("LEAD_ALREADY_KNOWN", "This person is already known to us");
    }

    private Long requireDsaId() {
        CurrentActor actor = ActorContext.get();
        if (!"DSA".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "DSA required");
        }
        try {
            return Long.valueOf(actor.id());
        } catch (NumberFormatException e) {
            throw new BusinessException("FORBIDDEN_ROLE", "DSA identity required");
        }
    }

    private static long sumByStatus(List<DsaCommission> commissions, DsaCommissionStatus status) {
        return commissions.stream().filter(c -> c.getStatus() == status).mapToLong(DsaCommission::getAmountPaise).sum();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Long nonNegative(Long v) {
        if (v == null) {
            return null;
        }
        if (v < 0) {
            throw new BusinessException("INVALID_AMOUNT", "Amount must be >= 0");
        }
        return v;
    }
}
