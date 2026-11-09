package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.sms.SmsGateway;
import com.navix.common.whatsapp.WhatsAppGateway;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.dto.MessagingDtos.ChannelResult;
import com.navix.loan.dto.MessagingDtos.PreviewRequest;
import com.navix.loan.dto.MessagingDtos.SendRequest;
import com.navix.loan.dto.MessagingDtos.SendResult;
import com.navix.loan.dto.MessagingDtos.Target;
import com.navix.loan.entity.BorrowerPreferences;
import com.navix.loan.entity.CustomerMessage;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.entity.RepaymentDiscountOffer;
import com.navix.loan.repository.BorrowerPreferencesRepository;
import com.navix.loan.repository.CustomerMessageRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.RepaymentDiscountOfferRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerMessageServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 1);

    @Mock private FeatureFlagService flags;
    @Mock private WhatsAppGateway whatsApp;
    @Mock private SmsGateway sms;
    @Mock private LoanApplicationRepository applications;
    @Mock private CustomerProfileRepository profiles;
    @Mock private LoanRepository loans;
    @Mock private BorrowerPreferencesRepository prefs;
    @Mock private CustomerMessageRepository messages;
    @Mock private RepaymentDiscountOfferRepository offers;
    @Mock private RepaymentService repayments;

    private final org.springframework.mock.env.MockEnvironment environment = new org.springframework.mock.env.MockEnvironment();
    private CustomerMessageService service;
    private Loan loan;

    @BeforeEach
    void setUp() {
        service = new CustomerMessageService(flags, whatsApp, sms, applications, profiles, loans, prefs,
                messages, offers, repayments, new LoanMath(), environment);
        service.setClock(clockAt(10, 0));
        ActorContext.set(new CurrentActor("1", "Admin A", "ADMIN"));
        when(flags.isEnabled(CustomerMessageService.FLAG, false)).thenReturn(true);

        LoanApplication app = new LoanApplication();
        app.setId(100L);
        app.setCustomerId(7L);
        app.setLoanId(50L);
        app.setSalaryCreditDay(25);
        when(applications.findById(100L)).thenReturn(Optional.of(app));
        when(applications.findByCustomerId(7L)).thenReturn(List.of(app));
        CustomerProfile p = new CustomerProfile();
        p.setApplicationId(100L);
        p.setFullName("Asha Rao");
        p.setMobile("9876543210");
        when(profiles.findByApplicationId(100L)).thenReturn(Optional.of(p));
        loan = new Loan();
        loan.setId(50L);
        loan.setStatus(LoanStatus.ACTIVE);
        loan.setPrincipal(1_000_000L);
        loan.setDueDate(TODAY.minusDays(3)); // 3 days overdue
        when(loans.findById(50L)).thenReturn(Optional.of(loan));
        when(loans.findByCustomerId(7L)).thenReturn(List.of(loan));
        when(repayments.outstandingAsOf(50L, TODAY)).thenReturn(1_270_000L);
        when(messages.save(any())).thenAnswer(i -> {
            CustomerMessage m = i.getArgument(0);
            m.setId(900L + (long) m.getChannel().length());
            return m;
        });
        when(whatsApp.sendTemplate(anyString(), anyString(), any())).thenReturn("wa-1");
        when(sms.send(anyString(), anyString(), anyString())).thenReturn("sms-1");
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    private static Clock clockAt(int hour, int minute) {
        return Clock.fixed(ZonedDateTime.of(TODAY, java.time.LocalTime.of(hour, minute), IST).toInstant(), IST);
    }

    private SendRequest send(String template, List<String> channels, Map<String, String> inputs, boolean confirmed) {
        return new SendRequest(new Target(100L, null), template, channels, inputs, confirmed);
    }

    @org.junit.jupiter.api.Test
    void discountSmsUsesTheFeePercentageAndIsSkippedUntilItsDltIdExists() {
        SendRequest smsDiscount = send("repay_discount", List.of("SMS"),
                Map.of("percent", "5", "repayBy", TODAY.plusDays(5).toString()), false);

        assertThat(service.send(smsDiscount).toString()).contains("SMS_NOT_REGISTERED");
        org.mockito.Mockito.verifyNoInteractions(sms);

        environment.setProperty("navix.sms.dlt-template-ids.REPAY_FEE50", "1777");
        service.send(smsDiscount);
        org.mockito.ArgumentCaptor<String> text = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(sms).send(org.mockito.ArgumentMatchers.anyString(), text.capture(),
                org.mockito.ArgumentMatchers.eq("REPAY_FEE50"));
        assertThat(text.getValue()).contains("for 50% off processing fee on next approved loan. Pay at")
                .endsWith("https://dhanboost.com/login. - DhanBoost");
    }

    private SendRequest discount(String percent) {
        return send("repay_discount", List.of("WHATSAPP"),
                Map.of("percent", percent, "repayBy", TODAY.plusDays(5).toString()), false);
    }

    @Test
    void nonAdminIsRefused() {
        ActorContext.set(new CurrentActor("2", "Credit", "CREDIT_HEAD"));
        assertThatThrownBy(() -> service.send(send("no_response", List.of("WHATSAPP"), Map.of(), false)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("admin");
        assertThatThrownBy(service::templates).isInstanceOf(BusinessException.class);
        verify(whatsApp, never()).sendTemplate(anyString(), anyString(), any());
    }

    @Test
    void flagOffRefusesSendAndPreviewButTemplatesStillRead() {
        when(flags.isEnabled(CustomerMessageService.FLAG, false)).thenReturn(false);
        assertThatThrownBy(() -> service.send(send("no_response", List.of("WHATSAPP"), Map.of(), false)))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("FEATURE_DISABLED");
        assertThatThrownBy(() -> service.preview(new PreviewRequest(new Target(100L, null), "no_response",
                List.of("WHATSAPP"), Map.of()))).extracting("code").isEqualTo("FEATURE_DISABLED");
        assertThat(service.templates()).isNotEmpty();
    }

    @Test
    void outsideSendHoursIsRefused() {
        service.setClock(clockAt(19, 0));
        assertThatThrownBy(() -> service.send(send("no_response", List.of("WHATSAPP"), Map.of(), false)))
                .extracting("code").isEqualTo("OUTSIDE_SEND_HOURS");
        service.setClock(clockAt(7, 59));
        assertThatThrownBy(() -> service.send(send("no_response", List.of("WHATSAPP"), Map.of(), false)))
                .extracting("code").isEqualTo("OUTSIDE_SEND_HOURS");
        verify(whatsApp, never()).sendTemplate(anyString(), anyString(), any());
    }

    @Test
    void confirmationRequiredWhenTemplateHasConfirmText() {
        assertThatThrownBy(() -> service.send(send("reported_soft", List.of("WHATSAPP"), Map.of(), false)))
                .extracting("code").isEqualTo("CONFIRMATION_REQUIRED");
        SendResult ok = service.send(send("reported_soft", List.of("WHATSAPP"), Map.of(), true));
        assertThat(ok.results().get(0).status()).isEqualTo("SENT");
    }

    @Test
    void percentAndRepayByBounds() {
        assertThatThrownBy(() -> service.send(discount("0"))).extracting("code").isEqualTo("INVALID_PERCENT");
        assertThatThrownBy(() -> service.send(discount("8"))).extracting("code").isEqualTo("INVALID_PERCENT");
        assertThatThrownBy(() -> service.send(discount("2.5"))).extracting("code").isEqualTo("INVALID_PERCENT");
        assertThatThrownBy(() -> service.send(send("repay_discount", List.of("WHATSAPP"),
                Map.of("percent", "5", "repayBy", TODAY.minusDays(1).toString()), false)))
                .extracting("code").isEqualTo("INVALID_REPAY_BY");
        assertThatThrownBy(() -> service.send(send("repay_discount", List.of("WHATSAPP"),
                Map.of("percent", "5", "repayBy", TODAY.plusDays(31).toString()), false)))
                .extracting("code").isEqualTo("INVALID_REPAY_BY");
        service.send(discount("1"));
        service.send(discount("7"));
    }

    @Test
    void duplicateWithin24HoursIsSkipped() {
        when(messages.existsByCustomerIdAndTemplateKeyAndChannelAndStatusAndCreatedAtAfter(
                eq(7L), eq("no_response"), eq("WHATSAPP"), eq("SENT"), any())).thenReturn(true);
        ChannelResult r = service.send(send("no_response", List.of("WHATSAPP"), Map.of(), false)).results().get(0);
        assertThat(r.status()).isEqualTo("SKIPPED");
        assertThat(r.reason()).isEqualTo("DUPLICATE_24H");
        verify(whatsApp, never()).sendTemplate(anyString(), anyString(), any());
    }

    @Test
    void smsOptOutSkipsBothChannelsAndOffersOptOutSkipsMarketingOnly() {
        BorrowerPreferences p = new BorrowerPreferences();
        p.setSmsOptIn(false);
        when(prefs.findByCustomerId(7L)).thenReturn(Optional.of(p));
        ChannelResult r = service.send(send("payment_overdue", List.of("SMS", "WHATSAPP"), Map.of(), false))
                .results().get(0);
        assertThat(r.reason()).isEqualTo("OPTED_OUT");
        verify(sms, never()).send(anyString(), anyString(), anyString());

        BorrowerPreferences q = new BorrowerPreferences();
        q.setOffersOptIn(false);
        when(prefs.findByCustomerId(7L)).thenReturn(Optional.of(q));
        assertThat(service.send(discount("5")).results().get(0).reason()).isEqualTo("OFFERS_OPTED_OUT");
        assertThat(service.send(send("no_response", List.of("WHATSAPP"), Map.of(), false))
                .results().get(0).status()).isEqualTo("SENT");
        verify(offers, never()).save(any());
    }

    @Test
    void providerExceptionIsRecordedAsFailedAndTheOtherChannelStillSends() {
        when(sms.send(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("gateway down"));
        SendResult res = service.send(send("payment_overdue", List.of("SMS", "WHATSAPP"), Map.of(), false));
        assertThat(res.results()).extracting(ChannelResult::channel, ChannelResult::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("SMS", "FAILED"),
                        org.assertj.core.groups.Tuple.tuple("WHATSAPP", "SENT"));
        assertThat(res.results().get(0).reason()).contains("gateway down");
        verify(messages, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void ledgerWriteFailureDoesNotLoseTheOtherChannel() {
        org.mockito.Mockito.doThrow(new RuntimeException("db")).doAnswer(i -> {
            CustomerMessage m = i.getArgument(0);
            m.setId(1L);
            return m;
        }).when(messages).save(any());
        SendResult res = service.send(send("payment_overdue", List.of("SMS", "WHATSAPP"), Map.of(), false));
        assertThat(res.results()).hasSize(2);
        assertThat(res.results().get(0).messageId()).isNull();
        assertThat(res.results().get(1).messageId()).isEqualTo(1L);
    }

    @Test
    void offerIsCreatedOnlyWhenAChannelWasSent() {
        when(whatsApp.sendTemplate(anyString(), anyString(), any())).thenThrow(new RuntimeException("meta down"));
        service.send(discount("5"));
        verify(offers, never()).save(any());

        org.mockito.Mockito.doReturn("wa-2").when(whatsApp).sendTemplate(anyString(), anyString(), any());
        service.send(discount("5"));
        ArgumentCaptor<RepaymentDiscountOffer> cap = ArgumentCaptor.forClass(RepaymentDiscountOffer.class);
        verify(offers).save(cap.capture());
        assertThat(cap.getValue().getPercent()).isEqualTo((short) 5);
        assertThat(cap.getValue().getLoanId()).isEqualTo(50L);
        assertThat(cap.getValue().getRepayBy()).isEqualTo(TODAY.plusDays(5));
    }

    @Test
    void discountTemplateFollowsDaysOverdue() {
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        service.send(discount("5")); // 3 days overdue: normal wording
        loan.setDueDate(TODAY.minusDays(6)); // 6 days overdue: review wording
        service.send(discount("5"));
        loan.setDueDate(TODAY.plusDays(4)); // not overdue: normal wording
        service.send(discount("5"));
        verify(whatsApp, org.mockito.Mockito.times(3)).sendTemplate(anyString(), name.capture(), any());
        assertThat(name.getAllValues()).containsExactly("db_repay_discount_v1_164",
                "db_repay_discount_review_v1_164", "db_repay_discount_v1_164");
    }

    @Test
    void whatsAppParamOrderMatchesTheTemplate() {
        service.send(send("overdue_soft_d7", List.of("WHATSAPP"), Map.of(), false));
        verify(whatsApp).sendTemplate("919876543210", "db_overdue_soft_d7_v1_164",
                List.of("Asha Rao", "₹12,700", "50", "3"));

        service.send(send("ptp_missed", List.of("WHATSAPP"),
                Map.of("amount", "5,000", "date", "2026-07-03"), false));
        verify(whatsApp).sendTemplate("919876543210", "db_ptp_missed_v1_164",
                List.of("Asha Rao", "Rs. 5,000", "03 Jul 2026", "50"));
    }

    @Test
    void existingTemplatesSendRegisteredSmsWithRsAndDltKey() {
        service.send(send("payment_overdue", List.of("SMS"), Map.of(), false));
        verify(sms).send("919876543210",
                "Dear Asha Rao, repayment of Rs. 12,700 on your DhanBoost loan 50 is overdue by 3 day(s). "
                        + "Pay at https://dhanboost.com/login. - DhanBoost", "PAYMENT_OVERDUE");
    }

    @Test
    void notApplicableWhenNoLiveLoanOrWrongOverdueWindow() {
        loan.setDueDate(TODAY.minusDays(8));
        assertThat(service.send(send("eligibility_window", List.of("WHATSAPP"), Map.of(), false))
                .results().get(0).reason()).startsWith("NOT_APPLICABLE");
        loan.setDueDate(TODAY.plusDays(2));
        assertThat(service.send(send("cost_clock", List.of("WHATSAPP"), Map.of(), false))
                .results().get(0).reason()).startsWith("NOT_APPLICABLE");
        loan.setStatus(LoanStatus.CLOSED);
        assertThat(service.send(send("no_response", List.of("WHATSAPP"), Map.of(), false))
                .results().get(0).reason()).contains("no live loan");
        verify(whatsApp, never()).sendTemplate(anyString(), anyString(), any());
    }

    @Test
    void smsIsRefusedForWhatsAppOnlyTemplates() {
        assertThatThrownBy(() -> service.send(send("no_response", List.of("SMS"), Map.of(), false)))
                .extracting("code").isEqualTo("INVALID_CHANNEL");
    }
}
