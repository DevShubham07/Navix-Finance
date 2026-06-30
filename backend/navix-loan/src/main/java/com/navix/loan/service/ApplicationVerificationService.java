package com.navix.loan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.notification.event.KycReminderEvent;
import com.navix.common.risk.RiskPort;
import com.navix.common.security.ActorContext;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.common.verification.VerificationPort;
import com.navix.loan.entity.ApplicantProfile;
import com.navix.loan.entity.ApplicationDocument;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicantProfileRepository;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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

    // ---- check types ----
    public static final String PAN = "PAN";
    public static final String EMAIL = "EMAIL";
    public static final String ADDRESS = "ADDRESS";
    public static final String DIGILOCKER = "DIGILOCKER";
    public static final String AADHAAR = "AADHAAR";
    public static final String BUREAU = "BUREAU";
    public static final String SALARY = "SALARY";
    public static final String PENNY_DROP = "PENNY_DROP";
    public static final String SELFIE = "SELFIE";
    public static final String AGREEMENT = "AGREEMENT";

    // ---- statuses ----
    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String REVIEW = "REVIEW";
    public static final String PENDING = "PENDING";

    /** Checks that must be PASS or REVIEW (plus agreement consent) to submit KYC. */
    static final List<String> REQUIRED =
            List.of(PAN, EMAIL, ADDRESS, AADHAAR, BUREAU, SALARY, PENNY_DROP, SELFIE);

    /** Every recognised check type — guards the staff manual-override target. */
    static final Set<String> KNOWN_CHECKS =
            Set.of(PAN, EMAIL, ADDRESS, DIGILOCKER, AADHAAR, BUREAU, SALARY, PENNY_DROP, SELFIE, AGREEMENT);

    /** Permissive name-match cutoff: below this is REVIEW (not hard fail) — approver decides. */
    static final double NAME_MATCH_THRESHOLD = 0.60;

    private final ApplicationVerificationRepository verificationRepo;
    private final ApplicantProfileRepository profileRepo;
    private final LoanApplicationRepository applicationRepo;
    private final ApplicationDocumentRepository documentRepo;
    private final VerificationPort verification;
    private final DocumentStoragePort storage;
    private final RiskPort risk;
    private final ObjectMapper objectMapper;
    private final CreditBriefService creditBriefService;
    private final ApplicationEventPublisher eventPublisher;

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
    public record VerificationOverviewRow(Long applicationId, Long applicantId, String borrowerName,
                                          String checkType, String status, String provider,
                                          String message, Instant updatedAt) {
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
        VerificationPort.PanCheck r = verification.verifyPan(pan, ref);
        ApplicantProfile profile = profile(appId);
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
        profileRepo.save(profile);

        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("fullName", r.fullName());
        derived.put("aadhaarLinked", r.aadhaarLinked());
        derived.put("maskedAadhaar", r.maskedAadhaar());
        derived.put("addressState", r.addressState());
        String status = r.valid() ? PASS : FAIL;
        ApplicationVerification row = upsert(appId, PAN, status, "FINTRIX", r.txnId(), ref,
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
        ApplicantProfile profile = profile(appId);
        String ref = ref(appId, EMAIL);
        VerificationPort.EmailCheck r = verification.verifyEmail(
                email, nz(profile.getFullName()), nz(profile.getEmployer()), ref);
        profile.setEmailVerified(r.verified());
        // Persist the verified official email as the contact email only if the borrower hasn't
        // already supplied one (personal email saved via the profile takes precedence). V22.
        if (profile.getEmail() == null || profile.getEmail().isBlank()) {
            profile.setEmail(email);
        }
        profileRepo.save(profile);

        boolean ok = r.verified() && r.establishmentMatched() && !r.genericEmail();
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("verified", r.verified());
        derived.put("establishmentMatched", r.establishmentMatched());
        derived.put("genericEmail", r.genericEmail());
        derived.put("matchedEstablishment", r.matchedEstablishment());
        String status = ok ? PASS : REVIEW;
        String msg = ok ? "Email + employer matched"
                : (r.genericEmail() ? "Not an official email" : "Employer not matched — manual review");
        return view(upsert(appId, EMAIL, status, "FINTRIX", r.txnId(), ref, null, null, null, derived, msg));
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
        VerificationPort.AddressCheck r = verification.verifyAddress(lat, lng, ref);
        ApplicantProfile profile = profile(appId);
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
        String status = resolved ? PASS : REVIEW;
        return view(upsert(appId, ADDRESS, status, "FINTRIX", r.txnId(), ref, null, null, null, derived,
                resolved ? "Address resolved" : "Address could not be resolved — review"));
    }

    /** Manual address fallback when geolocation is unavailable — recorded for approver review. */
    @Transactional
    public StepResult recordManualAddress(Long appId, String manualAddress) {
        if (manualAddress == null || manualAddress.isBlank()) {
            throw new BusinessException("ADDRESS_REQUIRED", "Provide coordinates or a manual address");
        }
        ApplicantProfile profile = profile(appId);
        profile.setAddress(manualAddress);
        profile.setAddressVerified(Boolean.FALSE);
        profileRepo.save(profile);
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("manualAddress", manualAddress);
        return view(upsert(appId, ADDRESS, REVIEW, "NAVIX", null, ref(appId, ADDRESS),
                null, null, null, derived, "Manual address — pending review"));
    }

    /** App-scoped presigned PUT target for a browser upload (salary slip, selfie). */
    @Transactional(readOnly = true)
    public PresignedUpload presignUpload(Long appId, String docType, String fileName, String contentType) {
        requireApplication(appId);
        String ext = extensionOf(fileName, contentType);
        String key = storage.buildApplicationKey(appId, docType, ext);
        String url = storage.presignUpload(key, contentType);
        return new PresignedUpload(key, url);
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
        VerificationPort.DigiLockerSession s = verification.digilockerInit(redirectUrl, 20, true);
        ApplicantProfile profile = profile(appId);
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
        ApplicantProfile profile = profile(appId);
        String clientId = profile.getDigilockerClientId();
        if (clientId == null) {
            throw new BusinessException("DIGILOCKER_NOT_STARTED", "No DigiLocker session for this application");
        }
        VerificationPort.DigiLockerStatus s = verification.digilockerStatus(clientId);
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("status", s.status());
        derived.put("completed", s.completed());
        derived.put("failed", s.failed());
        derived.put("aadhaarLinked", s.aadhaarLinked());
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
        ApplicantProfile profile = profile(appId);
        String clientId = profile.getDigilockerClientId();
        if (clientId == null) {
            throw new BusinessException("DIGILOCKER_NOT_STARTED", "No DigiLocker session for this application");
        }
        VerificationPort.AadhaarResult a = verification.digilockerAadhaar(clientId);

        // Aadhaar is the authoritative DOB source — persist it onto the profile (overriding any
        // earlier PAN-derived value) so the borrower's stored date of birth reflects KYC.
        LocalDate aadhaarDob = parseDob(a.dob());
        if (aadhaarDob != null) {
            profile.setDob(aadhaarDob);
            profileRepo.save(profile);
        }

        // Server-side ingest of the Aadhaar PDF (bytes never reach the browser).
        String s3Key = null;
        try {
            String fileId = pickAadhaarFile(clientId);
            if (fileId != null) {
                VerificationPort.DigiLockerDownload d = verification.digilockerDownload(clientId, fileId);
                String key = storage.buildApplicationKey(appId, AADHAAR, "pdf");
                s3Key = storage.storeFromUrl(key, d.downloadUrl(),
                        d.mimeType() != null ? d.mimeType() : "application/pdf");
                ApplicationDocument doc = new ApplicationDocument();
                doc.setApplicationId(appId);
                doc.setDocType(AADHAAR);
                doc.setFileName("aadhaar.pdf");
                doc.setContentType("application/pdf");
                doc.setS3ObjectKey(s3Key);
                documentRepo.save(doc);
            }
        } catch (RuntimeException ingestFailure) {
            // Demographics still recorded; the PDF can be re-fetched. Don't fail the whole step.
            s3Key = null;
        }

        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("fullName", a.fullName());
        derived.put("dob", a.dob());
        derived.put("maskedAadhaar", a.maskedAadhaar());
        derived.put("state", a.state());
        derived.put("pincode", a.pincode());
        ApplicationVerification row = upsert(appId, AADHAAR, PASS, "DIGILOCKER", a.txnId(), clientId,
                null, null, s3Key, derived, "Aadhaar fetched from DigiLocker");
        double match = recomputeNameMatch(appId);
        if (match > 0 && match < NAME_MATCH_THRESHOLD) {
            row.setStatus(REVIEW);
            row.setMessage("Name mismatch vs PAN — manual review");
            verificationRepo.save(row);
        }
        return view(row);
    }

    /** Credit bureau pull (Experian → CRIF). Recorded for staff/risk; never shown to borrower. */
    @Transactional
    public StepResult pullBureau(Long appId) {
        Optional<ApplicationVerification> existing = passed(appId, BUREAU);
        if (existing.isPresent()) {
            return new StepResult(BUREAU, existing.get().getStatus(), existing.get().getMessage(), Map.of());
        }
        ApplicantProfile profile = profile(appId);
        String ref = ref(appId, BUREAU);
        VerificationPort.BureauCheck r = verification.pullBureau(
                profile.getPan(), nz(profile.getFullName()), nz(profile.getMobile()),
                profile.getDob() != null ? profile.getDob().toString() : "", ref);

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
        creditBriefService.generate(appId, profile, r.facts());
        profileRepo.save(profile);

        // Borrower-safe derived: NO score, NO category.
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("noRecord", r.noRecord());
        ApplicationVerification row = upsert(appId, BUREAU, PASS, r.source(), r.txnId(), ref,
                null, bureauScore != null ? bureauScore.longValue() : null, null, derived,
                r.noRecord() ? "Thin-file (no bureau record)" : "Bureau pulled");
        return new StepResult(BUREAU, PASS, row.getMessage(), Map.of());
    }

    /** Declared salary + salary-slip keys (min 3 months) → provisional eligible limit (25% cap). */
    @Transactional
    public StepResult verifySalary(Long appId, long monthlySalaryPaise, List<String> slipObjectKeys) {
        if (monthlySalaryPaise <= 0) {
            throw new BusinessException("INVALID_SALARY", "Monthly salary must be positive");
        }
        ApplicantProfile profile = profile(appId);
        profile.setMonthlySalaryPaise(monthlySalaryPaise);
        profileRepo.save(profile);

        long eligible = risk.eligibleLimitPaise(monthlySalaryPaise);
        LoanApplication app = requireApplication(appId);
        app.setEligibleLimit(eligible);
        applicationRepo.save(app);

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
        return view(upsert(appId, SALARY, PASS, "NAVIX", null, ref(appId, SALARY),
                null, null, primaryKey, derived, "Declared salary recorded"));
    }

    /** Penny-drop bank verify + name-at-bank match (payout gate). */
    @Transactional
    public StepResult verifyPennyDrop(Long appId, String accountNumber, String ifsc) {
        Optional<ApplicationVerification> existing = passed(appId, PENNY_DROP);
        if (existing.isPresent()) {
            return view(existing.get());
        }
        ApplicantProfile profile = profile(appId);
        String ref = ref(appId, PENNY_DROP);
        VerificationPort.PennyDropCheck r = verification.pennyDrop(accountNumber, ifsc, ref);
        double nameMatch = nameSimilarity(profile.getFullName(), r.fullName());
        boolean ok = r.accountExists() && nameMatch >= NAME_MATCH_THRESHOLD;
        profile.setPennyDropVerified(ok);
        if (profile.getSalaryBank() == null && r.bank() != null) {
            profile.setSalaryBank(r.bank());
        }
        profileRepo.save(profile);

        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("accountExists", r.accountExists());
        derived.put("bank", r.bank());
        derived.put("nameMatch", round2(nameMatch));
        String status = ok ? PASS : REVIEW;
        String msg = !r.accountExists() ? "Account not found"
                : (ok ? "Account + name matched" : "Name mismatch at bank — manual review");
        ApplicationVerification row = upsert(appId, PENNY_DROP, status, "FINTRIX", r.txnId(), ref,
                nameMatch, null, null, derived, msg);
        recomputeNameMatch(appId);
        return view(row);
    }

    /** Selfie liveness on the uploaded selfie (presigned GET → Fintrix). */
    @Transactional
    public StepResult verifySelfie(Long appId, String selfieObjectKey) {
        if (selfieObjectKey == null || selfieObjectKey.isBlank()) {
            throw new BusinessException("SELFIE_REQUIRED", "selfieObjectKey is required");
        }
        requireApplication(appId);
        String imageUrl = storage.presignDownload(selfieObjectKey);
        String ref = ref(appId, SELFIE);
        VerificationPort.FaceLivenessCheck r = verification.faceLiveness(imageUrl, ref);

        ApplicationDocument selfie = new ApplicationDocument();
        selfie.setApplicationId(appId);
        selfie.setDocType(SELFIE);
        selfie.setFileName("selfie.jpg");
        selfie.setContentType("image/jpeg");
        selfie.setS3ObjectKey(selfieObjectKey);
        documentRepo.save(selfie);

        boolean live = r.live() && !r.multipleFaces();
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("live", r.live());
        derived.put("confidence", r.confidence());
        // Fail → flagged for manual review (not hard block); approver decides.
        String status = live ? PASS : REVIEW;
        Long score = r.confidence() != null ? Math.round(r.confidence() * 100) : null;
        return view(upsert(appId, SELFIE, status, "FINTRIX", r.txnId(), ref, null, score, selfieObjectKey,
                derived, live ? "Liveness passed" : "Liveness low — manual review"));
    }

    /** Record agreement consent (the 3 documents the borrower accepted). */
    @Transactional
    public StepResult recordAgreement(Long appId, List<String> versions) {
        ApplicantProfile profile = profile(appId);
        profile.setAgreementAccepted(Boolean.TRUE);
        profileRepo.save(profile);
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("versions", versions != null ? versions : List.of());
        return view(upsert(appId, AGREEMENT, PASS, "NAVIX", null, ref(appId, AGREEMENT),
                null, null, null, derived, "Agreement accepted"));
    }

    // ---------------------------------------------------------------- gating + summary

    /** True if every required check is PASS/REVIEW and the agreement has been accepted. */
    @Transactional(readOnly = true)
    public boolean allRequiredPassed(Long appId) {
        Map<String, String> byType = verificationRepo.findByApplicationIdOrderByIdAsc(appId).stream()
                .collect(Collectors.toMap(ApplicationVerification::getCheckType,
                        ApplicationVerification::getStatus, (a, b) -> b));
        for (String required : REQUIRED) {
            String status = byType.get(required);
            if (!PASS.equals(status) && !REVIEW.equals(status)) {
                return false;
            }
        }
        return profileRepo.findByApplicationId(appId)
                .map(p -> Boolean.TRUE.equals(p.getAgreementAccepted()))
                .orElse(false);
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
        String status = verificationRepo.findByApplicationIdAndCheckType(appId, AADHAAR)
                .map(ApplicationVerification::getStatus)
                .orElse(null);
        if (!PASS.equals(status) && !REVIEW.equals(status)) {
            upsert(appId, AADHAAR, REVIEW, "MANUAL", null, null, null, null, null,
                    Map.of(), "DigiLocker not completed — Aadhaar pending manual review by staff");
        }
    }

    /** Number of verification checks an applicant must clear (PASS/REVIEW) to submit KYC. */
    public static int requiredCount() {
        return REQUIRED.size();
    }

    /** How many of the {@link #requiredCount()} required checks are currently PASS/REVIEW for an
     *  application — the onboarding-completeness signal used by the admin all-applications register. */
    @Transactional(readOnly = true)
    public int requiredPassedCount(Long appId) {
        Map<String, String> byType = verificationRepo.findByApplicationIdOrderByIdAsc(appId).stream()
                .collect(Collectors.toMap(ApplicationVerification::getCheckType,
                        ApplicationVerification::getStatus, (a, b) -> b));
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
        return verificationRepo.findByApplicationIdOrderByIdAsc(appId).stream()
                .map(this::view)
                .toList();
    }

    /**
     * Completion snapshot over the {@link #REQUIRED} checks (Phase 3.2): how many are cleared
     * (PASS/REVIEW), failed (FAIL), or pending (PENDING / never-run), plus a 0–100 percent.
     */
    @Transactional(readOnly = true)
    public VerificationProgress progress(Long appId) {
        Map<String, String> byType = verificationRepo.findByApplicationIdOrderByIdAsc(appId).stream()
                .collect(Collectors.toMap(ApplicationVerification::getCheckType,
                        ApplicationVerification::getStatus, (a, b) -> b));
        int completed = 0;
        int failed = 0;
        int pending = 0;
        for (String r : REQUIRED) {
            String s = byType.get(r);
            if (PASS.equals(s) || REVIEW.equals(s)) {
                completed++;
            } else if (FAIL.equals(s)) {
                failed++;
            } else {
                pending++; // PENDING or never-run
            }
        }
        int required = REQUIRED.size();
        int percent = required == 0 ? 100 : (int) Math.round(completed * 100.0 / required);
        return new VerificationProgress(required, completed, failed, pending, percent);
    }

    /**
     * Staff manual override of a verification step (Phase 3.1): a KYC approver (or ADMIN) sets a check
     * to PASS or FAIL with a note — used when an external check is stuck/inconclusive and needs human
     * judgement. Recorded via {@link #upsert} with provider {@code MANUAL} (idempotent per check type).
     */
    @Transactional
    public StepResult manualDecision(Long appId, String checkType, boolean pass, String notes) {
        String role = ActorContext.get().role();
        if (!"KYC_APPROVER".equals(role) && !"ADMIN".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "Manual verification override requires KYC_APPROVER");
        }
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
        String role = ActorContext.get().role();
        if (!"KYC_APPROVER".equals(role) && !"ADMIN".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "Sending a reminder requires KYC_APPROVER");
        }
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
        eventPublisher.publishEvent(new KycReminderEvent(app.getApplicantId(), appId, pendingSteps, Instant.now()));
        return new ReminderResult(true, pending.size(), pendingSteps);
    }

    /**
     * Cross-application pending-API dashboard (Phase 3.3): status tallies (passed / review / failed /
     * pending / never-run) plus the verification rows, enriched with borrower context and filterable by
     * status, check type and a free-text query (borrower name / application id / applicant id).
     */
    @Transactional(readOnly = true)
    public VerificationOverview overview(String statusFilter, String checkTypeFilter, String q) {
        List<ApplicationVerification> all = verificationRepo.findAll();
        Map<Long, LoanApplication> appById = applicationRepo.findAll().stream()
                .collect(Collectors.toMap(LoanApplication::getId, a -> a, (a, b) -> a));
        Map<Long, ApplicantProfile> profByApp = profileRepo.findAll().stream()
                .collect(Collectors.toMap(ApplicantProfile::getApplicationId, p -> p, (a, b) -> a));

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
                    ApplicantProfile p = profByApp.get(v.getApplicationId());
                    Instant ts = v.getUpdatedAt() != null ? v.getUpdatedAt() : v.getCreatedAt();
                    return new VerificationOverviewRow(v.getApplicationId(),
                            a != null ? a.getApplicantId() : null,
                            p != null ? p.getFullName() : null,
                            v.getCheckType(), v.getStatus(), v.getProvider(), v.getMessage(), ts);
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
        if (r.applicationId() != null && String.valueOf(r.applicationId()).contains(needle)) {
            return true;
        }
        return r.applicantId() != null && String.valueOf(r.applicantId()).contains(needle);
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
        // Audit provenance only — no PII names/aadhaar/account numbers persisted raw.
        row.setRawResponse(toJson(Map.of("provider", nz(provider), "txnId", nz(txnId), "status", status)));
        row.setMessage(message);
        return verificationRepo.save(row);
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

    private ApplicantProfile profile(Long appId) {
        return profileRepo.findByApplicationId(appId)
                .orElseThrow(() -> new BusinessException("PROFILE_REQUIRED",
                        "Save KYC profile before running verification"));
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
