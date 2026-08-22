package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.OfferDtos.DisbursalAccountRequest;
import com.navix.loan.dto.OfferDtos.ManualEsignRequest;
import com.navix.loan.dto.OfferDtos.OfferSummaryView;
import com.navix.loan.dto.OfferDtos.ReferenceInput;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.ApplicationDocument;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.pdf.SanctionLetterPdfRenderer;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.ApplicationReferenceRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.service.ApplicationVerificationService.StepResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Base64;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The borrower's post-sanction offer journey (V46; revamp.md Phase 3). */
@ExtendWith(MockitoExtension.class)
class OfferServiceTest {

    private static final Long APP = 1L;
    private static final Long CUSTOMER = 7L;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Mock private LoanApplicationRepository applicationRepo;
    @Mock private CustomerProfileRepository profileRepo;
    @Mock private ApplicationReferenceRepository referenceRepo;
    @Mock private PennyDropGuard pennyDropGuard;
    @Mock private ApplicationDocumentRepository documentRepo;
    @Mock private ApplicationVerificationService verification;
    @Mock private ApplicationFlowService flow;
    @Mock private JourneyService journey;
    @Mock private ProfileChangeLogger changeLogger;
    @Mock private SanctionLetterPdfRenderer letterRenderer;
    @Mock private DocumentStoragePort storage;

    private OfferService offer;
    private LoanApplication app;
    private CustomerProfile profile;

    @BeforeEach
    void setUp() {
        offer = new OfferService(applicationRepo, profileRepo, referenceRepo, pennyDropGuard,
                documentRepo, verification, flow, journey, changeLogger, letterRenderer, storage, new LoanMath());

        app = new LoanApplication();
        app.setId(APP);
        app.setCustomerId(CUSTOMER);
        app.setStatus(ApplicationStatus.SANCTIONED);
        app.setSanctionedAmountPaise(2_000_000L);           // ₹20,000 ceiling
        app.setApprovedRepaymentDate(LocalDate.now(IST).plusDays(30));

        profile = new CustomerProfile();
        profile.setApplicationId(APP);
        profile.setMobile("9000000001");
        profile.setFullName("Asha Kumari");
        profile.setSalaryAccountNumber("123456789012");
        profile.setSalaryIfsc("HDFC0001234");

        lenient().when(applicationRepo.findById(APP)).thenReturn(Optional.of(app));
        lenient().when(applicationRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(profile));
        lenient().when(referenceRepo.findByApplicationIdOrderBySlotAsc(APP)).thenReturn(List.of());
        lenient().when(referenceRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(pennyDropGuard.lockedUntil(CUSTOMER)).thenReturn(Optional.empty());
        lenient().when(pennyDropGuard.remainingAttempts(CUSTOMER)).thenReturn(PennyDropGuard.STRIKES);
    }

    // ---------------------------------------------------------------- amount

    @Test
    void chooseAmountAcceptsLessThanTheCeilingAndDoesNotMoveTheApplication() {
        offer.chooseAmount(APP, 1_500_000L);

        assertThat(app.getAmountRequested()).isEqualTo(1_500_000L);
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.SANCTIONED);
    }

    @Test
    void chooseAmountRejectsMoreThanTheCeiling() {
        assertThatThrownBy(() -> offer.chooseAmount(APP, 2_500_000L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("exceeds the sanctioned amount");
    }

    @Test
    void chooseAmountRejectsBelowTheMinimum() {
        assertThatThrownBy(() -> offer.chooseAmount(APP, 50_000L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("below the minimum");
    }

    // ---------------------------------------------------------------- references

    @Test
    void referencesMustBeTwoDistinctPeopleWhoAreNotTheBorrower() {
        assertThatThrownBy(() -> offer.saveReferences(APP,
                List.of(new ReferenceInput("Ravi", "9000000002", "PARENT"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Two references");

        assertThatThrownBy(() -> offer.saveReferences(APP, List.of(
                new ReferenceInput("Ravi", "9000000002", "PARENT"),
                new ReferenceInput("Ravi again", "9000000002", "FRIEND"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("different mobile numbers");

        assertThatThrownBy(() -> offer.saveReferences(APP, List.of(
                new ReferenceInput("Ravi", "9000000002", "PARENT"),
                new ReferenceInput("Myself", "9000000001", "FRIEND"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("your own mobile number");

        assertThatThrownBy(() -> offer.saveReferences(APP, List.of(
                new ReferenceInput("Ravi", "9000000002", "PARENT"),
                new ReferenceInput("Sita", "9000000003", "BEST_MATE"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("relation from the list");
    }

    @Test
    void referencesSaveOneRowPerSlot() {
        offer.saveReferences(APP, List.of(
                new ReferenceInput("Ravi", "90000 00002", "PARENT"),
                new ReferenceInput("Sita", "9000000003", "COLLEAGUE")));

        verify(referenceRepo).findByApplicationIdAndSlot(APP, (short) 1);
        verify(referenceRepo).findByApplicationIdAndSlot(APP, (short) 2);
    }

    /**
     * An ADMIN correcting a mistyped reference from the staff detail dialog arrives long after the
     * borrower accepted the offer, so SANCTIONED is not their gate — and their edit must not rewind
     * the borrower's journey pointer back to the summary screen.
     */
    @Test
    void adminMayCorrectReferencesAfterTheOfferHasMovedOn() {
        app.setStatus(ApplicationStatus.ACTIVE);
        ActorContext.set(new CurrentActor("12", "Admin", "ADMIN"));
        try {
            offer.saveReferences(APP, List.of(
                    new ReferenceInput("Ravi", "9000000002", "PARENT"),
                    new ReferenceInput("Sita", "9000000003", "COLLEAGUE")));
        } finally {
            ActorContext.clear();
        }

        verify(referenceRepo).findByApplicationIdAndSlot(APP, (short) 2);
        verify(journey, never()).advance(eq(APP), any(JourneyService.OfferStep.class));
        verify(changeLogger).logIfChanged(eq(CUSTOMER), eq(APP), eq("reference1.mobile"), any(), eq("9000000002"));
        verify(changeLogger).logIfChanged(eq(CUSTOMER), eq(APP), eq("reference2.mobile"), any(), eq("9000000003"));
    }

    /** An ADMIN correction is still held to every validation rule — only the SANCTIONED gate relaxes. */
    @Test
    void adminEditStillRejectsInvalidReferencePayload() {
        app.setStatus(ApplicationStatus.ACTIVE);
        ActorContext.set(new CurrentActor("12", "Admin", "ADMIN"));
        try {
            assertThatThrownBy(() -> offer.saveReferences(APP, List.of(
                    new ReferenceInput("Ravi", "9000000002", "PARENT"),
                    new ReferenceInput("Myself", "9000000001", "FRIEND"))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("your own mobile number");
        } finally {
            ActorContext.clear();
        }
    }

    /** A non-admin actor on a non-SANCTIONED application is still refused by saveReferences. */
    @Test
    void nonAdminActorOnNonSanctionedApplicationStillGetsNotApplicable() {
        app.setStatus(ApplicationStatus.KYC_PENDING);
        ActorContext.set(new CurrentActor(String.valueOf(CUSTOMER), "Asha", "BORROWER"));
        try {
            assertThatThrownBy(() -> offer.saveReferences(APP, List.of(
                    new ReferenceInput("Ravi", "9000000002", "PARENT"),
                    new ReferenceInput("Sita", "9000000003", "COLLEAGUE"))))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "NOT_APPLICABLE");
        } finally {
            ActorContext.clear();
        }
    }

    // ---------------------------------------------------------------- summary

    @Test
    void summaryUsesTheDrawnAmountAndTheDaysLeftToTheRepaymentDate() {
        app.setAmountRequested(1_000_000L); // ₹10,000 over 30 days

        OfferSummaryView s = offer.summary(APP);

        assertThat(s.tenureDays()).isEqualTo(30);
        assertThat(s.principalPaise()).isEqualTo(1_000_000L);
        assertThat(s.processingFeePaise()).isEqualTo(100_000L);
        assertThat(s.gstPaise()).isEqualTo(18_000L);
        assertThat(s.netDisbursedPaise()).isEqualTo(882_000L);
        assertThat(s.interestPaise()).isEqualTo(300_000L);
        assertThat(s.totalRepayablePaise()).isEqualTo(1_300_000L);
        assertThat(s.ceilingPaise()).isEqualTo(2_000_000L);
    }

    @Test
    void summaryOfAStaleSanctionNeverQuotesANegativeTenure() {
        // A sanction never expires (decision 41), so the approved date can already be in the past.
        app.setAmountRequested(1_000_000L);
        app.setApprovedRepaymentDate(LocalDate.now(IST).minusDays(5));

        assertThat(offer.summary(APP).tenureDays()).isEqualTo(1);
    }

    @Test
    void expectedDisbursalIsTodayBeforeSixPmAndTomorrowAfter() {
        ZonedDateTime beforeCutoff = ZonedDateTime.of(2026, 8, 6, 17, 59, 0, 0, IST);
        ZonedDateTime afterCutoff = ZonedDateTime.of(2026, 8, 6, 18, 0, 0, 0, IST);

        assertThat(OfferService.expectedDisbursalDate(beforeCutoff)).isEqualTo(LocalDate.of(2026, 8, 6));
        assertThat(OfferService.expectedDisbursalDate(afterCutoff)).isEqualTo(LocalDate.of(2026, 8, 7));
    }

    @Test
    void manualSigningStoresAPdfContainingTheDrawnSignatureAndServerIdentity() {
        app.setAmountRequested(1_500_000L);
        Instant signedAt = Instant.parse("2026-08-09T13:17:18Z");
        byte[] signature = new byte[] {1, 2, 3, 4};
        byte[] signedPdf = new byte[] {9, 8, 7};
        when(verification.recordManualEsign(eq(APP), anyString(), eq(null), eq(null), eq(null)))
                .thenReturn(new StepResult(ApplicationVerificationService.ESIGN,
                        ApplicationVerificationService.PASS, "signed",
                        Map.of("signedAt", signedAt.toString())));
        when(letterRenderer.renderSigned(any(), eq(signature), eq("Asha Kumari"), eq(signedAt)))
                .thenReturn(signedPdf);
        when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(
                APP, ApplicationVerificationService.SIGNED_AGREEMENT)).thenReturn(Optional.empty());

        offer.manualEsign(APP, new ManualEsignRequest(
                "data:image/png;base64," + Base64.getEncoder().encodeToString(signature),
                null, null, null));

        verify(storage).store("applications/1/signed_agreement/sanction-letter-signed.pdf",
                signedPdf, "application/pdf");
        ArgumentCaptor<ApplicationDocument> document = ArgumentCaptor.forClass(ApplicationDocument.class);
        verify(documentRepo).save(document.capture());
        assertThat(document.getValue().getDocType()).isEqualTo(ApplicationVerificationService.SIGNED_AGREEMENT);
        assertThat(document.getValue().getS3ObjectKey())
                .isEqualTo("applications/1/signed_agreement/sanction-letter-signed.pdf");
    }

    // ---------------------------------------------------------------- disbursal account

    /**
     * A re-apply's reference point is also the account the last advance was paid into. Without this,
     * a returning borrower keeping the same account was treated as having changed it and penny-dropped
     * every time — the opposite of decision 45.
     */
    @Test
    void confirmingTheAccountTheLastAdvanceWentToNeverRunsAPennyDrop() {
        app.setAmountRequested(1_500_000L);
        app.setReappliedFrom(41L);
        app.setDisbursalAccountNumber("555544443333");
        app.setDisbursalIfsc("SBIN0009999");
        app.setDisbursalAccountVerified(true);
        // …and their salary now lands somewhere else entirely, so only the carried account matches.
        profile.setSalaryAccountNumber(null);
        profile.setSalaryIfsc(null);

        offer.confirmDisbursalAccount(APP,
                new DisbursalAccountRequest("555544443333", "SBIN0009999", "Asha Kumari", null, false));

        verify(verification, never()).verifyPennyDrop(any(), anyString(), anyString(), anyBoolean());
        assertThat(app.getDisbursalAccountChanged()).isFalse();
    }

    /** With nothing on file to compare against, a typed account IS a change — and is dropped. */
    @Test
    void withNoAccountOnFileTheTypedOneCountsAsAChange() {
        app.setAmountRequested(1_500_000L);
        profile.setSalaryAccountNumber(null);
        profile.setSalaryIfsc(null);
        when(verification.verifyPennyDrop(eq(APP), anyString(), anyString(), anyBoolean()))
                .thenReturn(new ApplicationVerificationService.StepResult(
                        ApplicationVerificationService.PENNY_DROP, ApplicationVerificationService.PASS,
                        "ok", java.util.Map.of()));

        offer.confirmDisbursalAccount(APP,
                new DisbursalAccountRequest("777766665555", "AXIS0001111", "Asha Kumari", null, false));

        assertThat(app.getDisbursalAccountChanged()).isTrue();
        verify(verification).verifyPennyDrop(eq(APP), eq("777766665555"), eq("AXIS0001111"), anyBoolean());
    }

    @Test
    void firstDisbursalPennyDropsEvenTheUnchangedSalaryAccount() {
        app.setAmountRequested(1_500_000L);
        when(verification.verifyPennyDrop(eq(APP), eq("123456789012"), eq("HDFC0001234"), eq(true)))
                .thenReturn(pass());

        offer.confirmDisbursalAccount(APP,
                new DisbursalAccountRequest("123456789012", "HDFC0001234", "Asha Kumari", null, false));

        verify(verification).verifyPennyDrop(eq(APP), eq("123456789012"), eq("HDFC0001234"), eq(true));
        verify(pennyDropGuard).record(eq(CUSTOMER), eq(APP), eq("123456789012"), eq("HDFC0001234"),
                eq(true), eq(null));
        assertThat(app.getDisbursalAccountChanged()).isFalse();
        assertThat(app.getDisbursalAccountVerified()).isTrue();
        assertThat(app.getDisbursalConfirmedAt()).isNotNull();
        verify(flow).acceptOffer(APP, 1_500_000L);
    }

    @Test
    void aSuccessfulVerificationIsReusedOnlyForTheSameAccountPair() {
        app.setAmountRequested(1_500_000L);
        app.setDisbursalAccountNumber("123456789012");
        app.setDisbursalIfsc("HDFC0001234");
        app.setDisbursalAccountVerified(true);

        offer.confirmDisbursalAccount(APP,
                new DisbursalAccountRequest("123456789012", "hdfc0001234", "Asha Kumari", null, false));

        verify(verification, never()).verifyPennyDrop(any(), anyString(), anyString(), anyBoolean());
        verify(pennyDropGuard, never()).record(any(), any(), anyString(), anyString(), anyBoolean(), any());
        assertThat(app.getDisbursalAccountVerified()).isTrue();
    }

    @Test
    void aChangedAccountIsPennyDroppedAndPassingClearsAnyStrikes() {
        app.setAmountRequested(1_500_000L);
        when(verification.verifyPennyDrop(eq(APP), eq("999988887777"), eq("ICIC0004321"), eq(true)))
                .thenReturn(pass());

        offer.confirmDisbursalAccount(APP,
                new DisbursalAccountRequest("999988887777", "icic0004321", null, null, false));

        assertThat(app.getDisbursalAccountChanged()).isTrue();
        assertThat(app.getDisbursalAccountVerified()).isTrue();
        assertThat(app.getDisbursalIfsc()).isEqualTo("ICIC0004321");
        // The holder name falls back to the KYC name when the borrower leaves it blank.
        assertThat(app.getDisbursalHolderName()).isEqualTo("Asha Kumari");
        verify(pennyDropGuard).record(eq(CUSTOMER), eq(APP), eq("999988887777"), eq("ICIC0004321"),
                eq(true), eq(null));
        verify(flow).acceptOffer(APP, 1_500_000L);
    }

    @Test
    void aFailedPennyDropIsStillCountedAgainstTheBorrower() {
        // The strike must be recorded even though this call throws: it is logged through
        // PennyDropGuard precisely so it commits independently of the rejection.
        app.setAmountRequested(1_500_000L);
        when(verification.verifyPennyDrop(eq(APP), anyString(), anyString(), eq(true)))
                .thenReturn(review());

        assertThatThrownBy(() -> offer.confirmDisbursalAccount(APP,
                new DisbursalAccountRequest("999988887777", "ICIC0004321", null, null, false)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid account details");

        verify(pennyDropGuard).record(eq(CUSTOMER), eq(APP), eq("999988887777"), eq("ICIC0004321"),
                eq(false), anyString());
        assertThat(app.getDisbursalConfirmedAt()).isNull();
        verify(flow, never()).acceptOffer(any(), any());
    }

    @Test
    void aLiveLockRefusesTheAttemptWithoutCallingTheProvider() {
        app.setAmountRequested(1_500_000L);
        when(pennyDropGuard.lockedUntil(CUSTOMER))
                .thenReturn(Optional.of(Instant.now().plusSeconds(3600)));

        assertThatThrownBy(() -> offer.confirmDisbursalAccount(APP,
                new DisbursalAccountRequest("999988887777", "ICIC0004321", null, null, false)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Too many failed attempts");

        verify(verification, never()).verifyPennyDrop(any(), anyString(), anyString(), anyBoolean());
        verify(pennyDropGuard, never()).record(any(), any(), anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void theDisbursalAccountCannotBeConfirmedBeforeAnAmountIsChosen() {
        assertThatThrownBy(() -> offer.confirmDisbursalAccount(APP,
                new DisbursalAccountRequest("123456789012", "HDFC0001234", null, null, false)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Choose your loan amount first");
    }

    // ---------------------------------------------------------------- bank proof (penny-drop fallback)

    @Test
    void bankProofRequiresAnUploadedDocumentFirst() {
        app.setAmountRequested(1_500_000L);
        when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(
                APP, ApplicationVerificationService.BANK_PROOF)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> offer.confirmDisbursalAccount(APP,
                new DisbursalAccountRequest("999988887777", "ICIC0004321", "Asha Kumari", null, true)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "BANK_PROOF_REQUIRED");

        verify(verification, never()).verifyPennyDrop(any(), anyString(), anyString(), anyBoolean());
        verify(pennyDropGuard, never()).record(any(), any(), anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void bankProofSkipsThePennyDropEntirelyAndLeavesTheAccountUnverified() {
        app.setAmountRequested(1_500_000L);
        when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(
                APP, ApplicationVerificationService.BANK_PROOF))
                .thenReturn(Optional.of(new ApplicationDocument()));

        offer.confirmDisbursalAccount(APP,
                new DisbursalAccountRequest("999988887777", "ICIC0004321", "Asha Kumari", null, true));

        verify(verification, never()).verifyPennyDrop(any(), anyString(), anyString(), anyBoolean());
        verify(pennyDropGuard, never()).record(any(), any(), anyString(), anyString(), anyBoolean(), any());
        verify(verification).recordBankProofPending(APP, "999988887777", "ICIC0004321");
        assertThat(app.getDisbursalAccountVerified()).isFalse();
        assertThat(app.getDisbursalAccountNumber()).isEqualTo("999988887777");
        assertThat(app.getDisbursalIfsc()).isEqualTo("ICIC0004321");
        verify(flow).acceptOffer(APP, 1_500_000L);
    }

    @Test
    void bankProofSucceedsEvenUnderAnActivePennyDropLock() {
        app.setAmountRequested(1_500_000L);
        // Never even consulted on this path — the useBankProof branch skips the lock check entirely,
        // which is the point: this IS the escape hatch from that lock.
        lenient().when(pennyDropGuard.lockedUntil(CUSTOMER))
                .thenReturn(Optional.of(Instant.now().plusSeconds(3600)));
        when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(
                APP, ApplicationVerificationService.BANK_PROOF))
                .thenReturn(Optional.of(new ApplicationDocument()));

        offer.confirmDisbursalAccount(APP,
                new DisbursalAccountRequest("999988887777", "ICIC0004321", "Asha Kumari", null, true));

        verify(verification, never()).verifyPennyDrop(any(), anyString(), anyString(), anyBoolean());
        verify(flow).acceptOffer(APP, 1_500_000L);
    }

    @Test
    void bankProofStillRejectsAMalformedAccountOrIfsc() {
        app.setAmountRequested(1_500_000L);

        assertThatThrownBy(() -> offer.confirmDisbursalAccount(APP,
                new DisbursalAccountRequest("123", "SHORT", "Asha Kumari", null, true)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "ACCOUNT_INVALID");

        verify(documentRepo, never()).findFirstByApplicationIdAndDocTypeOrderByIdDesc(
                any(), anyString());
    }

    @Test
    void everyOfferStepRefusesAnApplicationThatIsNotSanctioned() {
        app.setStatus(ApplicationStatus.KYC_PENDING);

        assertThatThrownBy(() -> offer.chooseAmount(APP, 1_000_000L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("isn't ready");
    }

    private static StepResult pass() {
        return new StepResult(ApplicationVerificationService.PENNY_DROP,
                ApplicationVerificationService.PASS, "Account + name matched", Map.of());
    }

    private static StepResult review() {
        return new StepResult(ApplicationVerificationService.PENNY_DROP,
                ApplicationVerificationService.REVIEW, "Name mismatch at bank", Map.of());
    }
}
