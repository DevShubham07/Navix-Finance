package com.navix.loan.dto;

import com.navix.loan.entity.Lead;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Request/response shapes for the telecaller leads API. */
public final class LeadDtos {

    private LeadDtos() {
    }

    public record CreateLeadRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Pattern(regexp = "^[0-9]{10}$") String mobile,
            @Size(max = 160) String email,
            @Size(max = 120) String city,
            @Size(max = 160) String employer,
            Long monthlySalaryPaise,
            Long loanAmountInterestedPaise,
            String source,
            @Size(max = 240) String sourceDetail,
            String notes) {
    }

    public record UpdateLeadRequest(
            @Size(max = 160) String name,
            @Pattern(regexp = "^[0-9]{10}$") String mobile,
            @Size(max = 160) String email,
            @Size(max = 120) String city,
            @Size(max = 160) String employer,
            Long monthlySalaryPaise,
            Long loanAmountInterestedPaise,
            String source,
            @Size(max = 240) String sourceDetail,
            String notes) {
    }

    public record DispositionRequest(
            @NotBlank String callStatus,
            @Min(1) @Max(5) Integer qualityRating,
            String remarks) {
    }

    public record LeadView(
            Long id,
            String name,
            String mobile,
            String email,
            String city,
            String employer,
            Long monthlySalaryPaise,
            Long loanAmountInterestedPaise,
            String source,
            String sourceDetail,
            String callStatus,
            Integer qualityRating,
            String notes,
            String remarks,
            Long createdByStaffId,
            String createdByStaffName,
            Instant createdAt,
            Instant updatedAt,
            String pincode) {

        public static LeadView of(Lead l, String staffName) {
            return new LeadView(
                    l.getId(),
                    l.getName(),
                    l.getMobile(),
                    l.getEmail(),
                    l.getCity(),
                    l.getEmployer(),
                    l.getMonthlySalaryPaise(),
                    l.getLoanAmountInterestedPaise(),
                    l.getSource(),
                    l.getSourceDetail(),
                    l.getCallStatus(),
                    l.getQualityRating(),
                    l.getNotes(),
                    l.getRemarks(),
                    l.getCreatedByStaffId(),
                    staffName,
                    l.getCreatedAt(),
                    l.getUpdatedAt(),
                    l.getPincode());
        }
    }

    /** One parsed row of an uploaded lead list, straight from {@code LeadFileParser}. */
    public record ImportRow(String name, String mobile, String pan, String pincode, String email) {
    }

    public record ImportIssue(int row, String field, String message) {
    }

    /**
     * Start an import of an already-uploaded file. The bytes went browser -> S3 directly (presigned
     * PUT), so this carries only the key — the request body stays small no matter how big the list.
     */
    public record ImportFileRequest(
            @NotBlank @Size(max = 512) String s3Key,
            @NotBlank @Size(max = 200) String fileName,
            boolean merge) {
    }

    /**
     * An import's live state. {@code issues} is capped at the first 50 bad rows; {@code issueCount}
     * is the true total.
     */
    public record ImportJobView(
            Long id,
            String status,
            String fileName,
            boolean merge,
            Integer totalRows,
            int processedRows,
            int insertedCount,
            int mergedCount,
            int skippedDuplicates,
            int skippedCustomers,
            int issueCount,
            List<ImportIssue> issues,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt) {
    }

    public record StatusCount(String status, long count) {
    }

    public record SourceCount(String source, long count) {
    }

    public record RatingCount(int rating, long count) {
    }

    public record DayCount(String date, long created, long called) {
    }

    public record StaffCount(Long staffId, String staffName, long count) {
    }

    public record LeadStats(
            long total,
            List<StatusCount> byCallStatus,
            List<SourceCount> bySource,
            List<RatingCount> byQualityRating,
            long unratedCount,
            List<DayCount> byDay,
            List<StaffCount> byStaff,
            Double avgQualityRating) {
    }
}
