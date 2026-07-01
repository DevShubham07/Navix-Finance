package com.navix.loan.service;

import com.navix.loan.entity.ProfileChangeLog;
import com.navix.loan.repository.ProfileChangeLogRepository;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reusable append-only writer for the audited profile/salary change history. Records a
 * {@link ProfileChangeLog} row only when a field's value actually changes; {@code created_by}
 * (editor's name) and {@code created_at} come from {@code BaseAuditEntity} auditing. Shared by the
 * ADMIN customer-correction path and the borrower reborrow salary re-declaration.
 */
@Component
@RequiredArgsConstructor
public class ProfileChangeLogger {

    private final ProfileChangeLogRepository changeLogRepository;

    /** Append a change-log row when {@code oldValue != newValue} (no-op when unchanged). */
    public void logIfChanged(Long customerId, Long applicationId, String field, String oldValue, String newValue) {
        if (Objects.equals(oldValue, newValue)) {
            return;
        }
        ProfileChangeLog entry = new ProfileChangeLog();
        entry.setCustomerId(customerId);
        entry.setApplicationId(applicationId);
        entry.setField(field);
        entry.setOldValue(oldValue);
        entry.setNewValue(newValue);
        changeLogRepository.save(entry);
    }
}
