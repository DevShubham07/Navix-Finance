package com.navix.notification.channel;

/** The result of a single channel send — mapped onto a {@code notification_delivery} row. */
public record DeliveryOutcome(DeliveryStatus status, String providerRef, String error) {

    public static DeliveryOutcome sent(String providerRef) {
        return new DeliveryOutcome(DeliveryStatus.SENT, providerRef, null);
    }

    public static DeliveryOutcome failed(String error) {
        return new DeliveryOutcome(DeliveryStatus.FAILED, null, error);
    }

    public static DeliveryOutcome skipped(String reason) {
        return new DeliveryOutcome(DeliveryStatus.SKIPPED, null, reason);
    }
}
