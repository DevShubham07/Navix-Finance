package com.navix.loan.dto;

import com.navix.common.util.Masking;
import com.navix.loan.entity.ApplicantProfile;
import com.navix.loan.entity.ApplicationDocument;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;

/**
 * DTOs for the applicant-review surface: the KYC profile and uploaded documents a staff reviewer
 * sees for an application. PAN is only ever returned masked.
 */
public final class ReviewDtos {

    private ReviewDtos() {
    }

    /** Borrower-supplied KYC details for an application. All fields optional (progressive capture). */
    public record ProfileRequest(
            String fullName,
            String pan,
            String aadhaar,
            String mobile,
            LocalDate dob,
            String address,
            String employer,
            String employmentStatus,
            Long monthlySalaryPaise,
            String salaryBank) {
    }

    /** Staff-facing KYC view — PAN/Aadhaar/mobile masked (e.g. ABXXXXX34F, XXXXXXXX1234). */
    public record ProfileView(
            Long applicationId,
            String fullName,
            String panMasked,
            String aadhaarMasked,
            String mobileMasked,
            LocalDate dob,
            String address,
            String employer,
            String employmentStatus,
            Long monthlySalaryPaise,
            String salaryBank) {

        public static ProfileView of(ApplicantProfile p) {
            return new ProfileView(
                    p.getApplicationId(), p.getFullName(), Masking.maskPan(p.getPan()),
                    Masking.maskAadhaar(p.getAadhaar()), Masking.maskPhone(p.getMobile()), p.getDob(),
                    p.getAddress(), p.getEmployer(), p.getEmploymentStatus(),
                    p.getMonthlySalaryPaise(), p.getSalaryBank());
        }
    }

    /** Borrower uploads a document as base64 (kept simple so it rides the JSON BFF proxy). */
    public record DocumentRequest(
            @NotBlank String docType,
            @NotBlank String fileName,
            String contentType,
            @NotBlank String dataBase64) {
    }

    /** Document metadata for listing — no bytes. {@code s3} ⇒ fetch via the presigned-URL route. */
    public record DocumentView(
            Long id,
            String docType,
            String fileName,
            String contentType,
            Long sizeBytes,
            boolean s3,
            Instant uploadedAt) {

        public static DocumentView of(ApplicationDocument d) {
            return new DocumentView(d.getId(), d.getDocType(), d.getFileName(), d.getContentType(),
                    d.getSizeBytes(), d.getS3ObjectKey() != null, d.getCreatedAt());
        }
    }

    /** Full document for view/download — inline bytes as base64 (legacy/demo rows only). */
    public record DocumentContentView(
            Long id,
            String docType,
            String fileName,
            String contentType,
            String dataBase64) {

        public static DocumentContentView of(ApplicationDocument d) {
            String base64 = d.getData() != null ? Base64.getEncoder().encodeToString(d.getData()) : null;
            return new DocumentContentView(d.getId(), d.getDocType(), d.getFileName(), d.getContentType(), base64);
        }
    }

    /** Presigned GET URL for an S3-backed document (short TTL). */
    public record DocumentUrlView(Long id, String fileName, String contentType, String url) {
    }
}
