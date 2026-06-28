package com.navix.notification.audience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.navix.common.loan.BorrowerContactDirectory;
import com.navix.common.notification.ContactInfo;
import com.navix.common.notification.RecipientType;
import com.navix.common.staff.StaffContactDirectory;
import com.navix.notification.catalog.RecipientPolicy;
import com.navix.notification.dispatch.NotificationContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Fan-out across role holders, single-target resolution, the explicit subject, and de-dup. */
@ExtendWith(MockitoExtension.class)
class AudienceResolverTest {

    @Mock
    private StaffContactDirectory staff;
    @Mock
    private BorrowerContactDirectory borrower;

    private AudienceResolver resolver() {
        return new AudienceResolver(staff, borrower);
    }

    private static ContactInfo staffContact(long id, String role) {
        return new ContactInfo(RecipientType.STAFF, id, "Staff " + id, "s" + id + "@navix.test", null, role);
    }

    @Test
    void fansOutAcrossEveryActiveRoleHolder() {
        when(staff.contactsByRole("CREDIT_HEAD"))
                .thenReturn(List.of(staffContact(1, "CREDIT_HEAD"), staffContact(2, "CREDIT_HEAD")));
        NotificationContext ctx = NotificationContext.builder().applicationId(10L).build();

        List<ContactInfo> out = resolver().resolve(Set.of(RecipientPolicy.TO_CREDIT_HEADS), ctx);

        assertThat(out).extracting(ContactInfo::id).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void resolvesBorrowerFromApplicantId() {
        when(borrower.borrowerContact(77L)).thenReturn(Optional.of(
                new ContactInfo(RecipientType.BORROWER, 77L, "Asha", "asha@x.test", "9876500000", "BORROWER")));
        NotificationContext ctx = NotificationContext.builder().applicantId(77L).build();

        List<ContactInfo> out = resolver().resolve(Set.of(RecipientPolicy.TO_BORROWER), ctx);

        assertThat(out).singleElement().satisfies(c -> {
            assertThat(c.type()).isEqualTo(RecipientType.BORROWER);
            assertThat(c.id()).isEqualTo(77L);
        });
    }

    @Test
    void borrowerPolicyWithNoApplicantResolvesEmpty() {
        NotificationContext ctx = NotificationContext.builder().build();
        assertThat(resolver().resolve(Set.of(RecipientPolicy.TO_BORROWER), ctx)).isEmpty();
    }

    @Test
    void dedupesRecipientNamedByTwoPolicies() {
        ContactInfo head = staffContact(5, "COLLECTION_HEAD");
        when(staff.contactsByRole("COLLECTION_HEAD")).thenReturn(List.of(head));
        when(staff.contact(5L)).thenReturn(Optional.of(head)); // assigned executive == same staff id 5
        NotificationContext ctx = NotificationContext.builder().assignedExecutiveId(5L).build();

        List<ContactInfo> out = resolver().resolve(
                Set.of(RecipientPolicy.TO_COLLECTION_HEADS, RecipientPolicy.TO_ASSIGNED_EXECUTIVE), ctx);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).id()).isEqualTo(5L);
    }

    @Test
    void explicitStaffSubjectIsUsedDirectly() {
        // An invited staffer has no staff row yet, so the listener supplies the contact inline.
        ContactInfo invitee = new ContactInfo(RecipientType.STAFF, 0L, "New Hire", "new@navix.test", null, "ACCOUNTANT");
        NotificationContext ctx = NotificationContext.builder()
                .staffSubjectId(0L).explicitStaffSubject(invitee).build();

        List<ContactInfo> out = resolver().resolve(Set.of(RecipientPolicy.TO_STAFF_SUBJECT), ctx);

        assertThat(out).singleElement().isEqualTo(invitee);
    }
}
