package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.dto.AdminApplicationDtos.AdminApplicationPage;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.ApplicationRejectionRepository;
import com.navix.loan.repository.CustomerOwnerRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * The All-applications register's two-stage shape: column filters narrow the id list in SQL, the derived
 * completeness filter then runs over THAT list, and the survivors are paged. The id list is the single
 * source of the count and the pages, so a filter can never make them disagree. SQL itself is not run here
 * (no DB in the unit suite); the statement and its bound operands are asserted instead.
 */
@ExtendWith(MockitoExtension.class)
class AdminApplicationRegisterFilterTest {

    @Mock private LoanApplicationRepository applicationRepository;
    @Mock private CustomerProfileRepository profileRepository;
    @Mock private ApplicationVerificationService verification;
    @Mock private ApplicationRejectionRepository rejectionRepository;
    @Mock private ApplicationEventRepository eventRepository;
    @Mock private CustomerOwnerRepository ownerRepository;
    @Mock private BureauStateService bureauStateService;
    @Mock private com.navix.common.staff.StaffDirectory staffDirectory;
    @Mock private CustomerTrustSignalsService trustSignals;
    @Mock private NamedParameterJdbcTemplate jdbc;

    private AdminApplicationService service;

    @BeforeEach
    void setUp() {
        service = new AdminApplicationService(applicationRepository, profileRepository, verification,
                rejectionRepository, eventRepository, ownerRepository, bureauStateService, staffDirectory,
                trustSignals, jdbc);
        ActorContext.set(new CurrentActor("1", "Meera", "ADMIN"));
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    private static LoanApplication app(long id) {
        LoanApplication a = new LoanApplication();
        a.setId(id);
        return a;
    }

    private void stubIds(List<Long> ids) {
        when(jdbc.queryForList(any(String.class), any(SqlParameterSource.class), eq(Long.class))).thenReturn(ids);
        when(applicationRepository.findAllById(anyCollection()))
                .thenAnswer(i -> ((java.util.Collection<Long>) i.getArgument(0)).stream().map(AdminApplicationRegisterFilterTest::app).toList());
        when(applicationRepository.count()).thenReturn(50L);
    }

    @Test
    void columnFilterIsBoundIntoTheSqlAndTotalFollowsThePageList() {
        stubIds(List.of(9L, 7L, 5L, 3L, 1L));
        // Money filter arrives in rupees; the column is paise.
        Map<String, String> params = Map.of("f.status", "in:KYC_PENDING,DRAFT", "f.amount", "num:10000..", "f.bogus", "in:x");

        AdminApplicationPage first = service.page(null, null, params, 1, 2);
        AdminApplicationPage last = service.page(null, null, params, 3, 2);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> bound = ArgumentCaptor.forClass(SqlParameterSource.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.times(2))
                .queryForList(sql.capture(), bound.capture(), eq(Long.class));
        String statement = sql.getAllValues().get(0);
        assertThat(statement).contains("(a.status)").contains("(a.amount_requested)").doesNotContain("bogus");
        assertThat(statement).endsWith("order by a.id desc");
        // The operand carries the stored unit: 10,000 rupees -> 1,000,000 paise, and it is a long, not text.
        assertThat(bound.getValue().getValue("cf0min")).isEqualTo(1_000_000L);
        // The user's request text never reaches the statement.
        assertThat(statement).doesNotContain("KYC_PENDING").doesNotContain("10000");

        // One id list feeds both: the count is the whole narrowed list, the pages tile it.
        assertThat(first.total()).isEqualTo(5);
        assertThat(last.total()).isEqualTo(5);
        assertThat(last.page()).isEqualTo(3);
        assertThat(first.allCount()).isEqualTo(50);
    }

    @Test
    void completenessComposesOnTopOfTheColumnFilter() {
        // The column filter leaves 4 ids; completeness is evaluated over exactly those, not the book.
        stubIds(List.of(8L, 6L, 4L, 2L));
        when(verification.requiredPassedCounts(anyCollection()))
                .thenReturn(Map.of(8L, ApplicationVerificationService.requiredCount(), 6L, 0,
                        4L, ApplicationVerificationService.requiredCount(), 2L, 0));
        when(applicationRepository.findIdsWithTermsAccepted(anyCollection())).thenReturn(Set.of(8L, 6L, 2L));

        AdminApplicationPage complete = service.page(null, "COMPLETE", Map.of("f.status", "in:DRAFT"), 1, 25);
        AdminApplicationPage incomplete = service.page(null, "incomplete", Map.of("f.status", "in:DRAFT"), 1, 25);

        // 8 passed every step and agreed; 4 passed every step but never accepted the terms.
        assertThat(complete.total()).isEqualTo(1);
        assertThat(incomplete.total()).isEqualTo(3);
        // The two partitions of the narrowed list are exhaustive: nothing outside the filter leaks in.
        assertThat(complete.total() + incomplete.total()).isEqualTo(4);
    }
}
