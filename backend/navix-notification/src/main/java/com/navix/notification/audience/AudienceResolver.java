package com.navix.notification.audience;

import com.navix.common.loan.BorrowerContactDirectory;
import com.navix.common.notification.ContactInfo;
import com.navix.common.staff.StaffContactDirectory;
import com.navix.notification.catalog.RecipientPolicy;
import com.navix.notification.dispatch.NotificationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Turns a {@link RecipientPolicy} set + a {@link NotificationContext} into a concrete, de-duplicated
 * recipient list. Role policies fan out across every ACTIVE holder (via {@link StaffContactDirectory});
 * targeted policies resolve a single contact; {@code TO_BORROWER} uses {@link BorrowerContactDirectory}.
 * Recipients are de-duped by {@code (type, id)} so a borrower/staffer named by two policies is hit once.
 */
@Component
public class AudienceResolver {

    private final StaffContactDirectory staff;
    private final BorrowerContactDirectory borrower;

    public AudienceResolver(StaffContactDirectory staff, BorrowerContactDirectory borrower) {
        this.staff = staff;
        this.borrower = borrower;
    }

    public List<ContactInfo> resolve(Set<RecipientPolicy> policies, NotificationContext ctx) {
        Map<String, ContactInfo> byKey = new LinkedHashMap<>();
        for (RecipientPolicy policy : policies) {
            for (ContactInfo c : resolveOne(policy, ctx)) {
                if (c != null && c.type() != null && c.id() != null) {
                    byKey.putIfAbsent(c.type() + ":" + c.id(), c);
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private List<ContactInfo> resolveOne(RecipientPolicy policy, NotificationContext ctx) {
        return switch (policy) {
            case TO_BORROWER -> ctx.applicantId() == null
                    ? List.of()
                    : borrower.borrowerContact(ctx.applicantId()).map(List::of).orElseGet(List::of);
            case TO_ASSIGNED_EXECUTIVE -> staffOne(ctx.assignedExecutiveId());
            case TO_STAFF_SUBJECT -> ctx.explicitStaffSubject() != null
                    ? List.of(ctx.explicitStaffSubject())
                    : staffOne(ctx.staffSubjectId());
            case TO_KYC_APPROVERS -> staff.contactsByRole("KYC_APPROVER");
            case TO_CREDIT_HEADS -> staff.contactsByRole("CREDIT_HEAD");
            case TO_CREDIT_EXECUTIVES -> staff.contactsByRole("CREDIT_EXECUTIVE");
            case TO_DISBURSEMENT_HEADS -> staff.contactsByRole("DISBURSEMENT_HEAD");
            case TO_ACCOUNTANTS -> staff.contactsByRole("ACCOUNTANT");
            case TO_COLLECTION_HEADS -> staff.contactsByRole("COLLECTION_HEAD");
            case TO_COLLECTION_EXECUTIVES -> staff.contactsByRole("COLLECTION_EXECUTIVE");
            case TO_ADMINS -> staff.contactsByRole("ADMIN");
        };
    }

    private List<ContactInfo> staffOne(Long staffId) {
        return staffId == null ? List.of() : staff.contact(staffId).map(List::of).orElseGet(List::of);
    }
}
