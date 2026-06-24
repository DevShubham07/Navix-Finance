package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.dto.ReviewDtos.DocumentRequest;
import com.navix.loan.dto.ReviewDtos.ProfileRequest;
import com.navix.loan.entity.ApplicantProfile;
import com.navix.loan.entity.ApplicationDocument;
import com.navix.loan.repository.ApplicantProfileRepository;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.util.Base64;
import java.util.List;
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

    @Transactional
    public ApplicantProfile saveProfile(Long appId, ProfileRequest req) {
        requireRole("BORROWER");
        requireApplication(appId);
        ApplicantProfile p = profileRepository.findByApplicationId(appId).orElseGet(ApplicantProfile::new);
        p.setApplicationId(appId);
        p.setFullName(trimToNull(req.fullName()));
        p.setPan(normalizePan(req.pan()));
        p.setDob(req.dob());
        p.setAddress(trimToNull(req.address()));
        p.setEmployer(trimToNull(req.employer()));
        p.setEmploymentStatus(trimToNull(req.employmentStatus()));
        p.setMonthlySalaryPaise(req.monthlySalaryPaise());
        p.setSalaryBank(trimToNull(req.salaryBank()));
        return profileRepository.save(p);
    }

    @Transactional(readOnly = true)
    public ApplicantProfile getProfile(Long appId) {
        requireApplication(appId);
        return profileRepository.findByApplicationId(appId)
                .orElseThrow(() -> new ResourceNotFoundException("ApplicantProfile", "application:" + appId));
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
}
