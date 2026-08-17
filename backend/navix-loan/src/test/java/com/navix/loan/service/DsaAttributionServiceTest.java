package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.DsaDtos.DsaLeadStatus;
import com.navix.loan.entity.Lead;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.service.DsaAttributionService.AttributedApplication;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The attribution rule is the load-bearing part of the whole DSA feature: the attributed
 * application is the SINGLE earliest application created after the lead, pinned for the lead's
 * lifetime, so a customer's repeat borrowing is invisible to the DSA and can never earn a second
 * commission.
 */
@ExtendWith(MockitoExtension.class)
class DsaAttributionServiceTest {

    @Mock
    private CustomerProfileRepository customerProfileRepository;

    private DsaAttributionService service() {
        return new DsaAttributionService(customerProfileRepository);
    }

    private static Lead lead(String pan, Instant createdAt) {
        Lead l = new Lead();
        l.setId(1L);
        l.setPan(pan);
        l.setCreatedAt(createdAt);
        return l;
    }

    private static LoanApplication app(long id, Instant createdAt, ApplicationStatus status) {
        LoanApplication a = new LoanApplication();
        a.setId(id);
        a.setCustomerId(100L);
        a.setCreatedAt(createdAt);
        a.setStatus(status);
        return a;
    }

    @Test
    void noApplicationFound_isNotApplied() {
        Instant leadCreated = Instant.parse("2026-01-01T00:00:00Z");
        Lead l = lead("ABCDE1234F", leadCreated);
        when(customerProfileRepository.findApplicationsAfterByPan("ABCDE1234F", leadCreated))
                .thenReturn(List.of());

        AttributedApplication result = service().attributedApplication(l);

        assertThat(result.application()).isNull();
        assertThat(result.status()).isEqualTo(DsaLeadStatus.NOT_APPLIED);
    }

    @Test
    void theEarliestPostLeadApplicationIsAttributed_notALaterOne() {
        Instant leadCreated = Instant.parse("2026-01-01T00:00:00Z");
        Lead l = lead("ABCDE1234F", leadCreated);
        LoanApplication earliest = app(1L, leadCreated.plusSeconds(10), ApplicationStatus.KYC_PENDING);
        LoanApplication later = app(2L, leadCreated.plusSeconds(9999), ApplicationStatus.DRAFT);
        // The repository query is defined to return ascending-by-createdAt; the earliest is first.
        when(customerProfileRepository.findApplicationsAfterByPan("ABCDE1234F", leadCreated))
                .thenReturn(List.of(earliest, later));

        AttributedApplication result = service().attributedApplication(l);

        assertThat(result.application().getId()).isEqualTo(1L);
        assertThat(result.status()).isEqualTo(DsaLeadStatus.APPLIED);
    }

    @Test
    void aSecondApplicationBySamePanNeverReplacesThePinnedOne() {
        Instant leadCreated = Instant.parse("2026-01-01T00:00:00Z");
        Lead l = lead("ABCDE1234F", leadCreated);
        LoanApplication first = app(1L, leadCreated.plusSeconds(10), ApplicationStatus.CLOSED);
        LoanApplication reborrow = app(2L, leadCreated.plusSeconds(999999), ApplicationStatus.ACTIVE);
        // Regardless of how many later applications exist, the earliest stays first and pinned.
        when(customerProfileRepository.findApplicationsAfterByPan("ABCDE1234F", leadCreated))
                .thenReturn(List.of(first, reborrow));

        AttributedApplication result = service().attributedApplication(l);

        assertThat(result.application().getId()).isEqualTo(1L);
        assertThat(result.status()).isEqualTo(DsaLeadStatus.REPAID);
    }

    @Test
    void aPreExistingInFlightApplicationIsNeverAttributed() {
        // The repository contract only returns applications with createdAt > lead.createdAt, so an
        // in-flight file that predates the lead is excluded at the query itself — the service adds
        // no further filtering, which is exactly the point (nothing to accidentally get wrong here).
        Instant leadCreated = Instant.parse("2026-01-01T00:00:00Z");
        Lead l = lead("ABCDE1234F", leadCreated);
        when(customerProfileRepository.findApplicationsAfterByPan("ABCDE1234F", leadCreated))
                .thenReturn(List.of());

        AttributedApplication result = service().attributedApplication(l);
        assertThat(result.status()).isEqualTo(DsaLeadStatus.NOT_APPLIED);
    }

    @Test
    void everyApplicationStatusMapsToItsDocumentedCoarseBucket() {
        Instant leadCreated = Instant.parse("2026-01-01T00:00:00Z");
        record Case(ApplicationStatus status, DsaLeadStatus expected) {
        }
        List<Case> cases = List.of(
                new Case(ApplicationStatus.DRAFT, DsaLeadStatus.APPLIED),
                new Case(ApplicationStatus.KYC_PENDING, DsaLeadStatus.APPLIED),
                new Case(ApplicationStatus.KYC_APPROVED, DsaLeadStatus.IN_PROGRESS),
                new Case(ApplicationStatus.CREDIT_EXEC_PENDING, DsaLeadStatus.IN_PROGRESS),
                new Case(ApplicationStatus.SANCTIONED, DsaLeadStatus.IN_PROGRESS),
                new Case(ApplicationStatus.DISBURSEMENT_PENDING, DsaLeadStatus.IN_PROGRESS),
                new Case(ApplicationStatus.KYC_REJECTED, DsaLeadStatus.DECLINED),
                new Case(ApplicationStatus.REJECTED, DsaLeadStatus.DECLINED),
                new Case(ApplicationStatus.CANCELLED, DsaLeadStatus.DECLINED),
                new Case(ApplicationStatus.DISBURSED, DsaLeadStatus.DISBURSED),
                new Case(ApplicationStatus.ACTIVE, DsaLeadStatus.DISBURSED),
                new Case(ApplicationStatus.OVERDUE, DsaLeadStatus.DISBURSED),
                new Case(ApplicationStatus.CLOSED, DsaLeadStatus.REPAID));

        for (Case c : cases) {
            Lead l = lead("ABCDE1234F", leadCreated);
            LoanApplication a = app(1L, leadCreated.plusSeconds(1), c.status());
            when(customerProfileRepository.findApplicationsAfterByPan("ABCDE1234F", leadCreated))
                    .thenReturn(List.of(a));
            assertThat(service().attributedApplication(l).status())
                    .as("status for " + c.status())
                    .isEqualTo(c.expected());
        }
    }
}
