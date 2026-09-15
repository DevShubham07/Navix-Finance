package com.navix.app.lead;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navix.loan.entity.Lead;
import com.navix.loan.repository.LeadRepository;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V70's two lead columns against a real Flyway-migrated PostgreSQL.
 *
 * <p>Exists because the last two lead-side bugs this repo shipped were both things only a real
 * database says: a {@code jsonb}/{@code varchar} mapping that failed on every insert, and a CHECK
 * constraint nobody exercised. Unit tests mock the repository, so neither would have been caught.
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
class LeadOutcomeAndNotePersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired LeadRepository repository;
    @Autowired JdbcTemplate jdbc;

    private static Lead lead(String mobile) {
        Lead l = new Lead();
        l.setName("Ravi Kumar");
        l.setMobile(mobile);
        l.setCreatedByStaffId(7L);
        l.setCreatedAt(Instant.now());
        return l;
    }

    /** The default must back-fill, or every row already in the table violates the NOT NULL. */
    @Test
    void anInsertWithoutAnOutcomeDefaultsToNew() {
        Lead saved = repository.saveAndFlush(lead("9876543210"));

        assertThat(repository.findById(saved.getId()))
                .get()
                .satisfies(found -> {
                    assertThat(found.getLeadOutcome()).isEqualTo("NEW");
                    assertThat(found.getDsaNote()).isNull();
                });
    }

    @Test
    void storesEachSettableOutcomeAndTheNote() {
        for (String outcome : new String[] {"NEW", "OUTREACHED", "REJECTED"}) {
            Lead l = lead("98765432" + (10 + outcome.length()));
            l.setLeadOutcome(outcome);
            l.setDsaNote("Spoke to them on " + outcome);
            Long id = repository.saveAndFlush(l).getId();

            assertThat(repository.findById(id)).get().satisfies(found -> {
                assertThat(found.getLeadOutcome()).isEqualTo(outcome);
                assertThat(found.getDsaNote()).isEqualTo("Spoke to them on " + outcome);
            });
        }
    }

    /**
     * CONFIRMED is derived on read, never stored — the CHECK is what guarantees no code path can
     * quietly persist it and make the derived value disagree with the column.
     */
    @Test
    void theDatabaseRefusesConfirmedAndAnyOtherValue() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into lead (name, mobile, call_status, lead_outcome, created_by_staff_id, created_at)"
                        + " values (?, ?, 'NOT_CALLED', 'CONFIRMED', 7, now())",
                "Ravi", "9876543299"))
                .hasMessageContaining("chk_lead_outcome");

        assertThatThrownBy(() -> jdbc.update(
                "insert into lead (name, mobile, call_status, lead_outcome, created_by_staff_id, created_at)"
                        + " values (?, ?, 'NOT_CALLED', 'BOGUS', 7, now())",
                "Ravi", "9876543298"))
                .hasMessageContaining("chk_lead_outcome");
    }

    /** The DSA portal's widened read: leads they own, plus leads they merely uploaded. */
    @Test
    void theVisibilityQueryReturnsOwnedAndUploadedButNotAnotherAgents() {
        Lead entered = lead("9811111111");
        entered.setOwnerDsaId(7L);
        entered.setPan("ABCDE1234F");
        repository.saveAndFlush(entered);

        Lead uploaded = lead("9822222222");     // created_by_staff_id 7, owner null — an import
        repository.saveAndFlush(uploaded);

        Lead otherAgents = lead("9833333333");
        otherAgents.setOwnerDsaId(8L);
        otherAgents.setCreatedByStaffId(8L);
        otherAgents.setPan("ZYXWV9876E");
        repository.saveAndFlush(otherAgents);

        assertThat(repository.findByOwnerDsaIdOrCreatedByStaffIdOrderByIdDesc(7L, 7L))
                .extracting(Lead::getMobile)
                .containsExactlyInAnyOrder("9811111111", "9822222222");

        assertThat(repository.findVisibleToDsa(otherAgents.getId(), 7L)).isEmpty();
        assertThat(repository.findVisibleToDsa(uploaded.getId(), 7L)).isPresent();
    }
}
