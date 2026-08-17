package com.navix.iam.domain;

/**
 * Staff roles used for access control and maker-checker separation of duties.
 * TODO: map each role to its permitted actions / API scopes.
 */
public enum StaffRole {
    /**
     * Credit Executive — verifies the file and makes the <b>final</b> credit decision (V45): they
     * sanction the amount + repayment date, or reject. There is no Head counter-approval; the
     * Credit Head's role is to assign the work. Absorbed the deleted KYC_APPROVER's duties.
     */
    CREDIT_EXECUTIVE,
    CREDIT_HEAD,
    DISBURSEMENT_HEAD,
    ACCOUNTANT,
    COLLECTION_HEAD,
    COLLECTION_EXECUTIVE,
    /**
     * Telecaller — calls the lead list and logs the outcome. Deliberately holds NO lifecycle
     * authority (no KYC/credit/disbursement/collections step); it only views customers and
     * writes call logs + remarks, so it never participates in maker-checker or SoD.
     */
    TELECALLER,
    /**
     * DSA (Direct Selling Agent) — an external commission agent who brings leads via their own
     * portal (PAN-attributed) and earns a flat commission on the lead's first disbursed loan once
     * fully repaid. Firewalled from the platform: no customer data, no pipeline, no other agent's
     * leads. Deliberately holds NO lifecycle authority (no KYC/credit/disbursement/collections
     * step), so — like TELECALLER — it never participates in maker-checker or SoD.
     */
    DSA,
    ADMIN,
    /** Internal read-only operations role (health, logs, read-only DB). */
    DEVELOPER
}
