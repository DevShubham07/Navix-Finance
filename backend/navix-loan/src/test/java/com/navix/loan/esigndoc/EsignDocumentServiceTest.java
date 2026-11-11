package com.navix.loan.esigndoc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import com.navix.common.exception.BusinessException;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.notification.event.EsignInviteEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.common.verification.EsignPort;
import com.navix.common.verification.EsignPort.ContractRequest;
import com.navix.common.verification.EsignPort.ContractSession;
import com.navix.common.verification.EsignPort.ContractSignerLink;
import com.navix.common.verification.EsignPort.ContractSignerStatus;
import com.navix.common.verification.EsignPort.ContractStatus;
import com.navix.loan.esigndoc.EsignDocumentDtos.CreateRequest;
import com.navix.loan.esigndoc.EsignDocumentDtos.Detail;
import com.navix.loan.esigndoc.EsignDocumentDtos.SignerInput;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class EsignDocumentServiceTest {

    private static final String KEY = "esign-documents/abc-agreement.pdf";

    private final EsignDocumentRepository documents = mock(EsignDocumentRepository.class);
    private final EsignDocumentSignerRepository signers = mock(EsignDocumentSignerRepository.class);
    private final EsignPort esign = mock(EsignPort.class);
    private final DocumentStoragePort storage = mock(DocumentStoragePort.class);
    private final FeatureFlagService flags = mock(FeatureFlagService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private EsignDocumentService service;
    private final List<EsignDocumentSigner> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new EsignDocumentService(documents, signers, esign, storage, flags, events);
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "https://app.test/");
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        when(flags.isEnabled(EsignDocumentService.FLAG, false)).thenReturn(true);
        when(documents.save(any())).thenAnswer(i -> {
            EsignDocument d = i.getArgument(0);
            if (d.getId() == null) {
                d.setId(7L);
            }
            return d;
        });
        when(signers.saveAll(any())).thenAnswer(i -> {
            List<EsignDocumentSigner> rows = new ArrayList<>(i.getArgument(0));
            saved.clear();
            saved.addAll(rows);
            long id = 100;
            for (EsignDocumentSigner r : rows) {
                r.setId(id++);
            }
            return rows;
        });
        when(signers.findByDocumentIdOrderBySignerOrder(7L)).thenAnswer(i -> new ArrayList<>(saved));
        when(storage.presignDownload(anyString())).thenAnswer(i -> "https://s3/" + i.getArgument(0));
    }

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    private static byte[] pdf(int pages) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document();
        PdfWriter.getInstance(doc, out);
        doc.open();
        for (int i = 0; i < pages; i++) {
            if (i > 0) {
                doc.newPage();
            }
            doc.add(new Paragraph("page " + (i + 1)));
        }
        doc.close();
        return out.toByteArray();
    }

    private static CreateRequest request(boolean sendEmail, SignerInput... s) {
        return new CreateRequest("Vendor MSA", KEY, "agreement.pdf", sendEmail, List.of(s));
    }

    private static SignerInput external(String name, String email, String pages) {
        return new SignerInput(name, email, false, pages, "BottomLeft");
    }

    private static SignerInput self(String name) {
        return new SignerInput(name, null, true, "Last", "TopRight");
    }

    private static BusinessException refused(Runnable r) {
        return (BusinessException) org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class, r::run);
    }

    @Test
    void nonAdminIsRefusedEverywhere() {
        ActorContext.set(new CurrentActor("2", "Exec", "CREDIT_HEAD"));
        assertThat(refused(() -> service.create(request(false, self("A"))))).hasFieldOrPropertyWithValue("code", "FORBIDDEN_ROLE");
        assertThat(refused(() -> service.list())).hasFieldOrPropertyWithValue("code", "FORBIDDEN_ROLE");
        assertThat(refused(() -> service.refresh(1L))).hasFieldOrPropertyWithValue("code", "FORBIDDEN_ROLE");
        verify(esign, never()).initiateContract(any());
    }

    @Test
    void flagOffRefusesCreate() {
        when(flags.isEnabled(EsignDocumentService.FLAG, false)).thenReturn(false);
        assertThat(refused(() -> service.create(request(false, self("A"))))).hasFieldOrPropertyWithValue("code", "FEATURE_DISABLED");
        verify(esign, never()).initiateContract(any());
    }

    @Test
    void pageBeyondCountIsRefusedBeforeAnyContractIsMinted() throws Exception {
        when(storage.fetch(KEY)).thenReturn(pdf(2));
        BusinessException e = refused(() -> service.create(request(false, external("Ravi", null, "1,3"))));
        assertThat(e.getCode()).isEqualTo("ESIGN_DOC_INVALID");
        assertThat(e.getMessage()).contains("2 page");
        verify(esign, never()).initiateContract(any());
    }

    @Test
    void keyOutsideThePrefixAndNonPdfAreRefused() throws Exception {
        assertThat(refused(() -> service.create(new CreateRequest("T", "kyc/document/x.pdf", "x.pdf", false,
                List.of(self("A")))))).hasFieldOrPropertyWithValue("code", "ESIGN_DOC_INVALID");
        when(storage.fetch(KEY)).thenReturn("not a pdf".getBytes());
        assertThat(refused(() -> service.create(request(false, self("A"))))).hasFieldOrPropertyWithValue("code", "ESIGN_DOC_INVALID");
    }

    @Test
    void emailRequiredForExternalSignerWhenSendingButNeverForSelf() {
        assertThat(refused(() -> service.create(request(true, external("Ravi", " ", "All")))))
                .hasFieldOrPropertyWithValue("code", "ESIGN_DOC_INVALID");
    }

    @Test
    void successPersistsSignersResolvesPagesAndEmailsOnlyExternalSigners() throws Exception {
        when(storage.fetch(KEY)).thenReturn(pdf(4));
        when(esign.initiateContract(any())).thenReturn(new ContractSession("c-1", List.of(
                new ContractSignerLink("s-1", "https://sign/1"),
                new ContractSignerLink("s-2", "https://sign/2"),
                new ContractSignerLink("s-3", "https://sign/3"))));

        Detail d = service.create(request(true,
                self("Admin Name"), external("Ravi", "ravi@x.com", "Last"), external("Asha", "asha@x.com", "1,3")));

        assertThat(d.status()).isEqualTo("SENT");
        assertThat(d.pageCount()).isEqualTo(4);
        assertThat(d.contractId()).isEqualTo("c-1");
        assertThat(d.signers()).extracting("pages").containsExactly("Last", "Last", "1,3");
        assertThat(d.signers().get(0).email()).isNull();
        assertThat(d.signers().get(1).signUrl()).isEqualTo("https://sign/2");

        ArgumentCaptor<ContractRequest> sent = ArgumentCaptor.forClass(ContractRequest.class);
        verify(esign).initiateContract(sent.capture());
        assertThat(sent.getValue().signers().get(1).pages()).containsExactly("4");
        assertThat(sent.getValue().signers().get(2).pages()).containsExactly("1", "3");
        assertThat(sent.getValue().successRedirectUrl()).isEqualTo("https://app.test/esign/done?status=ok");
        assertThat(sent.getValue().failureRedirectUrl()).isEqualTo("https://app.test/esign/done?status=failed");

        ArgumentCaptor<EsignInviteEvent> ev = ArgumentCaptor.forClass(EsignInviteEvent.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(ev.capture());
        assertThat(ev.getAllValues()).extracting(EsignInviteEvent::email).containsExactly("ravi@x.com", "asha@x.com");
    }

    @Test
    void providerFailureKeepsTheRecordAsFailed() throws Exception {
        when(storage.fetch(KEY)).thenReturn(pdf(1));
        when(esign.initiateContract(any())).thenThrow(new RuntimeException("boom"));

        Detail d = service.create(request(true, external("Ravi", "ravi@x.com", "All")));

        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.errorMessage()).contains("boom");
        verify(events, never()).publishEvent(any(Object.class));
    }

    private EsignDocument sentDoc() {
        EsignDocument doc = new EsignDocument();
        doc.setId(7L);
        doc.setTitle("T");
        doc.setFileName("t.pdf");
        doc.setS3ObjectKey(KEY);
        doc.setPageCount(1);
        doc.setContractId("c-1");
        doc.setStatus("SENT");
        when(documents.findById(7L)).thenReturn(Optional.of(doc));
        EsignDocumentSigner s = new EsignDocumentSigner();
        s.setId(100L);
        s.setDocumentId(7L);
        s.setSignerOrder(1);
        s.setName("Ravi");
        s.setPages("All");
        s.setPosition("BottomLeft");
        s.setSignerId("s-1");
        when(signers.findByDocumentIdOrderBySignerOrder(7L)).thenReturn(List.of(s));
        return doc;
    }

    @Test
    void refreshToCompletedStoresTheSignedPdf() {
        EsignDocument doc = sentDoc();
        byte[] signed = "%PDF-signed".getBytes();
        when(esign.fetchContract("c-1")).thenReturn(new ContractStatus(false, true,
                List.of(new ContractSignerStatus("s-1", "SIGNED", null, null)), signed, "https://audit"));

        Detail d = service.refresh(7L);

        verify(storage).store("esign-documents/7/signed.pdf", signed, "application/pdf");
        assertThat(d.status()).isEqualTo("COMPLETED");
        assertThat(d.signedUrl()).isEqualTo("https://s3/esign-documents/7/signed.pdf");
        assertThat(d.auditCertificateUrl()).isEqualTo("https://audit");
        assertThat(d.signers().get(0).status()).isEqualTo("SIGNED");
        assertThat(doc.getCompletedAt()).isNotNull();
    }

    @Test
    void expiredContractMarksTheDocumentExpired() {
        sentDoc();
        when(esign.fetchContract("c-1")).thenReturn(new ContractStatus(true, false, List.of(), null, null));
        assertThat(service.refresh(7L).status()).isEqualTo("EXPIRED");
    }

    @Test
    void callbackForUnknownContractIsANoOpAndKnownOneIsResolved() {
        when(documents.findFirstByContractId("nope")).thenReturn(Optional.empty());
        assertThat(service.onCallback("nope")).isFalse();
        verify(esign, never()).fetchContract(anyString());

        EsignDocument doc = sentDoc();
        when(documents.findFirstByContractId("c-1")).thenReturn(Optional.of(doc));
        when(esign.fetchContract("c-1")).thenReturn(new ContractStatus(true, false, List.of(), null, null));
        ActorContext.clear(); // the webhook has no actor
        assertThat(service.onCallback("c-1")).isTrue();
        assertThat(doc.getStatus()).isEqualTo("EXPIRED");
    }

    @Test
    void resendPublishesOneInviteForAnEmailedPendingSigner() {
        sentDoc();
        EsignDocumentSigner s = signers.findByDocumentIdOrderBySignerOrder(7L).get(0);
        s.setEmail("ravi@x.com");
        s.setSignUrl("https://sign/1");
        when(signers.findById(100L)).thenReturn(Optional.of(s));

        service.resend(7L, 100L);

        verify(events).publishEvent(eq(new EsignInviteEvent(100L, "T", "Ravi", "ravi@x.com", "https://sign/1")));
    }
}
