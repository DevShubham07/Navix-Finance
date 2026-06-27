package com.navix.storage.config;

/**
 * Allowed document categories and their S3 key prefixes. Restricting uploads to
 * a fixed set of categories prevents arbitrary key injection and keeps the
 * bucket organised by artifact type.
 */
public enum StorageCategory {

    /** Selfie captured during KYC (records only — no liveness API). */
    KYC_SELFIE("kyc/selfie"),
    /** DigiLocker / identity documents retained for KYC. */
    KYC_DOCUMENT("kyc/document"),
    /** Address-proof uploads. */
    ADDRESS_PROOF("kyc/address-proof"),
    /** Generated loan documents (Agreement / Sanction Letter / KFS). */
    LOAN_DOCUMENT("loan/document"),
    /** Manual repayment proof (UPI/bank-transfer screenshots). */
    REPAYMENT_PROOF("loan/repayment-proof"),
    /** Collections interaction proof. */
    COLLECTIONS_PROOF("collections/proof"),
    /** Admin-managed company payment assets (UPI QR image, payee account-info PDF). */
    PAYMENT_SETTINGS("payment/settings");

    private final String prefix;

    StorageCategory(String prefix) {
        this.prefix = prefix;
    }

    /** Key prefix (folder) under which objects of this category are stored. */
    public String prefix() {
        return prefix;
    }
}
