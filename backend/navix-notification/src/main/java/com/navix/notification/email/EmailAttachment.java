package com.navix.notification.email;

/**
 * A file to attach to an outgoing email. {@code content} is the raw bytes — kept small (a rendered
 * PDF, not a bulk export) since it rides in memory through the send path. Never logged in full;
 * {@code LogEmailClient} records only the filename + byte count.
 */
public record EmailAttachment(String filename, String contentType, byte[] content) {
}
