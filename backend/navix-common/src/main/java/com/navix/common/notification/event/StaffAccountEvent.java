package com.navix.common.notification.event;

import java.time.Instant;

/**
 * Published on staff/IAM account changes. The recipient is the staff subject themselves; the engine
 * builds an explicit {@code ContactInfo} from these fields (an INVITED subject has no staff row yet).
 * {@code inviteToken} is set only for {@code INVITED} — it carries the one-time accept token so the
 * email can include the activation link.
 */
public record StaffAccountEvent(
        Long staffId,
        String email,
        String name,
        String role,
        ChangeType changeType,
        String inviteToken,
        Instant at) {

    /** Which account change occurred — maps 1:1 to STAFF_{INVITED,CREATED,ROLE_CHANGED,DISABLED}. */
    public enum ChangeType { INVITED, CREATED, ROLE_CHANGED, DISABLED }
}
