package com.navix.loan.dto;

import com.navix.loan.entity.Lead;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
            Instant updatedAt) {

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
                    l.getUpdatedAt());
        }
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
