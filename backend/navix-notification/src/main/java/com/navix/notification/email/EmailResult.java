package com.navix.notification.email;

/** Outcome of an {@link EmailClient} send. */
public record EmailResult(boolean ok, String providerRef, String error) {

    public static EmailResult ok(String providerRef) {
        return new EmailResult(true, providerRef, null);
    }

    public static EmailResult fail(String error) {
        return new EmailResult(false, null, error);
    }
}
