package com.navix.loan.domain;

/**
 * How a repayment was made.
 *
 * <p>Manual methods (UPI, bank transfer) are [IN BUILD]; NACH auto-debit is
 * [FUTURE] and unlocked once an NBFC / NACH partner is in place.
 */
public enum PaymentMethod {
    UPI,
    BANK_TRANSFER,
    NACH,
    /** PayU Hosted Checkout: confirmed by PayU (verify_payment), recorded VERIFIED in one step. */
    PAYU
}
