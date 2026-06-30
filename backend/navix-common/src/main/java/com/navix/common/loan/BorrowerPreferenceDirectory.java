package com.navix.common.loan;

import com.navix.common.notification.NotificationChannel;
import java.util.Set;

/**
 * Borrower notification-preference lookup for the engine — which channels a borrower has opted OUT of.
 * Implemented by navix-loan (backed by {@code borrower_preferences}); consumed by the
 * {@code NotificationDispatcher} to suppress an opted-out SMS/EMAIL per recipient (IN_APP is the inbox
 * and is never suppressed). Default (no row) opts the borrower IN to everything → an empty set.
 */
public interface BorrowerPreferenceDirectory {

    /** The channels this borrower has opted out of (never includes IN_APP). Empty when all-on / unknown. */
    Set<NotificationChannel> optedOutChannels(Long applicantId);
}
