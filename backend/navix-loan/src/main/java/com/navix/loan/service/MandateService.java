package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.verification.MandatePort;
import com.navix.common.verification.MandatePort.BankCapability;
import com.navix.common.verification.MandatePort.MandateRequest;
import com.navix.common.verification.MandatePort.MandateResult;
import com.navix.common.verification.MandatePort.MandateSession;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.entity.Mandate;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.MandateRepository;
import com.navix.loan.service.ApplicationVerificationService.StepResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers the borrower's NACH eMandate with the provider behind {@link MandatePort}. One
 * {@link Mandate} row per attempt. A create is never blind-retried: after a failed {@code register}
 * we look the attempt up by its CRN before surfacing anything, because the provider has no idempotency key.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MandateService {

    public static final String CHECK_TYPE = "EMANDATE";

    /** ECS runs UTC; collection dates are Indian calendar dates (see RepaymentService.IST). */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Pattern CRN = Pattern.compile("^[0-9A-Za-z-]{1,20}$");
    private static final Pattern ACCOUNT = Pattern.compile("^\\d{8,18}$");
    private static final long BANKS_TTL_MS = 24L * 60 * 60 * 1000;

    private final MandatePort port;
    private final MandateRepository mandates;
    private final LoanApplicationRepository applications;
    private final CustomerProfileRepository profiles;
    private final LoanMath loanMath;

    private volatile Map<String, BankCapability> banks;
    private volatile long banksLoadedAt;

    @Transactional
    public StepResult init(Long appId) {
        LoanApplication app = applications.findById(appId)
                .orElseThrow(() -> new BusinessException("APPLICATION_NOT_FOUND", "Application not found"));
        CustomerProfile profile = profiles.findByApplicationId(appId)
                .orElseThrow(() -> new BusinessException("PROFILE_NOT_FOUND", "Profile not found"));
        Long customerId = app.getCustomerId();

        String account = app.getDisbursalAccountNumber() == null ? "" : app.getDisbursalAccountNumber().trim();
        if (!ACCOUNT.matcher(account).matches()) {
            throw new BusinessException("INVALID_ACCOUNT_NUMBER", "The bank account number is not valid for a mandate");
        }
        String ifsc = app.getDisbursalIfsc() == null ? "" : app.getDisbursalIfsc().trim().toUpperCase(Locale.ROOT);
        if (ifsc.length() != 11) {
            throw new BusinessException("INVALID_IFSC", "The bank IFSC is not valid for a mandate");
        }
        long ceiling = ceiling(app, profile);

        Optional<Mandate> done = reusable(customerId, account, ifsc, ceiling);
        if (done.isPresent()) {
            return view(done.get(), null);
        }

        int attemptNo = mandates.findFirstByCustomerIdOrderByAttemptNoDesc(customerId)
                .map(m -> m.getAttemptNo() + 1).orElse(1);
        String crn = customerId + "-" + attemptNo;
        if (!CRN.matcher(crn).matches()) {
            throw new BusinessException("INVALID_CUSTOMER_REF", "Cannot form a mandate reference");
        }

        LocalDate today = LocalDate.now(IST);
        Mandate m = new Mandate();
        m.setCustomerId(customerId);
        m.setLoanApplicationId(appId);
        m.setAttemptNo(attemptNo);
        m.setCustomerRefNumber(crn);
        m.setBankName(app.getDisbursalBank());
        m.setAccountLast4(account.substring(account.length() - 4));
        m.setIfsc(ifsc);

        BankCapability cap = capability(ifsc.substring(0, 4));
        if (cap == null || !cap.apiMandate()) {
            // digio_mandate_id is NOT NULL + unique; nothing was filed, so mint a clearly-fake key.
            m.setDigioMandateId("NONE-" + crn);
            m.setStatus(Mandate.Status.BANK_UNSUPPORTED);
            m.setMaximumAmountPaise(0);
            mandates.save(m);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("bankUnsupported", true);
            return new StepResult(CHECK_TYPE, ApplicationVerificationService.PENDING,
                    "This bank does not support online mandate registration", d);
        }

        m.setMaximumAmountPaise(ceiling);
        m.setFirstCollectionDate(today);
        m.setFinalCollectionDate(today.plusYears(5));
        m.setStatus(Mandate.Status.PENDING_AUTHORIZATION);

        MandateRequest req = new MandateRequest(crn, sanitiseName(profile.getFullName()), profile.getMobile(),
                account, ifsc, app.getDisbursalBank(), ceiling, today, today.plusYears(5));

        String authUrl = null;
        try {
            MandateSession s = port.register(req);
            m.setDigioMandateId(s.digioMandateId());
            m.setDigioState(s.state());
            authUrl = s.authenticationUrl();
        } catch (RuntimeException failure) {
            log.warn("Mandate register failed crn={} exception={}", crn, failure.getClass().getSimpleName());
            MandateResult found;
            try {
                found = port.findByRef(crn).orElse(null);
            } catch (RuntimeException lookupFailure) {
                log.warn("Mandate recovery lookup failed crn={} exception={}", crn,
                        lookupFailure.getClass().getSimpleName());
                throw failure;
            }
            if (found == null) {
                throw failure; // never filed: nothing to adopt, and we do not re-POST
            }
            m.setDigioMandateId(found.digioMandateId());
            apply(m, found);
            if (m.getStatus() != Mandate.Status.ACTIVE) {
                // The lookup carries no authentication url, so the borrower could never authorise this
                // one. Keep the row for audit (it advances attempt_no) but abandon it; the next init
                // mints a fresh CRN and a fresh url.
                if (m.getStatus() == Mandate.Status.PENDING_AUTHORIZATION) {
                    m.setStatus(Mandate.Status.AUTHORIZATION_FAILED);
                }
                m.setRegisteredAt(Instant.now());
                mandates.save(m);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("retry", true);
                return new StepResult(CHECK_TYPE, ApplicationVerificationService.FAIL,
                        "We could not complete the mandate setup. Please try again.", d);
            }
        }
        m.setRegisteredAt(Instant.now());
        mandates.save(m);
        return view(m, authUrl);
    }

    @Transactional
    public StepResult status(Long appId) {
        LoanApplication app = applications.findById(appId)
                .orElseThrow(() -> new BusinessException("APPLICATION_NOT_FOUND", "Application not found"));
        Long customerId = app.getCustomerId();
        // Same rule as init: a valid ACTIVE mandate wins over any newer non-ACTIVE attempt.
        CustomerProfile profile = profiles.findByApplicationId(appId).orElse(null);
        String account = app.getDisbursalAccountNumber() == null ? "" : app.getDisbursalAccountNumber().trim();
        String ifsc = app.getDisbursalIfsc() == null ? "" : app.getDisbursalIfsc().trim().toUpperCase(Locale.ROOT);
        if (profile != null && ACCOUNT.matcher(account).matches() && ifsc.length() == 11) {
            try {
                Optional<Mandate> done = reusable(customerId, account, ifsc, ceiling(app, profile));
                if (done.isPresent()) {
                    return view(done.get(), null);
                }
            } catch (BusinessException noCeiling) {
                // status() is a read: report the stored row below rather than throw. init() guards.
            }
        }
        Mandate m = mandates.findFirstByCustomerIdOrderByAttemptNoDesc(customerId).orElse(null);
        if (m == null) {
            return new StepResult(CHECK_TYPE, ApplicationVerificationService.PENDING,
                    "Mandate not started", new LinkedHashMap<>());
        }
        if (m.getStatus() != Mandate.Status.PENDING_AUTHORIZATION) {
            return view(m, null);
        }
        apply(m, port.fetch(m.getDigioMandateId()));
        mandates.save(m);
        return view(m, null);
    }

    /** 2 x max(formula limit, sanctioned principal); zero would satisfy any reuse check, so it is refused. */
    private long ceiling(LoanApplication app, CustomerProfile profile) {
        long salary = profile.getMonthlySalaryPaise() == null ? 0L : profile.getMonthlySalaryPaise();
        long sanctioned = app.getSanctionedAmountPaise() == null ? 0L : app.getSanctionedAmountPaise();
        long ceiling = 2 * Math.max(loanMath.eligibleLimitPaise(salary), sanctioned);
        if (ceiling <= 0) {
            throw new BusinessException("MANDATE_CEILING_UNAVAILABLE", "Cannot size a mandate for this application");
        }
        return ceiling;
    }

    /** The customer's ACTIVE mandate, if it debits this account and covers this advance. */
    private Optional<Mandate> reusable(Long customerId, String account, String ifsc, long ceiling) {
        String last4 = account.substring(account.length() - 4);
        return mandates.findFirstByCustomerIdAndStatusAndUmrnIsNotNullOrderByIdDesc(customerId, Mandate.Status.ACTIVE)
                .filter(x -> x.getStatus() == Mandate.Status.ACTIVE && last4.equals(x.getAccountLast4()) && ifsc.equals(x.getIfsc())
                        && x.getMaximumAmountPaise() >= ceiling);
    }

    /**
     * The gate predicate: the customer holds an ACTIVE mandate (UMRN present) that debits THIS
     * application's disbursal account and covers its ceiling. Same rule {@code init} reuses on.
     * Anything unusable (no account yet, no ceiling) is simply "no".
     */
    @Transactional(readOnly = true)
    public boolean hasUsableMandate(LoanApplication app) {
        String account = app.getDisbursalAccountNumber() == null ? "" : app.getDisbursalAccountNumber().trim();
        String ifsc = app.getDisbursalIfsc() == null ? "" : app.getDisbursalIfsc().trim().toUpperCase(Locale.ROOT);
        if (!ACCOUNT.matcher(account).matches() || ifsc.length() != 11) {
            return false;
        }
        CustomerProfile profile = profiles.findByApplicationId(app.getId()).orElse(null);
        if (profile == null) {
            return false;
        }
        try {
            return reusable(app.getCustomerId(), account, ifsc, ceiling(app, profile)).isPresent();
        } catch (BusinessException noCeiling) {
            return false;
        }
    }

    /** What staff see: no authentication url, no full account number (only the stored last four). */
    public record MandateView(String status, String umrn, String bankName, String accountLast4,
                              Instant authenticatedAt, String rejectReason, int attemptNo, boolean overridable) {}

    /** Latest attempt for the application's customer, or empty when none was ever started. */
    @Transactional(readOnly = true)
    public Optional<MandateView> staffView(Long appId) {
        return applications.findById(appId)
                .flatMap(a -> mandates.findFirstByCustomerIdOrderByAttemptNoDesc(a.getCustomerId()))
                .map(m -> new MandateView(m.getStatus().name(), m.getUmrn(), m.getBankName(), m.getAccountLast4(),
                        m.getAuthenticatedAt(), m.getRejectReason(), m.getAttemptNo(),
                        m.getStatus() == Mandate.Status.BANK_UNSUPPORTED && appId.equals(m.getLoanApplicationId())));
    }

    /**
     * The only state a staff override may act on: the newest attempt made ON THIS APPLICATION hit a bank
     * with no API mandate. An earlier advance's BANK_UNSUPPORTED row says nothing about this account.
     */
    public boolean isBankUnsupported(Long appId) {
        return mandates.findFirstByLoanApplicationIdOrderByAttemptNoDesc(appId)
                .filter(m -> m.getStatus() == Mandate.Status.BANK_UNSUPPORTED).isPresent();
    }

    public Optional<Mandate> activeMandate(Long customerId) {
        return mandates.findFirstByCustomerIdAndStatusAndUmrnIsNotNullOrderByIdDesc(customerId, Mandate.Status.ACTIVE);
    }

    /** UMRN present means ACTIVE, whatever the state strings say. */
    private static void apply(Mandate m, MandateResult r) {
        m.setDigioState(r.state());
        m.setDigioBankState(r.bankState());
        m.setAuthSubMode(r.authSubMode());
        m.setRejectCode(r.rejectCode());
        m.setRejectReason(r.rejectReason());
        m.setAuthenticatedAt(r.authenticatedAt());
        if (r.umrn() != null && !r.umrn().isBlank()) {
            m.setUmrn(r.umrn());
            m.setStatus(Mandate.Status.ACTIVE);
            return;
        }
        m.setStatus(mapNoUmrn(r.state(), r.bankState()));
    }

    /**
     * docs/digio/09-state-mapping.md. Without a UMRN nothing is usable; only documented terminal values
     * map to a terminal status. Anything unrecognised (and the unproven {@code complete}) stays pending.
     */
    static Mandate.Status mapNoUmrn(String state, String bankState) {
        String s = state == null ? "" : state.toLowerCase(Locale.ROOT);
        String b = bankState == null ? "" : bankState.toLowerCase(Locale.ROOT);
        if (s.equals("expired")) {
            return Mandate.Status.EXPIRED;
        }
        if (s.equals("auth_failed") || s.equals("cancelled") || s.equals("revoked")
                || b.equals("register_failed") || b.equals("nack_received") || b.equals("reject_spo_bank")
                || b.equals("transfer_failed") || b.equals("failed") || b.equals("revoked")) {
            return Mandate.Status.AUTHORIZATION_FAILED;
        }
        return Mandate.Status.PENDING_AUTHORIZATION;
    }

    private static StepResult view(Mandate m, String authUrl) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("mandateId", m.getDigioMandateId());
        d.put("status", m.getStatus().name());
        if (authUrl != null) {
            d.put("url", authUrl);
        }
        String status = switch (m.getStatus()) {
            case ACTIVE -> ApplicationVerificationService.PASS;
            case AUTHORIZATION_FAILED, EXPIRED -> ApplicationVerificationService.FAIL;
            default -> ApplicationVerificationService.PENDING;
        };
        return new StepResult(CHECK_TYPE, status, "eMandate " + m.getStatus().name().toLowerCase(Locale.ROOT), d);
    }

    /** Provider rule: alphanumerics, spaces and dots only, max 40, must start alphanumeric. */
    static String sanitiseName(String name) {
        String s = (name == null ? "" : name).replace("'", "").replaceAll("[^0-9A-Za-z\\s.]", " ")
                .replaceAll("\\s+", " ").trim().replaceFirst("^[\\s.]+", "");
        if (s.length() > 40) {
            s = s.substring(0, 40).trim();
        }
        if (s.isEmpty()) {
            throw new BusinessException("INVALID_NAME", "The name is not valid for a mandate");
        }
        return s;
    }

    private BankCapability capability(String prefix) {
        Map<String, BankCapability> cached = banks;
        if (cached == null || System.currentTimeMillis() - banksLoadedAt > BANKS_TTL_MS) {
            Map<String, BankCapability> fresh = new java.util.HashMap<>();
            for (BankCapability b : port.liveBanks()) {
                fresh.put(b.ifscPrefix().toUpperCase(Locale.ROOT), b);
            }
            banks = cached = fresh;
            banksLoadedAt = System.currentTimeMillis();
        }
        return cached.get(prefix);
    }
}
