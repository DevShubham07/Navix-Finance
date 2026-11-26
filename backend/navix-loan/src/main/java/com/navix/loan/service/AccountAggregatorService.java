package com.navix.loan.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.common.verification.AccountAggregatorPort;
import com.navix.common.verification.ProviderCallContext;
import com.navix.loan.bre.BreRuleCatalog;
import com.navix.loan.bre.BreRuleService;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.entity.ApplicationDocument;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Digitap Account Aggregator (bank-data consent), behind the {@code bre-aa-enabled} flag (default OFF).
 *
 * <p>State is one {@code application_verification} row, check type {@link #BANK_AA}: PENDING while a
 * consent link is open, PASS once the report is retrieved, REVIEW (provider MANUAL_STATEMENT) when the
 * borrower used the statement fallback. {@code derived} holds {requestId, mainTxnId, attempts, phase,
 * templates, consentEnd, summary, pulls[], lastPolledAt, lastCode}; every write copies it forward.
 * Reports are stored as {@code AA_REPORT} documents. Deliberately not {@code @Transactional}: provider
 * calls must not run inside a DB transaction, and each repository save is its own.
 *
 * <p>There is no webhook receiver — polling ({@link #status}) is the source of truth.
 */
@Service
@RequiredArgsConstructor
public class AccountAggregatorService {

    private static final Logger log = LoggerFactory.getLogger(AccountAggregatorService.class);

    public static final String BANK_AA = "BANK_AA";
    public static final String AA_REPORT = "AA_REPORT";
    /** Roles that may see AA detail and pull on demand. Everyone else (DSA, TELECALLER, …) is refused. */
    public static final Set<String> STAFF_ROLES = Set.of(
            "ADMIN", "CREDIT_HEAD", "CREDIT_EXECUTIVE", "COLLECTION_HEAD", "COLLECTION_EXECUTIVE");

    private static final String PASS = ApplicationVerificationService.PASS;
    private static final String REVIEW = ApplicationVerificationService.REVIEW;
    private static final String PENDING = ApplicationVerificationService.PENDING;
    private static final Duration POLL_GAP = Duration.ofSeconds(10);
    /** A pull still open after this long is given up on so it cannot block new pulls forever. */
    private static final Duration PULL_TIMEOUT = Duration.ofHours(1);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};

    private final BreRuleService bre;
    private final ApplicationVerificationRepository rows;
    private final ApplicationDocumentRepository docs;
    private final CustomerProfileRepository profiles;
    private final LoanApplicationRepository apps;
    private final AccountAggregatorPort aa;
    private final DocumentStoragePort storage;
    private final ObjectMapper json;

    @Value("${navix.app.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;
    @Value("${navix.digitap-aa.callback-url:}")
    private String callbackUrl;

    /** What the borrower page (and, with staff fields, Customer 360) reads. */
    public record AaView(boolean enabled, String status, int attempts, int maxAttempts, int attemptsLeft,
                         boolean fallbackAvailable, boolean completed, Map<String, Object> summary,
                         List<Object> pulls) {
    }

    public record AaLink(String url) {
    }

    // ------------------------------------------------------------------ journey hooks

    public boolean enabled() {
        return bre.isOn(BreRuleCatalog.AA_ENABLED);
    }

    /** Intake gate: the step is done once the consent is connected or the borrower fell back to a statement. */
    public boolean intakeSatisfied(Long appId) {
        return row(appId).filter(v -> v.getReopenedAt() == null)
                .filter(v -> PASS.equals(v.getStatus()) || REVIEW.equals(v.getStatus())).isPresent();
    }

    /** Whether the offer journey carries the AA step: on, and consent not already connected at intake. */
    public boolean offerApplicable(Long appId) {
        if (!enabled()) {
            return false;
        }
        ApplicationVerification v = row(appId).orElse(null);
        return v == null || !PASS.equals(v.getStatus()) || "OFFER".equals(read(v).get("phase"));
    }

    /** Whether the offer journey must still hold the borrower on AA (not connected, attempts left). */
    public boolean offerPending(Long appId) {
        if (!offerApplicable(appId)) {
            return false;
        }
        ApplicationVerification v = row(appId).orElse(null);
        if (v == null) {
            return true;
        }
        return !PASS.equals(v.getStatus()) && attempts(read(v), "OFFER") < bre.threshold(BreRuleCatalog.T_AA_MAX_ATTEMPTS);
    }

    // ------------------------------------------------------------------ borrower / staff reads

    public AaView view(Long appId) {
        return build(appId, row(appId).orElse(null));
    }

    /** Polls the provider (throttled to once per 10 s) and advances the main consent and any open pull. */
    public AaView status(Long appId) {
        ApplicationVerification v = row(appId).orElse(null);
        if (v != null && enabled()) {
            v = refresh(appId, v);
        }
        return build(appId, v);
    }

    // ------------------------------------------------------------------ borrower writes

    public AaLink init(Long appId, String returnUrl) {
        requireEnabled();
        requireReturnUrl(returnUrl);
        ApplicationVerification v = row(appId).orElse(null);
        if (v != null) {
            v = refresh(appId, v);
            if (PASS.equals(v.getStatus())) {
                throw new BusinessException("AA_ALREADY_CONNECTED", "Your bank is already connected");
            }
        }
        LoanApplication app = apps.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("LoanApplication", String.valueOf(appId)));
        String phase = app.getStatus() == ApplicationStatus.SANCTIONED ? "OFFER" : "INTAKE";
        Map<String, Object> d = v == null ? new LinkedHashMap<>() : read(v);
        int attempt = attempts(d, phase) + 1;
        CustomerProfile p = profiles.findByApplicationId(appId)
                .orElseThrow(() -> new BusinessException("PROFILE_REQUIRED", "Complete your profile first"));
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> consents = consentRequests(
                bre.isOn(BreRuleCatalog.AA_CT001), bre.isOn(BreRuleCatalog.AA_CT003), bre.isOn(BreRuleCatalog.AA_CT035),
                bre.threshold(BreRuleCatalog.T_AA_CT001_MONTHS), bre.threshold(BreRuleCatalog.T_AA_CT003_MONTHS),
                bre.threshold(BreRuleCatalog.T_AA_CT035_MONTHS), today);
        if (consents.isEmpty()) {
            throw new BusinessException("AA_MISCONFIGURED", "No Account Aggregator consent template is enabled");
        }
        String ref = "navix-" + appId + "-" + BANK_AA + "-" + attempt;
        Map<String, Object> body = generateUrlBody(ref, p.getMobile(), p.getPan(), p.getDob(),
                bre.isOn(BreRuleCatalog.AA_MULTI), callbackUrl, returnUrl, consents);
        ProviderCallContext.setApplicationId(appId);
        ProviderCallContext.setCheckType(BANK_AA);
        AccountAggregatorPort.Link link;
        try {
            link = aa.generateUrl(body);
        } catch (RuntimeException providerFailure) {
            log.warn("AA generateurl failed application={} {}", appId, providerFailure.toString());
            throw new BusinessException("AA_UNAVAILABLE", "Bank connection is unavailable right now. Please try again.");
        }
        Map<String, Object> next = new LinkedHashMap<>();
        if (v != null) {
            next.putAll(d);          // carry pulls etc. forward — upsert replaces derived wholesale
        }
        next.remove("mainTxnId");
        next.remove("lastCode");
        next.remove("summary");
        next.put("phase", phase);
        next.put("attempts", attempt);
        next.put("requestId", link.requestId());
        next.put("clientRef", ref);
        next.put("templates", consents.stream().map(c -> c.get("template_id")).toList());
        next.put("consentEnd", consentEnd(consents));
        save(appId, v, PENDING, "DIGITAP_AA", next, "Account Aggregator consent link issued");
        return new AaLink(link.url());
    }

    public AaView statementFallback(Long appId) {
        requireEnabled();
        ApplicationVerification v = row(appId).orElse(null);
        AaView view = build(appId, v);
        if (PASS.equals(view.status())) {
            return view;
        }
        if (!view.fallbackAvailable()) {
            throw new BusinessException("AA_FALLBACK_NOT_AVAILABLE", "Please try connecting your bank again");
        }
        if (docs.findFirstByApplicationIdAndDocTypeOrderByIdDesc(appId, "BANK_STATEMENT").isEmpty()) {
            throw new BusinessException("BANK_STATEMENT_REQUIRED", "Upload your bank statement to continue");
        }
        Map<String, Object> d = read(v);
        d.put("phase", phaseOf(appId));
        save(appId, v, REVIEW, "MANUAL_STATEMENT", d, "Bank statement uploaded instead of Account Aggregator");
        return build(appId, row(appId).orElse(null));
    }

    // ------------------------------------------------------------------ staff pull

    /** On-demand periodic CT003 pull against the borrower's existing consent. Credit, Collections, ADMIN. */
    public AaView pull(Long appId) {
        CurrentActor actor = ActorContext.get();
        if (actor == null || !STAFF_ROLES.contains(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "Pulling a bank statement requires a credit or collections role");
        }
        requireEnabled();
        ApplicationVerification v = row(appId).orElse(null);
        if (v != null) {
            v = refresh(appId, v);
        }
        Map<String, Object> d = v == null ? Map.of() : read(v);
        if (v == null || !PASS.equals(v.getStatus()) || !templates(d).contains("CT003")
                || text(d.get("mainTxnId")) == null) {
            throw new BusinessException("AA_NO_CONSENT", "This customer has no active periodic (CT003) consent");
        }
        List<Map<String, Object>> pulls = pulls(d);
        if (pulls.stream().anyMatch(x -> PENDING.equals(x.get("status")))) {
            throw new BusinessException("AA_PULL_IN_PROGRESS", "A pull is already in progress for this customer");
        }
        ProviderCallContext.setApplicationId(appId);
        ProviderCallContext.setCheckType(BANK_AA);
        AccountAggregatorPort.Pull r;
        try {
            r = aa.initiatePeriodicFetch(text(d.get("mainTxnId")), "CT003");
        } catch (RuntimeException providerFailure) {
            log.warn("AA periodic fetch failed application={} {}", appId, providerFailure.toString());
            throw new BusinessException("AA_PULL_FAILED", "The pull could not be started. Please try again.");
        }
        if (!r.ok()) {
            throw new BusinessException("AA_PULL_FAILED", r.code() + (r.message() == null ? "" : ": " + r.message()));
        }
        Map<String, Object> pull = new LinkedHashMap<>();
        pull.put("txnId", r.txnId());
        pull.put("status", PENDING);
        pull.put("code", r.code());
        pull.put("at", Instant.now().toString());
        pull.put("byName", actor.name());
        pull.put("byRole", actor.role());
        pulls.add(pull);
        d = new LinkedHashMap<>(d);
        d.put("pulls", pulls);
        save(appId, v, PASS, v.getProvider(), d, v.getMessage());
        return build(appId, row(appId).orElse(null));
    }

    // ------------------------------------------------------------------ polling

    /** Throttled provider poll. Never throws: a provider hiccup leaves the row as it was. */
    private ApplicationVerification refresh(Long appId, ApplicationVerification v) {
        Map<String, Object> d = read(v);
        boolean mainOpen = PENDING.equals(v.getStatus()) && text(d.get("requestId")) != null;
        boolean pullOpen = pulls(d).stream().anyMatch(x -> PENDING.equals(x.get("status")));
        if (!mainOpen && !pullOpen) {
            return v;
        }
        Instant last = d.get("lastPolledAt") == null ? null : Instant.parse(String.valueOf(d.get("lastPolledAt")));
        if (last != null && Duration.between(last, Instant.now()).compareTo(POLL_GAP) < 0) {
            return v;
        }
        d.put("lastPolledAt", Instant.now().toString());
        v = save(appId, v, v.getStatus(), v.getProvider(), d, v.getMessage());   // claim the slot first
        ProviderCallContext.setApplicationId(appId);
        ProviderCallContext.setCheckType(BANK_AA);
        String status = v.getStatus();
        try {
            String requestId = text(d.get("requestId"));
            if (mainOpen) {
                AccountAggregatorPort.Status st = aa.statusCheck(requestId, null);
                d.put("lastCode", st.code());
                if ("ReportGenerated".equals(st.code())) {
                    String report = aa.retrieveReport(st.txnId());
                    storeReport(appId, st.txnId(), report);
                    d.put("mainTxnId", st.txnId());
                    d.put("summary", summarise(report, st.txnId()));
                    status = PASS;
                }
            }
            if (pullOpen) {
                List<Map<String, Object>> pulls = pulls(d);
                for (Map<String, Object> pull : pulls) {
                    if (PENDING.equals(pull.get("status"))) {
                        advancePull(appId, requestId, pull);
                    }
                }
                d.put("pulls", pulls);
            }
        } catch (RuntimeException providerFailure) {
            log.warn("AA poll failed application={} {}", appId, providerFailure.toString());
            d.put("lastCode", "POLL_FAILED");
        }
        return save(appId, v, status, v.getProvider(), d, v.getMessage());
    }

    private void advancePull(Long appId, String requestId, Map<String, Object> pull) {
        String txnId = text(pull.get("txnId"));
        AccountAggregatorPort.Status st = aa.statusCheck(requestId, txnId);
        pull.put("code", st.code());
        if ("ReportGenerated".equals(st.code())) {
            pull.put("documentId", storeReport(appId, txnId, aa.retrieveReport(txnId)));
            pull.put("status", "REPORT_READY");
        } else if (Duration.between(Instant.parse(String.valueOf(pull.get("at"))), Instant.now())
                .compareTo(PULL_TIMEOUT) > 0) {
            pull.put("status", "FAILED");
        }
    }

    private Long storeReport(Long appId, String txnId, String report) {
        String key = storage.buildApplicationKey(appId, AA_REPORT, "json");
        byte[] bytes = report.getBytes(StandardCharsets.UTF_8);
        storage.store(key, bytes, "application/json");
        ApplicationDocument doc = new ApplicationDocument();
        doc.setApplicationId(appId);
        doc.setDocType(AA_REPORT);
        doc.setFileName("aa-report-" + txnId + ".json");
        doc.setContentType("application/json");
        doc.setSizeBytes((long) bytes.length);
        doc.setS3ObjectKey(key);
        return docs.save(doc).getId();
    }

    /** Best-effort digest of the type3 report; field names beyond the documented top level are probed. */
    Map<String, Object> summarise(String report, String txnId) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("txnId", txnId);
        try {
            JsonNode root = json.readTree(report);
            s.put("statementStart", text(root.path("statement_start_date")));
            s.put("statementEnd", text(root.path("statement_end_date")));
            s.put("fetchType", text(root.path("report_fetch_type")));
            JsonNode bank = root.path("banks").path(0);
            s.put("holderName", first(bank, "account_holder_name", "holder_name", "name", "customer_name"));
            s.put("accountType", first(bank, "account_type", "accountType", "type"));
            s.put("maskedAccount", first(bank, "masked_account_number", "account_number", "maskedAccNumber"));
            s.put("bankName", first(bank, "bank_name", "bank", "fip_name"));
        } catch (Exception unparseable) {
            log.warn("AA report summary failed txn={}", txnId);
        }
        return s;
    }

    private static String first(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.findValue(k);
            if (v != null && v.isValueNode() && !v.asText("").isBlank()) {
                return v.asText();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ request body

    /** One consent item per enabled template: CT001 is a one-time look back, CT003/CT035 periodic going forward. */
    static List<Map<String, Object>> consentRequests(boolean ct001, boolean ct003, boolean ct035,
                                                     int ct001Months, int ct003Months, int ct035Months,
                                                     LocalDate today) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (ct001) {
            out.add(consent("CT001", "ONETIME", today.minusMonths(ct001Months), today));
        }
        if (ct003) {
            out.add(consent("CT003", "PERIODIC", today, today.plusMonths(ct003Months)));
        }
        if (ct035) {
            out.add(consent("CT035", "PERIODIC", today, today.plusMonths(ct035Months)));
        }
        return out;
    }

    private static Map<String, Object> consent(String template, String fetchType, LocalDate from, LocalDate to) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("template_id", template);
        c.put("fetch_type", fetchType);
        c.put("fi_types", List.of("DEPOSIT"));
        c.put("fi_date_range", Map.of("start_date", from.toString(), "end_date", to.toString()));
        return c;
    }

    /** Digitap wants a bare 10-digit mobile (a 91 prefix is refused) and fills the %s placeholders itself. */
    static Map<String, Object> generateUrlBody(String clientRef, String mobile, String pan, LocalDate dob,
                                               boolean multiAa, String callbackUrl, String returnUrl,
                                               List<Map<String, Object>> consents) {
        String digits = mobile == null ? "" : mobile.replaceAll("\\D", "");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("client_ref_num", clientRef);
        b.put("destination", "accountaggregator");
        b.put("mobile_num", digits.length() > 10 ? digits.substring(digits.length() - 10) : digits);
        b.put("pan", pan);
        b.put("date_of_birth", dob == null ? null : dob.toString());
        if (multiAa) {
            b.put("multi_aa", "1");
        } else {
            b.put("aa_vendor", "anumati");
        }
        b.put("txn_completed_cburl", callbackUrl);
        b.put("return_url", returnUrl + (returnUrl.contains("?") ? "&" : "?") + "returned=1&transID=%s&reqID=%s");
        b.put("consent_request", consents);
        return b;
    }

    private static String consentEnd(List<Map<String, Object>> consents) {
        String end = null;
        for (Map<String, Object> c : consents) {
            if ("PERIODIC".equals(c.get("fetch_type"))) {
                String e = String.valueOf(((Map<?, ?>) c.get("fi_date_range")).get("end_date"));
                if (end == null || e.compareTo(end) > 0) {
                    end = e;
                }
            }
        }
        return end;
    }

    // ------------------------------------------------------------------ helpers

    private AaView build(Long appId, ApplicationVerification v) {
        boolean on = enabled();
        int max = bre.threshold(BreRuleCatalog.T_AA_MAX_ATTEMPTS);
        String phase = phaseOf(appId);
        Map<String, Object> d = v == null ? Map.of() : read(v);
        int attempts = attempts(d, phase);
        String status = v == null ? "NONE" : v.getStatus();
        boolean fallback = on && !PASS.equals(status) && attempts >= max;
        boolean completed = PASS.equals(status) || (REVIEW.equals(status) && phase.equals(d.getOrDefault("phase", "INTAKE")));
        CurrentActor actor = ActorContext.get();
        boolean staff = actor != null && !"BORROWER".equals(actor.role());
        return new AaView(on, status, attempts, max, Math.max(0, max - attempts), fallback, completed,
                staff ? (Map<String, Object>) d.get("summary") : null,
                staff ? new ArrayList<Object>(pulls(d)) : List.of());
    }

    private String phaseOf(Long appId) {
        return apps.findById(appId).map(a -> a.getStatus() == ApplicationStatus.SANCTIONED ? "OFFER" : "INTAKE")
                .orElse("INTAKE");
    }

    /** Attempts recorded in {@code phase}; a row from the other phase (e.g. carried from intake) starts at 0. */
    private static int attempts(Map<String, Object> d, String phase) {
        if (!phase.equals(d.getOrDefault("phase", "INTAKE"))) {
            return 0;
        }
        return d.get("attempts") instanceof Number n ? n.intValue() : 0;
    }

    private java.util.Optional<ApplicationVerification> row(Long appId) {
        return rows.findByApplicationIdAndCheckType(appId, BANK_AA);
    }

    private ApplicationVerification save(Long appId, ApplicationVerification v, String status, String provider,
                                         Map<String, Object> derived, String message) {
        ApplicationVerification row = v == null ? new ApplicationVerification() : v;
        row.setApplicationId(appId);
        row.setCheckType(BANK_AA);
        row.setStatus(status);
        row.setProvider(provider);
        row.setClientRefNum(text(derived.get("clientRef")));
        row.setProviderTxnId(text(derived.get("mainTxnId")) != null ? text(derived.get("mainTxnId"))
                : text(derived.get("requestId")));
        row.setMessage(message);
        row.setReopenedAt(null);
        row.setReopenedBy(null);
        try {
            row.setDerived(json.writeValueAsString(derived));
            row.setRawResponse(json.writeValueAsString(Map.of("provider", String.valueOf(provider), "status", status)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return rows.save(row);
    }

    private Map<String, Object> read(ApplicationVerification v) {
        if (v == null || v.getDerived() == null || v.getDerived().isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return json.readValue(v.getDerived(), MAP);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> pulls(Map<String, Object> d) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (d.get("pulls") instanceof List<?> l) {
            for (Object o : l) {
                out.add(new LinkedHashMap<>((Map<String, Object>) o));
            }
        }
        return out;
    }

    private static List<?> templates(Map<String, Object> d) {
        return d.get("templates") instanceof List<?> l ? l : List.of();
    }

    private static String text(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new BusinessException("AA_DISABLED", "Account Aggregator is not enabled");
        }
    }

    /** http(s) only, and the host must be ours (or localhost) so the consent flow cannot bounce elsewhere. */
    private void requireReturnUrl(String returnUrl) {
        try {
            URI u = URI.create(returnUrl == null ? "" : returnUrl.trim());
            String host = u.getHost();
            URI ours = URI.create(frontendBaseUrl);
            boolean schemeOk = "https".equals(u.getScheme()) || "http".equals(u.getScheme());
            boolean hostOk = host != null && u.getUserInfo() == null
                    && (host.equals(ours.getHost()) || host.equals("localhost") || host.endsWith(".dhanboost.com") || host.equals("dhanboost.com"));
            if (schemeOk && hostOk) {
                return;
            }
        } catch (IllegalArgumentException malformed) {
            // fall through
        }
        throw new BusinessException("AA_INVALID_RETURN_URL", "Invalid return address");
    }
}
