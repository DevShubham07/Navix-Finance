package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.common.verification.BureauReportFacts;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.ApplicationDocument;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.pdf.CreditBriefPdfRenderer;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.util.Optional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class CreditBriefServiceTest {

    @Mock private DocumentStoragePort storage;
    @Mock private ApplicationDocumentRepository documentRepo;
    @Mock private CustomerProfileRepository profileRepo;
    @Mock private LoanApplicationRepository applicationRepo;
    @Mock private ApplicationVerificationRepository verificationRepo;
    @Mock private BureauStateService bureauStateService;

    @Test
    void generatedDocumentContainsTheCompleteProviderResponseAppendix() throws Exception {
        CreditBriefService service = new CreditBriefService(
                new CreditRatingCalculator(), new CreditBriefPdfRenderer(),
                new CreditBriefPdfWriter(storage, documentRepo),
                documentRepo, profileRepo, applicationRepo, verificationRepo, new ObjectMapper(),
                bureauStateService);
        CustomerProfile profile = new CustomerProfile();
        profile.setApplicationId(123L);
        profile.setBureauSource("DIGITAP_EXPERIAN");
        profile.setBureauScore(778L);
        profile.setFullName("TEST BORROWER");
        LoanApplication application = new LoanApplication();
        application.setCustomerId(45L);
        when(applicationRepo.findById(123L)).thenReturn(Optional.of(application));
        when(profileRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(123L, CreditBriefService.DOC_TYPE))
                .thenReturn(Optional.empty());
        when(documentRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        BureauReportFacts facts = new BureauReportFacts(
                "TEST BORROWER", "ABCDE1234F", "9000000000", "1990-01-01", "Testville", "100001",
                778, 11, 9, 2, 0, 805314L, 717556L, 87758L, 0, "TEST-REPORT-1");
        String raw = """
                {"result":{"result_json":{"INProfileResponse":{"CAIS_Account":{
                "CAIS_Account_DETAILS":[{"Subscriber_Name":"TEST BANK","Account_Status":"11"}]}}}}}
                """;

        service.generate(123L, profile, facts, raw);

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(storage).store(anyString(), bytes.capture(), anyString());
        PdfReader reader = new PdfReader(bytes.getValue());
        StringBuilder text = new StringBuilder();
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            text.append(new PdfTextExtractor(reader).getTextFromPage(page)).append(' ');
        }
        reader.close();
        assertThat(text.toString().replaceAll("\\s+", " "))
                .contains("City Testville", "PIN 100001", "Complete Provider Response",
                        "Subscriber Name", "TEST BANK", "Account Status", "11");
    }

    @Test
    void staffViewReturnsTheCompleteStoredProviderResponseAsStructuredJson() throws Exception {
        ObjectMapper json = new ObjectMapper();
        CreditBriefService service = new CreditBriefService(
                new CreditRatingCalculator(), new CreditBriefPdfRenderer(),
                new CreditBriefPdfWriter(storage, documentRepo),
                documentRepo, profileRepo, applicationRepo, verificationRepo, json,
                bureauStateService);
        BureauReportFacts facts = new BureauReportFacts(
                "TEST BORROWER", "ABCDE1234F", "9000000000", "1990-01-01", "Testville", "100001",
                778, 11, 9, 2, 0, 805314L, 717556L, 87758L, 0, "TEST-REPORT-1");
        CustomerProfile profile = new CustomerProfile();
        profile.setApplicationId(123L);
        profile.setFullName("TEST BORROWER");
        profile.setPan("ABCDE1234F");
        profile.setMobile("9000000000");
        profile.setDob(LocalDate.of(1990, 1, 1));
        profile.setBureauScore(778L);
        profile.setCreditStarRating(BigDecimal.valueOf(4.5));
        profile.setCreditRecommendation("STRONGLY RECOMMEND");
        profile.setCreditBriefSummary("Strong report");
        profile.setCreditBriefGeneratedAt(Instant.parse("2026-08-09T17:45:00Z"));
        profile.setCreditBriefFacts(json.writeValueAsString(facts));
        when(profileRepo.findByApplicationId(123L)).thenReturn(Optional.of(profile));
        ApplicationDocument document = new ApplicationDocument();
        document.setId(99L);
        when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(123L, CreditBriefService.DOC_TYPE))
                .thenReturn(Optional.of(document));
        ApplicationVerification bureau = new ApplicationVerification();
        bureau.setCheckType("BUREAU");
        bureau.setRawResponse("""
                {"result":{"result_json":{"INProfileResponse":{"CAIS_Account":{
                "CAIS_Account_DETAILS":[{"Subscriber_Name":"TEST BANK","Payment_History_Profile":"000000"}]}}}}}
                """);
        when(verificationRepo.findByApplicationIdAndCheckType(123L, "BUREAU"))
                .thenReturn(Optional.of(bureau));

        var view = service.view(123L);

        assertThat(view.providerResponse().path("result").path("result_json").path("INProfileResponse")
                .path("CAIS_Account").path("CAIS_Account_DETAILS").path(0)
                .path("Payment_History_Profile").asText()).isEqualTo("000000");
        assertThat(view.facts().city()).isEqualTo("Testville");
        assertThat(view.facts().pin()).isEqualTo("100001");
    }

    /**
     * The 500 this guard exists to prevent: {@code CustomerService.detail()} is
     * {@code @Transactional(readOnly = true)}, so the lazy PDF regeneration inside {@code view()} used
     * to attempt an INSERT on a read-only transaction. Postgres rejects it (25006) and — swallowed or
     * not — marks the whole transaction aborted, so the caller's next read fails too. On a read-only
     * transaction the brief must still be returned, just without touching storage or the document table.
     */
    @Test
    void viewOnAReadOnlyTransactionReturnsTheBriefWithoutWritingAnything() throws Exception {
        ObjectMapper json = new ObjectMapper();
        CreditBriefService service = new CreditBriefService(
                new CreditRatingCalculator(), new CreditBriefPdfRenderer(),
                new CreditBriefPdfWriter(storage, documentRepo),
                documentRepo, profileRepo, applicationRepo, verificationRepo, json,
                bureauStateService);
        BureauReportFacts facts = new BureauReportFacts(
                "TEST BORROWER", "ABCDE1234F", "9000000000", "1990-01-01", "Testville", "100001",
                778, 11, 9, 2, 0, 805314L, 717556L, 87758L, 0, "TEST-REPORT-1");
        CustomerProfile profile = new CustomerProfile();
        profile.setApplicationId(123L);
        profile.setFullName("TEST BORROWER");
        profile.setCreditStarRating(BigDecimal.valueOf(4.5));
        profile.setCreditBriefFacts(json.writeValueAsString(facts));
        when(profileRepo.findByApplicationId(123L)).thenReturn(Optional.of(profile));
        // Facts present, CREDIT_BRIEF document missing — exactly the reborrow state that triggered
        // the regeneration attempt.
        when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(123L, CreditBriefService.DOC_TYPE))
                .thenReturn(Optional.empty());

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        try {
            var view = service.view(123L);

            assertThat(view.available()).isTrue();
            assertThat(view.starRating()).isEqualTo(4.5);
            assertThat(view.documentId()).isNull(); // no PDF yet — cosmetic, and the read survives
        } finally {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        verifyNoInteractions(storage);
        verify(documentRepo, never()).save(any());
    }
}
