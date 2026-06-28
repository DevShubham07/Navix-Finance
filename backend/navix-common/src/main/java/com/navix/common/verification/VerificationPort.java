package com.navix.common.verification;

import java.util.List;

/**
 * Port for external identity/income/bureau verification, implemented by
 * {@code VerificationAdapter} in {@code navix-verification} and consumed by the
 * loan aggregate ({@code ApplicationVerificationService}) — the same "swap at a
 * seam" pattern as {@link com.navix.common.loan.LoanDirectory}.
 *
 * <p>The records below are <b>provider-neutral</b>: no Fintrix/DigiLocker DTO ever
 * crosses onto the loan classpath. The adapter maps the raw provider envelopes
 * into these. Every record carries the provider {@code txnId} for audit.
 */
public interface VerificationPort {

    /** PAN comprehensive (Fintrix {@code pan_comprehensive}). */
    PanCheck verifyPan(String pan, String clientRef);

    /** Official email + EPFO employer match (Fintrix {@code cv_email_verification}). */
    EmailCheck verifyEmail(String email, String individualName, String establishmentName, String clientRef);

    /** Geo (lat/long) → address within India (Fintrix {@code ent_address_verification}). */
    AddressCheck verifyAddress(double latitude, double longitude, String clientRef);

    /** Credit bureau pull: Experian primary → CRIF fallback, unified. */
    BureauCheck pullBureau(String pan, String name, String mobile, String dob, String clientRef);

    /** Penny-drop bank account verify + name-at-bank (Fintrix {@code verification_pennydrop}). */
    PennyDropCheck pennyDrop(String accountNumber, String ifsc, String clientRef);

    /** Selfie liveness on a presigned image URL (Fintrix {@code vkyc_face_liveness}). */
    FaceLivenessCheck faceLiveness(String imageUrl, String clientRef);

    /** DigiLocker: start a consent session; returns the redirect URL + session client id. */
    DigiLockerSession digilockerInit(String redirectUrl, int expiryMinutes, boolean signupFlow);

    /** DigiLocker: poll session status until {@code completed} or {@code failed}. */
    DigiLockerStatus digilockerStatus(String clientId);

    /** DigiLocker: list the documents the user shared. */
    List<DigiLockerDoc> digilockerList(String clientId);

    /** DigiLocker: resolve a file id to a short-lived (~10 min) presigned download URL. */
    DigiLockerDownload digilockerDownload(String clientId, String fileId);

    /** DigiLocker: parsed Aadhaar demographics + photo + raw-XML URL. */
    AadhaarResult digilockerAadhaar(String clientId);

    // ---- neutral result records ----

    record PanCheck(String txnId, boolean valid, String fullName, String dob, String gender,
                    boolean aadhaarLinked, String maskedAadhaar, String panNumber,
                    String addressState, String addressZip) {
    }

    record EmailCheck(String txnId, boolean verified, boolean establishmentMatched,
                      boolean individualMatched, boolean genericEmail, String matchedEstablishment) {
    }

    record AddressCheck(String txnId, boolean withinIndia, String address, String pincode,
                        String state, String district) {
    }

    /** {@code facts} carries the categorized credit-report snapshot (Experian); null on thin-file/CRIF. */
    record BureauCheck(String txnId, String source, Integer score, boolean noRecord,
                       Integer activeAccounts, Integer overdueAccounts, Double totalBalance,
                       BureauReportFacts facts) {
    }

    record PennyDropCheck(String txnId, boolean accountExists, String fullName, String bank, String ifsc) {
    }

    record FaceLivenessCheck(String txnId, boolean live, Double confidence, boolean multipleFaces) {
    }

    record DigiLockerSession(String txnId, String clientId, String url, Integer expirySeconds) {
    }

    record DigiLockerStatus(String txnId, String status, boolean completed, boolean failed,
                            boolean aadhaarLinked) {
    }

    record DigiLockerDoc(String fileId, String name, String docType, String fileType) {
    }

    record DigiLockerDownload(String txnId, String downloadUrl, String mimeType) {
    }

    record AadhaarResult(String txnId, String fullName, String dob, String gender, String maskedAadhaar,
                         String fullAddress, String state, String pincode, String profileImageBase64,
                         String xmlUrl) {
    }
}
