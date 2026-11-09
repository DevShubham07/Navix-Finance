package com.navix.common.notification.event;

import java.time.Instant;

/**
 * Staff cancelled a customer's ACTIVE eMandate at the provider — on the customer's request, or to replace
 * it (typically with one carrying a higher ceiling). The borrower is told either way: auto-repayment has
 * stopped, so any live loan is now theirs to repay manually until a new mandate is in place.
 *
 * <p>Only the bank name and last four of the account travel — never the full account number or the UMRN.
 */
public record MandateCancelledEvent(Long customerId, Long applicationId, String name, String bankName,
                                    String accountLast4, boolean customerRequested, Instant at) {
}
