package com.navix.app.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navix.loan.entity.MandateDebit;
import com.navix.loan.entity.MandateDebit.Status;
import com.navix.loan.repository.MandateDebitRepository;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V84 against a real Flyway-migrated PostgreSQL (ddl-auto=validate also checks the entity mapping). */
@SpringBootTest
@Testcontainers
@Tag("integration")
class MandateDebitRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MandateDebitRepository repository;

    private static MandateDebit debit(long loanId, int attempt, String key, Status status) {
        MandateDebit d = new MandateDebit();
        d.setMandateId(1L);
        d.setLoanId(loanId);
        d.setUniqueKey(key);
        d.setAmountPaise(1_270_000L);
        d.setStatus(status);
        d.setAttemptNo(attempt);
        d.setTriggeredBy(MandateDebit.Trigger.SCHEDULED);
        return d;
    }

    @Test
    void partialUniqueIndexRefusesASecondInFlightDebitForOneLoan() {
        repository.saveAndFlush(debit(1001, 1, "k-1001-1", Status.SCHEDULED));

        assertThatThrownBy(() -> repository.saveAndFlush(debit(1001, 2, "k-1001-2", Status.SCHEDULED)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> repository.saveAndFlush(debit(1001, 3, "k-1001-3", Status.IN_PROGRESS)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // another loan is independent
        repository.saveAndFlush(debit(1002, 1, "k-1002-1", Status.SCHEDULED));

        // a finished attempt does not block the next one
        repository.saveAndFlush(debit(1003, 1, "k-1003-1", Status.SUCCEEDED));
        repository.saveAndFlush(debit(1003, 2, "k-1003-2", Status.SCHEDULED));
        repository.saveAndFlush(debit(1003, 3, "k-1003-3", Status.FAILED));

        assertThat(repository.existsByLoanIdAndStatusIn(1001L, List.of(Status.SCHEDULED, Status.IN_PROGRESS))).isTrue();
        assertThat(repository.existsByLoanIdAndStatusIn(1004L, List.of(Status.SCHEDULED, Status.IN_PROGRESS))).isFalse();
    }

    @Test
    void uniqueKeyIsUnique() {
        repository.saveAndFlush(debit(1010, 1, "k-dup", Status.FAILED));

        assertThatThrownBy(() -> repository.saveAndFlush(debit(1011, 1, "k-dup", Status.FAILED)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repository.findByUniqueKey("k-dup")).isPresent();
    }

    @Test
    void findsNewestAttemptForALoan() {
        repository.saveAndFlush(debit(1020, 1, "k-1020-1", Status.FAILED));
        repository.saveAndFlush(debit(1020, 2, "k-1020-2", Status.FAILED_TECHNICAL));
        repository.saveAndFlush(debit(1020, 3, "k-1020-3", Status.IN_PROGRESS));

        assertThat(repository.findFirstByLoanIdOrderByAttemptNoDesc(1020L))
                .get().extracting(MandateDebit::getAttemptNo).isEqualTo(3);
    }

    @Test
    void findDueForReconcileFiltersOrdersAndPages() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 10, 17);
        List<Status> open = List.of(Status.SCHEDULED, Status.IN_PROGRESS, Status.FAILED_TECHNICAL);
        MandateDebit a = saved(2001, "k-2001", Status.IN_PROGRESS, today);
        MandateDebit b = saved(2002, "k-2002", Status.SCHEDULED, today.minusDays(3));
        MandateDebit c = saved(2003, "k-2003", Status.FAILED_TECHNICAL, today.minusDays(1));
        saved(2004, "k-2004", Status.SCHEDULED, today.plusDays(1));   // not yet settled
        saved(2005, "k-2005", Status.SUCCEEDED, today.minusDays(2));  // terminal
        saved(2006, "k-2006", Status.FAILED, today.minusDays(2));     // terminal

        var all = repository.findDueForReconcile(open, today, org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(all.stream().map(MandateDebit::getUniqueKey).filter(k -> k.matches("k-200[1-6]")).toList())
                .containsExactlyInAnyOrder("k-2001", "k-2002", "k-2003");
        assertThat(repository.findDueForReconcile(open, today, org.springframework.data.domain.PageRequest.of(0, 1)))
                .hasSize(1);

        // touching the first-ordered row sends it to the back
        Long first = all.get(0).getId();
        repository.touch(first, java.time.Instant.now().plusSeconds(3600));
        var after = repository.findDueForReconcile(open, today, org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(after.get(after.size() - 1).getId()).isEqualTo(first);
        assertThat(after.get(0).getId()).isNotEqualTo(first);
    }

    private MandateDebit saved(long loanId, String key, Status st, java.time.LocalDate settle) {
        MandateDebit d = debit(loanId, 1, key, st);
        d.setSettlementDate(settle);
        return repository.saveAndFlush(d);
    }
}
