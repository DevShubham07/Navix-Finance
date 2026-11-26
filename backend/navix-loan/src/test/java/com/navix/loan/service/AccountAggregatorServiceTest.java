package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.common.verification.AccountAggregatorPort;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AccountAggregatorServiceTest {

    private static final long APP = 5L;
    private static final String RETURN = "https://www.dhanboost.com/signup/account-aggregator";

    private final BreRuleService bre = mock(BreRuleService.class);
    private final ApplicationVerificationRepository rows = mock(ApplicationVerificationRepository.class);
    private final ApplicationDocumentRepository docs = mock(ApplicationDocumentRepository.class);
    private final CustomerProfileRepository profiles = mock(CustomerProfileRepository.class);
    private final LoanApplicationRepository apps = mock(LoanApplicationRepository.class);
    private final AccountAggregatorPort port = mock(AccountAggregatorPort.class);
    private final DocumentStoragePort storage = mock(DocumentStoragePort.class);
    private final AtomicReference<ApplicationVerification> stored = new AtomicReference<>();
    private AccountAggregatorService service;
    private LoanApplication app;

    @BeforeEach
    void setUp() {
        service = new AccountAggregatorService(bre, rows, docs, profiles, apps, port, storage, new ObjectMapper());
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "https://www.dhanboost.com");
        ReflectionTestUtils.setField(service, "callbackUrl", "https://www.dhanboost.com/api/webhooks/digitap-aa");
        app = new LoanApplication();
        app.setId(APP);
        app.setStatus(ApplicationStatus.DRAFT);
        lenient().when(apps.findById(APP)).thenReturn(Optional.of(app));
        lenient().when(bre.isOn(BreRuleCatalog.AA_ENABLED)).thenReturn(true);
        lenient().when(bre.isOn(BreRuleCatalog.AA_CT003)).thenReturn(true);
        lenient().when(bre.threshold(BreRuleCatalog.T_AA_MAX_ATTEMPTS)).thenReturn(3);
        lenient().when(bre.threshold(BreRuleCatalog.T_AA_CT003_MONTHS)).thenReturn(12);
        CustomerProfile p = new CustomerProfile();
        p.setMobile("+91 74176 82036");
        p.setPan("ABCDE1234F");
        p.setDob(LocalDate.of(1990, 5, 17));
        lenient().when(profiles.findByApplicationId(APP)).thenReturn(Optional.of(p));
        lenient().when(rows.findByApplicationIdAndCheckType(APP, "BANK_AA"))
                .thenAnswer(i -> Optional.ofNullable(stored.get()));
        lenient().when(rows.save(any())).thenAnswer(i -> {
            stored.set(i.getArgument(0));
            return i.getArgument(0);
        });
        lenient().when(port.generateUrl(any())).thenReturn(new AccountAggregatorPort.Link("https://aa/x", "777"));
        as("BORROWER");
    }

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    private static void as(String role) {
        ActorContext.set(new CurrentActor("9", "Tester", role));
    }

    private AccountAggregatorService.AaView view() {
        return service.view(APP);
    }

    @Test
    void generateUrlBodyUsesTenDigitMobileVendorAndTemplateDates() {
        LocalDate today = LocalDate.of(2026, 11, 27);
        var consents = AccountAggregatorService.consentRequests(true, true, false, 12, 6, 12, today);

        Map<String, Object> body = AccountAggregatorService.generateUrlBody("navix-5-BANK_AA-1", "+917417682036",
                "ABCDE1234F", LocalDate.of(1990, 5, 17), false, "https://cb", RETURN, consents);

        assertThat(body.get("mobile_num")).isEqualTo("7417682036");
        assertThat(body.get("aa_vendor")).isEqualTo("anumati");
        assertThat(body).doesNotContainKey("multi_aa");
        assertThat(body.get("date_of_birth")).isEqualTo("1990-05-17");
        assertThat(String.valueOf(body.get("return_url"))).endsWith("?returned=1&transID=%s&reqID=%s");
        assertThat(consents).hasSize(2);
        assertThat(consents.get(0)).containsEntry("template_id", "CT001").containsEntry("fetch_type", "ONETIME")
                .containsEntry("fi_date_range", Map.of("start_date", "2025-11-27", "end_date", "2026-11-27"));
        assertThat(consents.get(1)).containsEntry("template_id", "CT003").containsEntry("fetch_type", "PERIODIC")
                .containsEntry("fi_date_range", Map.of("start_date", "2026-06-01", "end_date", "2027-05-27"));
        assertThat(AccountAggregatorService.generateUrlBody("r", "7417682036", "P", null, true, "c", RETURN, consents))
                .containsEntry("multi_aa", "1").doesNotContainKey("aa_vendor");
    }

    @Test
    void initCountsEveryAttemptAndOffersTheFallbackAtTheMaximum() {
        for (int i = 1; i <= 3; i++) {
            assertThat(view().fallbackAvailable()).isFalse();
            assertThat(service.init(APP, RETURN).url()).isEqualTo("https://aa/x");
            assertThat(view().attempts()).isEqualTo(i);
        }
        AccountAggregatorService.AaView v = view();
        assertThat(v.status()).isEqualTo("PENDING");
        assertThat(v.attemptsLeft()).isZero();
        assertThat(v.fallbackAvailable()).isTrue();
        assertThat(v.completed()).isFalse();
        // the first link used the application-attributed client ref
        verify(port, org.mockito.Mockito.times(3)).generateUrl(any());
    }

    @Test
    void initRefusedWhenDisabledOrReturnUrlForeign() {
        when(bre.isOn(BreRuleCatalog.AA_ENABLED)).thenReturn(false);
        assertThatThrownBy(() -> service.init(APP, RETURN)).hasFieldOrPropertyWithValue("code", "AA_DISABLED");
        when(bre.isOn(BreRuleCatalog.AA_ENABLED)).thenReturn(true);
        assertThatThrownBy(() -> service.init(APP, "https://evil.example/x"))
                .hasFieldOrPropertyWithValue("code", "AA_INVALID_RETURN_URL");
        verify(port, never()).generateUrl(any());
    }

    @Test
    void statusStoresTheReportAndPassesTheStep() {
        service.init(APP, RETURN);
        when(port.statusCheck("777", null)).thenReturn(new AccountAggregatorPort.Status("ReportGenerated", "da1"));
        when(port.retrieveReport("da1")).thenReturn(
                "{\"statement_start_date\":\"2026-01-01\",\"banks\":[{\"account_type\":\"SAVINGS\",\"masked_account_number\":\"XXXX1234\"}]}");
        when(storage.buildApplicationKey(APP, "AA_REPORT", "json")).thenReturn("applications/5/aa_report/1.json");
        when(docs.save(any())).thenAnswer(i -> i.getArgument(0));

        as("ADMIN");
        AccountAggregatorService.AaView v = service.status(APP);

        assertThat(v.status()).isEqualTo("PASS");
        assertThat(v.completed()).isTrue();
        assertThat(v.summary()).containsEntry("accountType", "SAVINGS").containsEntry("maskedAccount", "XXXX1234");
        verify(storage).store(eq("applications/5/aa_report/1.json"), any(), eq("application/json"));
        assertThat(service.intakeSatisfied(APP)).isTrue();
        // a borrower never sees the summary
        as("BORROWER");
        assertThat(service.view(APP).summary()).isNull();
    }

    @Test
    void statusIsThrottledAndTxnNotFoundStaysPending() {
        service.init(APP, RETURN);
        when(port.statusCheck("777", null)).thenReturn(new AccountAggregatorPort.Status("TxnNotFound", null));

        assertThat(service.status(APP).status()).isEqualTo("PENDING");
        assertThat(service.status(APP).status()).isEqualTo("PENDING");

        verify(port, org.mockito.Mockito.times(1)).statusCheck(anyString(), isNull());
        verify(port, never()).retrieveReport(any());
    }

    @Test
    void statementFallbackNeedsMaxAttemptsAndAStatement() {
        service.init(APP, RETURN);
        assertThatThrownBy(() -> service.statementFallback(APP))
                .hasFieldOrPropertyWithValue("code", "AA_FALLBACK_NOT_AVAILABLE");
        service.init(APP, RETURN);
        service.init(APP, RETURN);
        assertThatThrownBy(() -> service.statementFallback(APP))
                .hasFieldOrPropertyWithValue("code", "BANK_STATEMENT_REQUIRED");

        when(docs.findFirstByApplicationIdAndDocTypeOrderByIdDesc(APP, "BANK_STATEMENT"))
                .thenReturn(Optional.of(new ApplicationDocument()));
        assertThat(service.statementFallback(APP).status()).isEqualTo("REVIEW");
        assertThat(stored.get().getProvider()).isEqualTo("MANUAL_STATEMENT");
        assertThat(service.intakeSatisfied(APP)).isTrue();
    }

    @Test
    void offerJourneyAppliesUntilPassedAndCountsAttemptsFresh() {
        // intake fell back to a statement (REVIEW, 3 attempts)...
        service.init(APP, RETURN);
        service.init(APP, RETURN);
        service.init(APP, RETURN);
        when(docs.findFirstByApplicationIdAndDocTypeOrderByIdDesc(APP, "BANK_STATEMENT"))
                .thenReturn(Optional.of(new ApplicationDocument()));
        service.statementFallback(APP);
        // ...so after sanction the offer journey still asks for AA, with a full set of attempts.
        app.setStatus(ApplicationStatus.SANCTIONED);
        assertThat(service.offerApplicable(APP)).isTrue();
        assertThat(service.offerPending(APP)).isTrue();
        assertThat(view().attempts()).isZero();

        service.init(APP, RETURN);
        service.init(APP, RETURN);
        service.init(APP, RETURN);
        assertThat(service.offerPending(APP)).isFalse(); // out of attempts: continue without AA
    }

    @Test
    void connectedAtIntakeIsNotOfferedAgain() {
        service.init(APP, RETURN);
        when(port.statusCheck("777", null)).thenReturn(new AccountAggregatorPort.Status("ReportGenerated", "da1"));
        when(port.retrieveReport("da1")).thenReturn("{}");
        when(docs.save(any())).thenAnswer(i -> i.getArgument(0));
        service.status(APP);
        app.setStatus(ApplicationStatus.SANCTIONED);

        assertThat(service.offerApplicable(APP)).isFalse();
        assertThat(service.offerPending(APP)).isFalse();
    }

    @Test
    void pullRoleMatrix() {
        for (String role : List.of("BORROWER", "DSA", "TELECALLER", "DISBURSEMENT_HEAD", "ACCOUNTANT")) {
            as(role);
            assertThatThrownBy(() -> service.pull(APP)).hasFieldOrPropertyWithValue("code", "FORBIDDEN_ROLE");
        }
        for (String role : List.of("ADMIN", "CREDIT_HEAD", "CREDIT_EXECUTIVE", "COLLECTION_HEAD", "COLLECTION_EXECUTIVE")) {
            as(role);
            // allowed past the role gate; no consent yet, so refused for that reason instead
            assertThatThrownBy(() -> service.pull(APP)).isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "AA_NO_CONSENT");
        }
    }

    @Test
    void pullStartsOnePeriodicFetchAtATime() {
        service.init(APP, RETURN);
        when(port.statusCheck("777", null)).thenReturn(new AccountAggregatorPort.Status("ReportGenerated", "da1"));
        when(port.retrieveReport("da1")).thenReturn("{}");
        when(docs.save(any())).thenAnswer(i -> i.getArgument(0));
        service.status(APP);
        when(port.initiatePeriodicFetch("da1", "CT003"))
                .thenReturn(new AccountAggregatorPort.Pull(true, "AAFIPullRequestSuccess", "ok", "ap1"));

        as("CREDIT_EXECUTIVE");
        AccountAggregatorService.AaView v = service.pull(APP);

        assertThat(v.pulls()).hasSize(1);
        assertThat(((Map<?, ?>) v.pulls().get(0)).get("txnId")).isEqualTo("ap1");
        assertThat(((Map<?, ?>) v.pulls().get(0)).get("byRole")).isEqualTo("CREDIT_EXECUTIVE");
        assertThatThrownBy(() -> service.pull(APP)).hasFieldOrPropertyWithValue("code", "AA_PULL_IN_PROGRESS");
        verify(port, org.mockito.Mockito.times(1)).initiatePeriodicFetch(anyString(), anyString());
    }

    @Test
    void pullSurfacesDigitapErrorCode() {
        service.init(APP, RETURN);
        when(port.statusCheck("777", null)).thenReturn(new AccountAggregatorPort.Status("ReportGenerated", "da1"));
        when(port.retrieveReport("da1")).thenReturn("{}");
        when(docs.save(any())).thenAnswer(i -> i.getArgument(0));
        service.status(APP);
        when(port.initiatePeriodicFetch("da1", "CT003"))
                .thenReturn(new AccountAggregatorPort.Pull(false, "AAMaxAllowedPullExceededError", "limit", null));

        as("ADMIN");
        assertThatThrownBy(() -> service.pull(APP)).hasFieldOrPropertyWithValue("code", "AA_PULL_FAILED")
                .hasMessageContaining("AAMaxAllowedPullExceededError");
    }
}
