package com.navix.common.staff;

import com.navix.common.notification.ContactInfo;
import java.util.List;
import java.util.Optional;

/**
 * PII-bearing staff lookup for the notification engine — deliberately separate from the lean
 * {@link StaffDirectory}/{@code StaffSummary} (which carries no email/mobile). Implemented by
 * navix-iam; consumed by the notification {@code AudienceResolver} for staff fan-out and targeting.
 */
public interface StaffContactDirectory {

    /** Contact details (incl. email) for one staff user, or empty if none. */
    Optional<ContactInfo> contact(Long staffId);

    /** Contact details for every ACTIVE staff user holding {@code role} (a role fan-out target). */
    List<ContactInfo> contactsByRole(String role);
}
