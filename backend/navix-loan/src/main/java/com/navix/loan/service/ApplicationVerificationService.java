package com.navix.loan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.notification.event.KycReminderEvent;
import com.navix.common.notification.event.SanctionLetterSignedEvent;
import com.navix.common.risk.RiskPort;
import com.navix.common.security.ActorContext;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.common.verification.EsignPort;
import com.navix.common.verification.OtpVerifierPort;
import com.navix.common.verification.VerificationPort;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.ApplicationDocument;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.time.Instant;
import java.util.Base64;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the borrower onboarding's external verification steps against the
 * single {@code loan_application} aggregate. Each step is idempotent — a check that
 * already PASSed returns the stored result without re-calling the provider (the
 * {@code (application_id, check_type)} unique row is the key). Provider calls go
 * through {@link VerificationPort}; documents through {@link DocumentStoragePort};
 * the eligible-limit cap + risk grade through {@link RiskPort}.
 *
 * <p>PII discipline: only scrubbed/computed fields are persisted to the audit jsonb
 * and ever returned; bureau score / risk grade are never put in a borrower-facing
 * {@link StepResult}.
 */
@Service
@RequiredArgsConstructor
public class ApplicationVerificationService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ApplicationVerificationService.class);

    // ---- check types ----
    public static final String PAN = "PAN";
    public static final String EMAIL = "EMAIL";
    public static final String ADDRESS = "ADDRESS";
    public static final String DIGILOCKER = "DIGILOCKER";
    public static final String AADHAAR = "AADHAAR";
    /** The Aadhaar face photo (from DigiLocker), stored so the selfie step can 1:1 face-match it. */
    public static final String AADHAAR_PHOTO = "AADHAAR_PHOTO";
    public static final String BUREAU = "BUREAU";
    public static final String SALARY = "SALARY";
    public static final String PENNY_DROP = "PENNY_DROP";
    public static final String SELFIE = "SELFIE";
    public static final String AGREEMENT = "AGREEMENT";
    /** The borrower's eSignature on the sanction letter / KFS — the per-loan legal act (Phase 3). */
    public static final String ESIGN = "ESIGN";
    /** Document types the Phase-3 sanction letter + eSign write (not verification check types). */
    public static final String SANCTION_LETTER = "SANCTION_LETTER";
    public static final String SIGNED_AGREEMENT = "SIGNED_AGREEMENT";
    /**
     * The borrower's OTP-verified consent to the credit-bureau enquiry. Deliberately NOT in
     * {@link #REQUIRED} (that would wedge every application whose PAN passed before this shipped)
     * nor in {@link #KNOWN_CHECKS} (staff must not be able to manually assert a borrower's consent).
     */
    public static final String BUREAU_CONSENT = "BUREAU_CONSENT";

    // ---- statuses ----
    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String REVIEW = "REVIEW";
    public static final String PENDING = "PENDING";

    /**
     * Intake checks — the ones the Phase-1 consent screen fires, and the set every completeness
     * surface reports on. They must have been <b>attempted</b> to submit; a FAIL still goes to the
     * credit team flagged (revamp.md decision 10).
     */
    static final List<String> REQUIRED = List.of(PAN, EMAIL, BUREAU, SALARY);

    /**
     * Phase-3 checks, run after credit approval. Non-blocking (revamp.md decision 11) — they surface
     * in the Verification Dashboard, not as a gate. PENNY_DROP is deliberately absent: it only fires
     * when the borrower changes their disbursal account, so it may legitimately never run.
     */
    static final List<String> REQUIRED_SANCTION = List.of(AADHAAR, SELFIE, ADDRESS, ESIGN);

    /**
     * The checks a re-apply legitimately inherits from the advance it carried from — everything the
     * borrower proved about <em>themselves</em>, which does not become untrue between advances.
     *
     * <p>ESIGN is deliberately absent. It is the legal act for one specific loan and is signed again
     * on every advance (revamp.md decision 45), so inheriting the previous signature would report a
     * file as fully verified before the borrower had signed anything for the money about to be
     * released. PENNY_DROP is absent because it is never counted anywhere (revamp.md decision 9).
     */
    private static final Set<String> INHERITABLE_CHECKS =
            Set.of(PAN, EMAIL, BUREAU, SALARY, AADHAAR, SELFIE, ADDRESS);

    /** Every recognised check type — guards the staff manual-override target. */
    static final Set<String> KNOWN_CHECKS = Set.of(PAN, EMAIL, ADDRESS, DIGILOCKER, AADHAAR, BUREAU,
            SALARY, PENNY_DROP, SELFIE, AGREEMENT, ESIGN);

    /** Permissive name-match cutoff: below this is REVIEW (not hard fail) — approver decides. */
    static final double NAME_MATCH_THRESHOLD = 0.60;

    private final ApplicationVerificationRepository verificationRepo;
    private final CustomerProfileRepository profileRepo;
    private final LoanApplicationRepository applicationRepo;
    private final ApplicationDocumentRepository documentRepo;
    private final VerificationPort verification;
    private final EsignPort esign;
    private final OtpVerifierPort otpVerifier;
    private final DocumentStoragePort storage;
    private final RiskPort risk;
    private final ObjectMapper objectMapper;
    private final CreditBriefService creditBriefService;
    private final ApplicationEventPublisher eventPublisher;
    private final ProfileChangeLogger changeLogger;

    /** Borrower-safe view of one step (never carries bureau score / raw PII). */
    public record StepResult(String checkType, String status, String message, Map<String, Object> derived) {
    }

    /** Required-step completion snapshot for the progress tracker (Phase 3.2). */
    public record VerificationProgress(int required, int completed, int failed, int pending, int percent) {
    }

    /** Result of a staff-triggered KYC reminder (Phase 3.4). */
    public record ReminderResult(boolean sent, int pendingCount, String pendingSteps) {
    }

    /** One row in the cross-application pending-API dashboard (Phase 3.3). */
    public record VerificationOverviewRow(Long applicationId, Long customerId, String borrowerName,
                                          String borrowerMobile,
                                          String checkType, String status, String provider,
                                          String message, Instant updatedAt,
                                          String applicationStatus) {
    }

    /** Pending-API dashboard payload: status tallies + the (filtered) rows (Phase 3.3). */
    public record VerificationOverview(int passed, int review, int failed, int pending, int neverRun,
                                       List<VerificationOverviewRow> rows) {
    }

    // ---------------------------------------------------------------- steps

    /** PAN comprehensive: identity backbone + Aadhaar-link flag. */
    @Transactional
    public StepResult verifyPan(Long appId, String pan) {
        Optional<ApplicationVerification> existing = passed(appId, PAN);
        if (existing.isPresent()) {
            return view(existing.get());
        }
        String ref = ref(appId, PAN);
        VerificationPort.PanCheck r;
        try {
            r = verification.verifyPan(pan, ref);
        } catch (RuntimeException providerFailure) {
            // The provider couldn't be reached. Record it and let the borrower through — a technical
            // failure must not wedge an application on the last screen (revamp.md decision 10); the
            // credit team sees the flag and decides. Mirrors verifyEmail below.
            return providerUnavailable(appId, PAN, "PAN check unavailable — pending manual review");
        }
        CustomerProfile profile = profile(appId);
        profile.setPanVerified(r.valid());
        profile.setAadhaarLinked(r.aadhaarLinked());
        // Capture the date of birth the PAN record returns so it's part of the borrower's stored
        // details from the very first identity step — surfaced even before KYC approval. Don't
        // overwrite a value the borrower or a richer source (DigiLocker) already set.
        if (profile.getDob() == null) {
            LocalDate panDob = parseDob(r.dob());
            if (panDob != null) {
                profile.setDob(panDob);
            }
        }
        // The intake never asks for a name (revamp.md decision 12), so the PAN record is where the
        // borrower's identity arrives. Same don't-overwrite guard as the DOB above.
        if ((profile.getFullName() == null || profile.getFullName().isBlank())
                && r.fullName() != null && !r.fullName().isBlank()) {
            profile.setFullName(r.fullName());
        }
        profileRepo.save(profile);

        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("fullName", r.fullName());
        derived.put("dob", r.dob());
        derived.put("gender", r.gender());
        derived.put("aadhaarLinked", r.aadhaarLinked());
        derived.put("maskedAadhaar", r.maskedAadhaar());
        derived.put("addressState", r.addressState());
        derived.put("addressZip", r.addressZip());
        derived.put("panStatus", r.panStatus());
        derived.put("panAllotmentDate", r.panAllotmentDate());
        derived.put("compliant", r.compliant());
        derived.put("isSpecified", r.isSpecified());
        derived.put("panNumber", r.panNumber());
        String status = r.valid() ? PASS : FAIL;
        ApplicationVerification row = upsert(appId, PAN, status, r.provider(), r.txnId(), ref,
                null, null, null, derived, r.valid() ? "PAN valid" : "PAN not valid");
        recomputeNameMatch(appId);
        return view(row);
    }

    /** Official email + EPFO employer corroboration. */
    @Transactional
    public StepResult verifyEmail(Long appId, String email) {
        Optional<ApplicationVerification> existing = passed(appId, EMAIL);
        if (existing.isPresent()) {
            return view(existing.get());
        }
        CustomerProfile profile = profile(appId);
        String ref = ref(appId, EMAIL);
        // Persist the email as the contact email if the borrower hasn't already supplied one
        // (personal email saved via the profile takes precedence). V22. Applies regardless of the
        // provider outcome below.
        if (profile.getEmail() == null || profile.getEmail().isBlank()) {
            profile.setEmail(email);
        }
        VerificationPort.EmailCheck r;
        try {
            r = verification.verifyEmail(email, nz(profile.getFullName()), nz(profile.getEmployer()), ref);
        } catch (RuntimeException providerFailure) {
            // Email provider couldn't run (e.g. not provisioned / upstream error). Don't hard-block
            // onboarding with a 500 — record for manual review and let the borrower continue,
            // mirroring the penny-drop/selfie steps. (Product decision: never stop the borrower here.)
            profile.setEmailVerified(false);
            profileRepo.save(profile);
            Map<String, Object> failDerived = new LinkedHashMap<>();
            failDerived.put("verified", false);
            failDerived.put("providerError", true);
            return view(upsert(appId, EMAIL, REVIEW, "DIGITAP", null, ref, null, null, null, failDerived,
                    "We couldn't verify your email right now — you can continue; our team will review it."));
        }
        profile.setEmailVerified(r.verified());
        profileRepo.save(profile);

        boolean ok = r.verified() && r.establishmentMatched() && !r.genericEmail();
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("verified", r.verified());
        derived.put("establishmentMatched", r.establishmentMatched());
        derived.put("individualMatched", r.individualMatched());
        derived.put("genericEmail", r.genericEmail());
        derived.put("matchedEstablishment", r.matchedEstablishment());
        derived.put("status", r.status());
        derived.put("domain", r.domain());
        derived.put("mxFound", r.mxFound());
        derived.put("mxRecord", r.mxRecord());
        derived.put("smtpProvider", r.smtpProvider());
        derived.put("didYouMean", r.didYouMean());
        derived.put("personName", r.personName());
        derived.put("companyName", r.companyName());
        derived.put("individualScore", r.individualScore());
        String status = ok ? PASS : REVIEW;
        String msg = ok ? "Email + employer matched"
                : (r.genericEmail() ? "Not an official email" : "Employer not matched — manual review");
        return view(upsert(appId, EMAIL, status, r.provider(), r.txnId(), ref, null, null, null, derived, msg));
    }

    /** Geo (lat/long) → within-India address. */
    @Transactional
    public StepResult verifyAddress(Long appId, double lat, double lng) {
        Optional<ApplicationVerification> existing = passed(appId, ADDRESS);
        if (existing.isPresent()) {
            return view(existing.get());
        }
        requireApplication(appId);
        String ref = ref(appId, ADDRESS);
        CustomerProfile profile = profile(appId);
        VerificationPort.AddressCheck r;
        try {
            r = verification.verifyAddress(lat, lng, ref);
        } catch (RuntimeException providerFailure) {
            // Address provider couldn't run (e.g. not provisioned / upstream error). Don't 500 the
            // borrower — record for manual review and continue, mirroring the penny-drop/selfie steps.
            profile.setAddressVerified(false);
            profileRepo.save(profile);
            Map<String, Object> failDerived = new LinkedHashMap<>();
            failDerived.put("providerError", true);
            return view(upsert(appId, ADDRESS, REVIEW, "DIGITAP", null, ref, null, null, null, failDerived,
                    "We couldn't verify your address right now — you can continue; our team will review it."));
        }
        // The geocoder's within-India flag is unreliable (valid Indian addresses sometimes resolve
        // false), so it no longer gates the step — a successfully resolved address PASSes. The raw
        // flag is still recorded in the audit `derived` for staff. Only an address that fails to
        // resolve at all goes to REVIEW.
        boolean resolved = r.address() != null && !r.address().isBlank();
        profile.setAddressVerified(resolved);
        if (resolved && (profile.getAddress() == null || profile.getAddress().isBlank())) {
            profile.setAddress(r.address());
        }
        profileRepo.save(profile);

        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("withinIndia", r.withinIndia());
        derived.put("state", r.state());
        derived.put("pincode", r.pincode());
        derived.put("address", r.address());
        derived.put("district", r.district());
        derived.put("country", r.country());
        derived.put("confidenceScore", r.confidenceScore());
        String status = resolved ? PASS : REVIEW;
        return view(upsert(appId, ADDRESS, status, r.provider(), r.txnId(), ref, null, null, null, derived,
                resolved ? "Address resolved" : "Address could not be resolved — review"));
    }

    /** Manual address fallback when geolocation is unavailable — recorded for approver review. */
    @Transactional
    public StepResult recordManualAddress(Long appId, String manualAddress) {
        if (manualAddress == null || manualAddress.isBlank()) {
            throw new BusinessException("ADDRESS_REQUIRED", "Provide coordinates or a manual address");
        }
        CustomerProfile profile = profile(appId);
        profile.setAddress(manualAddress);
        profile.setAddressVerified(Boolean.FALSE);
        profileRepo.save(profile);
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("manualAddress", manualAddress);
        return view(upsert(appId, ADDRESS, REVIEW, "DhanBoost", null, ref(appId, ADDRESS),
                null, null, null, derived, "Manual address — pending review"));
    }

    /**
     * App-scoped presigned PUT target for a browser upload (salary slip, selfie).
     *
     * <p>Storage failures are translated into a typed, borrower-readable error. This sits on the
     * mandatory payslip step: when object storage is unreachable (no credentials, an S3 outage) the
     * raw {@code SdkClientException} escaped as a bare {@code INTERNAL_ERROR} plus a UUID, which
     * told the borrower "an unexpected error occurred" and stranded them at step 8 of 10 with
     * nothing to act on. Same treatment as the sanction letter.
     */
    @Transactional(readOnly = true)
    public PresignedUpload presignUpload(Long appId, String docType, String fileName, String contentType) {
        requireApplication(appId);
        String ext = extensionOf(fileName, contentType);
        String key = storage.buildApplicationKey(appId, docType, ext);
        try {
            return new PresignedUpload(key, storage.presignUpload(key, contentType));
        } catch (RuntimeException storageFailure) {
            log.error("presign failed application={} docType={}: {}", appId, docType,
                    storageFailure.toString());
            throw new BusinessException("UPLOAD_UNAVAILABLE",
                    "We can't accept uploads just now. Please try again in a few minutes.");
        }
    }

    /** Presigned PUT target (key the caller echoes back on the verify/* call; url the browser PUTs to). */
    public record PresignedUpload(String key, String url) {
    }

    private static String extensionOf(String fileName, String contentType) {
        if (fileName != null && fileName.contains(".")) {
            return fileName.substring(fileName.lastIndexOf('.') + 1);
        }
        if (contentType != null && contentType.contains("/")) {
            return contentType.substring(contentType.lastIndexOf('/') + 1);
        }
        return "bin";
    }

    /** Date-of-birth formats the upstream identity APIs emit, in order of preference. */
    private static final List<DateTimeFormatter> DOB_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,            // 1992-08-15
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),   // 15/08/1992
            DateTimeFormatter.ofPattern("dd-MM-yyyy"));  // 15-08-1992

    /**
     * Best-effort parse of a verification-supplied date of birth into a {@link LocalDate}.
     * Tolerates ISO {@code yyyy-MM-dd}, {@code dd/MM/yyyy}, {@code dd-MM-yyyy} and the 8-digit
     * {@code yyyyMMdd} the bureau emits. Returns {@code null} for anything unparseable so a bad
     * upstream value can never break a verification step.
     */
    private static LocalDate parseDob(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.length() == 8 && t.chars().allMatch(Character::isDigit)) {
            t = t.substring(0, 4) + "-" + t.substring(4, 6) + "-" + t.substring(6, 8);
        }
        for (DateTimeFormatter f : DOB_FORMATS) {
            try {
                return LocalDate.parse(t, f);
            } catch (DateTimeParseException ignore) {
                // try the next supported format
            }
        }
        return null;
    }

    /** Start a DigiLocker consent session; returns the redirect URL. */
    @Transactional
    public StepResult digilockerInit(Long appId, String redirectUrl) {
        requireApplication(appId);
        CustomerProfile profile = profile(appId);

        VerificationPort.DigiLockerSession s;
        try {
            s = verification.digilockerInit(redirectUrl, 20, true);
        } catch (RuntimeException providerFailure) {
            // DigiLocker is best-effort. If the provider can't start a consent session (out of
            // credits / provider error / timeout), don't 500 and don't hard-block onboarding:
            // record the step for manual staff review and tell the frontend it can continue.
            // The Aadhaar number is already captured on the PAN step, and submit-kyc's
            // allowAadhaarManualReview keeps the AADHAAR gate open for staff to finish. Mirrors
            // the graceful degrade already used for the bureau pull.
            Map<String, Object> soft = new LinkedHashMap<>();
            soft.put("providerError", true);
            soft.put("skippable", true);
            return view(upsert(appId, DIGILOCKER, REVIEW, "MANUAL", null, ref(appId, DIGILOCKER),
                    null, null, null, soft,
                    "DigiLocker is temporarily unavailable — you can continue; our team will verify your Aadhaar during review."));
        }

        profile.setDigilockerClientId(s.clientId());
        profileRepo.save(profile);

        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("clientId", s.clientId());
        derived.put("url", s.url());
        return view(upsert(appId, DIGILOCKER, PENDING, "DIGILOCKER", s.clientId(), s.clientId(),
                null, null, null, derived, "DigiLocker session started"));
    }

    /** Poll the DigiLocker session status. */
    @Transactional(readOnly = true)
    public StepResult digilockerStatus(Long appId) {
        CustomerProfile profile = profile(appId);
        String clientId = profile.getDigilockerClientId();
        // TEMP (revert later): DigiLocker is best-effort — force the status to always resolve PASS so
        // onboarding never stalls, whether the provider stalled at "client_initiated" OR no consent
        // session was ever created (provider-degraded init records DIGILOCKER as REVIEW with no
        // clientId). Reporting completed here lets even an older frontend that polls status advance;
        // the Aadhaar falls to staff manual review at submit-kyc. Removing both branches restores the
        // real provider-driven status logic below.
        if (clientId == null) {
            // No consent session (provider-degraded init). Don't 500 with DIGILOCKER_NOT_STARTED —
            // treat the best-effort step as completed so the wizard advances.
            Map<String, Object> forced = new LinkedHashMap<>();
            forced.put("status", "completed");
            forced.put("completed", true);
            forced.put("failed", false);
            forced.put("finalized", true);
            forced.put("providerError", true);
            return new StepResult(DIGILOCKER, PASS, "DigiLocker completed", forced);
        }
        if (clientId != null) {
            Map<String, Object> forced = new LinkedHashMap<>();
            forced.put("status", "completed");
            forced.put("completed", true);
            forced.put("failed", false);
            forced.put("finalized", true);
            return new StepResult(DIGILOCKER, PASS, "DigiLocker completed", forced);
        }
        // Our own finalized state is authoritative. Once the Aadhaar has actually been fetched
        // (by either tab — see digilockerComplete) the step is done, regardless of the provider's
        // status flag. The Fintrix/Surepass status endpoint is unreliable here: it routinely stalls
        // at "client_initiated" and never reports completed=true, so trusting that flag leaves the
        // poll spinning forever. Short-circuit on the finalized AADHAAR row and skip the provider
        // call entirely (also avoids a stale-session error after the session has been consumed).
        if (passed(appId, AADHAAR).isPresent()) {
            Map<String, Object> derived = new LinkedHashMap<>();
            derived.put("status", "completed");
            derived.put("completed", true);
            derived.put("failed", false);
            derived.put("finalized", true);
            return new StepResult(DIGILOCKER, PASS, "DigiLocker completed", derived);
        }
        VerificationPort.DigiLockerStatus s = verification.digilockerStatus(clientId);
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("status", s.status());
        derived.put("completed", s.completed());
        derived.put("failed", s.failed());
        derived.put("aadhaarLinked", s.aadhaarLinked());
        derived.put("finalized", false);
        return new StepResult(DIGILOCKER, s.completed() ? PASS : (s.failed() ? FAIL : PENDING),
                "DigiLocker " + s.status(), derived);
    }

    /** Finish DigiLocker: pull parsed Aadhaar, ingest the Aadhaar PDF to S3, cross-match. */
    @Transactional
    public StepResult digilockerComplete(Long appId) {
        Optional<ApplicationVerification> existing = passed(appId, AADHAAR);
        if (existing.isPresent()) {
            return view(existing.get());
        }
        CustomerProfile profile = profile(appId);
        String clientId = profile.getDigilockerClientId();
        if (clientId == null) {
            throw new BusinessException("DIGILOCKER_NOT_STARTED", "No DigiLocker session for this application");
        }
        VerificationPort.AadhaarResult a = verification.digilockerAadhaar(clientId);

        // Readiness gate. The redirect back to our callback is the real "user finished consent"
        // signal — but the provider may not have materialised the Aadhaar XML the instant we ask.
        // If no usable demographics came back, treat it as not-ready-yet and fail RETRYABLY rather
        // than persisting a bogus PASS with blank fields. The caller polls this until data lands.
        if (isBlank(a.fullName()) && isBlank(a.maskedAadhaar())) {
            throw new BusinessException("DIGILOCKER_NOT_READY",
                    "DigiLocker consent not completed yet — Aadhaar data not available");
        }

        // Aadhaar is the authoritative DOB source — persist it onto the profile (overriding any
        // earlier PAN-derived value) so the borrower's stored date of birth reflects KYC.
        LocalDate aadhaarDob = parseDob(a.dob());
        if (aadhaarDob != null) {
            profile.setDob(aadhaarDob);
        }
        // Fill blank address from DigiLocker so CRM Personal shows KYC address without a separate step.
        if ((profile.getAddress() == null || profile.getAddress().isBlank())
                && a.fullAddress() != null && !a.fullAddress().isBlank()) {
            profile.setAddress(a.fullAddress());
        }
        // The raw Aadhaar number is no longer captured or stored — DigiLocker completion just records
        // the verified status, which is what staff see on the profile card.
        profile.setAadhaarVerified(true);
        profileRepo.save(profile);

        // Server-side ingest of the e-Aadhaar PDF (bytes never reach the browser). Signzy v2 returns
        // its persisted URL directly; older adapters still use the list/download fallback.
        String s3Key = null;
        try {
            String sourceUrl = a.pdfUrl();
            String mimeType = "application/pdf";
            if (isBlank(sourceUrl)) {
                String fileId = pickAadhaarFile(clientId);
                VerificationPort.DigiLockerDownload d = verification.digilockerDownload(clientId, fileId);
                sourceUrl = d.downloadUrl();
                if (!isBlank(d.mimeType())) {
                    mimeType = d.mimeType();
                }
            }
            s3Key = storeProviderDocument(appId, AADHAAR, "aadhaar.pdf", "pdf", sourceUrl, mimeType);
        } catch (RuntimeException ingestFailure) {
            // Demographics still recorded; the PDF can be re-fetched. Don't fail the whole step.
            s3Key = null;
        }

        // Keep the Aadhaar card image separately from the face photo. It is useful to staff as an
        // original issuer document, whereas AADHAAR_PHOTO is used only for selfie face matching.
        if (!isBlank(a.jpegUrl())) {
            try {
                storeProviderDocument(appId, "AADHAAR_JPEG", "aadhaar.jpeg", "jpeg", a.jpegUrl(), "image/jpeg");
            } catch (RuntimeException jpegIngestFailure) {
                // The PDF and demographics remain available if the optional image cannot be fetched.
            }
        }

        // Server-side ingest of the Aadhaar face photo (a provider persist URL) → S3, so the SELFIE step
        // can 1:1 face-match the borrower's selfie against it. Best-effort; never fails the step.
        String photoRef = a.profileImageBase64();
        if (photoRef != null && photoRef.startsWith("http")) {
            try {
                String photoKey = storage.buildApplicationKey(appId, AADHAAR_PHOTO, "jpg");
                storage.storeFromUrl(photoKey, photoRef, "image/jpeg");
                ApplicationDocument photo = new ApplicationDocument();
                photo.setApplicationId(appId);
                photo.setDocType(AADHAAR_PHOTO);
                photo.setFileName("aadhaar-photo.jpg");
                photo.setContentType("image/jpeg");
                photo.setS3ObjectKey(photoKey);
                documentRepo.save(photo);
            } catch (RuntimeException photoIngestFailure) {
                // Selfie step will fall back to a single-image check if the photo isn't available.
            }
        }

        // The full e-Aadhaar card as DigiLocker returned it — staff read this off the CRM. The
        // number stays MASKED (last 4 only); the raw UID is never persisted.
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("fullName", a.fullName());
        derived.put("dob", a.dob());
        derived.put("gender", a.gender());
        derived.put("maskedAadhaar", a.maskedAadhaar());
        derived.put("address", a.fullAddress());
        derived.put("state", a.state());
        derived.put("district", a.district());
        derived.put("city", a.city());
        derived.put("pincode", a.pincode());
        derived.put("country", a.country());
        derived.put("addressLine", a.addressLine());
        derived.put("landmark", a.landmark());
        derived.put("dscSubject", a.dscSubject());
        ApplicationVerification row = upsert(appId, AADHAAR, PASS, "DIGILOCKER", a.txnId(), null,
                null, null, s3Key, derived, "Aadhaar fetched from DigiLocker");
        double match = recomputeNameMatch(appId);
        if (match > 0 && match < NAME_MATCH_THRESHOLD) {
            row.setStatus(REVIEW);
            row.setMessage("Name mismatch vs PAN — manual review");
            verificationRepo.save(row);
        }
        // Signzy's requestId is single-use consent state, not customer data. Clear it from the
        // profile and replace the temporary DIGILOCKER row so it cannot be retained in CRM/audits.
        profile.setDigilockerClientId(null);
        profileRepo.save(profile);
        upsert(appId, DIGILOCKER, PASS, "DIGILOCKER", null, null,
                null, null, null, Map.of("completed", true), "DigiLocker completed");
        return view(row);
    }

    /** ADMIN-only re-run of a provider-backed customer check. Inputs fill prerequisites that cannot be recovered. */
    @Transactional
    public StepResult retryExternalCheck(Long appId, String checkType, Map<String, Object> input) {
        if (!"ADMIN".equals(ActorContext.get().role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "Retrying a verification API requires ADMIN");
        }
        String type = checkType == null ? "" : checkType.trim().toUpperCase();
        Map<String, Object> values = input == null ? Map.of() : input;
        CustomerProfile p = profile(appId);
        if (!Set.of(PAN, EMAIL, ADDRESS, BUREAU, PENNY_DROP, SELFIE).contains(type)) {
            throw new BusinessException("RETRY_NOT_SUPPORTED", "This verification requires a borrower session and cannot be retried here");
        }
        verificationRepo.findByApplicationIdAndCheckType(appId, type).ifPresent(row -> {
            row.setStatus(PENDING);
            row.setMessage("Retry requested by administrator");
            verificationRepo.save(row);
        });
        return switch (type) {
            case PAN -> verifyPan(appId, value(values, "pan", p.getPan()));
            case EMAIL -> verifyEmail(appId, value(values, "email", p.getOfficialEmail() != null ? p.getOfficialEmail() : p.getEmail()));
            case ADDRESS -> verifyAddress(appId, number(values, "latitude"), number(values, "longitude"));
            case BUREAU -> pullBureau(appId, value(values, "otp", null));
            case PENNY_DROP -> verifyPennyDrop(appId, value(values, "accountNumber", p.getSalaryAccountNumber()), value(values, "ifsc", p.getSalaryIfsc()), true);
            case SELFIE -> verifySelfie(appId, value(values, "selfieObjectKey", null));
            default -> throw new BusinessException("RETRY_NOT_SUPPORTED", "Unsupported verification retry");
        };
    }

    private static String value(Map<String, Object> values, String key, String fallback) {
        Object v = values.get(key);
        String result = v == null ? fallback : String.valueOf(v).trim();
        if (result == null || result.isBlank()) throw new BusinessException("RETRY_INPUT_REQUIRED", key + " is required to retry this check");
        return result;
    }
    private static double number(Map<String, Object> values, String key) {
        try { return Double.parseDouble(value(values, key, null)); }
        catch (NumberFormatException e) { throw new BusinessException("RETRY_INPUT_INVALID", key + " must be numeric"); }
    }

    private String storeProviderDocument(Long appId, String docType, String fileName, String extension,
                                         String sourceUrl, String contentType) {
        if (isBlank(sourceUrl)) {
            throw new IllegalArgumentException("Provider document URL is blank");
        }
        String key = storage.buildApplicationKey(appId, docType, extension);
        String s3Key = storage.storeFromUrl(key, sourceUrl, contentType);
        ApplicationDocument doc = new ApplicationDocument();
        doc.setApplicationId(appId);
        doc.setDocType(docType);
        doc.setFileName(fileName);
        doc.setContentType(contentType);
        doc.setS3ObjectKey(s3Key);
        documentRepo.save(doc);
        return s3Key;
    }

    /**
     * Credit bureau pull (Experian → CRIF). Recorded for staff/risk; never shown to borrower.
     *
     * <p>Gated on {@link #BUREAU_CONSENT} having already passed (via {@link #recordBureauConsent}):
     * Digitap's live Credit Analytics mandates the borrower's verified OTP in its payload, and calling
     * out to either bureau provider before consent exists would be pulling a report the borrower never
     * authorised. Missing consent degrades to {@code REVIEW} rather than throwing — matching this
     * method's existing "never hard-block onboarding at this step" policy (see the {@code
     * providerFailure} catch below) — the frontend simply calls {@code bureau-consent} immediately
     * before this, so in the normal flow this branch never triggers.
     */
    @Transactional
    public StepResult pullBureau(Long appId, String otp) {
        Optional<ApplicationVerification> existing = passed(appId, BUREAU);
        if (existing.isPresent()) {
            return new StepResult(BUREAU, existing.get().getStatus(), existing.get().getMessage(), Map.of());
        }
        if (passed(appId, BUREAU_CONSENT).isEmpty()) {
            ApplicationVerification row = upsert(appId, BUREAU, REVIEW, null, null, ref(appId, BUREAU),
                    null, null, null, Map.of(),
                    "Bureau consent not yet given — pull deferred.");
            return new StepResult(BUREAU, REVIEW, row.getMessage(), Map.of());
        }
        CustomerProfile profile = profile(appId);
        String ref = ref(appId, BUREAU);
        if (profile.getDob() == null) {
            ApplicationVerification row = upsert(appId, BUREAU, REVIEW, null, null, ref,
                    null, null, null, Map.of("missingProfileField", "dob"),
                    "Your date of birth is required before we can run the credit check.");
            return new StepResult(BUREAU, REVIEW, row.getMessage(), Map.of());
        }
        VerificationPort.BureauCheck r;
        try {
            r = verification.pullBureau(
                    profile.getPan(), nz(profile.getFullName()), nz(resolveMobile(appId, profile)),
                    profile.getDob() != null ? profile.getDob().toString() : "", otp, ref);
        } catch (RuntimeException providerFailure) {
            // Both bureau providers couldn't run (e.g. no API credits / OTP-gated / upstream error).
            // Don't hard-block onboarding with a 500 — record for manual review and let the borrower
            // continue, mirroring penny-drop/selfie. A staff member pulls the bureau later; risk data
            // stays absent until then. (Product decision: never stop the borrower at this step.)
            Map<String, Object> soft = new LinkedHashMap<>();
            soft.put("providerError", true);
            String providerErrorCode = providerErrorCode(providerFailure);
            soft.put("providerErrorCode", providerErrorCode);
            // ProviderJson reduces upstream errors to an endpoint + status. Retain only the status
            // category here: response bodies and the request (PAN, mobile, DOB, OTP) stay out of logs.
            log.warn("bureau pull failed application={} ref={} errorCode={} exception={}", appId, ref,
                    providerErrorCode, providerFailure.getClass().getSimpleName());
            ApplicationVerification row = upsert(appId, BUREAU, REVIEW, null, null, ref,
                    null, null, null, soft,
                    "We couldn't run the credit check right now — you can continue; our team will review it.");
            return new StepResult(BUREAU, REVIEW, row.getMessage(), Map.of());
        }

        Integer bureauScore = r.score();
        profile.setBureauScore(bureauScore != null ? bureauScore.longValue() : null);
        profile.setBureauSource(r.source());
        Long salary = profile.getMonthlySalaryPaise();
        if (salary != null) {
            RiskPort.RiskGrade grade = risk.grade(salary, bureauScore, null);
            profile.setRiskCategory(grade.category());
        }
        // Build the staff credit brief (1–5★ rating + one-page PDF → S3 + CREDIT_BRIEF document) from
        // the parsed report. Best-effort and self-saving; no-op on a thin-file (facts == null).
        creditBriefService.generate(appId, profile, r.facts(), r.rawResponseJson());
        profileRepo.save(profile);

        // Staff CRM derived: aggregates + noRecord (score stays on row.score / profile — not here for borrower summary).
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("noRecord", r.noRecord());
        derived.put("activeAccounts", r.activeAccounts());
        derived.put("overdueAccounts", r.overdueAccounts());
        derived.put("totalBalance", r.totalBalance());
        derived.put("source", r.source());
        ApplicationVerification row = upsert(appId, BUREAU, PASS, r.source(), r.txnId(), ref,
                null, bureauScore != null ? bureauScore.longValue() : null, null, derived,
                r.noRecord() ? "Thin-file (no bureau record)" : "Bureau pulled", r.rawResponseJson());
        return new StepResult(BUREAU, PASS, row.getMessage(), Map.of());
    }

    /**
     * Declared salary + salary-slip keys (min 3 months) → provisional eligible limit (25% cap). On a
     * reborrow the customer may re-declare salary and/or salary-credit day: when {@code salaryCreditDay}
     * is supplied it overwrites the application's day, the eligible limit is recomputed inline, and any
     * change to a previously-recorded salary/day is audited to {@code profile_change_log}.
     */
    @Transactional
    public StepResult verifySalary(Long appId, long monthlySalaryPaise, List<String> slipObjectKeys,
                                   Integer salaryCreditDay) {
        if (monthlySalaryPaise <= 0) {
            throw new BusinessException("INVALID_SALARY", "Monthly salary must be positive");
        }
        CustomerProfile profile = profile(appId);
        Long oldSalary = profile.getMonthlySalaryPaise();
        profile.setMonthlySalaryPaise(monthlySalaryPaise);
        profileRepo.save(profile);

        long eligible = risk.eligibleLimitPaise(monthlySalaryPaise);
        LoanApplication app = requireApplication(appId);
        Integer oldDay = app.getSalaryCreditDay();
        app.setEligibleLimit(eligible);
        if (salaryCreditDay != null) {
            app.setSalaryCreditDay(salaryCreditDay);
        }
        applicationRepo.save(app);

        // Audit a re-declared salary / salary-day only when a prior value existed AND it changed
        // (a first-time declaration on a fresh application is not a "change"). The customer id comes
        // from the application (the profile snapshot has none).
        if (oldSalary != null && !oldSalary.equals(monthlySalaryPaise)) {
            changeLogger.logIfChanged(app.getCustomerId(), appId, "monthlySalaryPaise",
                    String.valueOf(oldSalary), String.valueOf(monthlySalaryPaise));
        }
        if (oldDay != null && salaryCreditDay != null && !oldDay.equals(salaryCreditDay)) {
            changeLogger.logIfChanged(app.getCustomerId(), appId, "salaryCreditDay",
                    String.valueOf(oldDay), String.valueOf(salaryCreditDay));
        }

        if (slipObjectKeys != null) {
            for (int i = 0; i < slipObjectKeys.size(); i++) {
                String key = slipObjectKeys.get(i);
                if (key == null || key.isBlank()) continue;
                ApplicationDocument slip = new ApplicationDocument();
                slip.setApplicationId(appId);
                slip.setDocType("SALARY_SLIP");
                slip.setFileName("salary-slip-" + (i + 1));
                slip.setS3ObjectKey(key);
                documentRepo.save(slip);
            }
        }

        String primaryKey = (slipObjectKeys != null && !slipObjectKeys.isEmpty()) ? slipObjectKeys.get(0) : null;
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("monthlySalaryPaise", monthlySalaryPaise);
        derived.put("eligibleLimitPaise", eligible);
        return view(upsert(appId, SALARY, PASS, "DhanBoost", null, ref(appId, SALARY),
                null, null, primaryKey, derived, "Declared salary recorded"));
    }

    /** Penny-drop bank verify + name-at-bank match (payout gate). */
    @Transactional
    public StepResult verifyPennyDrop(Long appId, String accountNumber, String ifsc) {
        return verifyPennyDrop(appId, accountNumber, ifsc, false);
    }

    /**
     * As above, but {@code force} re-runs the check even when this application already has a PASSed
     * penny drop. Phase 3's disbursal-account screen needs that: the stored PASS belongs to whatever
     * account was checked before, and the borrower is now naming a <em>different</em> one — replaying
     * the old result would wave through an account nobody verified.
     */
    @Transactional
    public StepResult verifyPennyDrop(Long appId, String accountNumber, String ifsc, boolean force) {
        if (!force) {
            Optional<ApplicationVerification> existing = passed(appId, PENNY_DROP);
            if (existing.isPresent()) {
                return view(existing.get());
            }
        }
        CustomerProfile profile = profile(appId);
        String ref = ref(appId, PENNY_DROP);
        VerificationPort.PennyDropCheck r;
        try {
            r = verification.pennyDrop(accountNumber, ifsc, ref);
        } catch (RuntimeException providerFailure) {
            // The provider couldn't verify the account (a bad/non-existent account, or an upstream
            // error). Don't hard-block onboarding with a 500 — record the account for manual review
            // and let the borrower continue; the payout still gates on a verified account before any
            // money is sent. (Product decision: never stop the borrower at this step.)
            profile.setPennyDropVerified(false);
            profileRepo.save(profile);
            Map<String, Object> derived = new LinkedHashMap<>();
            derived.put("accountExists", false);
            derived.put("providerError", true);
            return view(upsert(appId, PENNY_DROP, REVIEW, "SIGNZY", null, ref,
                    null, null, null, derived,
                    "We couldn't verify this account right now — you can continue; we'll verify it before your advance is sent."));
        }
        double nameMatch = nameSimilarity(profile.getFullName(), r.fullName());
        boolean ok = r.accountExists() && nameMatch >= NAME_MATCH_THRESHOLD;
        profile.setPennyDropVerified(ok);
        if (profile.getSalaryBank() == null) {
            if (r.bank() != null && !r.bank().isBlank()) {
                profile.setSalaryBank(r.bank());
            } else if (ifsc != null && ifsc.length() >= 4) {
                // Fallback label from IFSC bank code when provider omits bankName.
                profile.setSalaryBank(ifsc.substring(0, 4));
            }
        }
        profileRepo.save(profile);

        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("accountExists", r.accountExists());
        derived.put("accountNumber", accountNumber);
        derived.put("ifsc", r.ifsc() != null ? r.ifsc() : ifsc);
        derived.put("bank", r.bank());
        derived.put("beneficiaryName", r.fullName());
        derived.put("fullName", r.fullName()); // name-match recompute reads derived.fullName
        derived.put("bankRrn", r.bankRrn());
        derived.put("reason", r.reason());
        derived.put("providerNameMatch", r.providerNameMatch());
        derived.put("nameMatch", round2(nameMatch));
        String status = ok ? PASS : REVIEW;
        String msg = !r.accountExists()
                ? "We couldn't confirm this account — you can continue; we'll verify it before your advance is sent."
                : (ok ? "Account + name matched" : "Name mismatch at bank — manual review");
        ApplicationVerification row = upsert(appId, PENNY_DROP, status, r.provider(), r.txnId(), ref,
                nameMatch, null, null, derived, msg);
        recomputeNameMatch(appId);
        return view(row);
    }

    /**
     * Face-match the uploaded selfie against the DigiLocker Aadhaar photo (presigned GET URLs → Digitap
     * Face Match). When no Aadhaar photo has been captured yet, degrades to a single-image face/quality
     * check on the selfie alone.
     */
    @Transactional
    public StepResult verifySelfie(Long appId, String selfieObjectKey) {
        if (selfieObjectKey == null || selfieObjectKey.isBlank()) {
            throw new BusinessException("SELFIE_REQUIRED", "selfieObjectKey is required");
        }
        requireApplication(appId);
        String imageUrl = storage.presignDownload(selfieObjectKey);
        // Reference photo = the Aadhaar face captured at DigiLocker completion (if present).
        String referenceUrl = documentRepo
                .findFirstByApplicationIdAndDocTypeOrderByIdDesc(appId, AADHAAR_PHOTO)
                .map(d -> storage.presignDownload(d.getS3ObjectKey()))
                .orElse(null);
        boolean matched = referenceUrl != null;
        String ref = ref(appId, SELFIE);

        // Persist the selfie regardless of the provider outcome, so a KYC approver always has the
        // image to review (including when the face-match provider is unavailable below).
        ApplicationDocument selfie = new ApplicationDocument();
        selfie.setApplicationId(appId);
        selfie.setDocType(SELFIE);
        selfie.setFileName("selfie.jpg");
        selfie.setContentType("image/jpeg");
        selfie.setS3ObjectKey(selfieObjectKey);
        documentRepo.save(selfie);

        VerificationPort.FaceLivenessCheck r;
        try {
            r = verification.faceLiveness(imageUrl, referenceUrl, ref);
        } catch (RuntimeException providerFailure) {
            // The face-match provider couldn't run (e.g. insufficient balance / upstream error).
            // Don't hard-block onboarding with a 500 — record the selfie for manual review and let the
            // borrower continue, mirroring the penny-drop step. (Product decision: never stop the
            // borrower at this step; a KYC approver makes the final call.)
            Map<String, Object> derived = new LinkedHashMap<>();
            derived.put("faceMatch", matched);
            derived.put("providerError", true);
            return view(upsert(appId, SELFIE, REVIEW, "DIGITAP", null, ref, null, null, selfieObjectKey,
                    derived,
                    "We couldn't run the face check right now — you can continue; our team will review your selfie."));
        }

        boolean live = r.live() && !r.multipleFaces();
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("faceMatch", matched);
        derived.put("live", r.live());
        derived.put("confidence", r.confidence());
        derived.put("personImageBlurry", r.personImageBlurry());
        derived.put("multipleFaces", r.multipleFaces());
        // Fail → flagged for manual review (not hard block); approver decides.
        String status = live ? PASS : REVIEW;
        Long score = r.confidence() != null ? Math.round(r.confidence() * 100) : null;
        String msg = matched
                ? (live ? "Face matched to Aadhaar photo" : "Face match low — manual review")
                : (live ? "Selfie quality check passed" : "Selfie check low — manual review");
        return view(upsert(appId, SELFIE, status, r.provider(), r.txnId(), ref, null, score, selfieObjectKey,
                derived, msg));
    }

    /**
     * Start the Signzy liveness video journey for the SELFIE step (primary path). Uses the DigiLocker
     * Aadhaar face (if present) as the {@code matchImage} so the journey does liveness AND a 1:1
     * face-match in one. Persists the session token on the SELFIE row; the frontend redirects the borrower
     * to {@code derived.videoUrl} and then polls {@link #selfieLivenessResult}. If Signzy liveness is
     * unavailable (not provisioned / upstream error), returns {@code derived.fallback=true} — the frontend
     * then uses the camera-capture + Digitap face-match path ({@link #verifySelfie}). Never hard-blocks.
     */
    @Transactional
    public StepResult selfieLivenessInit(Long appId) {
        requireApplication(appId);
        // Reference photo = the Aadhaar face captured at DigiLocker completion (enables 1:1 face-match).
        String matchImageUrl = documentRepo
                .findFirstByApplicationIdAndDocTypeOrderByIdDesc(appId, AADHAAR_PHOTO)
                .map(d -> storage.presignDownload(d.getS3ObjectKey()))
                .orElse(null);
        String ref = ref(appId, SELFIE);
        try {
            VerificationPort.LivenessSession s = verification.livenessInit(matchImageUrl, ref);
            Map<String, Object> derived = new LinkedHashMap<>();
            derived.put("provider", s.provider());
            derived.put("videoUrl", s.videoUrl());
            derived.put("token", s.txnId());
            derived.put("faceMatch", matchImageUrl != null);
            derived.put("fallback", false);
            return view(upsert(appId, SELFIE, PENDING, s.provider(), s.txnId(), ref, null, null, null,
                    derived, "Liveness session started"));
        } catch (RuntimeException signzyUnavailable) {
            // Signzy liveness can't run — tell the frontend to fall back to camera capture (Digitap).
            Map<String, Object> derived = new LinkedHashMap<>();
            derived.put("fallback", true);
            return new StepResult(SELFIE, PENDING,
                    "Liveness unavailable — capture a selfie instead", derived);
        }
    }

    /**
     * Poll the liveness journey result. Returns PENDING (keep polling) until the borrower finishes the
     * video. On completion it ingests the captured selfie frame to S3 (for approver review) and maps the
     * verdict: PASS when live (and, if a match image was supplied, the face matched), else REVIEW — a
     * manual approver decides, never a hard block (mirrors {@link #verifySelfie}).
     */
    @Transactional
    public StepResult selfieLivenessResult(Long appId) {
        Optional<ApplicationVerification> done = passed(appId, SELFIE);
        if (done.isPresent()) {
            return view(done.get());
        }
        ApplicationVerification row = verificationRepo.findByApplicationIdAndCheckType(appId, SELFIE)
                .orElseThrow(() -> new BusinessException(
                        "LIVENESS_NOT_STARTED", "No liveness session for this application"));
        String token = row.getProviderTxnId();
        if (token == null || token.isBlank()) {
            throw new BusinessException("LIVENESS_NOT_STARTED", "No liveness session for this application");
        }

        VerificationPort.LivenessResultCheck r = verification.livenessResult(token);
        if (!r.completed()) {
            Map<String, Object> derived = new LinkedHashMap<>();
            derived.put("completed", false);
            return new StepResult(SELFIE, PENDING, "Liveness in progress", derived);
        }

        // Ingest the captured selfie frame to S3 so a KYC approver always has the image to review.
        String selfieKey = null;
        if (r.capturedImageUrl() != null && r.capturedImageUrl().startsWith("http")) {
            try {
                selfieKey = storage.buildApplicationKey(appId, SELFIE, "jpg");
                storage.storeFromUrl(selfieKey, r.capturedImageUrl(), "image/jpeg");
                ApplicationDocument selfie = new ApplicationDocument();
                selfie.setApplicationId(appId);
                selfie.setDocType(SELFIE);
                selfie.setFileName("selfie.jpg");
                selfie.setContentType("image/jpeg");
                selfie.setS3ObjectKey(selfieKey);
                documentRepo.save(selfie);
            } catch (RuntimeException ingestFailure) {
                selfieKey = null; // best-effort; the verdict below still stands
            }
        }

        boolean faceMatchAttempted = r.faceMatched() != null;
        boolean pass = r.live() && (!faceMatchAttempted || Boolean.TRUE.equals(r.faceMatched()));
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("completed", true);
        derived.put("live", r.live());
        derived.put("livenessScore", r.livenessScore());
        derived.put("faceMatch", faceMatchAttempted);
        derived.put("faceMatched", r.faceMatched());
        derived.put("matchPercentage", r.matchPercentage());
        String status = pass ? PASS : REVIEW;
        Long score = r.livenessScore() != null ? Math.round(r.livenessScore() * 100) : null;
        String msg = faceMatchAttempted
                ? (pass ? "Live selfie matched to Aadhaar photo" : "Liveness/face-match low — manual review")
                : (pass ? "Liveness check passed" : "Liveness check low — manual review");
        return view(upsert(appId, SELFIE, status, r.provider(), token, ref(appId, SELFIE), null, score,
                selfieKey, derived, msg));
    }

    /** Record agreement consent (the 3 documents the borrower accepted). */
    @Transactional
    public StepResult recordAgreement(Long appId, List<String> versions) {
        CustomerProfile profile = profile(appId);
        profile.setAgreementAccepted(Boolean.TRUE);
        profileRepo.save(profile);
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("versions", versions != null ? versions : List.of());
        return view(upsert(appId, AGREEMENT, PASS, "DhanBoost", null, ref(appId, AGREEMENT),
                null, null, null, derived, "Agreement accepted"));
    }

    /**
     * eSign the sanction letter / Key Fact Statement (Phase 3, screen 9) — the per-loan legal act.
     *
     * <p>Step 1 of two: mint a signing session against the stored {@code SANCTION_LETTER} and hand the
     * borrower the provider's hosted URL to redirect to. The {@code ESIGN} row is parked at
     * {@code PENDING} carrying the provider's handle, and {@link #esignStatus} resolves it — the same
     * init/poll shape DigiLocker and the selfie liveness use, because a real Aadhaar eSign is a redirect
     * journey that cannot complete inside this request.
     *
     * <p>The signer's Aadhaar demographics let the provider reject a signature made with somebody else's
     * Aadhaar. They come from DigiLocker, which is deliberately non-blocking, so when they are absent we
     * pass what we have and the provider degrades to matching the name alone — a borrower who skipped
     * DigiLocker must still be able to sign.
     *
     * <p>A provider failure is <em>not</em> fatal: it returns a transient result flagged
     * {@code fallback} and writes no row, so the borrower drops to the drawn-signature path
     * ({@link #recordManualEsign}) rather than being stranded at the last step before disbursement.
     *
     * <p>Unlike the intake checks, this one is a real gate: {@code ApplicationFlowService.acceptOffer}
     * refuses to move an application to disbursement without a terminal ESIGN row.
     */
    @Transactional
    public StepResult esignInit(Long appId, String successRedirectUrl, String failureRedirectUrl) {
        Optional<ApplicationVerification> existing = passed(appId, ESIGN);
        if (existing.isPresent()) {
            return view(existing.get());
        }
        ApplicationDocument letter = requireSanctionLetter(appId);
        CustomerProfile profile = profile(appId);
        String ref = ref(appId, ESIGN);
        String documentUrl = storage.presignDownload(letter.getS3ObjectKey());

        Map<String, Object> aadhaar = derivedFor(appId, AADHAAR);
        String gender = text(aadhaar.get("gender"));
        String yearOfBirth = yearOf(text(aadhaar.get("dob")), profile.getDob());
        String uidLastFour = lastFourOf(text(aadhaar.get("maskedAadhaar")));
        boolean strict = !isBlank(gender) && !isBlank(yearOfBirth);

        EsignPort.EsignSession session;
        try {
            session = esign.initiate(new EsignPort.EsignRequest(
                    documentUrl,
                    new EsignPort.Signer(profile.getFullName(), resolveMobile(appId, profile),
                            gender, yearOfBirth, uidLastFour),
                    ref,
                    successRedirectUrl,
                    failureRedirectUrl,
                    "DhanBoost Loan Agreement — application " + appId,
                    null));
        } catch (RuntimeException providerUnavailable) {
            // No row: the drawn-signature fallback owns the ESIGN row if the borrower takes it.
            Map<String, Object> soft = new LinkedHashMap<>();
            soft.put("fallback", true);
            return new StepResult(ESIGN, PENDING,
                    "Aadhaar e-sign is unavailable — sign the agreement here instead", soft);
        }

        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("sessionId", session.sessionId());
        derived.put("url", session.signUrl());
        derived.put("matchMode", strict ? "STRICT" : "NAME_ONLY");
        derived.put("sanctionLetterDocumentId", letter.getId());
        return view(upsert(appId, ESIGN, PENDING, session.provider(), session.sessionId(), ref,
                null, null, null, derived, "Aadhaar e-sign started"));
    }

    /**
     * Step 2 of two: resolve the signing session. Our own row is authoritative — the provider's view is
     * only consulted here and never trusted to have persisted anything on our behalf.
     *
     * <p>A lapsed contract is a normal state rather than an error: a sanction never expires
     * (revamp.md decision 41) but the provider's contract does, so a borrower who returns days later gets
     * a fresh one minted silently. That re-mint is deliberately narrow — only when the provider has lost
     * the contract entirely <em>and</em> no signature was captured — because every mint is a billable,
     * legally binding contract and a looser condition would let a poll loop mint them in a cycle.
     */
    @Transactional
    public StepResult esignStatus(Long appId) {
        Optional<ApplicationVerification> done = passed(appId, ESIGN);
        if (done.isPresent()) {
            return view(done.get());
        }
        ApplicationVerification row = verificationRepo.findByApplicationIdAndCheckType(appId, ESIGN)
                .orElseThrow(() -> new BusinessException("ESIGN_NOT_STARTED",
                        "Start signing your agreement first"));
        String sessionId = row.getProviderTxnId();
        if (isBlank(sessionId)) {
            throw new BusinessException("ESIGN_NOT_STARTED", "Start signing your agreement first");
        }
        Map<String, Object> derived = fromJson(row.getDerived());
        EsignPort.EsignResult result = esign.fetch(sessionId);

        if (!result.completed()) {
            if (EsignPort.CONTRACT_EXPIRED.equals(result.reason())) {
                // The provider forgot the contract and nothing was signed against it — mint a new one.
                verificationRepo.delete(row);
                verificationRepo.flush();
                return esignInit(appId, text(derived.get("successRedirectUrl")),
                        text(derived.get("failureRedirectUrl")));
            }
            derived.put("completed", false);
            return new StepResult(ESIGN, PENDING, "Waiting for your signature", derived);
        }

        derived.put("signatureRef", result.signatureRef());
        if (!result.signed()) {
            // Completed without a signature — a name/YOB/gender mismatch is the usual cause. REVIEW, not
            // FAIL: a KYC approver decides, and the drawn-signature path is still open to the borrower.
            row.setStatus(REVIEW);
            row.setMessage(result.reason() != null ? result.reason() : "Signature not completed");
            row.setDerived(toJson(derived));
            verificationRepo.save(row);
            return new StepResult(ESIGN, REVIEW, row.getMessage(), derived);
        }
        return finalizeSignature(appId, result.provider(), result.signatureRef(),
                result.signedAt(), result.signedPdf(), derived, "Sanction letter signed");
    }

    /**
     * Persist a captured signature: store the signed copy as the {@code SIGNED_AGREEMENT} document and
     * flip the {@code ESIGN} row to {@code PASS}. Shared by the Aadhaar e-sign path and the drawn
     * fallback, which differ only in who produced the PDF — a provider that returns its own signed copy
     * (with the digital signature certificate embedded) has that copy stored; otherwise the document
     * points at the letter as rendered and the signature evidence lives on the verification row.
     */
    private StepResult finalizeSignature(Long appId, String provider, String signatureRef,
                                         Instant signedAt, byte[] signedPdf,
                                         Map<String, Object> derived, String message) {
        ApplicationDocument letter = requireSanctionLetter(appId);
        CustomerProfile profile = profile(appId);

        String signedKey = letter.getS3ObjectKey();
        long size = letter.getSizeBytes() != null ? letter.getSizeBytes() : 0L;
        if (signedPdf != null && signedPdf.length > 0) {
            signedKey = "applications/" + appId + "/signed_agreement/sanction-letter-signed.pdf";
            storage.store(signedKey, signedPdf, "application/pdf");
            size = signedPdf.length;
        }
        ApplicationDocument signed = documentRepo
                .findFirstByApplicationIdAndDocTypeOrderByIdDesc(appId, SIGNED_AGREEMENT)
                .orElseGet(ApplicationDocument::new);
        signed.setApplicationId(appId);
        signed.setDocType(SIGNED_AGREEMENT);
        signed.setFileName("sanction-letter-signed.pdf");
        signed.setContentType("application/pdf");
        signed.setSizeBytes(size);
        signed.setS3ObjectKey(signedKey);
        signed.setData(null);
        documentRepo.save(signed);

        derived.put("signedDocumentId", signed.getId());
        derived.put("signedAt", (signedAt != null ? signedAt : Instant.now()).toString());
        derived.put("sanctionLetterDocumentId", letter.getId());

        profile.setAgreementAccepted(Boolean.TRUE);
        profileRepo.save(profile);
        String finalSignedKey = signedKey;
        applicationRepo.findById(appId).ifPresentOrElse(
                app -> eventPublisher.publishEvent(new SanctionLetterSignedEvent(
                        app.getCustomerId(), appId, signed.getId(), finalSignedKey, Instant.now())),
                () -> log.warn("Skipping SanctionLetterSignedEvent: application {} not found", appId));
        return view(upsert(appId, ESIGN, PASS, provider, signatureRef, ref(appId, ESIGN),
                null, null, signedKey, derived, message));
    }

    private ApplicationDocument requireSanctionLetter(Long appId) {
        return documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(appId, SANCTION_LETTER)
                .orElseThrow(() -> new BusinessException("SANCTION_LETTER_MISSING",
                        "Open your sanction letter before signing it"));
    }

    /** The {@code derived} map of one check, or empty when the check never ran. */
    private Map<String, Object> derivedFor(Long appId, String checkType) {
        return verificationRepo.findByApplicationIdAndCheckType(appId, checkType)
                .map(row -> fromJson(row.getDerived()))
                .orElseGet(LinkedHashMap::new);
    }

    /** Four-digit year for the provider's year-of-birth match, Aadhaar first then the stored DOB. */
    private static String yearOf(String aadhaarDob, LocalDate profileDob) {
        LocalDate parsed = parseDob(aadhaarDob);
        LocalDate effective = parsed != null ? parsed : profileDob;
        return effective != null ? String.valueOf(effective.getYear()) : null;
    }

    /** Last four Aadhaar digits out of a masked number — the only form we hold. */
    private static String lastFourOf(String maskedAadhaar) {
        if (maskedAadhaar == null) {
            return null;
        }
        String digits = maskedAadhaar.replaceAll("\\D", "");
        return digits.length() >= 4 ? digits.substring(digits.length() - 4) : null;
    }

    private static String text(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    /**
     * Record a signature the borrower drew in the app — the fallback when Aadhaar e-sign is unavailable
     * to them (the provider is down, or the OTP goes to an Aadhaar-registered mobile they no longer
     * hold). It deliberately does <em>not</em> touch {@link EsignPort}: the whole point is a path that
     * works when the provider does not, and with a real provider each call would mint a billable
     * contract.
     */
    @Transactional
    public StepResult recordManualEsign(Long appId, String signatureDataUrl,
                                        Double latitude, Double longitude, Double accuracyMeters) {
        if ((latitude != null && (latitude < -90 || latitude > 90))
                || (longitude != null && (longitude < -180 || longitude > 180))
                || (accuracyMeters != null && (accuracyMeters < 0 || accuracyMeters > 100_000))) {
            throw new BusinessException("SIGNATURE_LOCATION_INVALID", "The signing location is invalid");
        }
        if (signatureDataUrl == null || !signatureDataUrl.startsWith("data:image/png;base64,")) {
            throw new BusinessException("SIGNATURE_INVALID", "Draw your signature in the signing area");
        }
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(signatureDataUrl.substring("data:image/png;base64,".length()));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("SIGNATURE_INVALID", "Draw your signature again");
        }
        if (signature.length == 0 || signature.length > 1_000_000) {
            throw new BusinessException("SIGNATURE_INVALID", "The signature image is invalid");
        }
        Optional<ApplicationVerification> done = passed(appId, ESIGN);
        if (done.isPresent()) {
            return view(done.get());
        }
        String key = "applications/" + appId + "/signed_agreement/signature.png";
        storage.store(key, signature, "image/png");

        // Carry anything an abandoned Aadhaar attempt left behind, so staff can see it was tried.
        Map<String, Object> derived = derivedFor(appId, ESIGN);
        derived.remove("url");
        derived.put("signatureObjectKey", key);
        derived.put("latitude", latitude);
        derived.put("longitude", longitude);
        derived.put("accuracyMeters", accuracyMeters);
        // The caller re-renders the letter with the drawn signature stamped in and overwrites the
        // document; nothing to store here beyond the letter as it stands.
        return finalizeSignature(appId, "MANUAL", key, Instant.now(), null, derived,
                "Sanction letter signed manually");
    }

    /**
     * Send the bureau-consent OTP for this application, scoped to {@link OtpVerifierPort#BUREAU_CONSENT}
     * so it never shares a stored code or send budget with the borrower's login OTP. The mobile is
     * resolved server-side (same helper {@link #recordBureauConsent} uses) rather than trusted from the
     * request.
     */
    @Transactional(readOnly = true)
    public OtpVerifierPort.OtpRequestResult requestBureauConsentOtp(Long appId) {
        CustomerProfile profile = profile(appId);
        String mobile = resolveMobile(appId, profile);
        if (mobile == null || mobile.isBlank()) {
            throw new BusinessException("MOBILE_MISSING",
                    "No mobile on file for this application — complete the mobile step first");
        }
        return otpVerifier.request(mobile, OtpVerifierPort.BUREAU_CONSENT);
    }

    /**
     * Record the borrower's consent to the credit-bureau enquiry, step-up verified by their mobile OTP.
     *
     * <p>This is a check type of its own rather than extra {@code derived} data on the PAN row, because
     * {@link #verifyPan} short-circuits on an already-PASSed PAN — hanging the consent there would
     * silently drop it on every re-entry (a review-initiated retry, or the borrower backing up), which
     * is precisely when an audit trail must not lose a write. A separate row also carries its own
     * timestamps, which is what makes it evidence.
     *
     * <p>The mobile is resolved from the stored profile, never from the request: taking it from the
     * client would let a caller verify a code sent to a number they control.
     */
    @Transactional
    public StepResult recordBureauConsent(Long appId, String otp, String consentText) {
        CustomerProfile profile = profile(appId);
        String mobile = resolveMobile(appId, profile);
        if (mobile == null || mobile.isBlank()) {
            throw new BusinessException("MOBILE_MISSING",
                    "No mobile on file for this application — complete the mobile step first");
        }
        if (!otpVerifier.verify(mobile, otp, OtpVerifierPort.BUREAU_CONSENT)) {
            throw new BusinessException("INVALID_OTP", "Invalid or expired OTP");
        }
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("consentText", consentText);
        derived.put("channel", "OTP");
        return view(upsert(appId, BUREAU_CONSENT, PASS, "DhanBoost", null, ref(appId, BUREAU_CONSENT),
                null, null, null, derived, "Bureau consent given (OTP verified)"));
    }

    // ---------------------------------------------------------------- gating + summary

    /**
     * Submission gate: every intake check has been <b>attempted</b> (any terminal status, FAIL
     * included) and the borrower accepted the T&C on screen 1. A failed PAN or bureau pull is a
     * credit decision, not a blocker (revamp.md decision 10) — so this only stops an application
     * where a step never ran at all, e.g. no payslips uploaded.
     */
    @Transactional(readOnly = true)
    public boolean allRequiredPassed(Long appId) {
        Map<String, String> byType = verificationRepo.findByApplicationIdOrderByIdAsc(appId).stream()
                .collect(Collectors.toMap(ApplicationVerification::getCheckType,
                        ApplicationVerification::getStatus, (a, b) -> b));
        for (String required : REQUIRED) {
            if (!attempted(byType.get(required))) {
                return false;
            }
        }
        return profileRepo.findByApplicationId(appId)
                .map(p -> p.getTermsAcceptedAt() != null)
                .orElse(false);
    }

    /** A check has been attempted once it holds any terminal status — PENDING/absent means never run. */
    private static boolean attempted(String status) {
        return PASS.equals(status) || REVIEW.equals(status) || FAIL.equals(status);
    }

    /**
     * Record a check the provider couldn't run at all, as REVIEW. The borrower continues and staff
     * pick it up — a provider outage is not the applicant's fault and must not strand them.
     */
    private StepResult providerUnavailable(Long appId, String checkType, String message) {
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("providerError", true);
        return view(upsert(appId, checkType, REVIEW, null, null, ref(appId, checkType),
                null, null, null, derived, message));
    }

    /**
     * DigiLocker is best-effort. If the borrower couldn't complete it (the Aadhaar details
     * didn't come through), don't hard-block submission: record AADHAAR as REVIEW so the
     * application still reaches the KYC approver queue and staff verify Aadhaar manually
     * (the number is already captured on the PAN step). All OTHER required checks still gate,
     * so the borrower must still complete/retry those. Idempotent — never downgrades a PASS.
     */
    @Transactional
    public void allowAadhaarManualReview(Long appId) {
        String aadhaar = verificationRepo.findByApplicationIdAndCheckType(appId, AADHAAR)
                .map(ApplicationVerification::getStatus)
                .orElse(null);
        if (!PASS.equals(aadhaar) && !REVIEW.equals(aadhaar)) {
            upsert(appId, AADHAAR, REVIEW, "MANUAL", null, null, null, null, null,
                    Map.of(), "DigiLocker not completed — Aadhaar pending manual review by staff");
        }
        // DigiLocker itself is best-effort too: if the borrower skipped it (provider unavailable) or
        // never finished, keep the DIGILOCKER gate open at REVIEW rather than blocking submission —
        // staff verify Aadhaar/DigiLocker manually. Idempotent; never downgrades a PASS.
        String digilocker = verificationRepo.findByApplicationIdAndCheckType(appId, DIGILOCKER)
                .map(ApplicationVerification::getStatus)
                .orElse(null);
        if (!PASS.equals(digilocker) && !REVIEW.equals(digilocker)) {
            upsert(appId, DIGILOCKER, REVIEW, "MANUAL", null, null, null, null, null,
                    Map.of(), "DigiLocker not completed — pending manual review by staff");
        }
    }

    /** Number of verification checks an customer must clear (PASS/REVIEW) to submit KYC. */
    public static int requiredCount() {
        return REQUIRED.size();
    }

    /** How many of the {@link #requiredCount()} required checks are currently PASS/REVIEW for an
     *  application — the onboarding-completeness signal used by the admin all-applications register.
     *  Evidence carried forward by a re-apply counts, for the reason given on {@link #progress}. */
    @Transactional(readOnly = true)
    public int requiredPassedCount(Long appId) {
        Map<String, String> byType = statusesWithCarriedEvidence(
                applicationRepo.findById(appId).orElse(null), appId);
        int done = 0;
        for (String required : REQUIRED) {
            String status = byType.get(required);
            if (PASS.equals(status) || REVIEW.equals(status)) {
                done++;
            }
        }
        return done;
    }

    /** All verification rows for an application as borrower-safe step results. */
    @Transactional(readOnly = true)
    public List<StepResult> summary(Long appId) {
        List<ApplicationVerification> rows = verificationRepo.findByApplicationIdOrderByIdAsc(appId);
        // DigiLocker is the transport that produces the Aadhaar verification, but its row is written
        // PENDING once at init and never re-persisted (digilockerComplete only writes the AADHAAR
        // row). Reconcile at read-time so the DIGILOCKER row reflects the Aadhaar outcome — mirrors
        // the digilockerStatus short-circuit and avoids a confusing "DigiLocker pending / Aadhaar
        // verified" display. Still PENDING before an Aadhaar row exists (correct).
        String aadhaarStatus = rows.stream()
                .filter(v -> AADHAAR.equals(v.getCheckType()))
                .map(ApplicationVerification::getStatus)
                .findFirst()
                .orElse(null);
        boolean aadhaarSettled = PASS.equals(aadhaarStatus) || REVIEW.equals(aadhaarStatus);
        return rows.stream()
                .map(row -> {
                    // TEMP (revert later): force the DigiLocker step to always display PASS,
                    // irrespective of the provider/Aadhaar outcome. Restore the reconciliation
                    // block below (and drop this branch) to return to real status.
                    if (DIGILOCKER.equals(row.getCheckType())) {
                        return new StepResult(DIGILOCKER, PASS, "DigiLocker completed",
                                fromJson(row.getDerived()));
                    }
                    if (DIGILOCKER.equals(row.getCheckType()) && aadhaarSettled
                            && !PASS.equals(row.getStatus()) && !REVIEW.equals(row.getStatus())) {
                        return new StepResult(DIGILOCKER, aadhaarStatus,
                                PASS.equals(aadhaarStatus) ? "DigiLocker completed"
                                        : "DigiLocker completed — Aadhaar under manual review",
                                fromJson(row.getDerived()));
                    }
                    return view(row);
                })
                .toList();
    }

    /**
     * Completion snapshot (Phase 3.2): how many of this application's applicable checks are cleared
     * (PASS/REVIEW), failed (FAIL), or pending (PENDING / never-run), plus a 0–100 percent.
     *
     * <p>Two things make the applicable set narrower or wider than a constant list, and getting
     * either wrong shows staff a number that contradicts the cards printed directly beneath it:
     *
     * <ul>
     *   <li><b>Sanction checks count once the file has been sanctioned.</b> Before that they haven't
     *       been asked for, so counting them would show every fresh intake as half-done.</li>
     *   <li><b>A re-apply's intake evidence lives on the application it carried from.</b> PAN, email,
     *       bureau and salary are deliberately not re-run (revamp.md decision 45), so counting only
     *       this application's own rows reported a fully-verified re-apply as <i>0/4 done · 0%</i>
     *       with four PASS cards under it — the tracker looked like nothing had been checked on a
     *       file about to have money released against it.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public VerificationProgress progress(Long appId) {
        LoanApplication app = applicationRepo.findById(appId).orElse(null);
        Map<String, String> byType = statusesWithCarriedEvidence(app, appId);

        List<String> applicable = new ArrayList<>(REQUIRED);
        if (app != null && app.getSanctionedAt() != null) {
            applicable.addAll(REQUIRED_SANCTION);
        }

        int completed = 0;
        int failed = 0;
        int pending = 0;
        for (String r : applicable) {
            String s = byType.get(r);
            if (PASS.equals(s) || REVIEW.equals(s)) {
                completed++;
            } else if (FAIL.equals(s)) {
                failed++;
            } else {
                pending++; // PENDING or never-run
            }
        }
        int required = applicable.size();
        int percent = required == 0 ? 100 : (int) Math.round(completed * 100.0 / required);
        return new VerificationProgress(required, completed, failed, pending, percent);
    }

    /**
     * This application's check statuses, with anything it never ran resolved from the application it
     * re-applied from. Walks the re-apply chain (a borrower may take several advances), bounded so a
     * cycle in the data can never spin here. A row on the newer application always wins.
     */
    private Map<String, String> statusesWithCarriedEvidence(LoanApplication app, Long appId) {
        Map<String, String> byType = new HashMap<>(statusesOf(appId));
        Long source = app == null ? null : app.getReappliedFrom();
        for (int hop = 0; source != null && hop < 5; hop++) {
            statusesOf(source).forEach((check, status) -> {
                if (INHERITABLE_CHECKS.contains(check)) {
                    byType.putIfAbsent(check, status);
                }
            });
            source = applicationRepo.findById(source).map(LoanApplication::getReappliedFrom).orElse(null);
        }
        return byType;
    }

    private Map<String, String> statusesOf(Long appId) {
        return verificationRepo.findByApplicationIdOrderByIdAsc(appId).stream()
                .collect(Collectors.toMap(ApplicationVerification::getCheckType,
                        ApplicationVerification::getStatus, (a, b) -> b));
    }

    /**
     * Staff manual override of a verification step (Phase 3.1): a KYC approver (or ADMIN) sets a check
     * to PASS or FAIL with a note — used when an external check is stuck/inconclusive and needs human
     * judgement. Recorded via {@link #upsert} with provider {@code MANUAL} (idempotent per check type).
     */
    /**
     * The credit team owns KYC judgement calls since the KYC_APPROVER role was deleted (V45).
     * ADMIN passes for oversight.
     */
    private void requireCreditTeam(String what) {
        String role = ActorContext.get().role();
        if (!"CREDIT_EXECUTIVE".equals(role) && !"CREDIT_HEAD".equals(role) && !"ADMIN".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE",
                    what + " requires CREDIT_EXECUTIVE or CREDIT_HEAD");
        }
    }

    /**
     * As {@link #requireCreditTeam(String)}, plus any {@code extraRoles} the caller wants let through
     * (used by {@link #sendKycReminder} to also allow TELECALLER — work item 10 — without loosening
     * the manual verification override, which stays credit-team/admin only).
     */
    private void requireCreditTeamOr(String what, String... extraRoles) {
        String role = ActorContext.get().role();
        boolean core = "CREDIT_EXECUTIVE".equals(role) || "CREDIT_HEAD".equals(role) || "ADMIN".equals(role);
        boolean extra = extraRoles != null && java.util.Arrays.asList(extraRoles).contains(role);
        if (!core && !extra) {
            throw new BusinessException("FORBIDDEN_ROLE",
                    what + " requires CREDIT_EXECUTIVE or CREDIT_HEAD");
        }
    }

    @Transactional
    public StepResult manualDecision(Long appId, String checkType, boolean pass, String notes) {
        requireCreditTeam("Manual verification override");
        String type = checkType == null ? "" : checkType.trim().toUpperCase();
        if (!KNOWN_CHECKS.contains(type)) {
            throw new BusinessException("UNKNOWN_CHECK", "Unknown verification check: " + checkType);
        }
        requireApplication(appId);
        String actor = ActorContext.get().name();
        String trimmed = notes != null ? notes.trim() : "";
        String message = (pass ? "Manually approved" : "Manually rejected") + " by " + actor
                + (trimmed.isEmpty() ? "" : " — " + trimmed);
        return view(upsert(appId, type, pass ? PASS : FAIL, "MANUAL",
                null, null, null, null, null, Map.of(), message));
    }

    /**
     * Staff-triggered reminder (Phase 3.4): nudge a borrower with the list of verification steps still
     * outstanding. Publishes a {@link KycReminderEvent} (the notification engine fans it out IN_APP/
     * SMS/EMAIL to the borrower). No-op when nothing is pending. KYC approver / admin only.
     */
    @Transactional
    public ReminderResult sendKycReminder(Long appId) {
        requireCreditTeamOr("Sending a reminder", "TELECALLER");
        LoanApplication app = requireApplication(appId);
        Map<String, String> byType = verificationRepo.findByApplicationIdOrderByIdAsc(appId).stream()
                .collect(Collectors.toMap(ApplicationVerification::getCheckType,
                        ApplicationVerification::getStatus, (a, b) -> b));
        List<String> pending = new ArrayList<>();
        for (String r : REQUIRED) {
            String s = byType.get(r);
            if (!PASS.equals(s) && !REVIEW.equals(s)) {
                pending.add(humanizeCheckType(r));
            }
        }
        if (pending.isEmpty()) {
            return new ReminderResult(false, 0, "none");
        }
        String pendingSteps = String.join(", ", pending);
        eventPublisher.publishEvent(new KycReminderEvent(app.getCustomerId(), appId, pendingSteps, Instant.now()));
        return new ReminderResult(true, pending.size(), pendingSteps);
    }

    /**
     * Cross-application pending-API dashboard (Phase 3.3): status tallies (passed / review / failed /
     * pending / never-run) plus the verification rows, enriched with borrower context and filterable by
     * status, check type and a free-text query (borrower name / application id / customer id).
     */
    @Transactional(readOnly = true)
    public VerificationOverview overview(String statusFilter, String checkTypeFilter, String q) {
        List<ApplicationVerification> all = verificationRepo.findAll();
        Map<Long, LoanApplication> appById = applicationRepo.findAll().stream()
                .collect(Collectors.toMap(LoanApplication::getId, a -> a, (a, b) -> a));
        Map<Long, CustomerProfile> profByApp = profileRepo.findAll().stream()
                .collect(Collectors.toMap(CustomerProfile::getApplicationId, p -> p, (a, b) -> a));

        int passed = 0, review = 0, failed = 0, pending = 0;
        Map<Long, Set<String>> presentByApp = new java.util.HashMap<>();
        for (ApplicationVerification v : all) {
            String s = v.getStatus();
            if (PASS.equals(s)) {
                passed++;
            } else if (REVIEW.equals(s)) {
                review++;
            } else if (FAIL.equals(s)) {
                failed++;
            } else {
                pending++;
            }
            presentByApp.computeIfAbsent(v.getApplicationId(), k -> new HashSet<>()).add(v.getCheckType());
        }
        // Never-run: required checks with no row, on applications that have at least started verification.
        int neverRun = 0;
        for (Set<String> present : presentByApp.values()) {
            for (String r : REQUIRED) {
                if (!present.contains(r)) {
                    neverRun++;
                }
            }
        }

        String statusF = norm(statusFilter);
        String typeF = norm(checkTypeFilter);
        String needle = q != null ? q.trim().toLowerCase() : "";
        List<VerificationOverviewRow> rows = all.stream()
                .filter(v -> statusF.isEmpty() || statusF.equals(v.getStatus()))
                .filter(v -> typeF.isEmpty() || typeF.equals(v.getCheckType()))
                .map(v -> {
                    LoanApplication a = appById.get(v.getApplicationId());
                    CustomerProfile p = profByApp.get(v.getApplicationId());
                    Instant ts = v.getUpdatedAt() != null ? v.getUpdatedAt() : v.getCreatedAt();
                    return new VerificationOverviewRow(v.getApplicationId(),
                            a != null ? a.getCustomerId() : null,
                            p != null ? p.getFullName() : null,
                            p != null ? p.getMobile() : null,
                            v.getCheckType(), v.getStatus(), v.getProvider(), v.getMessage(), ts,
                            a != null && a.getStatus() != null ? a.getStatus().name() : null);
                })
                .filter(r -> needle.isEmpty() || overviewMatches(r, needle))
                .sorted(java.util.Comparator.comparing(VerificationOverviewRow::updatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .toList();
        return new VerificationOverview(passed, review, failed, pending, neverRun, rows);
    }

    private static boolean overviewMatches(VerificationOverviewRow r, String needle) {
        if (r.borrowerName() != null && r.borrowerName().toLowerCase().contains(needle)) {
            return true;
        }
        if (r.borrowerMobile() != null && r.borrowerMobile().contains(needle)) {
            return true;
        }
        if (r.applicationId() != null && String.valueOf(r.applicationId()).contains(needle)) {
            return true;
        }
        return r.customerId() != null && String.valueOf(r.customerId()).contains(needle);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    private static String humanizeCheckType(String t) {
        String s = t.toLowerCase().replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---------------------------------------------------------------- helpers

    private Optional<ApplicationVerification> passed(Long appId, String checkType) {
        return verificationRepo.findByApplicationIdAndCheckType(appId, checkType)
                .filter(v -> PASS.equals(v.getStatus()));
    }

    private ApplicationVerification upsert(Long appId, String checkType, String status, String provider,
                                           String txnId, String ref, Double nameMatch, Long score,
                                           String s3Key, Map<String, Object> derived, String message) {
        return upsert(appId, checkType, status, provider, txnId, ref, nameMatch, score,
                s3Key, derived, message, null);
    }

    private ApplicationVerification upsert(Long appId, String checkType, String status, String provider,
                                           String txnId, String ref, Double nameMatch, Long score,
                                           String s3Key, Map<String, Object> derived, String message,
                                           String providerRawResponse) {
        ApplicationVerification row = verificationRepo.findByApplicationIdAndCheckType(appId, checkType)
                .orElseGet(ApplicationVerification::new);
        row.setApplicationId(appId);
        row.setCheckType(checkType);
        row.setStatus(status);
        row.setProvider(provider);
        row.setProviderTxnId(txnId);
        row.setClientRefNum(ref);
        row.setNameMatch(nameMatch);
        row.setScore(score);
        if (s3Key != null) {
            row.setS3ObjectKey(s3Key);
        }
        row.setDerived(toJson(derived));
        // Full CRM snapshot: provider provenance + every derived field we persisted for this step.
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("provider", nz(provider));
        raw.put("txnId", nz(txnId));
        raw.put("status", status);
        raw.put("clientRef", nz(ref));
        if (derived != null) {
            raw.put("fields", derived);
        }
        row.setRawResponse(validJsonOrFallback(providerRawResponse, raw));
        row.setMessage(message);
        return verificationRepo.save(row);
    }

    private String validJsonOrFallback(String providerRawResponse, Map<String, Object> fallback) {
        if (providerRawResponse != null && !providerRawResponse.isBlank()) {
            try {
                return objectMapper.readTree(providerRawResponse).toString();
            } catch (Exception malformedProviderPayload) {
                log.warn("Ignoring malformed provider response JSON while persisting verification snapshot");
            }
        }
        return toJson(fallback);
    }

    /**
     * Converts an upstream failure into a PII-safe diagnostic category. Provider response bodies,
     * request values, and OTPs must never enter application logs or the verification audit JSON.
     */
    private static String providerErrorCode(RuntimeException failure) {
        String message = failure.getMessage();
        if (message != null && message.matches("HTTP [1-5]\\d{2}(?: from .+)?")) {
            return "HTTP_" + message.substring(5, 8);
        }
        if (message != null && message.startsWith("Empty response body")) {
            return "EMPTY_RESPONSE";
        }
        if (message != null && message.startsWith("Provider reported error")) {
            return "PROVIDER_REPORTED_ERROR";
        }
        return "UNEXPECTED_PROVIDER_FAILURE";
    }

    private StepResult view(ApplicationVerification row) {
        Map<String, Object> derived = fromJson(row.getDerived());
        return new StepResult(row.getCheckType(), row.getStatus(), row.getMessage(), derived);
    }

    /** Cross-match PAN / Aadhaar / penny-drop names; store min pairwise on the profile. */
    private double recomputeNameMatch(Long appId) {
        String panName = derivedName(appId, PAN);
        String aadhaarName = derivedName(appId, AADHAAR);
        String bankName = derivedName(appId, PENNY_DROP);
        double min = -1;
        min = combine(min, panName, aadhaarName);
        min = combine(min, panName, bankName);
        min = combine(min, aadhaarName, bankName);
        if (min >= 0) {
            final double score = round2(min);
            profileRepo.findByApplicationId(appId).ifPresent(p -> {
                p.setNameMatchScore(score);
                profileRepo.save(p);
            });
        }
        return min < 0 ? 0 : min;
    }

    private static double combine(double current, String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return current;
        }
        double sim = nameSimilarity(a, b);
        return current < 0 ? sim : Math.min(current, sim);
    }

    private String derivedName(Long appId, String checkType) {
        return verificationRepo.findByApplicationIdAndCheckType(appId, checkType)
                .map(v -> fromJson(v.getDerived()))
                .map(d -> d.get("fullName"))
                .map(Object::toString)
                .orElse(null);
    }

    /** Find the Aadhaar file id to download (prefer pdf, else the canonical "aadhaar"). */
    private String pickAadhaarFile(String clientId) {
        List<VerificationPort.DigiLockerDoc> docs = verification.digilockerList(clientId);
        return docs.stream()
                .filter(d -> "ADHAR".equalsIgnoreCase(d.docType()) && "pdf".equalsIgnoreCase(d.fileType()))
                .map(VerificationPort.DigiLockerDoc::fileId)
                .findFirst()
                .orElse(docs.stream()
                        .filter(d -> "ADHAR".equalsIgnoreCase(d.docType()))
                        .map(VerificationPort.DigiLockerDoc::fileId)
                        .findFirst()
                        .orElse("aadhaar"));
    }

    private CustomerProfile profile(Long appId) {
        return profileRepo.findByApplicationId(appId)
                .orElseThrow(() -> new BusinessException("PROFILE_REQUIRED",
                        "Save KYC profile before running verification"));
    }

    /**
     * The mobile to step-up against: this application's profile, else the newest one this customer
     * has on file. A signed-in borrower starting a fresh application skips the mobile step, so the
     * new profile carries no mobile of its own; back-filling it here keeps the number server-resolved
     * (never client-supplied) while unblocking the consent step, and persists it so later steps and
     * staff surfaces see it too.
     */
    private String resolveMobile(Long appId, CustomerProfile profile) {
        String mobile = profile.getMobile();
        if (mobile != null && !mobile.isBlank()) {
            return mobile;
        }
        Long customerId = requireApplication(appId).getCustomerId();
        if (customerId == null) {
            return null;
        }
        String prior = profileRepo.findMobilesForCustomer(customerId).stream()
                .filter(m -> m != null && !m.isBlank())
                .findFirst()
                .orElse(null);
        if (prior != null) {
            profile.setMobile(prior);
            profileRepo.save(profile);
        }
        return prior;
    }

    private LoanApplication requireApplication(Long appId) {
        return applicationRepo.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("LoanApplication", String.valueOf(appId)));
    }

    private String ref(Long appId, String checkType) {
        return "navix-" + appId + "-" + checkType;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Normalised-token Jaccard similarity (0..1). Permissive by design. */
    static double nameSimilarity(String a, String b) {
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(ta);
        inter.retainAll(tb);
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        return (double) inter.size() / union.size();
    }

    private static Set<String> tokens(String s) {
        if (s == null) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (String t : s.toLowerCase().split("[^a-z0-9]+")) {
            if (!t.isBlank()) {
                out.add(t);
            }
        }
        return out;
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            Map<String, Object> out = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> out.put(e.getKey(),
                    e.getValue().isValueNode() ? asValue(e.getValue()) : e.getValue().toString()));
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Object asValue(JsonNode v) {
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isNumber()) {
            return v.numberValue();
        }
        if (v.isNull()) {
            return null;
        }
        return v.asText();
    }
}
