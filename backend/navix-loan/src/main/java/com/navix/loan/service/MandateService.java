package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.notification.event.MandateBankUnsupportedEvent;
import com.navix.common.notification.event.MandateCancelledEvent;
import com.navix.common.notification.event.MandateInviteEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.verification.MandatePort;
import com.navix.common.verification.MandatePort.BankCapability;
import com.navix.common.verification.MandatePort.MandateRequest;
import com.navix.common.verification.MandatePort.MandateResult;
import com.navix.common.verification.MandatePort.MandateSession;
import com.navix.loan.entity.CustomerMandateCeilingOverride;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.entity.Mandate;
import com.navix.loan.entity.MandateDebit;
import com.navix.loan.entity.ProfileChangeLog;
import com.navix.loan.repository.CustomerMandateCeilingOverrideRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.MandateDebitRepository;
import com.navix.loan.repository.MandateRepository;
import com.navix.loan.repository.ProfileChangeLogRepository;
import com.navix.loan.service.ApplicationVerificationService.StepResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    /**
     * Digio's own constraint on {@code customer_ref_number}: {@code ^[0-9A-Za-z]{0,}$}, max 20 —
     * "ALPHA and NUMERIC allowed". <b>No hyphen.</b> This pattern used to permit one, and it cost a
     * live failure: {@code 7682036-1} came back {@code HTTP 400 REQUEST_VALIDATION_FAILED}
     * (Digio ref {@code EX261018203058579ESC}) on the first real registration attempt. Keep this
     * identical to the provider's pattern — a validation of ours that is WIDER than theirs is not a
     * validation, it just moves the rejection to a place where the reason is invisible.
     */
    private static final Pattern CRN = Pattern.compile("^[0-9A-Za-z]{1,20}$");
    private static final Pattern ACCOUNT = Pattern.compile("^\\d{8,18}$");
    private static final long BANKS_TTL_MS = 24L * 60 * 60 * 1000;
    /**
     * Attempts one application may file. The borrower retries this screen until it works (a failed
     * authorisation is normal), so the cap is generous — but it must exist: {@code init} is
     * borrower-reachable and every call is a billable provider create.
     */
    static final int MAX_ATTEMPTS_PER_APPLICATION = 10;
    /** How many earlier attempts init re-reads before filing a new one; each one is a provider call. */
    static final int SETTLE_LOOKBACK = 5;

    private final MandatePort port;
    private final MandateRepository mandates;
    private final LoanApplicationRepository applications;
    private final CustomerProfileRepository profiles;
    private final LoanMath loanMath;
    private final FeatureFlagService featureFlags;
    private final ApplicationEventPublisher eventPublisher;
    private final MandateDebitRepository debits;
    private final CustomerMandateCeilingOverrideRepository ceilingOverrides;
    private final ProfileChangeLogRepository changeLog;

    /** Builds the invite link; same property the password-reset emails use. */
    @org.springframework.beans.factory.annotation.Value("${navix.app.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl = "http://localhost:3000"; // initialised so a direct `new` is safe

    /** How long Digio keeps an authorisation url alive; mirrors navix.digio.expire-in-days. */
    @org.springframework.beans.factory.annotation.Value("${navix.digio.expire-in-days:7}")
    private int expireInDays = 7;

    private volatile Map<String, BankCapability> banks;
    private volatile long banksLoadedAt;

    @Transactional
    public StepResult init(Long appId) {
        LoanApplication app = applications.findById(appId)
                .orElseThrow(() -> new BusinessException("APPLICATION_NOT_FOUND", "Application not found"));
        CustomerProfile profile = profiles.findByApplicationId(appId)
                .orElseThrow(() -> new BusinessException("PROFILE_NOT_FOUND", "Profile not found"));
        Long customerId = app.getCustomerId();
        // I2 — every register() files a real, binding NPCI mandate, so two concurrent inits (two tabs, a
        // retried request) must not interleave: they would compute the same attempt_no/CRN, file TWO
        // mandates, and then one insert would die on uq_mandate_crn, leaving a real mandate at Digio with
        // no row anywhere. A transaction-scoped Postgres advisory lock on the customer serialises them;
        // unlike SELECT ... FOR UPDATE it also covers a customer's FIRST attempt, which has no row to lock.
        // ponytail: serialised, not deduplicated — the second init still mints a fresh attempt, exactly as
        // the borrower's own "Try again" does. Collapsing those two would need the authorisation URL stored.
        mandates.lockCustomer(customerId);
        // AFTER the lock, not before: the attempt cap is a read-then-write, so two concurrent inits that
        // both read count = MAX - 1 would both proceed and file MAX + 1 mandates. The same lock that stops
        // them computing the same attempt_no is what makes the count trustworthy.
        requireMandateAllowed(app);

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

        // Before filing anything, settle with the provider what this application's EARLIER attempts are
        // actually doing. Both branches exist because of the 2026-10-20 incident (docs/digio/13 section 13),
        // where a borrower authorised attempt 1 successfully, we had already written it off as auth_failed,
        // and seven NPCI mandates went out for one account before the bank started refusing them.
        // What a NEW request would be filed for (an ADMIN override may raise it above the formula, V89).
        long registered = registrationCeiling(customerId, ceiling);
        Optional<Mandate> settled = settleEarlierAttempts(appId, account, ifsc, ceiling, registered);
        if (settled.isPresent()) {
            Mandate m0 = settled.get();
            // A live attempt still awaiting authorisation: send them back to ITS url. A completed one:
            // report it. Either way nothing new is filed.
            return view(m0, m0.getStatus() == Mandate.Status.PENDING_AUTHORIZATION ? m0.getAuthenticationUrl() : null);
        }

        int attemptNo = mandates.findFirstByCustomerIdOrderByAttemptNoDesc(customerId)
                .map(m -> m.getAttemptNo() + 1).orElse(1);
        // "C<customerId>A<attemptNo>" — alphanumeric per the pattern above. Both halves are pure digits
        // and the single 'A' separates them, so the mapping stays injective: no two (customer, attempt)
        // pairs can collide, which matters because uq_mandate_crn is what stops a double filing.
        String crn = "C" + customerId + "A" + attemptNo;
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
            // I3 — the borrower is told "our team will be in touch"; this is what pages them. Event, not a
            // direct call (CLAUDE.md §12): the AFTER_COMMIT @Async listener delivers and nothing here
            // depends on it succeeding.
            eventPublisher.publishEvent(new MandateBankUnsupportedEvent(customerId, appId,
                    app.getDisbursalBank(), Instant.now()));
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("bankUnsupported", true);
            return new StepResult(CHECK_TYPE, ApplicationVerificationService.PENDING,
                    "This bank does not support online mandate registration", d);
        }

        // Filed at the registration ceiling (an ADMIN override may raise it, V89); `ceiling` above stays the
        // formula, because that is what "this mandate covers this application" is judged against.
        m.setMaximumAmountPaise(registered);
        m.setFirstCollectionDate(today);
        m.setFinalCollectionDate(today.plusYears(5));
        m.setStatus(Mandate.Status.PENDING_AUTHORIZATION);

        MandateRequest req = new MandateRequest(crn, sanitiseName(profile.getFullName()), profile.getMobile(),
                account, ifsc, app.getDisbursalBank(), registered, today, today.plusYears(5));

        String authUrl = null;
        try {
            MandateSession s = port.register(req);
            m.setDigioMandateId(s.digioMandateId());
            m.setDigioState(s.state());
            authUrl = s.authenticationUrl();
            m.setAuthenticationUrl(authUrl); // so a return visit reuses THIS attempt instead of filing another
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
            if (found == null || found.digioMandateId() == null || found.digioMandateId().isBlank()) {
                // Never filed, or filed with no id we can key the NOT NULL column on: nothing to adopt,
                // and we never re-POST a create.
                throw failure;
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
        if (m.getCancelledAt() != null) {
            // Staff withdrew it (borrower's request, or to replace it with a higher ceiling). Not a failed
            // setup: the borrower is starting fresh, so offer "Continue to your bank", not "Try again".
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("status", "NOT_STARTED");
            d.put("previousCancelled", true);
            return new StepResult(CHECK_TYPE, ApplicationVerificationService.PENDING, "Mandate not started", d);
        }
        if (m.getStatus() == Mandate.Status.ACTIVE) {
            // Reached only when reusable() said no: the mandate is live but does not cover THIS
            // application (its ceiling is below this advance's, or it debits another account). Reporting
            // it as PASS told the borrower "auto-repayment set up" and hid the button that registers the
            // mandate they actually need, so no higher-ceiling replacement could ever be filed.
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("status", "NOT_STARTED");
            d.put("existingDoesNotCover", true);
            return new StepResult(CHECK_TYPE, ApplicationVerificationService.PENDING,
                    "The existing mandate does not cover this advance", d);
        }
        if (m.getStatus() != Mandate.Status.PENDING_AUTHORIZATION) {
            return view(m, null);
        }
        apply(m, port.fetch(m.getDigioMandateId()));
        mandates.save(m);
        return view(m, null);
    }

    /**
     * Re-reads this application's earlier attempts from the provider and returns one to reuse, if any.
     *
     * <p>Two things are being prevented, both observed in production on 2026-10-20:
     *
     * <ol>
     *   <li><b>An attempt that has since succeeded.</b> Digio's {@code auth_failed} is NOT terminal — it
     *       means that authentication attempt failed, and the borrower may retry on the SAME Digio page
     *       and succeed. One did, seven minutes after we had recorded AUTHORIZATION_FAILED and stopped
     *       looking. So any attempt without a UMRN is re-fetched here, failed ones included, and adopted
     *       if it now carries one. This is the check that makes a duplicate filing impossible whatever
     *       the borrower's screen happened to say.</li>
     *   <li><b>An attempt still live.</b> A pending attempt whose authorisation url has not expired is
     *       returned as-is, so a borrower who closed the tab or reloaded the page goes back to the SAME
     *       NPCI mandate rather than being given a second one.</li>
     * </ol>
     *
     * <p>Bounded to {@link #SETTLE_LOOKBACK} attempts: this runs on a borrower-facing request and each
     * step is a provider call. Newest first, because that is the one most likely to be live.
     *
     * <p>A provider read that fails is swallowed deliberately — failing to adopt costs a duplicate
     * mandate, which is bad, but throwing here would block a borrower who has no usable mandate at all,
     * which is worse. The attempt cap remains the backstop.
     */
    private Optional<Mandate> settleEarlierAttempts(Long appId, String account, String ifsc, long ceiling,
                                                    long registered) {
        String last4 = account.substring(account.length() - 4);
        List<Mandate> attempts = mandates.findByLoanApplicationIdOrderByAttemptNoDesc(appId);
        int looked = 0;
        for (Mandate m : attempts) {
            if (looked >= SETTLE_LOOKBACK) {
                break;
            }
            // Only attempts against THIS account and ceiling can be reused; anything else is a different
            // authority and must not be silently handed back (same rule as reusable()).
            if (!last4.equals(m.getAccountLast4()) || !ifsc.equals(m.getIfsc()) || m.getMaximumAmountPaise() < ceiling) {
                continue;
            }
            if (m.getStatus() == Mandate.Status.BANK_UNSUPPORTED) {
                continue; // nothing was ever filed; the override path owns this one
            }
            if (m.getCancelledAt() != null) {
                continue; // we withdrew it; a lagging provider read must not bring it back
            }
            if (m.getUmrn() != null && !m.getUmrn().isBlank() && m.getStatus() == Mandate.Status.ACTIVE) {
                return Optional.of(m); // already settled locally
            }
            looked++;
            try {
                apply(m, port.fetch(m.getDigioMandateId()));
                mandates.save(m);
            } catch (RuntimeException e) {
                log.warn("Mandate re-read failed crn={} exception={}", m.getCustomerRefNumber(),
                        e.getClass().getSimpleName());
                continue;
            }
            if (m.getStatus() == Mandate.Status.ACTIVE) {
                log.info("Adopted a mandate that completed after we recorded it as failed crn={}",
                        m.getCustomerRefNumber());
                return Optional.of(m);
            }
            if (m.getStatus() == Mandate.Status.PENDING_AUTHORIZATION && urlStillLive(m)) {
                // An ADMIN raised the limit after this request was filed: handing back its url would register
                // the OLD amount, and the override would silently do nothing. Withdraw it and file afresh —
                // but only once the bank has accepted the cancel; otherwise reuse it, because two live
                // requests on one account is the duplicate-mandate failure this method exists to prevent.
                if (m.getMaximumAmountPaise() < registered && withdrawBelowLimit(m, registered)) {
                    continue;
                }
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    /** Cancels a live request filed below the current registration limit. True only when the bank accepted. */
    private boolean withdrawBelowLimit(Mandate m, long registered) {
        boolean accepted;
        try {
            accepted = port.cancelMandate(m.getDigioMandateId(), m.getUmrn(), m.getIfsc(), "C002");
        } catch (RuntimeException e) {
            log.warn("Withdrawing a below-limit mandate request failed crn={} exception={}", m.getCustomerRefNumber(),
                    e.getClass().getSimpleName());
            return false;
        }
        if (!accepted) {
            log.info("Bank refused to withdraw below-limit mandate request crn={}; reusing it", m.getCustomerRefNumber());
            return false;
        }
        m.setStatus(Mandate.Status.AUTHORIZATION_FAILED);
        m.setDigioState("cancelled");
        m.setCancelledAt(Instant.now());
        m.setCancelledBy("system");
        m.setCancelReason("C002: replaced by a request for the raised limit of " + registered + " paise");
        mandates.save(m);
        log.info("Withdrew below-limit mandate request crn={} to file at {} paise", m.getCustomerRefNumber(), registered);
        return true;
    }

    /** A stored authorisation url is only good while Digio still honours it. */
    private boolean urlStillLive(Mandate m) {
        return m.getAuthenticationUrl() != null && !m.getAuthenticationUrl().isBlank()
                && m.getRegisteredAt() != null
                && m.getRegisteredAt().isAfter(Instant.now().minus(expireInDays, java.time.temporal.ChronoUnit.DAYS));
    }

    /**
     * The three gates {@code init} is the only place to hold, because it is the only place that files
     * a mandate. The journey never routes a borrower here unless {@link #mandateRequired} is true, but
     * {@code POST .../mandate/init} is a borrower-reachable endpoint of its own and reached none of this:
     *
     * <ul>
     *   <li><b>The kill switch.</b> With {@code digio-mandate} off NOTHING may file a mandate — that is
     *       what a kill switch is for. Checked here rather than via {@link #mandateRequired}, whose
     *       second clause (no usable mandate) is false on the legitimate reuse path below.</li>
     *   <li><b>The account.</b> A mandate authorises debits from one account, so it may only be filed
     *       against the account we proved — the one the borrower confirmed, or (on the PRE_APPROVED
     *       fast-track, which has no confirm step) the penny-dropped account carried from the last advance.</li>
     *   <li><b>A ceiling on attempts.</b> Retrying is expected and allowed; looping is not.</li>
     * </ul>
     */
    private void requireMandateAllowed(LoanApplication app) {
        if (!featureFlags.isEnabled("digio-mandate", false)) {
            throw new BusinessException("MANDATE_NOT_ENABLED", "eMandate registration is not available");
        }
        if (app.getDisbursalConfirmedAt() == null && !Boolean.TRUE.equals(app.getDisbursalAccountVerified())) {
            throw new BusinessException("MANDATE_ACCOUNT_UNCONFIRMED",
                    "Confirm your repayment bank account before setting up auto-repayment");
        }
        if (mandates.countByLoanApplicationId(app.getId()) >= MAX_ATTEMPTS_PER_APPLICATION) {
            throw new BusinessException("MANDATE_ATTEMPTS_EXHAUSTED",
                    "Too many mandate attempts. Our team will get in touch.");
        }
    }

    /**
     * Invites a customer to register a mandate outside the offer journey — the only route for someone
     * whose loan is already live, because they never saw the mandate step.
     *
     * <p>Returns the link so staff can copy it into whatever channel they are already using, and
     * publishes {@link MandateInviteEvent} so the engine also sends it on every channel the customer
     * has (CLAUDE.md section 12: publish an event, never call the engine, and never depend on delivery).
     *
     * <p>The link points at OUR page, not Digio's {@code authentication_url}: that url expires and
     * belongs to one attempt, so a message carrying it would rot. Nothing is filed here — the borrower
     * opening the page is what mints an attempt, so an invite is free and repeatable.
     *
     * <p>Refuses when there is no account to mandate. "Everyone without a mandate" cannot include a
     * customer whose bank details we have never proven: {@code init} would reject them on arrival, and
     * a link that leads to an error is worse than an honest refusal here.
     */
    @Transactional
    public String invite(Long appId) {
        LoanApplication app = applications.findById(appId)
                .orElseThrow(() -> new BusinessException("APPLICATION_NOT_FOUND", "Application not found"));
        if (hasUsableMandate(app)) {
            throw new BusinessException("MANDATE_ALREADY_ACTIVE", "This customer already has an active mandate");
        }
        String account = app.getDisbursalAccountNumber() == null ? "" : app.getDisbursalAccountNumber().trim();
        String ifsc = app.getDisbursalIfsc() == null ? "" : app.getDisbursalIfsc().trim();
        if (!ACCOUNT.matcher(account).matches() || ifsc.length() != 11) {
            throw new BusinessException("MANDATE_NO_ACCOUNT",
                    "No verified bank account on file for this customer, so a mandate cannot be set up");
        }
        CustomerProfile profile = profiles.findByApplicationId(appId).orElse(null);
        // The existing offer-journey screen, deliberately NOT a new standalone page. That page resolves
        // the application through useLiveApplication (not useOffer, which redirects anything other than
        // SANCTIONED to /loan/status), so an invited borrower with a live part-repaid loan — or a closed
        // one setting this up before their next advance — completes it there too. No token and no app id
        // in the link: /mine is scoped server-side to the borrower's own JWT, so the page adopts their
        // current application on a fresh device and can never resolve someone else's.
        String link = frontendBaseUrl.replaceAll("/+$", "") + "/loan/mandate";
        eventPublisher.publishEvent(new MandateInviteEvent(app.getCustomerId(), appId,
                profile == null ? null : profile.getFullName(), link, Instant.now()));
        log.info("Mandate invite sent applicationId={} customerId={}", appId, app.getCustomerId());
        return link;
    }

    /** 2 x max(formula limit, sanctioned principal); zero would satisfy any reuse check, so it is refused. */
    /** Bounds for an ADMIN mandate-ceiling override: the minimum loan, and a sane cap well inside Digio's. */
    static final long MIN_CEILING_OVERRIDE_PAISE = LoanMath.MIN_LOAN_PAISE;
    static final long MAX_CEILING_OVERRIDE_PAISE = 10_00_000_00L; // Rs 10,00,000

    /**
     * What a NEW mandate is registered for: the formula, raised by an ADMIN override when one is set (V89).
     * Only ever raises — a lower figure would file a mandate that does not cover the application it is for.
     */
    private long registrationCeiling(Long customerId, long formula) {
        return ceilingOverrides.findById(customerId)
                .map(o -> Math.max(formula, o.getCeilingPaise()))
                .orElse(formula);
    }

    /** The mandate ceiling for one application: formula, override and what a new registration would use. */
    public record MandateCeilingView(Long formulaPaise, Long overridePaise, Long registrationPaise,
                                     String overrideNote, String overrideSetBy, Instant overrideSetAt) {}

    @Transactional(readOnly = true)
    public MandateCeilingView ceilingView(Long appId) {
        LoanApplication app = applications.findById(appId)
                .orElseThrow(() -> new BusinessException("APPLICATION_NOT_FOUND", "Application not found"));
        CustomerProfile profile = profiles.findByApplicationId(appId).orElse(null);
        Long formula = null;
        if (profile != null) {
            try {
                formula = ceiling(app, profile);
            } catch (BusinessException noCeiling) {
                // no salary and no sanction yet: nothing to size from
            }
        }
        CustomerMandateCeilingOverride o = ceilingOverrides.findById(app.getCustomerId()).orElse(null);
        Long registration = o == null ? formula
                : formula == null ? o.getCeilingPaise() : Math.max(formula, o.getCeilingPaise());
        return new MandateCeilingView(formula, o == null ? null : o.getCeilingPaise(), registration,
                o == null ? null : o.getNote(), o == null ? null : o.getSetBy(), o == null ? null : o.getSetAt());
    }

    /**
     * Set ({@code ceilingPaise} non-null) or clear (null) the customer's mandate-ceiling override. ADMIN only
     * (controller). Affects NEW registrations only: an existing mandate keeps the ceiling the borrower
     * authorised, and whether it covers an application is still judged against the formula, so setting an
     * override never makes a working mandate unusable.
     */
    @Transactional
    public MandateCeilingView setCeilingOverride(Long appId, Long ceilingPaise, String note) {
        CurrentActor actor = ActorContext.get();
        if (actor == null || !"ADMIN".equals(actor.role())) { // the controller checks too; this is the authority
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ADMIN");
        }
        LoanApplication app = applications.findById(appId)
                .orElseThrow(() -> new BusinessException("APPLICATION_NOT_FOUND", "Application not found"));
        Long customerId = app.getCustomerId();
        Long previous = ceilingOverrides.findById(customerId)
                .map(CustomerMandateCeilingOverride::getCeilingPaise).orElse(null);
        if (ceilingPaise == null) {
            ceilingOverrides.deleteById(customerId);
            // Clearing deletes the row, so the change log is the only record of who removed what.
            logCeilingChange(customerId, appId, previous, null);
            log.info("Mandate ceiling override cleared customerId={}", customerId);
            return ceilingView(appId);
        }
        if (ceilingPaise < MIN_CEILING_OVERRIDE_PAISE || ceilingPaise > MAX_CEILING_OVERRIDE_PAISE) {
            throw new BusinessException("MANDATE_CEILING_OUT_OF_RANGE",
                    "The mandate limit must be between Rs 1,000 and Rs 10,00,000");
        }
        CustomerMandateCeilingOverride o = ceilingOverrides.findById(customerId)
                .orElseGet(CustomerMandateCeilingOverride::new);
        o.setCustomerId(customerId);
        o.setCeilingPaise(ceilingPaise);
        String n = note == null ? null : note.trim();
        o.setNote(n == null || n.isEmpty() ? null : n.substring(0, Math.min(n.length(), 500)));
        o.setSetBy(actor == null ? "system" : actor.name() + " (" + actor.role() + ", id " + actor.id() + ")");
        o.setSetAt(Instant.now());
        ceilingOverrides.save(o);
        logCeilingChange(customerId, appId, previous, ceilingPaise);
        log.info("Mandate ceiling override set customerId={} ceilingPaise={}", customerId, ceilingPaise);
        return ceilingView(appId);
    }

    /** Same profile_change_log trail the borrowing-limit override writes; created_by records the actor. */
    private void logCeilingChange(Long customerId, Long appId, Long previous, Long next) {
        if (Objects.equals(previous, next)) {
            return;
        }
        ProfileChangeLog entry = new ProfileChangeLog();
        entry.setCustomerId(customerId);
        entry.setApplicationId(appId);
        entry.setField("mandateCeilingPaise");
        entry.setOldValue(previous == null ? null : String.valueOf(previous));
        entry.setNewValue(next == null ? null : String.valueOf(next));
        changeLog.save(entry);
    }

    private long ceiling(LoanApplication app, CustomerProfile profile) {
        long salary = profile.getMonthlySalaryPaise() == null ? 0L : profile.getMonthlySalaryPaise();
        long sanctioned = app.getSanctionedAmountPaise() == null ? 0L : app.getSanctionedAmountPaise();
        long ceiling = 2 * Math.max(loanMath.eligibleLimitPaise(salary), sanctioned);
        if (ceiling <= 0) {
            throw new BusinessException("MANDATE_CEILING_UNAVAILABLE", "Cannot size a mandate for this application");
        }
        return ceiling;
    }

    /**
     * The customer's ACTIVE mandate, if it debits this account and covers this advance.
     *
     * <p>Matched on {@code accountLast4} + {@code ifsc} rather than the account number, because the full
     * number is deliberately never stored (V82). Two accounts at one branch sharing their last four digits
     * would therefore match; the penny drop on the disbursal account is what proves the account itself.
     */
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
        return usableMandate(app).isPresent();
    }

    /**
     * The one gate: the {@code digio-mandate} flag is on and this application has no usable mandate. The
     * journey step, the hand-off and {@code acceptOffer}'s guard all read THIS — three copies of a
     * money-affecting rule drifted twice on this branch.
     */
    @Transactional(readOnly = true)
    public boolean mandateRequired(LoanApplication app) {
        return featureFlags.isEnabled("digio-mandate", false) && !hasUsableMandate(app);
    }

    /**
     * The usable mandate behind a loan (loan -> application -> mandate), by the same rule as
     * {@link #hasUsableMandate}. A debit starts from a loan; the mandate is keyed by application.
     */
    @Transactional(readOnly = true)
    public Optional<Mandate> usableMandateForLoan(Long loanId) {
        return applications.findByLoanId(loanId).flatMap(this::usableMandate);
    }

    /** The mandate {@link #hasUsableMandate} accepts for this application, if any. */
    private Optional<Mandate> usableMandate(LoanApplication app) {
        String account = app.getDisbursalAccountNumber() == null ? "" : app.getDisbursalAccountNumber().trim();
        String ifsc = app.getDisbursalIfsc() == null ? "" : app.getDisbursalIfsc().trim().toUpperCase(Locale.ROOT);
        if (!ACCOUNT.matcher(account).matches() || ifsc.length() != 11) {
            return Optional.empty();
        }
        CustomerProfile profile = profiles.findByApplicationId(app.getId()).orElse(null);
        if (profile == null) {
            return Optional.empty();
        }
        try {
            return reusable(app.getCustomerId(), account, ifsc, ceiling(app, profile));
        } catch (BusinessException noCeiling) {
            return Optional.empty();
        }
    }

    /** What staff see: no authentication url, no full account number (only the stored last four). */
    public record MandateView(String status, String umrn, String bankName, String accountLast4,
                              Instant authenticatedAt, String rejectReason, int attemptNo, boolean overridable,
                              boolean carriedOver, Instant cancelledAt) {}

    /**
     * Newest attempt made on THIS application (same row the override gate checks). With none, a reborrower's
     * usable mandate from an earlier advance is shown instead, marked {@code carriedOver} and never overridable.
     */
    @Transactional(readOnly = true)
    public Optional<MandateView> staffView(Long appId) {
        // The EFFECTIVE mandate first. An ACTIVE UMRN is what actually governs this application, and a
        // later unauthorised attempt stacked on top of it does not change that. Showing the newest row
        // instead told staff "Awaiting borrower authorisation" for a customer who already held a live
        // mandate, while the borrower kept pressing and filing more (docs/digio/13 section 13).
        Optional<Mandate> usable = applications.findById(appId).flatMap(this::usableMandate);
        if (usable.isPresent()) {
            Mandate m = usable.get();
            return Optional.of(toView(m, false, !appId.equals(m.getLoanApplicationId())));
        }
        return mandates.findFirstByLoanApplicationIdOrderByAttemptNoDesc(appId)
                .map(m -> toView(m, m.getStatus() == Mandate.Status.BANK_UNSUPPORTED, false));
    }

    /**
     * Cancel one stale mandate attempt at the provider.
     *
     * <p>Built for the duplicates a borrower accumulates when the journey makes them re-press: each is a
     * live registration request at the destination bank with its own authorisation url, so one completed
     * weeks later yields a SECOND UMRN and a second ceiling on the same account (docs/digio/13 §13).
     *
     * <p>Two refusals are deliberate:
     * <ul>
     *   <li><b>The mandate in force is never cancellable here.</b> It is the authority collections
     *       depends on, and killing it from a history table would be a very quiet way to stop a loan
     *       being collected.</li>
     *   <li><b>No ACTIVE mandate is cancellable here at all</b>, even one not in force for this
     *       application — it may be in force for another. Revoking a live authority is a deliberate act
     *       with a borrower conversation behind it, not a row action; that flow is not built.</li>
     * </ul>
     *
     * <p>Reason {@code C002} (corporate request): we are withdrawing our own duplicate filing, not
     * acting on the customer's instruction. The provider decides whether the state permits it at all
     * (11- Q9 is still open), so a refusal surfaces rather than being swallowed.
     */
    @Transactional
    public MandateAttempt cancel(Long appId, Long mandateId) {
        LoanApplication app = applications.findById(appId)
                .orElseThrow(() -> new BusinessException("APPLICATION_NOT_FOUND", "Application not found"));
        Mandate m = mandates.findById(mandateId)
                .orElseThrow(() -> new BusinessException("MANDATE_NOT_FOUND", "Mandate not found"));
        if (!m.getCustomerId().equals(app.getCustomerId())) {
            throw new BusinessException("MANDATE_NOT_FOUND", "Mandate not found");
        }
        if (m.getStatus() == Mandate.Status.BANK_UNSUPPORTED) {
            throw new BusinessException("MANDATE_NOTHING_FILED",
                    "Nothing was filed for this attempt, so there is nothing to cancel");
        }
        if (m.getStatus() == Mandate.Status.ACTIVE) {
            throw new BusinessException("MANDATE_ACTIVE_NOT_CANCELLABLE",
                    "This mandate is active. Cancelling a live mandate is not done from here.");
        }
        boolean cancelled = port.cancelMandate(m.getDigioMandateId(), m.getUmrn(), m.getIfsc(), "C002");
        if (!cancelled) {
            throw new BusinessException("MANDATE_CANCEL_REFUSED", m.getUmrn() == null || m.getUmrn().isBlank()
                    ? "This attempt was never authorised, so there is no registered mandate (UMRN) to cancel yet."
                    : "The bank would not cancel this mandate. It may already be closed or past the point of cancellation.");
        }
        // Read back rather than assume: `cancelled`/`revoked` folds onto AUTHORIZATION_FAILED in apply(),
        // so the row records what the provider says rather than what we asked for.
        try {
            apply(m, port.fetch(m.getDigioMandateId()));
        } catch (RuntimeException e) {
            log.warn("Mandate re-read after cancel failed crn={} exception={}", m.getCustomerRefNumber(),
                    e.getClass().getSimpleName());
            m.setStatus(Mandate.Status.AUTHORIZATION_FAILED);
            m.setDigioState("cancelled");
        }
        markCancelled(m, "C002: stale attempt withdrawn");
        mandates.save(m);
        log.info("Mandate cancelled crn={} applicationId={}", m.getCustomerRefNumber(), appId);
        return history(appId).stream().filter(a -> a.id().equals(mandateId)).findFirst().orElseThrow();
    }

    /** Why staff are revoking a live mandate; maps onto Digio's registration_cancel reason codes. */
    public enum CancelReason {
        /** The borrower asked us to stop auto-debit. */
        CUSTOMER_REQUEST("C001"),
        /** Our decision — typically replacing it with a mandate that carries a higher ceiling. */
        CORPORATE_REQUEST("C002");

        final String code;

        CancelReason(String code) {
            this.code = code;
        }
    }

    /**
     * Cancel an ACTIVE mandate — the live debit authority — at the provider.
     *
     * <p>The deliberate counterpart to {@link #cancel}, which refuses ACTIVE rows: revoking a live authority
     * is a decision with a borrower conversation behind it, so it takes a reason code and a written note,
     * and the row records who did it ({@code cancelled_by}, V88). Heads and ADMIN only (controller).
     *
     * <p>Allowed while a loan is live. Auto-debit simply stops for every loan this mandate served — they fall
     * back to manual repayment — and the borrower is told. What it will not do is race a debit already at
     * the bank: the loan locks DebitService takes are acquired first, then any SCHEDULED/IN_PROGRESS debit
     * against this mandate refuses the cancel until it settles, because a cancel that lands after NPCI has
     * the debit file leaves money moving on an authority we believe is dead.
     *
     * <p>Once the provider accepts, the row is dead for us even if a read-back still says active (a bank
     * cancellation is not instant): usableMandate needs ACTIVE, so no further debit is presented, and
     * {@code cancelled_at} stops refresh/settle from re-adopting it.
     */
    @Transactional
    public MandateAttempt cancelActive(Long appId, Long mandateId, CancelReason reason, String notes) {
        if (reason == null) {
            throw new BusinessException("CANCEL_REASON_REQUIRED", "Choose why the mandate is being cancelled");
        }
        String note = notes == null ? "" : notes.trim();
        if (note.isEmpty()) {
            throw new BusinessException("CANCEL_NOTES_REQUIRED", "Add a note explaining the cancellation");
        }
        if (note.length() > 480) {
            note = note.substring(0, 480);
        }
        LoanApplication app = applications.findById(appId)
                .orElseThrow(() -> new BusinessException("APPLICATION_NOT_FOUND", "Application not found"));
        Long customerId = app.getCustomerId();

        // Locks BEFORE the mandate is loaded, so the read below is this transaction's first and sees whatever
        // a concurrent cancel committed (a re-read after loading would just hit the persistence context).
        // Same customer lock init takes, then every loan lock DebitService.prepare takes, in id order so two
        // cancels cannot deadlock. A presentation that already holds a loan lock finishes first; one that
        // starts after us re-checks the mandate under its lock and finds it dead.
        mandates.lockCustomer(customerId);
        applications.findByCustomerId(customerId).stream()
                .map(LoanApplication::getLoanId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .forEach(loanId -> debits.lockLoan(DebitService.LOCK_NAMESPACE | loanId));

        Mandate m = mandates.findById(mandateId)
                .orElseThrow(() -> new BusinessException("MANDATE_NOT_FOUND", "Mandate not found"));
        if (!m.getCustomerId().equals(customerId)) {
            throw new BusinessException("MANDATE_NOT_FOUND", "Mandate not found");
        }
        if (m.getStatus() != Mandate.Status.ACTIVE) {
            throw new BusinessException("MANDATE_NOT_ACTIVE",
                    "This mandate is not active. Use the row's Cancel action for an unfinished attempt.");
        }
        if (debits.existsByMandateIdAndStatusIn(m.getId(),
                EnumSet.of(MandateDebit.Status.SCHEDULED, MandateDebit.Status.IN_PROGRESS))) {
            throw new BusinessException("MANDATE_DEBIT_IN_FLIGHT",
                    "A debit against this mandate is still being processed. Check its status or cancel it under Debit attempts, then try again.");
        }

        boolean accepted = port.cancelMandate(m.getDigioMandateId(), m.getUmrn(), m.getIfsc(), reason.code);
        if (!accepted) {
            throw new BusinessException("MANDATE_CANCEL_REFUSED",
                    "The bank would not cancel this mandate. It may already be closed or past the point of cancellation.");
        }
        try {
            apply(m, port.fetch(m.getDigioMandateId()));
        } catch (RuntimeException e) {
            log.warn("Mandate re-read after cancel failed crn={} exception={}", m.getCustomerRefNumber(),
                    e.getClass().getSimpleName());
        }
        if (m.getStatus() == Mandate.Status.ACTIVE) {
            // Accepted but the bank has not processed it yet. It is dead to us from now.
            m.setStatus(Mandate.Status.AUTHORIZATION_FAILED);
            m.setDigioState("cancel_requested");
        }
        markCancelled(m, reason.code + ": " + note);
        mandates.save(m);

        CustomerProfile profile = profiles.findByApplicationId(appId).orElse(null);
        eventPublisher.publishEvent(new MandateCancelledEvent(customerId, appId,
                profile == null ? null : profile.getFullName(), m.getBankName(), m.getAccountLast4(),
                reason == CancelReason.CUSTOMER_REQUEST, Instant.now()));
        log.info("Active mandate cancelled crn={} applicationId={} reason={}", m.getCustomerRefNumber(), appId,
                reason.code);
        return history(appId).stream().filter(a -> a.id().equals(mandateId)).findFirst().orElseThrow();
    }

    private static void markCancelled(Mandate m, String reason) {
        CurrentActor actor = ActorContext.get();
        m.setCancelledAt(Instant.now());
        m.setCancelledBy(actor == null ? "system" : actor.name() + " (" + actor.role() + ", id " + actor.id() + ")");
        m.setCancelReason(reason);
    }

    /** One row of the attempt history. No authorisation url — it is a bearer link to a binding mandate. */
    public record MandateAttempt(Long id, Long applicationId, int attemptNo, String status, String umrn,
                                 String bankName, String accountLast4, String digioState, String digioBankState,
                                 String authSubMode, String rejectReason, long maximumAmountPaise,
                                 Instant createdAt, Instant authenticatedAt, boolean reusable,
                                 Instant cancelledAt, String cancelledBy, String cancelReason) {}

    /**
     * Every mandate this CUSTOMER has ever attempted, newest first — across applications, because a
     * mandate is a per-customer authority and a reborrower's earlier one may well be the live one.
     */
    @Transactional(readOnly = true)
    public List<MandateAttempt> history(Long appId) {
        LoanApplication app = applications.findById(appId)
                .orElseThrow(() -> new BusinessException("APPLICATION_NOT_FOUND", "Application not found"));
        Long effective = usableMandate(app).map(Mandate::getId).orElse(null);
        return mandates.findByCustomerIdOrderByAttemptNoDesc(app.getCustomerId()).stream()
                .map(m -> new MandateAttempt(m.getId(), m.getLoanApplicationId(), m.getAttemptNo(),
                        m.getStatus().name(), m.getUmrn(), m.getBankName(), m.getAccountLast4(),
                        m.getDigioState(), m.getDigioBankState(), m.getAuthSubMode(), m.getRejectReason(),
                        m.getMaximumAmountPaise(), m.getCreatedAt(), m.getAuthenticatedAt(),
                        m.getId().equals(effective), m.getCancelledAt(), m.getCancelledBy(), m.getCancelReason()))
                .toList();
    }

    /**
     * Re-reads every unsettled attempt from the provider and writes back what it says.
     *
     * <p>This is the staff-side counterpart to {@link #settleEarlierAttempts}: Digio's
     * {@code auth_failed} is not terminal, so a row we recorded as failed may since have completed, and
     * nothing in the borrower journey will notice unless they press the button again. Read-only at the
     * provider — it files nothing and cancels nothing.
     */
    /** Digio's bank state once the destination bank has accepted the registration (09-state-mapping.md). */
    static final String BANK_APPROVED = "register_success";

    @Transactional
    public List<MandateAttempt> refresh(Long appId) {
        LoanApplication app = applications.findById(appId)
                .orElseThrow(() -> new BusinessException("APPLICATION_NOT_FOUND", "Application not found"));
        for (Mandate m : mandates.findByCustomerIdOrderByAttemptNoDesc(app.getCustomerId())) {
            // An ACTIVE row is only settled once the destination bank has approved it: the UMRN arrives
            // with NPCI's ACK (`ack_received`), the bank's `register_success` a day or so later.
            boolean settled = (m.getStatus() == Mandate.Status.ACTIVE && BANK_APPROVED.equals(m.getDigioBankState()))
                    || m.getStatus() == Mandate.Status.BANK_UNSUPPORTED
                    || m.getCancelledAt() != null; // a cancelled row is never re-adopted, whatever Digio says
            if (settled) {
                continue;
            }
            try {
                apply(m, port.fetch(m.getDigioMandateId()));
                mandates.save(m);
            } catch (RuntimeException e) {
                log.warn("Mandate refresh failed crn={} exception={}", m.getCustomerRefNumber(),
                        e.getClass().getSimpleName());
            }
        }
        return history(appId);
    }

    private static MandateView toView(Mandate m, boolean overridable, boolean carriedOver) {
        return new MandateView(m.getStatus().name(), m.getUmrn(), m.getBankName(), m.getAccountLast4(),
                m.getAuthenticatedAt(), m.getRejectReason(), m.getAttemptNo(), overridable, carriedOver,
                m.getCancelledAt());
    }

    /**
     * The only state a staff override may act on: the newest attempt made ON THIS APPLICATION hit a bank
     * with no API mandate. An earlier advance's BANK_UNSUPPORTED row says nothing about this account.
     */
    public boolean isBankUnsupported(Long appId) {
        return mandates.findFirstByLoanApplicationIdOrderByAttemptNoDesc(appId)
                .filter(m -> m.getStatus() == Mandate.Status.BANK_UNSUPPORTED).isPresent();
    }

    /**
     * UMRN present means ACTIVE, whatever the state strings say — <b>except</b> when the provider also
     * says the mandate is revoked or cancelled. A borrower who authorises and then cancels at their bank
     * comes back as {@code umrn: "...", state: "revoked"} (09-state-mapping.md §2), and honouring the UMRN
     * there marked the row ACTIVE, let {@code reusable()} accept it, and handed the file to disbursement
     * with no live mandate — permanently, since {@code status()} only ever refreshes a pending row.
     */
    static void apply(Mandate m, MandateResult r) {
        m.setDigioState(r.state());
        m.setDigioBankState(r.bankState());
        m.setAuthSubMode(r.authSubMode());
        m.setRejectCode(r.rejectCode());
        m.setRejectReason(r.rejectReason());
        m.setAuthenticatedAt(r.authenticatedAt());
        boolean umrn = r.umrn() != null && !r.umrn().isBlank();
        if (umrn) {
            m.setUmrn(r.umrn()); // keep it for support/reconciliation even when the mandate is dead
        }
        m.setStatus(revoked(r.state(), r.bankState()) ? Mandate.Status.AUTHORIZATION_FAILED
                : umrn ? Mandate.Status.ACTIVE
                : mapNoUmrn(r.state(), r.bankState()));
    }

    /**
     * docs/digio/09-state-mapping.md. Without a UMRN nothing is usable; only documented terminal values
     * map to a terminal status. Anything unrecognised (and the unproven {@code complete}) stays pending.
     */
    /**
     * Revoked or cancelled — terminal whether or not a UMRN was ever issued. Collapsed onto
     * {@code AUTHORIZATION_FAILED} rather than carrying its own status: borrower- and staff-facing
     * behaviour is identical (dead, register a new one), and the raw {@code digio_state} /
     * {@code digio_bank_state} are stored verbatim so support can still read "revoked".
     */
    static boolean revoked(String state, String bankState) {
        String s = state == null ? "" : state.toLowerCase(Locale.ROOT);
        return s.equals("revoked") || s.equals("cancelled")
                || "revoked".equals(bankState == null ? null : bankState.toLowerCase(Locale.ROOT));
    }

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
            if (fresh.isEmpty()) {
                // An empty 200 is an outage, not "no bank supports mandates": caching it for 24h would park
                // every borrower as BANK_UNSUPPORTED, each needing a staff override to undo.
                throw new BusinessException("MANDATE_BANKS_UNAVAILABLE",
                        "We could not reach your bank just now. Please try again.");
            }
            banks = cached = fresh;
            banksLoadedAt = System.currentTimeMillis();
        }
        return cached.get(prefix);
    }
}
