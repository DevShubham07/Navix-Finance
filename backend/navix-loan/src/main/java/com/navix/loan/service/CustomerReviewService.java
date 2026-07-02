package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.loan.dto.ReviewDtos.DocumentRequest;
import com.navix.loan.dto.ReviewDtos.EditProfileRequest;
import com.navix.loan.dto.ReviewDtos.ProfileRequest;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.ApplicationDocument;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.entity.ProfileChangeLog;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.ProfileChangeLogRepository;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the customer-review data attached to an application: the KYC profile and uploaded
 * documents. Writes are borrower-only (ADMIN may act for the borrower); reads are open to any
 * authenticated actor so every reviewing staff role can view them. Document bytes are base64 in /
 * base64 out, kept inline ({@code bytea}) for the demo.
 */
@Service
@RequiredArgsConstructor
public class CustomerReviewService {

    /** Inline document cap — keeps base64-over-JSON sane for the demo. */
    static final int MAX_DOC_BYTES = 5 * 1024 * 1024;

    private final LoanApplicationRepository applicationRepository;
    private final CustomerProfileRepository profileRepository;
    private final ApplicationDocumentRepository documentRepository;
    private final DocumentStoragePort storage;
    private final VerificationInvalidationService verificationInvalidation;
    private final EligibilityService eligibilityService;
    private final ProfileChangeLogRepository changeLogRepository;

    @Transactional
    public CustomerProfile saveProfile(Long appId, ProfileRequest req) {
        requireRole("BORROWER");
        LoanApplication app = applicationRepository.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("LoanApplication", String.valueOf(appId)));
        Long customerId = app.getCustomerId();

        String pan = normalizePan(req.pan());
        String mobile = normalizeMobile(req.mobile());

        // A mobile / PAN may belong to only one customer. Uniqueness is now enforced ACROSS customers
        // (not per-application): the same customer re-onboarding through a NEW application — which
        // creates a fresh profile row carrying the same identity — is allowed, while a different person
        // reusing the PAN / mobile is still rejected. (The Aadhaar number is no longer captured; identity
        // is anchored on PAN + mobile + DigiLocker verification.)
        if (pan != null && profileRepository.existsPanForOtherCustomer(pan, customerId)) {
            throw new BusinessException("DUPLICATE_PAN",
                    "This PAN is already registered with another customer.");
        }
        if (mobile != null && profileRepository.existsMobileForOtherCustomer(mobile, customerId)) {
            throw new BusinessException("DUPLICATE_MOBILE",
                    "This mobile number is already registered with another customer.");
        }

        CustomerProfile p = profileRepository.findByApplicationId(appId).orElseGet(CustomerProfile::new);
        p.setApplicationId(appId);
        // PARTIAL MERGE: onboarding saves the profile in slices (name on the email step, salary on
        // the salary step, bank on the penny-drop step, …). Only overwrite a field when this request
        // actually provides it, so a later slice never wipes an earlier one — otherwise the profile
        // ends up holding only the last slice's fields (e.g. name/salary null on re-login).
        String fullName = trimToNull(req.fullName());
        if (fullName != null) p.setFullName(fullName);
        if (pan != null) p.setPan(pan);
        if (mobile != null) p.setMobile(mobile);
        if (req.dob() != null) p.setDob(req.dob());
        String address = trimToNull(req.address());
        if (address != null) p.setAddress(address);
        String employer = trimToNull(req.employer());
        if (employer != null) p.setEmployer(employer);
        String employmentStatus = trimToNull(req.employmentStatus());
        if (employmentStatus != null) p.setEmploymentStatus(employmentStatus);
        if (req.monthlySalaryPaise() != null) p.setMonthlySalaryPaise(req.monthlySalaryPaise());
        String salaryBank = trimToNull(req.salaryBank());
        if (salaryBank != null) p.setSalaryBank(salaryBank);
        String email = trimToNull(req.email());
        if (email != null) p.setEmail(email);
        return profileRepository.save(p);
    }

    /**
     * Borrower self-edit of their own profile (Phase 2.2). Identity fields (name/PAN/Aadhaar/mobile/DOB)
     * are <b>locked</b> — only contact, address, employment, salary, bank and emergency-contact fields
     * are editable. Editing a verification-linked field resets the matching check to PENDING (re-
     * verification required, via {@link VerificationInvalidationService}); a salary change recomputes
     * the eligible limit. A null field is left unchanged (partial update). BORROWER-only (ADMIN bypass);
     * ownership is enforced at the controller.
     */
    @Transactional
    public CustomerProfile editOwnProfile(Long appId, EditProfileRequest req) {
        requireRole("BORROWER");
        LoanApplication app = applicationRepository.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("LoanApplication", String.valueOf(appId)));
        // Edit the application's own profile snapshot (must exist — onboarding created it).
        CustomerProfile p = profileRepository.findByApplicationId(appId)
                .orElseThrow(() -> new ResourceNotFoundException("CustomerProfile", "application:" + appId));
        Long customerId = app.getCustomerId();

        java.util.Set<String> changed = new java.util.HashSet<>();
        String address = trimToNull(req.address());
        if (address != null && !address.equals(p.getAddress())) {
            logChange(customerId, appId, "address", p.getAddress(), address);
            p.setAddress(address);
            changed.add("address");
        }
        String employer = trimToNull(req.employer());
        if (employer != null && !employer.equals(p.getEmployer())) {
            logChange(customerId, appId, "employer", p.getEmployer(), employer);
            p.setEmployer(employer);
            changed.add("employer");
        }
        String employmentStatus = trimToNull(req.employmentStatus());
        if (employmentStatus != null && !employmentStatus.equals(p.getEmploymentStatus())) {
            logChange(customerId, appId, "employmentStatus", p.getEmploymentStatus(), employmentStatus);
            p.setEmploymentStatus(employmentStatus);
            changed.add("employmentStatus");
        }
        boolean salaryChanged = false;
        if (req.monthlySalaryPaise() != null
                && !req.monthlySalaryPaise().equals(p.getMonthlySalaryPaise())) {
            logChange(customerId, appId, "monthlySalaryPaise",
                    p.getMonthlySalaryPaise() != null ? p.getMonthlySalaryPaise().toString() : null,
                    req.monthlySalaryPaise().toString());
            p.setMonthlySalaryPaise(req.monthlySalaryPaise());
            changed.add("monthlySalaryPaise");
            salaryChanged = true;
        }
        String salaryBank = trimToNull(req.salaryBank());
        if (salaryBank != null && !salaryBank.equals(p.getSalaryBank())) {
            logChange(customerId, appId, "salaryBank", p.getSalaryBank(), salaryBank);
            p.setSalaryBank(salaryBank);
            changed.add("salaryBank");
        }
        String email = trimToNull(req.email());
        if (email != null && !email.equals(p.getEmail())) {
            logChange(customerId, appId, "email", p.getEmail(), email);
            p.setEmail(email);
            changed.add("email");
        }
        // Emergency contact — editable, not verified (no invalidation).
        p.setEmergencyContactName(trimToNull(req.emergencyContactName()));
        p.setEmergencyContactPhone(trimToNull(req.emergencyContactPhone()));
        p.setEmergencyContactRelation(trimToNull(req.emergencyContactRelation()));

        CustomerProfile saved = profileRepository.save(p);
        // Re-verification + eligibility side effects.
        verificationInvalidation.invalidateForFields(appId, changed);
        if (salaryChanged) {
            eligibilityService.recomputeForCustomer(app.getCustomerId(), saved.getMonthlySalaryPaise());
        }
        return saved;
    }

    /** Append a self-edit to the audited change log (so it shows in the customer activity timeline). */
    private void logChange(Long customerId, Long appId, String field, String oldVal, String newVal) {
        ProfileChangeLog entry = new ProfileChangeLog();
        entry.setCustomerId(customerId);
        entry.setApplicationId(appId);
        entry.setField(field);
        entry.setOldValue(oldVal);
        entry.setNewValue(newVal);
        changeLogRepository.save(entry);
    }

    /**
     * Read the customer KYC snapshot for an application. A reborrow application has no profile row
     * of its own (identity carries over from the prior loan), so fall back to the customer's most
     * recent saved profile — this is what the KYC reviewer and the borrower's amount page see.
     */
    @Transactional(readOnly = true)
    public CustomerProfile getProfile(Long appId) {
        LoanApplication app = applicationRepository.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("LoanApplication", String.valueOf(appId)));
        return profileRepository.findByApplicationId(appId)
                .or(() -> latestProfileForCustomer(app.getCustomerId()))
                .orElseThrow(() -> new ResourceNotFoundException("CustomerProfile", "application:" + appId));
    }

    /** Profiles for a set of applications, keyed by {@code applicationId} — to enrich list/queue views. */
    @Transactional(readOnly = true)
    public Map<Long, CustomerProfile> profilesByApplicationIds(Collection<Long> appIds) {
        if (appIds == null || appIds.isEmpty()) {
            return Map.of();
        }
        return profileRepository.findByApplicationIdIn(appIds).stream()
                .collect(Collectors.toMap(CustomerProfile::getApplicationId, p -> p, (a, b) -> a));
    }

    /**
     * Per-application <em>effective</em> profile keyed by {@code applicationId}: the application's own
     * KYC snapshot when it has one, otherwise the customer's most recent saved profile. Mirrors the
     * fallback in {@link #getProfile} so list / queue / customer views stay consistent — a reborrow (or
     * any application without its own snapshot) still shows the customer's credit headline instead of a
     * blank, matching the profile the single-application read returns.
     */
    @Transactional(readOnly = true)
    public Map<Long, CustomerProfile> effectiveProfilesByApplications(List<LoanApplication> apps) {
        if (apps == null || apps.isEmpty()) {
            return Map.of();
        }
        Map<Long, CustomerProfile> own = profilesByApplicationIds(
                apps.stream().map(LoanApplication::getId).toList());
        Map<Long, CustomerProfile> latestByCustomer = new HashMap<>();
        Map<Long, CustomerProfile> out = new HashMap<>();
        for (LoanApplication a : apps) {
            CustomerProfile p = own.get(a.getId());
            if (p == null) {
                p = latestByCustomer.computeIfAbsent(a.getCustomerId(),
                        aid -> latestProfileForCustomer(aid).orElse(null));
            }
            if (p != null) {
                out.put(a.getId(), p);
            }
        }
        return out;
    }

    /**
     * The customer's most recent saved KYC profile, if any — public entry point for the
     * notification {@code BorrowerContactDirectory} adapter (name + mobile + email by customer id).
     */
    @Transactional(readOnly = true)
    public Optional<CustomerProfile> latestProfile(Long customerId) {
        return latestProfileForCustomer(customerId);
    }

    /** The customer's most recent saved KYC profile (newest application first), if any. */
    private Optional<CustomerProfile> latestProfileForCustomer(Long customerId) {
        return applicationRepository.findByCustomerId(customerId).stream()
                .sorted(Comparator.comparing(LoanApplication::getId).reversed())
                .map(a -> profileRepository.findByApplicationId(a.getId()).orElse(null))
                .filter(Objects::nonNull)
                .findFirst();
    }

    @Transactional
    public ApplicationDocument addDocument(Long appId, DocumentRequest req) {
        requireRole("BORROWER");
        requireApplication(appId);
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(req.dataBase64());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("BAD_DOCUMENT", "Document data is not valid base64");
        }
        if (bytes.length == 0) {
            throw new BusinessException("EMPTY_DOCUMENT", "Document is empty");
        }
        if (bytes.length > MAX_DOC_BYTES) {
            throw new BusinessException("DOCUMENT_TOO_LARGE", "Document exceeds the 5 MB limit");
        }
        ApplicationDocument d = new ApplicationDocument();
        d.setApplicationId(appId);
        d.setDocType(req.docType());
        d.setFileName(req.fileName());
        d.setContentType(req.contentType());
        d.setSizeBytes((long) bytes.length);
        d.setData(bytes);
        return documentRepository.save(d);
    }

    @Transactional(readOnly = true)
    public List<ApplicationDocument> listDocuments(Long appId) {
        requireApplication(appId);
        return documentRepository.findByApplicationIdOrderByIdAsc(appId);
    }

    @Transactional(readOnly = true)
    public ApplicationDocument getDocument(Long appId, Long docId) {
        requireApplication(appId);
        return documentRepository.findByIdAndApplicationId(docId, appId)
                .orElseThrow(() -> new ResourceNotFoundException("ApplicationDocument", String.valueOf(docId)));
    }

    /**
     * ADMIN removes an uploaded document (the delete half of the CRM "replace" flow — an admin deletes
     * the existing document of a category before uploading a corrected one). Removes the document row
     * (the CRM's source of truth for the document list). Any S3 object is left for lifecycle cleanup —
     * it's no longer reachable once the row is gone.
     */
    @Transactional
    public void deleteDocument(Long appId, Long docId) {
        requireRole("ADMIN");
        ApplicationDocument d = getDocument(appId, docId);
        documentRepository.delete(d);
    }

    /**
     * Short-lived presigned GET URL for an S3-backed document (the live path; staff never stream
     * document bytes through the backend). Throws for legacy inline ({@code bytea}) rows — those are
     * served via {@link #getDocument} as base64.
     */
    @Transactional(readOnly = true)
    public String presignedUrl(Long appId, Long docId) {
        ApplicationDocument d = getDocument(appId, docId);
        if (d.getS3ObjectKey() == null) {
            throw new BusinessException("NOT_S3_BACKED", "This document is stored inline; fetch its bytes instead");
        }
        return storage.presignDownload(d.getS3ObjectKey());
    }

    // ---- internals -----------------------------------------------------------------

    private void requireApplication(Long appId) {
        if (!applicationRepository.existsById(appId)) {
            throw new ResourceNotFoundException("LoanApplication", String.valueOf(appId));
        }
    }

    /** Borrower-only write (ADMIN bypass), mirroring ApplicationFlowService's role gate. */
    private void requireRole(String role) {
        CurrentActor actor = ActorContext.get();
        if (!role.equals(actor.role()) && !"ADMIN".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role " + role);
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String normalizePan(String pan) {
        String t = trimToNull(pan);
        return t == null ? null : t.toUpperCase();
    }

    /** Digits only, last 10 (drops a country/STD prefix); must be exactly 10. Null when not supplied. */
    private static String normalizeMobile(String mobile) {
        String t = trimToNull(mobile);
        if (t == null) {
            return null;
        }
        String digits = t.replaceAll("\\D", "");
        if (digits.length() > 10) {
            digits = digits.substring(digits.length() - 10);
        }
        if (digits.length() != 10) {
            throw new BusinessException("INVALID_MOBILE", "Mobile must be a 10-digit number");
        }
        return digits;
    }
}
