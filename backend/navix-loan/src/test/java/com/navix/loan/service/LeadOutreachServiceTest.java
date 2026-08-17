package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.notification.MailPort;
import com.navix.common.sms.SmsGateway;
import com.navix.loan.dto.DsaDtos.OutreachRequest;
import com.navix.loan.dto.DsaDtos.OutreachResultView;
import com.navix.loan.entity.Lead;
import com.navix.loan.repository.LeadOutreachRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Rate limits, the NO_EMAIL skip, and "an SMS gateway failure is recorded, never thrown". */
@ExtendWith(MockitoExtension.class)
class LeadOutreachServiceTest {

    @Mock private SmsGateway smsGateway;
    @Mock private MailPort mailPort;
    @Mock private LeadOutreachRepository outreachRepository;

    private LeadOutreachService service() {
        return new LeadOutreachService(smsGateway, mailPort, outreachRepository);
    }

    private static Lead lead(long id, String mobile, String email) {
        Lead l = new Lead();
        l.setId(id);
        l.setMobile(mobile);
        l.setEmail(email);
        return l;
    }

    @Test
    void perLeadRateLimitBlocksTheFourthSendToday() {
        Lead l = lead(1L, "9876543210", "a@b.com");
        when(outreachRepository.countByLeadIdAndCreatedAtAfter(org.mockito.ArgumentMatchers.eq(1L), any()))
                .thenReturn(3L);

        assertThatThrownBy(() -> service().send(l, 5L, new OutreachRequest("EMAIL", "hi", "hello")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("OUTREACH_RATE_LIMITED"));
    }

    @Test
    void perDsaRateLimitBlocksTheFiftyFirstSendToday() {
        Lead l = lead(1L, "9876543210", "a@b.com");
        when(outreachRepository.countByLeadIdAndCreatedAtAfter(anyLong(), any())).thenReturn(0L);
        when(outreachRepository.countByDsaStaffIdAndCreatedAtAfter(anyLong(), any())).thenReturn(50L);

        assertThatThrownBy(() -> service().send(l, 5L, new OutreachRequest("EMAIL", "hi", "hello")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("OUTREACH_RATE_LIMITED"));
    }

    @Test
    void emailWithNoAddressIsSkipped_notThrown() {
        Lead l = lead(1L, "9876543210", null);
        when(outreachRepository.countByLeadIdAndCreatedAtAfter(anyLong(), any())).thenReturn(0L);
        when(outreachRepository.countByDsaStaffIdAndCreatedAtAfter(anyLong(), any())).thenReturn(0L);

        OutreachResultView result = service().send(l, 5L, new OutreachRequest("EMAIL", "hi", "hello"));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.error()).isEqualTo("NO_EMAIL");
        verify(mailPort, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void smsGatewayFailureIsRecordedFailed_neverThrown() {
        Lead l = lead(1L, "9876543210", "a@b.com");
        when(outreachRepository.countByLeadIdAndCreatedAtAfter(anyLong(), any())).thenReturn(0L);
        when(outreachRepository.countByDsaStaffIdAndCreatedAtAfter(anyLong(), any())).thenReturn(0L);
        when(smsGateway.send(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("gateway down"));

        OutreachResultView result = service().send(l, 5L, new OutreachRequest("SMS", null, null));

        assertThat(result.channel()).isEqualTo("SMS");
        assertThat(result.status()).isEqualTo("FAILED");
        verify(outreachRepository).save(any());
    }

    @Test
    void successfulEmailIsRecordedSent() {
        Lead l = lead(1L, "9876543210", "a@b.com");
        when(outreachRepository.countByLeadIdAndCreatedAtAfter(anyLong(), any())).thenReturn(0L);
        when(outreachRepository.countByDsaStaffIdAndCreatedAtAfter(anyLong(), any())).thenReturn(0L);
        when(mailPort.send(anyString(), anyString(), anyString())).thenReturn(true);

        OutreachResultView result = service().send(l, 5L, new OutreachRequest("EMAIL", "hi", "hello"));

        assertThat(result.status()).isEqualTo("SENT");
    }

    @Test
    void invalidChannelIsRejected() {
        Lead l = lead(1L, "9876543210", "a@b.com");
        assertThatThrownBy(() -> service().send(l, 5L, new OutreachRequest("CARRIER_PIGEON", null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("INVALID_CHANNEL"));
    }
}
