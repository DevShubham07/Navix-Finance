package com.navix.config;

import com.navix.common.notification.event.StaffAccountEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Evicts a staffer's {@link StaffSessionRegistry} cache entry the moment their account is disabled,
 * so the kick-out (§ {@code StaffSessionRegistry}) doesn't wait out the 30s
 * cache TTL. Event-driven like the rest of the account-change fan-out in {@code navix-notification}
 * — {@link com.navix.iam.service.StaffService} publishes {@link StaffAccountEvent} and never calls
 * the registry directly.
 *
 * <p>{@code AFTER_COMMIT}: {@code StaffService.updateStaff}/{@code disableStaff} publish the event
 * from inside their {@code @Transactional} method, so a listener that ran beforehand could evict the
 * cache and immediately reload the pre-change (still-ACTIVE) row.
 */
@Component
public class StaffSessionInvalidator {

    private final StaffSessionRegistry staffSessionRegistry;

    public StaffSessionInvalidator(StaffSessionRegistry staffSessionRegistry) {
        this.staffSessionRegistry = staffSessionRegistry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStaffAccount(StaffAccountEvent e) {
        if (e.changeType() == StaffAccountEvent.ChangeType.DISABLED) {
            staffSessionRegistry.invalidate(e.staffId());
        }
    }
}
