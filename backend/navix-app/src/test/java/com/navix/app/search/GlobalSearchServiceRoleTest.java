package com.navix.app.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.navix.app.search.GlobalSearchDtos.SearchResponse;
import com.navix.collections.service.CollectionsService;
import com.navix.common.exception.BusinessException;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.iam.service.BlocklistService;
import com.navix.iam.service.StaffService;
import com.navix.loan.dto.CustomerDtos.CustomerPage;
import com.navix.loan.dto.LeadDtos.LeadPage;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.service.ApplicationFlowService;
import com.navix.loan.service.CustomerService;
import com.navix.loan.service.LeadService;
import com.navix.loan.service.LoanRegisterService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Which entity families a role may search is the whole security surface of the palette: a group that
 * runs for the wrong role hands that role a list of names, mobiles and PANs it has no business
 * seeing. Each group's own service applies its own scoping on top; these tests pin the layer above
 * — that the group is not even asked for.
 */
@ExtendWith(MockitoExtension.class)
class GlobalSearchServiceRoleTest {

    @Mock private CustomerService customerService;
    @Mock private ApplicationFlowService applicationFlowService;
    @Mock private LoanRegisterService loanRegisterService;
    @Mock private CollectionsService collectionsService;
    @Mock private LeadService leadService;
    @Mock private StaffService staffService;
    @Mock private BlocklistService blocklistService;
    @Mock private CustomerProfileRepository profileRepository;
    @Mock private FeatureFlagService featureFlags;

    @InjectMocks private GlobalSearchService service;

    @BeforeEach
    void flagOn() {
        lenient().when(featureFlags.isEnabled(eq(GlobalSearchService.FLAG), anyBoolean())).thenReturn(true);
        lenient().when(customerService.page(anyString(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(new CustomerPage(List.of(), 1, 5, 0));
        lenient().when(applicationFlowService.search(anyString(), anyInt())).thenReturn(List.of());
        lenient().when(loanRegisterService.list(any(), anyString(), any(), any())).thenReturn(List.of());
        lenient().when(collectionsService.listCaseViews()).thenReturn(List.of());
        lenient().when(leadService.list(anyString(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt())).thenReturn(new LeadPage(List.of(), 1, 5, 0));
        lenient().when(staffService.listStaff()).thenReturn(List.of());
        lenient().when(blocklistService.listActive()).thenReturn(List.of());
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    private void actingAs(String role) {
        ActorContext.set(new CurrentActor("7", "Staffer", role));
    }

    @Test
    void aDsaCannotSearchAtAll() {
        // DSA satisfies ROLE_STAFF at the namespace gate, so this surface must reject it by name —
        // the same explicit exclusion CustomerService and ApplicationController already carry.
        actingAs("DSA");
        assertThatThrownBy(() -> service.search("rajesh", 5))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("DSA");
        verifyNoInteractions(customerService, applicationFlowService, loanRegisterService,
                collectionsService, leadService, staffService, blocklistService);
    }

    @Test
    void aBorrowerOrAnonymousTokenIsRejected() {
        for (String role : new String[] {"BORROWER", "ANONYMOUS", "SYSTEM"}) {
            actingAs(role);
            assertThatThrownBy(() -> service.search("rajesh", 5))
                    .as(role)
                    .isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(customerService, applicationFlowService, loanRegisterService,
                collectionsService, leadService, staffService, blocklistService);
    }

    @Test
    void aCreditExecutiveSearchesCustomersAndApplicationsOnly() {
        actingAs("CREDIT_EXECUTIVE");
        service.search("rajesh", 5);

        verify(customerService).page(eq("rajesh"), any(), any(), any(), anyBoolean(), anyInt(), anyInt());
        verify(applicationFlowService).search(eq("rajesh"), anyInt());
        verifyNoInteractions(loanRegisterService, collectionsService, leadService, staffService, blocklistService);
    }

    @Test
    void aTelecallerSearchesLeadsButNeverLoansOrStaff() {
        actingAs("TELECALLER");
        service.search("rajesh", 5);

        verify(leadService).list(eq("rajesh"), any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt());
        verifyNoInteractions(loanRegisterService, staffService, blocklistService, applicationFlowService);
    }

    @Test
    void aCollectionExecutiveGetsCasesButNotTheLoansRegister() {
        actingAs("COLLECTION_EXECUTIVE");
        service.search("rajesh", 5);

        verify(collectionsService).listCaseViews();
        // The register is loan:register — COLLECTION_HEAD and ADMIN only.
        verifyNoInteractions(loanRegisterService, staffService, blocklistService, leadService);
    }

    @Test
    void adminSearchesEverything() {
        actingAs("ADMIN");
        service.search("rajesh", 5);

        verify(customerService).page(eq("rajesh"), any(), any(), any(), anyBoolean(), anyInt(), anyInt());
        verify(applicationFlowService).search(eq("rajesh"), anyInt());
        verify(loanRegisterService).list(any(), eq("rajesh"), any(), any());
        verify(collectionsService).listCaseViews();
        verify(staffService).listStaff();
        verify(blocklistService).listActive();
    }

    @Test
    void theKillSwitchStopsEveryQuery() {
        when(featureFlags.isEnabled(eq(GlobalSearchService.FLAG), anyBoolean())).thenReturn(false);
        actingAs("ADMIN");

        assertThatThrownBy(() -> service.search("rajesh", 5))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("switched off");
        verifyNoInteractions(customerService, applicationFlowService, loanRegisterService,
                collectionsService, leadService, staffService, blocklistService);
    }

    @Test
    void aTooShortQueryNeverReachesAService() {
        actingAs("ADMIN");
        SearchResponse response = service.search("r", 5);

        assertThat(response.groups()).isEmpty();
        verifyNoInteractions(customerService, applicationFlowService, loanRegisterService,
                collectionsService, leadService, staffService, blocklistService);
    }

    @Test
    void aGroupThatRejectsTheRoleIsDroppedRatherThanFailingTheWholeSearch() {
        // Defence in depth: if the role matrix and a service's own guard ever disagree, the service
        // wins and the palette keeps working for every other group.
        actingAs("ADMIN");
        when(staffService.listStaff()).thenThrow(new BusinessException("FORBIDDEN_ROLE", "nope"));

        SearchResponse response = service.search("rajesh", 5);

        assertThat(response.groups()).noneMatch(g -> g.kind().equals("staff"));
    }

    @Test
    void anUnexpectedFailureIsNotSwallowed() {
        actingAs("ADMIN");
        when(staffService.listStaff()).thenThrow(new BusinessException("DB_DOWN", "boom"));

        assertThatThrownBy(() -> service.search("rajesh", 5))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("boom");
    }
}
