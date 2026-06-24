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
            LocalDate dob,
            String address,
            String employer,
            String employmentStatus,
            Long monthlySalaryPaise,
            String salaryBank) {
    }

    /** Staff-facing KYC view — PAN masked (e.g. ABXXXXX34F). */
    public record ProfileView(
            Long applicationId,
            String fullName,
            String panMasked,
            LocalDate dob,
            String address,
            String employer,
            String employmentStatus,
            Long monthlySalaryPaise,
            String salaryBank) {

        public static ProfileView of(ApplicantProfile p) {
            return new ProfileView(
                    p.getApplicationId(), p.getFullName(), Masking.maskPan(p.getPan()), p.getDob(),
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

    /** Document metadata for listing — no bytes. */
    public record DocumentView(
            Long id,
            String docType,
            String fileName,
            String contentType,
            Long sizeBytes,
            Instant uploadedAt) {

        public static DocumentView of(ApplicationDocument d) {
            return new DocumentView(d.getId(), d.getDocType(), d.getFileName(), d.getContentType(),
                    d.getSizeBytes(), d.getCreatedAt());
        }
    }

    /** Full document for view/download — bytes as base64 so it rides the JSON BFF proxy. */
    public record DocumentContentView(
            Long id,
            String docType,
            String fileName,
            String contentType,
            String dataBase64) {

        public static DocumentContentView of(ApplicationDocument d) {
            return new DocumentContentView(d.getId(), d.getDocType(), d.getFileName(), d.getContentType(),
                    Base64.getEncoder().encodeToString(d.getData()));
        }
    }
}
