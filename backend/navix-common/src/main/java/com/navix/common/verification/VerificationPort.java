package com.navix.common.verification;

import java.util.List;

/**
 * Port for external identity/income/bureau verification, implemented in {@code navix-verification} by
 * {@code RoutingVerificationPort} (the {@code @Primary} bean that routes Signzy → Digitap per capability)
 * and consumed by the loan aggregate ({@code ApplicationVerificationService}) — the same "swap at a
 * seam" pattern as {@link com.navix.common.loan.LoanDirectory}.
 *
 * <p>The records below are <b>provider-neutral</b>: no Signzy/Digitap DTO ever crosses onto the loan
 * classpath. The per-provider adapters map the raw provider envelopes into these. Every record carries
 * the provider {@code txnId} for audit.
 */
public interface VerificationPort {

    /** PAN check — Signzy 206AB primary, Digitap {@code pan_details_plus} fallback. */
    PanCheck verifyPan(String pan, String clientRef);

    /** Email + establishment match — Digitap {@code cv/email_verification} (Signzy has no email API). */
    EmailCheck verifyEmail(String email, String individualName, String establishmentName, String clientRef);

    /** Geo (lat/long) → address within India — Digitap {@code ent} (Signzy has no address API). */
    AddressCheck verifyAddress(double latitude, double longitude, String clientRef);

    /**
     * Credit bureau pull — Signzy Experian→CRIF primary, Digitap Credit Analytics fallback.
     * @param otp the borrower's already-verified bureau-consent OTP (see
     *            {@code ApplicationVerificationService.recordBureauConsent}) — Digitap's live Credit
     *            Analytics mandates this in its payload; Signzy ignores it (no OTP requirement).
     */
    BureauCheck pullBureau(String pan, String name, String mobile, String dob, String otp, String clientRef);

    /**
     * Answer a pending bureau KBA challenge (see {@link PendingChallenge}) and collect the report it
     * was gating. Fintrix {@code POST /bureau_ch_user_auth} is the only implementation — hence the
     * {@code default} body: Signzy and Digitap have no such endpoint and are never asked, because
     * {@code RoutingVerificationPort} delegates this capability straight to Fintrix rather than
     * walking the provider chain. An {@code orderId} belongs to exactly one vendor; falling through
     * to another bureau with it would be meaningless and would burn a billable call.
     *
     * <p>{@code answer} MUST be one of the challenge's option strings VERBATIM, including any leading
     * or trailing whitespace — CRIF returns them space-padded and compares literally. Never trim it
     * anywhere on the path from the browser to the provider.
     *
     * <p>Returns a normal {@link BureauCheck}: a score + facts on success, or a fresh
     * {@code pendingChallenge} if the bureau answered with another question.
     */
    default BureauCheck answerBureauChallenge(String orderId, String reportId, String answer,
                                              String name, String mobile, String clientRef) {
        throw new UnsupportedOperationException("This provider has no bureau KBA answer endpoint");
    }

    /** Penny-drop bank account verify + name-at-bank — Signzy only (Digitap lacks it). */
    PennyDropCheck pennyDrop(String accountNumber, String ifsc, String clientRef);

    /**
     * EPFO/UAN employment verification — Digitap {@code uan_advanced} only (Signzy has no UAN lookup).
     *
     * <p>Independently corroborates what the borrower declared about their job: which employer the EPFO
     * has them at, since when, and whether the PF filings still look live. Lookup needs at least one of
     * {@code pan} / {@code mobile}; supplying {@code dob} + {@code employeeName} widens the search, and
     * {@code employerName} is what makes {@code employerNameMatch} (and therefore a meaningful
     * {@code employed}) computable at all — the provider rejects it unless {@code employeeName} is sent
     * too, so the adapter drops it when the name is missing.
     *
     * <p>This carries <b>no salary figure</b>: the whole EPFO employment family reports employment, not
     * pay. Wage data needs the separate passbook/TDS APIs.
     *
     * <p>{@code uan}, when the borrower has supplied it, is lookup method 3 (direct by UAN) — the exact
     * match, tried ahead of the PAN/mobile/dob/name fallback.
     */
    EmploymentCheck verifyEmployment(String pan, String mobile, String dob, String employeeName,
                                     String employerName, String uan, String clientRef);

    /**
     * Selfie face check — Digitap Face Match. Matches the uploaded selfie ({@code imageUrl}) against a
     * reference photo ({@code referenceImageUrl}, typically the DigiLocker Aadhaar face). When
     * {@code referenceImageUrl} is null the call degrades to a single-image quality/face-detection check.
     * Both are presigned image URLs. {@code FaceLivenessCheck.live} carries the match/pass result.
     */
    FaceLivenessCheck faceLiveness(String imageUrl, String referenceImageUrl, String clientRef);

    /**
     * Liveness (interactive): start a Signzy Liveness-Secure video journey. {@code matchImageUrl} (a
     * presigned reference photo, typically the DigiLocker Aadhaar face) enables the 1:1 face-match; null
     * runs liveness only. Returns the token + hosted {@code videoUrl} to redirect the borrower to. This is
     * a two-step async flow (mirrors DigiLocker); the result is fetched later via {@link #livenessResult}.
     */
    LivenessSession livenessInit(String matchImageUrl, String clientRef);

    /** Liveness: fetch the result by token. {@code completed=false} while the video journey is still in progress. */
    LivenessResultCheck livenessResult(String token);

    /** DigiLocker: start a consent session; returns the redirect URL + session client id. */
    DigiLockerSession digilockerInit(String redirectUrl, int expiryMinutes, boolean signupFlow);

    /** DigiLocker: poll session status until {@code completed} or {@code failed}. */
    DigiLockerStatus digilockerStatus(String clientId);

    /** DigiLocker: list the documents the user shared. */
    List<DigiLockerDoc> digilockerList(String clientId);

    /** DigiLocker: resolve a file id to a short-lived (~10 min) presigned download URL. */
    DigiLockerDownload digilockerDownload(String clientId, String fileId);

    /** DigiLocker: parsed Aadhaar demographics + document URLs. */
    AadhaarResult digilockerAadhaar(String clientId);

    // ---- neutral result records ----
    // Each carries `provider` (SIGNZY | DIGITAP) — stamped by the adapter that actually served the call
    // (accurate even when the router fell back), so callers can persist the true provider, not a guess.

    record PanCheck(String txnId, String provider, boolean valid, String fullName, String dob, String gender,
                    boolean aadhaarLinked, String maskedAadhaar, String panNumber,
                    String addressState, String addressZip,
                    String panStatus, String panAllotmentDate, Boolean compliant, String isSpecified) {
    }

    record EmailCheck(String txnId, String provider, boolean verified, boolean establishmentMatched,
                      boolean individualMatched, boolean genericEmail, String matchedEstablishment,
                      String status, String domain, Boolean mxFound, String mxRecord, String smtpProvider,
                      String didYouMean, String personName, String companyName, Double individualScore) {
    }

    record AddressCheck(String txnId, String provider, boolean withinIndia, String address, String pincode,
                        String state, String district, String country, Double confidenceScore) {
    }

    /**
     * {@code source} is the bureau that answered (e.g. SIGNZY_EXPERIAN / DIGITAP_EXPERIAN); facts null
     * on thin-file/CRIF. {@code reportUrl} is a provider-supplied link to the vendor's own report
     * document (e.g. Fintrix CRIF's {@code credit_report_link}), carried neutrally here rather than
     * pulled out of {@code rawResponseJson} downstream — {@code VerificationPort}'s records are
     * provider-neutral, and reaching into the Fintrix envelope shape from navix-loan would hard-code
     * that provider's JSON there. Null when the provider offers no such document (Signzy/Digitap today).
     *
     * <p>{@code pendingChallenge} is populated instead of a score when the bureau demands the borrower
     * answer a knowledge-based-authentication question before releasing a report that otherwise exists
     * (Fintrix CRIF's {@code crif_combine}). It's deliberately NOT {@code noRecord} — the report is
     * real, just gated. Null on every other outcome (success, thin-file, provider failure).
     */
    record BureauCheck(String txnId, String source, Integer score, boolean noRecord,
                       Integer activeAccounts, Integer overdueAccounts, Double totalBalance,
                       BureauReportFacts facts, String rawResponseJson, String reportUrl,
                       PendingChallenge pendingChallenge) {

        /** Backward-compatible constructor for providers/tests that do not yet expose a raw report. */
        public BureauCheck(String txnId, String source, Integer score, boolean noRecord,
                           Integer activeAccounts, Integer overdueAccounts, Double totalBalance,
                           BureauReportFacts facts, String rawResponseJson, String reportUrl) {
            this(txnId, source, score, noRecord, activeAccounts, overdueAccounts, totalBalance, facts,
                    rawResponseJson, reportUrl, null);
        }

        /** Backward-compatible constructor for providers/tests that do not yet expose a raw report. */
        public BureauCheck(String txnId, String source, Integer score, boolean noRecord,
                           Integer activeAccounts, Integer overdueAccounts, Double totalBalance,
                           BureauReportFacts facts, String rawResponseJson) {
            this(txnId, source, score, noRecord, activeAccounts, overdueAccounts, totalBalance, facts,
                    rawResponseJson, null, null);
        }

        /** Backward-compatible constructor for providers/tests that do not yet expose a raw report. */
        public BureauCheck(String txnId, String source, Integer score, boolean noRecord,
                           Integer activeAccounts, Integer overdueAccounts, Double totalBalance,
                           BureauReportFacts facts) {
            this(txnId, source, score, noRecord, activeAccounts, overdueAccounts, totalBalance, facts,
                    null, null, null);
        }
    }

    /**
     * A pending bureau KBA (knowledge-based-authentication) challenge — the report exists but CRIF
     * wants the borrower to answer a question about their own credit history first. {@code orderId} is
     * the handle the answer submission needs, and {@code reportId} identifies the withheld report —
     * BOTH are required by {@link #answerBureauChallenge}, so both must be persisted when a challenge
     * is parked. Answering is implemented against Fintrix {@code /bureau_ch_user_auth}.
     */
    record PendingChallenge(String question, List<String> options, String orderId, String reportId) {

        /** Back-compat for callers/tests predating the answer flow, which had no {@code reportId}. */
        public PendingChallenge(String question, List<String> options, String orderId) {
            this(question, options, orderId, null);
        }
    }

    record PennyDropCheck(String txnId, String provider, boolean accountExists, String fullName,
                          String bank, String ifsc, String bankRrn, String reason, String providerNameMatch) {
    }

    /**
     * EPFO/UAN employment record.
     *
     * <p>{@code found} distinguishes "the EPFO has no record for this identity" (a legitimate outcome for
     * a first job, a cash employer, or a non-PF establishment) from a provider failure, which throws.
     * {@code employed} is the provider's own verdict and is only as strong as its inputs: the name-match
     * booleans are {@code null} when the corresponding names were not supplied, so a {@code true} here
     * off a PAN-only lookup means "PF filings are recent and no exit is marked", not "employer confirmed".
     *
     * <p>{@code tooManyRecords} flags the provider's {@code result_code 104} — the identity maps to more
     * than five UANs, so nothing was resolved and the file needs a human.
     */
    record EmploymentCheck(String txnId, String provider, boolean found, boolean tooManyRecords,
                           String message, boolean employed, String uan, Integer uanCount,
                           String employerName, String establishmentId, String memberId,
                           String dateOfJoining, String dateOfExit,
                           Boolean dateOfExitMarked, String leaveReason, String uanSource,
                           Boolean employeeNameMatch, Boolean employerNameMatch,
                           Double employerConfidenceScore, Boolean recentPfFiling, Boolean hasPfFilings,
                           String nameOnRecord, String dobOnRecord, String genderOnRecord) {
    }

    record FaceLivenessCheck(String txnId, String provider, boolean live, Double confidence,
                             boolean multipleFaces, Boolean personImageBlurry) {
    }

    /** Liveness journey session — the token to poll and the hosted video URL to redirect the borrower to. */
    record LivenessSession(String txnId, String provider, String consumerId, String videoUrl) {
    }

    /**
     * Liveness journey result. {@code completed=false} means the borrower has not finished the video yet
     * (keep polling). {@code faceMatched}/{@code matchPercentage} are populated only when a match image was
     * supplied at init; {@code overallPass} is the provider's own combined verdict.
     */
    record LivenessResultCheck(String txnId, String provider, boolean completed, boolean live,
                               Double livenessScore, Boolean faceMatched, String matchPercentage,
                               String capturedImageUrl, boolean overallPass) {
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
                          String fullAddress, String state, String district, String city, String pincode,
                          String country, String addressLine, String landmark, String dscSubject,
                          String profileImageBase64, String pdfUrl, String jpegUrl, String xmlUrl) {
    }
}
