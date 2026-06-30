package com.navix.notification.suppression;

import com.navix.common.util.Masking;
import com.navix.notification.entity.EmailSuppression;
import com.navix.notification.repository.EmailSuppressionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records and queries the email suppression list. {@link #suppress} is idempotent (a repeat
 * bounce/complaint for an already-suppressed address is a no-op) and {@link #isSuppressed} is the
 * send-time guard the {@code EmailSender} consults. Both are null/blank-safe.
 */
@Service
public class EmailSuppressionService {

    private static final Logger log = LoggerFactory.getLogger(EmailSuppressionService.class);

    private final EmailSuppressionRepository repo;

    public EmailSuppressionService(EmailSuppressionRepository repo) {
        this.repo = repo;
    }

    /** Add an address to the suppression list. No-op if blank or already suppressed. */
    @Transactional
    public void suppress(String email, String reason, String subType, String messageId, String detail) {
        if (email == null || email.isBlank()) {
            return;
        }
        String trimmed = email.trim();
        if (repo.existsByEmailIgnoreCase(trimmed)) {
            return;
        }
        EmailSuppression entry = new EmailSuppression();
        entry.setEmail(trimmed);
        entry.setReason(reason);
        entry.setSubType(subType);
        entry.setSesMessageId(messageId);
        entry.setDetail(detail == null ? null : detail.substring(0, Math.min(detail.length(), 1000)));
        repo.save(entry);
        log.info("EMAIL suppressed to={} reason={} subType={}", Masking.maskEmail(trimmed), reason, subType);
    }

    /** Whether this address is on the suppression list (case-insensitive). */
    @Transactional(readOnly = true)
    public boolean isSuppressed(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return repo.existsByEmailIgnoreCase(email.trim());
    }
}
