package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.storage.DocumentStoragePort;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.OfferDtos.DisbursalAccountRequest;
import com.navix.loan.entity.ApplicationDocument;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.pdf.SanctionLetterPdfRenderer;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.ApplicationReferenceRepository;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.service.ApplicationVerificationService.StepResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Task 4: the OFFER_MANDATE step sits between the disbursal account and hand-off, and gates it. */
@ExtendWith(MockitoExtension.class)
class MandateJourneyTest {

    private static final long APP = 1L;
    private static final long CUSTOMER = 7L;

    @Mock private LoanApplicationRepository applicationRepo;
    @Mock private CustomerProfileRepository profileRepo;
    @Mock private ApplicationVerificationRepository verificationRepo;
    @Mock private ApplicationReferenceRepository referenceRepo;
    @Mock private ApplicationDocumentRepository documentRepo;
    @Mock private PennyDropGuard pennyDropGuard;
    @Mock private ApplicationVerificationService verification;
    @Mock private ApplicationFlowService flow;
    @Mock private JourneyService journeyMock;
    @Mock private ProfileChangeLogger changeLogger;
    @Mock private SanctionLetterPdfRenderer letterRenderer;
    @Mock private DocumentStoragePort storage;
    @Mock private MandateService mandateService;

    private OfferService offer;
    private JourneyService journey;
    private LoanApplication app;

    @BeforeEach
    void setUp() {
        offer = new OfferService(applicationRepo, profileRepo, referenceRepo, pennyDropGuard, documentRepo,
                verification, flow, journeyMock, changeLogger, letterRenderer, storage, new LoanMath(),
                mandateService);
        journey = new JourneyService(applicationRepo, profileRepo, verificationRepo, referenceRepo,
                documentRepo, mandateService);

        app = new LoanApplication();
        app.setId(APP);
        app.setCustomerId(CUSTOMER);
        app.setStatus(ApplicationStatus.SANCTIONED);
        app.setSanctionedAmountPaise(2_000_000L);
        app.setAmountRequested(1_500_000L);

        CustomerProfile profile = new CustomerProfile();
        profile.setApplicationId(APP);
        profile.setSalaryAccountNumber("123456789012");
        profile.setSalaryIfsc("HDFC0001234");

        lenient().when(applicationRepo.findById(APP)).thenReturn(Optional.of(app));
        lenient().when(applicationRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(profile));
        lenient().when(pennyDropGuard.lockedUntil(CUSTOMER)).thenReturn(Optional.empty());
        lenient().when(referenceRepo.findByApplicationIdOrderBySlotAsc(APP)).thenReturn(List.of());
        lenient().when(verification.verifyPennyDrop(any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new StepResult(ApplicationVerificationService.PENNY_DROP,
                        ApplicationVerificationService.PASS, "ok", Map.of()));
        lenient().when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(
                APP, ApplicationVerificationService.BANK_PROOF)).thenReturn(Optional.of(new ApplicationDocument()));
    }

    /** The one gate predicate: flag on and no usable mandate for this application. */
    private void flag(boolean on) {
        lenient().when(mandateService.mandateRequired(app)).thenReturn(on);
    }

    private static ApplicationVerification check(String type, String status) {
        ApplicationVerification v = new ApplicationVerification();
        v.setCheckType(type);
        v.setStatus(status);
        return v;
    }

    /** Everything up to and including the disbursal account is on file. */
    private void everythingButMandateDone() {
        app.setDisbursalConfirmedAt(java.time.Instant.now());
        when(referenceRepo.findByApplicationIdOrderBySlotAsc(APP)).thenReturn(List.of(
                new com.navix.loan.entity.ApplicationReference(), new com.navix.loan.entity.ApplicationReference()));
        when(verificationRepo.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of(
                check(ApplicationVerificationService.AADHAAR, ApplicationVerificationService.PASS),
                check(ApplicationVerificationService.SELFIE, ApplicationVerificationService.PASS),
                check(ApplicationVerificationService.ADDRESS, ApplicationVerificationService.PASS)));
        when(verificationRepo.findByApplicationIdAndCheckType(APP, ApplicationVerificationService.ESIGN))
                .thenReturn(Optional.of(check(ApplicationVerificationService.ESIGN, ApplicationVerificationService.PASS)));
    }

    @Test
    void confirmDisbursalAccountAdvancesToMandateNotDisbursement() {
        flag(true);
        offer.confirmDisbursalAccount(APP,
                new DisbursalAccountRequest("555544443333", "SBIN0009999", "Asha", null, false));
        verify(journeyMock).advance(APP, JourneyService.OfferStep.OFFER_MANDATE);
        verify(flow, never()).acceptOffer(any(), any());

        // The bank-proof exit must not be an escape hatch around the mandate.
        offer.confirmDisbursalAccount(APP,
                new DisbursalAccountRequest("999988887777", "ICIC0004321", "Asha", null, true));
        verify(journeyMock, org.mockito.Mockito.times(2)).advance(APP, JourneyService.OfferStep.OFFER_MANDATE);
        verify(flow, never()).acceptOffer(any(), any());
    }

    @Test
    void mandateStatusWithUmrnCallsAcceptOffer() {
        flag(true);
        app.setDisbursalConfirmedAt(java.time.Instant.now());
        when(mandateService.status(APP)).thenReturn(new StepResult("EMANDATE", "PASS", "ok", Map.of()));
        when(mandateService.mandateRequired(app)).thenReturn(false); // the UMRN landed

        offer.mandateStatus(APP);

        verify(journeyMock).advance(APP, JourneyService.OfferStep.OFFER_DONE);
        verify(flow).acceptOffer(APP, 1_500_000L);
    }

    @Test
    void mandateStatusWithoutUmrnDoesNotAcceptOffer() {
        flag(true);
        when(mandateService.status(APP)).thenReturn(new StepResult("EMANDATE", "PENDING", "wait", Map.of()));

        offer.mandateStatus(APP);

        verify(flow, never()).acceptOffer(any(), any());
    }

    @Test
    void deriveOfferReturnsMandateWhenAccountConfirmedAndNoUmrn() {
        flag(true);
        everythingButMandateDone();
        assertThat(journey.current(APP).step()).isEqualTo("OFFER_MANDATE");
        assertThat(journey.current(APP).route()).isEqualTo("/loan/mandate");
    }

    @Test
    void deriveOfferReturnsDoneWhenCustomerAlreadyHasUmrn() {
        flag(true);
        everythingButMandateDone();
        flag(false);
        assertThat(journey.current(APP).step()).isEqualTo("OFFER_DONE");
    }

    @Test
    void stepIsDroppedForExistingUmrnHolderOnNonReborrowApplication() {
        flag(true);
        app.setReappliedFrom(null);
        flag(false);
        assertThat(journey.current(APP).steps()).doesNotContain("OFFER_MANDATE");
    }

    @Test
    void stepIsPresentForFirstTimeBorrower() {
        flag(true);
        assertThat(journey.current(APP).steps()).contains("OFFER_MANDATE");
    }

    @Test
    void flagOffLetsAccountConfirmationRouteStraightToDisbursement() {
        flag(false);
        offer.confirmDisbursalAccount(APP,
                new DisbursalAccountRequest("555544443333", "SBIN0009999", "Asha", null, false));
        verify(journeyMock).advance(APP, JourneyService.OfferStep.OFFER_DONE);
        verify(flow).acceptOffer(APP, 1_500_000L);
        assertThat(journey.current(APP).steps()).doesNotContain("OFFER_MANDATE");
    }

    /** F12: a second poll once the file is already at DISBURSEMENT_PENDING reports success, not an error. */
    @Test
    void repeatMandatePollAfterHandOffDoesNotThrow() {
        flag(true);
        app.setStatus(ApplicationStatus.DISBURSEMENT_PENDING);
        when(mandateService.status(APP)).thenReturn(new StepResult("EMANDATE", "PASS", "ok", Map.of()));

        assertThat(offer.mandateStatus(APP).status()).isEqualTo("PASS");
        verify(flow, never()).acceptOffer(any(), any());
    }

    /**
     * I4: after a staff BANK_UNSUPPORTED override the file is at DISBURSEMENT_PENDING with NO usable
     * mandate — the page must get the stored row, not NOT_APPLICABLE three times and "Still waiting".
     */
    @Test
    void pollAfterAStaffOverrideReportsTheStoredRowInsteadOfThrowing() {
        flag(true);
        app.setStatus(ApplicationStatus.DISBURSEMENT_PENDING);
        when(mandateService.status(APP))
                .thenReturn(new StepResult("EMANDATE", "PENDING", "bank unsupported", Map.of()));

        assertThat(offer.mandateStatus(APP).message()).isEqualTo("bank unsupported");
        verify(flow, never()).acceptOffer(any(), any());
    }

    /**
     * R1: with the flag OFF (the shipped default) mandateRequired is false for every application, so the
     * I6 hand-off must still refuse a file whose disbursal account has not been confirmed — acceptOffer
     * checks the eSign, not the account, and would queue it for disbursement with no destination.
     */
    @Test
    void flagOffDoesNotHandOffAnApplicationWithNoConfirmedAccount() {
        flag(false);
        app.setDisbursalConfirmedAt(null);
        when(mandateService.status(APP)).thenReturn(new StepResult("EMANDATE", "PENDING", "not started", Map.of()));

        offer.mandateStatus(APP);

        verify(flow, never()).acceptOffer(any(), any());
        verify(journeyMock, never()).advance(APP, JourneyService.OfferStep.OFFER_DONE);
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.SANCTIONED);
    }

    /**
     * I6: the kill switch is flipped off under a borrower sitting on the mandate screen with their
     * account already confirmed. The poll must hand the file off exactly as the pre-feature code did,
     * instead of leaving them SANCTIONED for ever.
     */
    @Test
    void flagTurnedOffMidJourneyHandsTheFileOffOnTheNextPoll() {
        flag(false);
        app.setDisbursalConfirmedAt(java.time.Instant.now());
        when(mandateService.status(APP)).thenReturn(new StepResult("EMANDATE", "PENDING", "not started", Map.of()));

        assertThat(offer.mandateStatus(APP).status()).isEqualTo("PASS");
        verify(journeyMock).advance(APP, JourneyService.OfferStep.OFFER_DONE);
        verify(flow).acceptOffer(APP, 1_500_000L);
    }

    /** F10 (journey side): a mandate bound to another account is not usable, so the step stays. */
    @Test
    void aMandateBoundToAnotherAccountDoesNotSkipTheStep() {
        flag(true);
        assertThat(journey.current(APP).steps()).contains("OFFER_MANDATE");
    }
}
