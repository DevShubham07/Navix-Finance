package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.entity.Loan;
import com.navix.loan.repository.LoanRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MessageAudienceServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 31);

    private final FeatureFlagService flags = mock(FeatureFlagService.class);
    private final LoanRepository loans = mock(LoanRepository.class);
    private final CustomerBookQuery book = mock(CustomerBookQuery.class);
    private final MessageAudienceService service = new MessageAudienceService(flags, loans, book, new LoanMath());

    private static Loan loan(long customer, int daysPastDue, LoanStatus status) {
        Loan l = new Loan();
        l.setCustomerId(customer);
        l.setDueDate(TODAY.minusDays(daysPastDue));
        l.setStatus(status);
        return l;
    }

    @BeforeEach
    void setUp() {
        ActorContext.set(new CurrentActor("1", "Admin A", "ADMIN"));
        when(flags.isEnabled(CustomerMessageService.FLAG, false)).thenReturn(true);
        service.setClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        when(loans.findByStatusIn(any())).thenReturn(List.of(
                loan(1, 0, LoanStatus.ACTIVE), loan(2, 1, LoanStatus.OVERDUE), loan(3, 7, LoanStatus.OVERDUE),
                loan(4, 8, LoanStatus.OVERDUE), loan(5, 30, LoanStatus.IN_COLLECTIONS), loan(6, 91, LoanStatus.IN_COLLECTIONS),
                loan(7, -3, LoanStatus.ACTIVE), loan(8, -4, LoanStatus.ACTIVE), loan(2, 2, LoanStatus.OVERDUE)));
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    @Test
    void overdueRangesStopAtTheirEdgesAndCountACustomerOnce() {
        assertThat(service.audience("OVERDUE_1_7").customerIds()).containsExactly(2L, 3L);
        assertThat(service.audience("OVERDUE_8_30").customerIds()).containsExactly(4L, 5L);
        assertThat(service.audience("OVERDUE_90_PLUS").customerIds()).containsExactly(6L);
    }

    @Test
    void dueSoonIsTodayToThreeDaysAheadAndNeverOverdue() {
        assertThat(service.audience("DUE_SOON").customerIds()).containsExactly(1L, 7L);
    }

    @Test
    void aCustomerSegmentComesFromTheCustomerBook() {
        when(book.count(any())).thenReturn(2L);
        when(book.pageIds(any(), eq(0), eq(MessageAudienceService.CAP))).thenReturn(List.of(11L, 12L));
        var view = service.audience("rejected");
        assertThat(view.count()).isEqualTo(2);
        assertThat(view.customerIds()).containsExactly(11L, 12L);
        assertThat(view.capped()).isFalse();
    }

    @Test
    void blockedAndUnknownGroupsAreRefused() {
        assertThatThrownBy(() -> service.audience("blocked")).hasMessageContaining("Unknown customer group");
        assertThatThrownBy(() -> service.audience("nope")).hasMessageContaining("Unknown customer group");
    }

    @Test
    void onlyAnAdminWithMessagingOnCanReadAGroup() {
        ActorContext.set(new CurrentActor("2", "Dsa", "DSA"));
        assertThatThrownBy(() -> service.audience("OVERDUE_1_7")).hasMessageContaining("Only an admin");
        ActorContext.set(new CurrentActor("1", "Admin A", "ADMIN"));
        when(flags.isEnabled(CustomerMessageService.FLAG, false)).thenReturn(false);
        assertThatThrownBy(() -> service.audience("OVERDUE_1_7")).hasMessageContaining("switched off");
    }
}
