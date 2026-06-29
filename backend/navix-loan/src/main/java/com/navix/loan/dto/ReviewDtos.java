package com.navix.loan.dto;

import com.navix.loan.entity.ApplicantProfile;
import com.navix.loan.entity.ApplicationDocument;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;

/**
 * DTOs for the applicant-review surface: the KYC profile and uploaded documents a staff reviewer
 * sees for an application. Staff see the borrower's full, unmasked identity + verification detail;
 * the borrower's own read goes through {@link ProfileView#withoutCredit()}, which strips the
 * staff-only credit/risk/bureau headline.
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
            String salaryBank,
            String email) {
    }

    /**
     * Staff-facing KYC view — full, UNMASKED PAN/Aadhaar/mobile plus contact, verification flags and
     * the staff-only credit headline (score + 1–5★ rating + verdict) so customer/applicant cards can
     * show everything without a second call. The borrower's own read uses {@link #withoutCredit()},
     * which nulls the credit/risk/bureau fields; never return the staff form on a borrower path.
     */
    public record ProfileView(
            Long applicationId,
            String fullName,
            String pan,
            String aadhaar,
            String mobile,
            LocalDate dob,
            String address,
            String employer,
            String employmentStatus,
            Long monthlySalaryPaise,
            String salaryBank,
            String email,
            Integer creditScore,
            Double starRating,
            String recommendation,
            String bureauSource,
            String riskCategory,
            Boolean panVerified,
            Boolean aadhaarLinked,
            Boolean emailVerified,
            Boolean addressVerified,
            Boolean pennyDropVerified,
            Double nameMatchScore,
            String creditBriefSummary,
            Instant creditBriefGeneratedAt) {

        public static ProfileView of(ApplicantProfile p) {
            return new ProfileView(
                    p.getApplicationId(), p.getFullName(), p.getPan(),
                    p.getAadhaar(), p.getMobile(), p.getDob(),
                    p.getAddress(), p.getEmployer(), p.getEmploymentStatus(),
                    p.getMonthlySalaryPaise(), p.getSalaryBank(), p.getEmail(),
                    p.getBureauScore() != null ? p.getBureauScore().intValue() : null,
                    p.getCreditStarRating() != null ? p.getCreditStarRating().doubleValue() : null,
                    p.getCreditRecommendation(), p.getBureauSource(), p.getRiskCategory(),
                    p.getPanVerified(), p.getAadhaarLinked(), p.getEmailVerified(),
                    p.getAddressVerified(), p.getPennyDropVerified(), p.getNameMatchScore(),
                    p.getCreditBriefSummary(), p.getCreditBriefGeneratedAt());
        }

        /**
         * Copy with the staff-only credit/risk/bureau headline stripped — for borrower-facing
         * responses. Identity + own verification flags stay (the borrower may see their own).
         */
        public ProfileView withoutCredit() {
            return new ProfileView(applicationId, fullName, pan, aadhaar, mobile, dob,
                    address, employer, employmentStatus, monthlySalaryPaise, salaryBank, email,
                    null, null, null, null, null,
                    panVerified, aadhaarLinked, emailVerified, addressVerified, pennyDropVerified,
                    null, null, null);
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
