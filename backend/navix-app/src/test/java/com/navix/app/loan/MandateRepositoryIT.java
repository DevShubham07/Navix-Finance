package com.navix.app.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navix.loan.entity.Mandate;
import com.navix.loan.repository.MandateRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V82 against a real Flyway-migrated PostgreSQL (ddl-auto=validate also checks the entity mapping). */
@SpringBootTest
@Testcontainers
@Tag("integration")
class MandateRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MandateRepository repository;

    private static Mandate mandate(long customerId, int attempt, String digioId, String umrn) {
        Mandate m = new Mandate();
        m.setCustomerId(customerId);
        m.setAttemptNo(attempt);
        m.setDigioMandateId(digioId);
        m.setUmrn(umrn);
        m.setCustomerRefNumber(customerId + "-" + attempt);
        m.setStatus(umrn == null ? Mandate.Status.PENDING_AUTHORIZATION : Mandate.Status.ACTIVE);
        m.setMaximumAmountPaise(1_500_000L);
        return m;
    }

    @Test
    void findsNewestActiveMandateWithUmrnForCustomer() {
        repository.saveAndFlush(mandate(9001, 1, "ENA9001A", "UMRN-OLD-9001"));
        repository.saveAndFlush(mandate(9001, 2, "ENA9001B", null));
        Mandate newest = repository.saveAndFlush(mandate(9001, 3, "ENA9001C", "UMRN-NEW-9001"));
        repository.saveAndFlush(mandate(9001, 4, "ENA9001D", null));

        assertThat(repository.findFirstByCustomerIdAndStatusAndUmrnIsNotNullOrderByIdDesc(
                9001L, Mandate.Status.ACTIVE)).get().extracting(Mandate::getId).isEqualTo(newest.getId());
        assertThat(repository.findFirstByCustomerIdAndStatusAndUmrnIsNotNullOrderByIdDesc(
                424242L, Mandate.Status.ACTIVE)).isEmpty();
    }

    /** The advisory lock init() serialises on must actually be a valid statement against Postgres. */
    @Test
    void locksACustomerWithNoAttemptYet() {
        repository.lockCustomer(987654L);
    }

    @Test
    void customerRefNumberIsUnique() {
        repository.saveAndFlush(mandate(9002, 1, "ENA9002A", null));
        Mandate dup = mandate(9002, 1, "ENA9002B", null);

        assertThatThrownBy(() -> repository.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptNoIncrementsPerCustomer() {
        repository.saveAndFlush(mandate(9003, 1, "ENA9003A", null));
        repository.saveAndFlush(mandate(9003, 2, "ENA9003B", null));
        repository.saveAndFlush(mandate(9004, 1, "ENA9004A", null));

        assertThat(repository.findFirstByCustomerIdOrderByAttemptNoDesc(9003L))
                .get().extracting(Mandate::getAttemptNo).isEqualTo(2);
        assertThat(repository.findFirstByCustomerIdOrderByAttemptNoDesc(9004L))
                .get().extracting(Mandate::getAttemptNo).isEqualTo(1);
        assertThat(repository.findByCustomerRefNumber("9003-2")).isPresent();
        assertThat(repository.findByDigioMandateId("ENA9004A")).isPresent();
    }
}
