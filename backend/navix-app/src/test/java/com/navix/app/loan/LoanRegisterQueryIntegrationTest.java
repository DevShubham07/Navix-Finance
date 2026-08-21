package com.navix.app.loan;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.loan.domain.LoanStatus;
import com.navix.loan.entity.Loan;
import com.navix.loan.repository.LoanRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The staff loan register's date window, against a real Flyway-migrated Postgres.
 *
 * <p>This exists because {@code LoanRegisterServiceTest} mocks {@link LoanRepository} and therefore
 * cannot see the query at all. The register's optional bounds are expressed as
 * {@code (cast(:from as date) is null or l.disbursedOn >= :from)}, and Postgres rejects that
 * predicate outright — "could not determine data type of parameter" — if the cast is dropped, since
 * it cannot infer a bare parameter's type from {@code :param is null}. The failure is invisible to
 * every mocked test and to a both-bounds-supplied call; it only appears when exactly one bound is
 * null, which is the common case (a "from this date" filter with no end).
 *
 * <p>So the one-sided cases below are the point of this test, not the two-sided one.
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
class LoanRegisterQueryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired LoanRepository loans;

    private static final LocalDate JAN = LocalDate.of(2026, 1, 15);
    private static final LocalDate JUN = LocalDate.of(2026, 6, 15);
    private static final LocalDate DEC = LocalDate.of(2026, 12, 15);

    @BeforeEach
    void seed() {
        loans.deleteAll();
        loans.saveAll(List.of(loan(JAN), loan(JUN), loan(DEC)));
    }

    private static Loan loan(LocalDate disbursedOn) {
        Loan l = new Loan();
        l.setCustomerId(9_000_001L);
        l.setPrincipal(1_000_000L);
        l.setStatus(LoanStatus.ACTIVE);
        l.setDisbursedOn(disbursedOn);
        l.setDueDate(disbursedOn.plusDays(28));
        return l;
    }

    @Test
    void bothBoundsNullReturnsEveryLoan() {
        assertThat(loans.findAllForRegister(null, null)).hasSize(3);
    }

    @Test
    void onlyTheFromBoundIsSupplied() {
        assertThat(loans.findAllForRegister(JUN, null))
                .extracting(Loan::getDisbursedOn)
                .containsExactlyInAnyOrder(JUN, DEC);
    }

    @Test
    void onlyTheToBoundIsSupplied() {
        assertThat(loans.findAllForRegister(null, JUN))
                .extracting(Loan::getDisbursedOn)
                .containsExactlyInAnyOrder(JAN, JUN);
    }

    @Test
    void bothBoundsNarrowToTheWindow() {
        assertThat(loans.findAllForRegister(JUN, JUN))
                .extracting(Loan::getDisbursedOn)
                .containsExactly(JUN);
    }
}
