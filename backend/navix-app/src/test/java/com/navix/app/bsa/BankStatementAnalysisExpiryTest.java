package com.navix.app.bsa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.dto.CustomerDtos.CustomerDetail;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.service.CustomerService;
import com.navix.storage.service.DocumentStorageService;
import com.navix.verification.client.SignzyBankStatementClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** A report that never finishes must not lock its customer out of ever running another. */
class BankStatementAnalysisExpiryTest {

    private static final Long CUSTOMER = 42L;

    private final BankStatementAnalysisRepository repository = mock(BankStatementAnalysisRepository.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final SignzyBankStatementClient client = mock(SignzyBankStatementClient.class);
    private final BankStatementAnalysisService service = new BankStatementAnalysisService(repository, customers,
            mock(ApplicationDocumentRepository.class), mock(DocumentStorageService.class), client, new ObjectMapper());

    @BeforeEach
    void setUp() {
        ActorContext.set(new CurrentActor("9", "Admin", "ADMIN"));
        CustomerDetail detail = mock(CustomerDetail.class);
        when(detail.applications()).thenReturn(List.of());
        when(customers.detail(CUSTOMER)).thenReturn(detail);
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    private BankStatementAnalysis latestRunStarted(Duration ago) {
        BankStatementAnalysis row = new BankStatementAnalysis();
        row.setStatus(BankStatementAnalysis.PENDING);
        row.setReportId("PR1");
        ReflectionTestUtils.setField(row, "createdAt", Instant.now().minus(ago));
        when(repository.findFirstByCustomerIdOrderByCreatedAtDesc(CUSTOMER)).thenReturn(Optional.of(row));
        return row;
    }

    @Test
    void aRecentRunStillBlocksASecondPayment() {
        BankStatementAnalysis row = latestRunStarted(Duration.ofHours(23));

        assertThatThrownBy(() -> service.run(CUSTOMER)).hasMessageContaining("still running");
        assertThat(row.getStatus()).isEqualTo(BankStatementAnalysis.PENDING);
    }

    @Test
    void anExpiredRunNoLongerBlocksANewOne() {
        BankStatementAnalysis row = latestRunStarted(Duration.ofHours(25));

        // Past the in-progress guard: the next refusal is that this mock customer has no statements.
        assertThatThrownBy(() -> service.run(CUSTOMER)).hasMessageContaining("no bank statement");
        assertThat(row.getStatus()).isEqualTo(BankStatementAnalysis.FAILED);
    }

    @Test
    void aReadBackThatKeepsFailingExpiresAndKeepsTheReason() {
        BankStatementAnalysis row = latestRunStarted(Duration.ofHours(25));
        when(client.retrieveWorkOrder("PR1")).thenThrow(new RuntimeException("403 You cannot consume this service"));

        BankStatementAnalysisService.BankAnalysisView view = service.latest(CUSTOMER);

        assertThat(view.status()).isEqualTo(BankStatementAnalysis.FAILED);
        assertThat(view.message()).contains("within 24 hours").contains("You cannot consume this service");
        assertThat(row.getReportId()).isEqualTo("PR1");
    }

    @Test
    void aReadBackFailureInsideTheWindowStaysPending() {
        latestRunStarted(Duration.ofHours(1));
        when(client.retrieveWorkOrder("PR1")).thenThrow(new RuntimeException("503 from Signzy"));

        assertThat(service.latest(CUSTOMER).status()).isEqualTo(BankStatementAnalysis.PENDING);
    }
}
