package com.navix.app.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DashboardScopeTest {

    @Mock private StaffDirectory directory;

    @BeforeEach
    void roster() {
        lenient().when(directory.listActive("CREDIT_HEAD")).thenReturn(List.of(s(1, "CREDIT_HEAD")));
        lenient().when(directory.listActive("CREDIT_EXECUTIVE"))
                .thenReturn(List.of(s(2, "CREDIT_EXECUTIVE"), s(3, "CREDIT_EXECUTIVE")));
        lenient().when(directory.listActive("COLLECTION_HEAD")).thenReturn(List.of(s(10, "COLLECTION_HEAD")));
        lenient().when(directory.listActive("COLLECTION_EXECUTIVE"))
                .thenReturn(List.of(s(11, "COLLECTION_EXECUTIVE")));
        lenient().when(directory.listActive("TELECALLER")).thenReturn(List.of(s(20, "TELECALLER")));
    }

    private static StaffSummary s(long id, String role) {
        return new StaffSummary(id, "Staff " + id, role, true);
    }

    private static CurrentActor actor(long id, String role) {
        return new CurrentActor(String.valueOf(id), "n", role);
    }

    private DashboardScope resolve(CurrentActor a, String view, List<Long> ids) {
        return DashboardScope.resolve(a, view, ids, directory);
    }

    @Test
    void nonStaffAndDsaAreRejectedForEveryView() {
        for (String role : List.of("BORROWER", "ANONYMOUS", "SYSTEM", "DSA")) {
            assertThatThrownBy(() -> resolve(actor(9, role), "ADMIN", null))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getCode()).isEqualTo("FORBIDDEN_ROLE"));
        }
    }

    @Test
    void executiveIsForcedToSelfWhateverTheyAskFor() {
        DashboardScope scope = resolve(actor(2, "CREDIT_EXECUTIVE"), "CREDIT_EXECUTIVE", List.of(3L, 99L));
        assertThat(scope.staffIds()).containsExactly(2L);
        assertThat(scope.orgWide()).isFalse();
    }

    @Test
    void executiveCannotTakeAnotherViewNorAdmin() {
        assertThatThrownBy(() -> resolve(actor(2, "CREDIT_EXECUTIVE"), "CREDIT_HEAD", null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> resolve(actor(2, "CREDIT_EXECUTIVE"), "ADMIN", null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void headMayTakeItsChildViewButNotTheOtherDomain() {
        DashboardScope child = resolve(actor(1, "CREDIT_HEAD"), "CREDIT_EXECUTIVE", null);
        assertThat(child.staffIds()).containsExactlyInAnyOrder(2L, 3L);
        assertThat(resolve(actor(1, "CREDIT_HEAD"), "CREDIT_HEAD", null).staffIds())
                .containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThatThrownBy(() -> resolve(actor(1, "CREDIT_HEAD"), "COLLECTION_EXECUTIVE", null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> resolve(actor(1, "CREDIT_HEAD"), "ADMIN", null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void adminMayTakeAnyViewAndIsOrgWideOnlyOnTheAdminView() {
        assertThat(resolve(actor(5, "ADMIN"), "ADMIN", null).orgWide()).isTrue();
        DashboardScope sim = resolve(actor(5, "ADMIN"), "COLLECTION_EXECUTIVE", null);
        assertThat(sim.orgWide()).isFalse();
        assertThat(sim.staffIds()).containsExactly(11L);
        assertThat(resolve(actor(5, "ADMIN"), "TELECALLER", null).staffIds()).containsExactly(20L);
        assertThat(resolve(actor(5, "ADMIN"), "ACCOUNTANT", null).staffIds()).isEmpty();
    }

    @Test
    void requestedStaffIdsAreIntersectedWithTheRoster() {
        DashboardScope scope = resolve(actor(1, "CREDIT_HEAD"), "CREDIT_HEAD", List.of(2L, 77L));
        assertThat(scope.staffIds()).containsExactly(2L);
        DashboardScope none = resolve(actor(1, "CREDIT_HEAD"), "CREDIT_HEAD", List.of(77L));
        assertThat(none.staffIds()).isEmpty();
        assertThat(none.sqlIds()).containsExactly(-1L);
    }

    @Test
    void unknownViewIsForbidden() {
        assertThatThrownBy(() -> resolve(actor(5, "ADMIN"), "NOPE", null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> resolve(actor(5, "ADMIN"), null, null)).isInstanceOf(BusinessException.class);
    }

    @Test
    void requireNamesTheAllowedViews() {
        DashboardScope scope = resolve(actor(5, "ADMIN"), "ADMIN", null);
        scope.require("ADMIN");
        assertThatThrownBy(() -> scope.require("COLLECTION_HEAD")).isInstanceOf(BusinessException.class);
    }
}
