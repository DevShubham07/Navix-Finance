package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.notification.event.ReferralPayoutCreatedEvent;
import com.navix.common.notification.event.ReferralRewardCreditedEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.config.ReferralProperties;
import com.navix.loan.domain.ReferralBeneficiaryRole;
import com.navix.loan.domain.ReferralPayoutStatus;
import com.navix.loan.domain.ReferralStatus;
import com.navix.loan.dto.ReferralDtos.ApplyCodeResult;
import com.navix.loan.dto.ReferralDtos.ExpenseSummaryView;
import com.navix.loan.dto.ReferralDtos.MyReferralView;
import com.navix.loan.dto.ReferralDtos.PayoutView;
import com.navix.loan.dto.ReferralDtos.ValidateCodeView;
import com.navix.loan.entity.ApplicantProfile;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.Referral;
import com.navix.loan.entity.ReferralCode;
import com.navix.loan.entity.ReferralPayout;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.ReferralCodeRepository;
import com.navix.loan.repository.ReferralPayoutRepository;
import com.navix.loan.repository.ReferralRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ReferralServiceTest {

    private static final long REWARD = 20_000L; // ₹200

    @Mock private ReferralCodeRepository codeRepository;
    @Mock private ReferralRepository referralRepository;
    @Mock private ReferralPayoutRepository payoutRepository;
    @Mock private LoanRepository loanRepository;
    @Mock private ApplicantReviewService reviewService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ReferralService service(boolean enabled) {
        return new ReferralService(codeRepository, referralRepository, payoutRepository, loanRepository,
                reviewService, new ReferralProperties(enabled, REWARD), eventPublisher);
    }

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    private static void asBorrower(long id) {
        ActorContext.set(new CurrentActor(String.valueOf(id), "Borrower " + id, "BORROWER"));
    }

    private static ReferralCode code(long ownerApplicantId, String code) {
        ReferralCode rc = new ReferralCode();
        rc.setApplicantId(ownerApplicantId);
        rc.setCode(code);
        return rc;
    }

    private static Referral referral(long id, long referrer, long referred, ReferralStatus status) {
        Referral r = new Referral();
        r.setId(id);
        r.setReferrerApplicantId(referrer);
        r.setReferredApplicantId(referred);
        r.setCodeUsed("FRIEND12");
        r.setStatus(status);
        return r;
    }

    private static ApplicantProfile profile(String name) {
        ApplicantProfile p = new ApplicantProfile();
        p.setFullName(name);
        return p;
    }

    private static ReferralPayout payout(long id, long beneficiary, ReferralPayoutStatus status) {
        ReferralPayout p = new ReferralPayout();
        p.setId(id);
        p.setBeneficiaryApplicantId(beneficiary);
        p.setCounterpartyApplicantId(beneficiary + 1);
        p.setBeneficiaryRole(ReferralBeneficiaryRole.REFERRER);
        p.setAmountPaise(REWARD);
        p.setStatus(status);
        return p;
    }

    // ---- code + earnings -----------------------------------------------------------

    @Test
    void myReferral_mintsCodeAndRollsUpEarnings() {
        asBorrower(9001);
        when(codeRepository.findByApplicantId(9001L)).thenReturn(Optional.empty());
        when(codeRepository.existsByCode(any())).thenReturn(false);
        when(codeRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(referralRepository.countByReferrerApplicantIdAndStatus(9001L, ReferralStatus.QUALIFIED))
                .thenReturn(2L);
        when(payoutRepository.findByBeneficiaryApplicantId(9001L)).thenReturn(List.of(
                payout(1, 9001, ReferralPayoutStatus.PAID),
                payout(2, 9001, ReferralPayoutStatus.PENDING)));

        MyReferralView view = service(true).myReferral();

        assertThat(view.enabled()).isTrue();
        assertThat(view.code()).hasSize(8);
        assertThat(view.rewardPaise()).isEqualTo(REWARD);
        assertThat(view.rewardRupees()).isEqualTo(200L);
        assertThat(view.referredQualifiedCount()).isEqualTo(2L);
        assertThat(view.totalEarnedPaise()).isEqualTo(REWARD);
        assertThat(view.pendingPaise()).isEqualTo(REWARD);
        assertThat(view.shareMessage()).contains(view.code()).contains("₹200");
    }

    // ---- applyCode -----------------------------------------------------------------

    @Test
    void applyCode_createsPendingReferral() {
        asBorrower(9002);
        when(codeRepository.findByCode("FRIEND12")).thenReturn(Optional.of(code(9001, "FRIEND12")));
        when(referralRepository.findByReferredApplicantId(9002L)).thenReturn(Optional.empty());
        when(loanRepository.findByApplicantId(9002L)).thenReturn(List.of());
        when(reviewService.latestProfile(9001L)).thenReturn(Optional.of(profile("Asha")));

        ApplyCodeResult result = service(true).applyCode("friend12"); // case-insensitive

        assertThat(result.accepted()).isTrue();
        assertThat(result.referrerName()).isEqualTo("Asha");
        assertThat(result.rewardPaise()).isEqualTo(REWARD);
        assertThat(result.message()).contains("Asha").contains("₹200");

        ArgumentCaptor<Referral> saved = ArgumentCaptor.forClass(Referral.class);
        verify(referralRepository).save(saved.capture());
        assertThat(saved.getValue().getReferrerApplicantId()).isEqualTo(9001L);
        assertThat(saved.getValue().getReferredApplicantId()).isEqualTo(9002L);
        assertThat(saved.getValue().getStatus()).isEqualTo(ReferralStatus.PENDING);
    }

    @Test
    void applyCode_rejectsSelfReferral() {
        asBorrower(9001);
        when(codeRepository.findByCode("OWNCODE1")).thenReturn(Optional.of(code(9001, "OWNCODE1")));

        assertThatThrownBy(() -> service(true).applyCode("OWNCODE1"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("SELF_REFERRAL");
    }

    @Test
    void applyCode_rejectsAlreadyReferred() {
        asBorrower(9002);
        when(codeRepository.findByCode("FRIEND12")).thenReturn(Optional.of(code(9001, "FRIEND12")));
        when(referralRepository.findByReferredApplicantId(9002L))
                .thenReturn(Optional.of(referral(7, 5000, 9002, ReferralStatus.PENDING)));

        assertThatThrownBy(() -> service(true).applyCode("FRIEND12"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("ALREADY_REFERRED");
    }

    @Test
    void applyCode_rejectsExistingBorrower() {
        asBorrower(9002);
        when(codeRepository.findByCode("FRIEND12")).thenReturn(Optional.of(code(9001, "FRIEND12")));
        when(referralRepository.findByReferredApplicantId(9002L)).thenReturn(Optional.empty());
        when(loanRepository.findByApplicantId(9002L)).thenReturn(List.of(new Loan()));

        assertThatThrownBy(() -> service(true).applyCode("FRIEND12"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("NOT_NEW_BORROWER");
    }

    @Test
    void applyCode_rejectsUnknownCode() {
        asBorrower(9002);
        when(codeRepository.findByCode("NOPE1234")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(true).applyCode("NOPE1234"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_REFERRAL_CODE");
    }

    @Test
    void applyCode_rejectedWhenDisabled() {
        asBorrower(9002);

        assertThatThrownBy(() -> service(false).applyCode("FRIEND12"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("REFERRAL_DISABLED");
        verifyNoInteractions(codeRepository);
    }

    // ---- reward trigger ------------------------------------------------------------

    @Test
    void onLoanDisbursed_qualifiesAndCreatesTwoPayouts() {
        when(referralRepository.findByReferredApplicantId(9002L))
                .thenReturn(Optional.of(referral(5, 9001, 9002, ReferralStatus.PENDING)));

        service(true).onLoanDisbursed(9002L, 77L);

        ArgumentCaptor<Referral> ref = ArgumentCaptor.forClass(Referral.class);
        verify(referralRepository).save(ref.capture());
        assertThat(ref.getValue().getStatus()).isEqualTo(ReferralStatus.QUALIFIED);
        assertThat(ref.getValue().getQualifyingLoanId()).isEqualTo(77L);

        ArgumentCaptor<ReferralPayout> payouts = ArgumentCaptor.forClass(ReferralPayout.class);
        verify(payoutRepository, times(2)).save(payouts.capture());
        List<ReferralPayout> created = payouts.getAllValues();
        assertThat(created).allSatisfy(p -> {
            assertThat(p.getAmountPaise()).isEqualTo(REWARD);
            assertThat(p.getStatus()).isEqualTo(ReferralPayoutStatus.PENDING);
            assertThat(p.getQualifyingLoanId()).isEqualTo(77L);
        });
        assertThat(created).extracting(ReferralPayout::getBeneficiaryApplicantId)
                .containsExactlyInAnyOrder(9001L, 9002L);
        assertThat(created).extracting(ReferralPayout::getBeneficiaryRole)
                .containsExactlyInAnyOrder(ReferralBeneficiaryRole.REFERRER, ReferralBeneficiaryRole.REFERRED);
        verify(eventPublisher).publishEvent(any(ReferralPayoutCreatedEvent.class));
    }

    @Test
    void onLoanDisbursed_noopWhenNoReferral() {
        when(referralRepository.findByReferredApplicantId(9002L)).thenReturn(Optional.empty());

        service(true).onLoanDisbursed(9002L, 77L);

        verify(payoutRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void onLoanDisbursed_idempotentWhenAlreadyQualified() {
        when(referralRepository.findByReferredApplicantId(9002L))
                .thenReturn(Optional.of(referral(5, 9001, 9002, ReferralStatus.QUALIFIED)));

        service(true).onLoanDisbursed(9002L, 88L);

        verify(referralRepository, never()).save(any());
        verify(payoutRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void onLoanDisbursed_noopWhenDisabled() {
        service(false).onLoanDisbursed(9002L, 77L);

        verifyNoInteractions(referralRepository, payoutRepository, eventPublisher);
    }

    // ---- payouts -------------------------------------------------------------------

    @Test
    void payPayout_marksPaidAndPublishesCredit() {
        ActorContext.set(new CurrentActor("5", "Devang", "DISBURSEMENT_HEAD"));
        when(payoutRepository.findById(10L))
                .thenReturn(Optional.of(payout(10, 9001, ReferralPayoutStatus.PENDING)));
        when(reviewService.latestProfile(9001L)).thenReturn(Optional.of(profile("Asha")));
        when(reviewService.latestProfile(9002L)).thenReturn(Optional.of(profile("Bilal")));

        PayoutView view = service(true).payPayout(10L, " UTR123 ");

        assertThat(view.status()).isEqualTo("PAID");
        assertThat(view.txnRef()).isEqualTo("UTR123");
        assertThat(view.paidBy()).isEqualTo("Devang");
        verify(eventPublisher).publishEvent(any(ReferralRewardCreditedEvent.class));
    }

    @Test
    void payPayout_requiresTxnRef() {
        ActorContext.set(new CurrentActor("5", "Devang", "DISBURSEMENT_HEAD"));

        assertThatThrownBy(() -> service(true).payPayout(10L, "  "))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("MISSING_TXN_REF");
    }

    @Test
    void payPayout_rejectsAlreadyPaid() {
        ActorContext.set(new CurrentActor("5", "Devang", "DISBURSEMENT_HEAD"));
        when(payoutRepository.findById(10L))
                .thenReturn(Optional.of(payout(10, 9001, ReferralPayoutStatus.PAID)));

        assertThatThrownBy(() -> service(true).payPayout(10L, "UTR123"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("ALREADY_PAID");
    }

    @Test
    void payPayout_rejectsNonDisbursementHead() {
        asBorrower(9001);

        assertThatThrownBy(() -> service(true).payPayout(10L, "UTR123"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("FORBIDDEN_ROLE");
    }

    @Test
    void expenseSummary_totalsPendingAndPaid() {
        ActorContext.set(new CurrentActor("5", "Devang", "DISBURSEMENT_HEAD"));
        when(payoutRepository.findAll()).thenReturn(List.of(
                payout(1, 9001, ReferralPayoutStatus.PAID),
                payout(2, 9002, ReferralPayoutStatus.PENDING),
                payout(3, 9003, ReferralPayoutStatus.PAID)));

        ExpenseSummaryView s = service(true).expenseSummary();

        assertThat(s.paidCount()).isEqualTo(2L);
        assertThat(s.paidPaise()).isEqualTo(40_000L);
        assertThat(s.pendingCount()).isEqualTo(1L);
        assertThat(s.pendingPaise()).isEqualTo(REWARD);
        assertThat(s.totalCount()).isEqualTo(3L);
        assertThat(s.totalPaise()).isEqualTo(60_000L);
    }

    // ---- validate ------------------------------------------------------------------

    @Test
    void validate_previewsValidCode() {
        asBorrower(9002);
        when(codeRepository.findByCode("FRIEND12")).thenReturn(Optional.of(code(9001, "FRIEND12")));
        when(reviewService.latestProfile(9001L)).thenReturn(Optional.of(profile("Asha")));

        ValidateCodeView v = service(true).validate("friend12");

        assertThat(v.valid()).isTrue();
        assertThat(v.referrerName()).isEqualTo("Asha");
        assertThat(v.rewardPaise()).isEqualTo(REWARD);
    }

    @Test
    void validate_rejectsOwnCode() {
        asBorrower(9001);
        when(codeRepository.findByCode("OWNCODE1")).thenReturn(Optional.of(code(9001, "OWNCODE1")));

        ValidateCodeView v = service(true).validate("OWNCODE1");

        assertThat(v.valid()).isFalse();
    }
}
