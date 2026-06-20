package com.navix.iam.domain;

/**
 * Identifier categories screened against the fraud blocklist at sign-up AND
 * before approval. A match stops the application.
 */
public enum BlocklistType {
    PAN,
    AADHAAR_REF,
    PHONE,
    DEVICE,
    BANK_ACCOUNT
}
