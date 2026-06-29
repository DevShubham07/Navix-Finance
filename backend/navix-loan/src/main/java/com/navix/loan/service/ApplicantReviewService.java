package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.loan.dto.ReviewDtos.DocumentRequest;
import com.navix.loan.dto.ReviewDtos.ProfileRequest;
import com.navix.loan.entity.ApplicantProfile;
import com.navix.loan.entity.ApplicationDocument;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicantProfileRepository;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the applicant-review data attached to an application: the KYC profile and uploaded
 * documents. Writes are borrower-only (ADMIN may act for the borrower); reads are open to any
 * authenticated actor so every reviewing staff role can view them. Document bytes are base64 in /
 * base64 out, kept inline ({@code bytea}) for the demo.
 */
@Service
@RequiredArgsConstructor
public class ApplicantReviewService {

    /** Inline document cap — keeps base64-over-JSON sane for the demo. */
    static final int MAX_DOC_BYTES = 5 * 1024 * 1024;

    private final LoanApplicationRepository applicationRepository;
    private final ApplicantProfileRepository profileRepository;
    private final ApplicationDocumentRepository documentRepository;
    private final DocumentStoragePort storage;

    @Transactional
    public ApplicantProfile saveProfile(Long appId, ProfileRequest req) {
        requireRole("BORROWER");
        LoanApplication app = applicationRepository.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("LoanApplication", String.valueOf(appId)));
        Long applicantId = app.getApplicantId();

        String pan = normalizePan(req.pan());
        String aadhaar = normalizeAadhaar(req.aadhaar());
        String mobile = normalizeMobile(req.mobile());

        // A mobile / PAN / Aadhaar may belong to only one applicant. Uniqueness is now enforced
        // ACROSS applicants (not per-application): the same applicant re-onboarding through a NEW
        // application — which creates a fresh profile row carrying the same identity — is allowed,
        // while a different person reusing the PAN / Aadhaar / mobile is still rejected.
        if (pan != null && profileRepository.existsPanForOtherApplicant(pan, applicantId)) {
            throw new BusinessException("DUPLICATE_PAN",
                    "This PAN is already registered with another applicant.");
        }
        if (aadhaar != null && profileRepository.existsAadhaarForOtherApplicant(aadhaar, applicantId)) {
            throw new BusinessException("DUPLICATE_AADHAAR",
                    "This Aadhaar number is already registered with another applicant.");
        }
        if (mobile != null && profileRepository.existsMobileForOtherApplicant(mobile, applicantId)) {
            throw new BusinessException("DUPLICATE_MOBILE",
                    "This mobile number is already registered with another applicant.");
        }

        ApplicantProfile p = profileRepository.findByApplicationId(appId).orElseGet(ApplicantProfile::new);
        p.setApplicationId(appId);
        // PARTIAL MERGE: onboarding saves the profile in slices (name on the email step, salary on
        // the salary step, bank on the penny-drop step, …). Only overwrite a field when this request
        // actually provides it, so a later slice never wipes an earlier one — otherwise the profile
        // ends up holding only the last slice's fields (e.g. name/salary null on re-login).
        String fullName = trimToNull(req.fullName());
        if (fullName != null) p.setFullName(fullName);
        if (pan != null) p.setPan(pan);
        if (aadhaar != null) p.setAadhaar(aadhaar);
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
     * Read the applicant KYC snapshot for an application. A reborrow application has no profile row
     * of its own (identity carries over from the prior loan), so fall back to the applicant's most
     * recent saved profile — this is what the KYC reviewer and the borrower's amount page see.
     */
    @Transactional(readOnly = true)
    public ApplicantProfile getProfile(Long appId) {
        LoanApplication app = applicationRepository.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("LoanApplication", String.valueOf(appId)));
        return profileRepository.findByApplicationId(appId)
                .or(() -> latestProfileForApplicant(app.getApplicantId()))
                .orElseThrow(() -> new ResourceNotFoundException("ApplicantProfile", "application:" + appId));
    }

    /** Profiles for a set of applications, keyed by {@code applicationId} — to enrich list/queue views. */
    @Transactional(readOnly = true)
    public Map<Long, ApplicantProfile> profilesByApplicationIds(Collection<Long> appIds) {
        if (appIds == null || appIds.isEmpty()) {
            return Map.of();
        }
        return profileRepository.findByApplicationIdIn(appIds).stream()
                .collect(Collectors.toMap(ApplicantProfile::getApplicationId, p -> p, (a, b) -> a));
    }

    /**
     * The applicant's most recent saved KYC profile, if any — public entry point for the
     * notification {@code BorrowerContactDirectory} adapter (name + mobile + email by applicant id).
     */
    @Transactional(readOnly = true)
    public Optional<ApplicantProfile> latestProfile(Long applicantId) {
        return latestProfileForApplicant(applicantId);
    }

    /** The applicant's most recent saved KYC profile (newest application first), if any. */
    private Optional<ApplicantProfile> latestProfileForApplicant(Long applicantId) {
        return applicationRepository.findByApplicantId(applicantId).stream()
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

    /** Digits only; must be exactly 12. Null when not supplied. */
    private static String normalizeAadhaar(String aadhaar) {
        String t = trimToNull(aadhaar);
        if (t == null) {
            return null;
        }
        String digits = t.replaceAll("\\D", "");
        if (digits.length() != 12) {
            throw new BusinessException("INVALID_AADHAAR", "Aadhaar must be a 12-digit number");
        }
        return digits;
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
