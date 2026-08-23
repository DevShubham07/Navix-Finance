package com.navix.common.staff;

import com.navix.common.notification.NotificationChannel;
import java.util.Set;

/**
 * Staff notification-preference lookup for the engine — which channels a staffer has opted OUT of.
 * Implemented by navix-iam (backed by {@code staff_user.email_opt_in}); consumed by the
 * {@code NotificationDispatcher} to suppress an opted-out EMAIL per recipient (IN_APP is the inbox
 * and is never suppressed). Mirrors {@code BorrowerPreferenceDirectory}. Default (no row / unknown
 * id) opts the staffer IN to everything → an empty set (fail open, never silently mute a real send).
 */
public interface StaffPreferenceDirectory {

    /** The channels this staffer has opted out of (never includes IN_APP). Empty when all-on / unknown. */
    Set<NotificationChannel> optedOutChannels(Long staffId);
}
