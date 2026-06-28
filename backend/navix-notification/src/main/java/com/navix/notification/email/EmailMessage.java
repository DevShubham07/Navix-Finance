package com.navix.notification.email;

/** A ready-to-send email (recipient address + rendered subject/body). */
public record EmailMessage(String to, String subject, String body) {
}
