package com.navix.loan.dto;

import com.navix.loan.domain.DsaCommissionStatus;
import com.navix.loan.entity.DsaCommission;
import com.navix.loan.entity.Lead;
import com.navix.loan.entity.LeadOutreach;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Request/response shapes for the DSA portal ({@code /api/dsa/**}) and its ADMIN administration
 * ({@code /api/admin/dsa/**}). {@link DsaLeadView} is the security-critical one: it echoes back
 * ONLY the fields the DSA typed plus a coarse conversion status and (once disbursed) the net
 * disbursed amount + commission — nothing read out of KYC/bureau/documents/application internals.
 */
public final class DsaDtos {

    private DsaDtos() {
    }

    /** PAN format mirrors the DB check (V55): 5 letters, 4 digits, 1 letter. */
    private static final String PAN_REGEX = "^[A-Z]{5}[0-9]{4}[A-Z]$";

    public record CreateDsaLeadRequest(
            @NotBlank @Pattern(regexp = PAN_REGEX, message = "pan must be a valid PAN, e.g. ABCDE1234F") String pan,
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Pattern(regexp = "^[0-9]{10}$") String mobile,
            @Size(max = 160) String email,
            @Size(max = 120) String city,
            @Size(max = 160) String employer,
            Long monthlySalaryPaise,
            Long loanAmountInterestedPaise,
            String notes) {
    }

    public record UpdateDsaLeadRequest(
            @Size(max = 160) String name,
            @Pattern(regexp = "^[0-9]{10}$") String mobile,
            @Size(max = 160) String email,
            @Size(max = 120) String city,
            @Size(max = 160) String employer,
            Long monthlySalaryPaise,
            Long loanAmountInterestedPaise,
            String notes) {
    }

    public record OutreachRequest(
            @NotBlank String channel,
            @Size(max = 240) String subject,
            String body) {
    }

    public record OutreachResultView(String channel, String status, String error) {
    }

    /**
     * Live conversion status of a DSA's lead, resolved by {@code DsaAttributionService} — never
     * persisted. Deliberately coarse: DECLINED never says why.
     */
    public enum DsaLeadStatus {
        NOT_APPLIED, APPLIED, IN_PROGRESS, DISBURSED, REPAID, DECLINED
    }

    /**
     * Everything a DSA may see about their own lead: the fields they typed, a coarse conversion
     * status, and — once disbursed — the net disbursed amount + their commission. No PII/KYC field
     * that the DSA themselves did not type is ever present here.
     */
    public record DsaLeadView(
            Long id,
            String pan,
            String name,
            String mobile,
            String email,
            String city,
            String employer,
            Long monthlySalaryPaise,
            Long loanAmountInterestedPaise,
            String notes,
            DsaLeadStatus status,
            Long netDisbursedPaise,
            Long commissionPaise,
            /** NEW | OUTREACHED | REJECTED, or CONFIRMED once an application is attributed (V70). */
            String leadOutcome,
            /** The one staff note written FOR this DSA. Never {@code remarks}, which stays internal. */
            String dsaNote,
            /**
             * True when this row came from a file the DSA uploaded rather than one they entered.
             * Uploaded rows are deliberately unattributed, so they never earn commission — the flag
             * exists so the portal can say that plainly instead of just showing a blank amount.
             */
            boolean uploaded,
            Instant createdAt,
            Instant updatedAt) {

        public static DsaLeadView of(Lead l, DsaLeadStatus status, Long netDisbursedPaise,
                Long commissionPaise, String effectiveOutcome) {
            return new DsaLeadView(
                    l.getId(), l.getPan(), l.getName(), l.getMobile(), l.getEmail(), l.getCity(),
                    l.getEmployer(), l.getMonthlySalaryPaise(), l.getLoanAmountInterestedPaise(),
                    l.getNotes(), status, netDisbursedPaise, commissionPaise,
                    effectiveOutcome, l.getDsaNote(), l.getOwnerDsaId() == null,
                    l.getCreatedAt(), l.getUpdatedAt());
        }
    }

    public record DsaCommissionView(
            Long id,
            Long leadId,
            String pan,
            String name,
            String mobile,
            Long netDisbursedPaise,
            int rateBps,
            long amountPaise,
            DsaCommissionStatus status,
            Instant accruedAt,
            Instant payableAt,
            Instant paidAt) {

        public static DsaCommissionView of(DsaCommission c, Lead l) {
            return new DsaCommissionView(
                    c.getId(), c.getLeadId(), l != null ? l.getPan() : null,
                    l != null ? l.getName() : null, l != null ? l.getMobile() : null,
                    c.getNetDisbursedPaise(), c.getRateBps(), c.getAmountPaise(), c.getStatus(),
                    c.getAccruedAt(), c.getPayableAt(), c.getPaidAt());
        }
    }

    public record DsaEarningsSummary(
            long leadsAdded,
            long leadsConverted,
            long accruedPaise,
            long payablePaise,
            long paidPaise) {
    }

    // ---- admin views ----------------------------------------------------------------

    public record AdminDsaRosterView(
            Long dsaStaffId,
            String name,
            boolean active,
            long leadsAdded,
            long leadsConverted,
            long accruedPaise,
            long payablePaise,
            long paidPaise) {
    }

    /** Full detail — ADMIN sees everything a telecaller lead would show, plus PAN + ownership. */
    public record AdminDsaLeadView(
            Long id,
            String pan,
            String name,
            String mobile,
            String email,
            String city,
            String employer,
            Long monthlySalaryPaise,
            Long loanAmountInterestedPaise,
            String notes,
            Long ownerDsaId,
            String ownerDsaName,
            /** Who put the row here — the entering DSA, or the staff member who uploaded the file. */
            Long createdByStaffId,
            String createdByStaffName,
            /** False for an imported row: visible to its uploader, but never commission-eligible. */
            boolean owned,
            String callStatus,
            /** NEW | OUTREACHED | REJECTED, or CONFIRMED once an application is attributed (V70). */
            String leadOutcome,
            String dsaNote,
            Instant createdAt,
            Instant updatedAt) {

        public static AdminDsaLeadView of(Lead l, String ownerDsaName, String createdByStaffName,
                String effectiveOutcome) {
            return new AdminDsaLeadView(
                    l.getId(), l.getPan(), l.getName(), l.getMobile(), l.getEmail(), l.getCity(),
                    l.getEmployer(), l.getMonthlySalaryPaise(), l.getLoanAmountInterestedPaise(),
                    l.getNotes(), l.getOwnerDsaId(), ownerDsaName,
                    l.getCreatedByStaffId(), createdByStaffName, l.getOwnerDsaId() != null,
                    l.getCallStatus(), effectiveOutcome, l.getDsaNote(),
                    l.getCreatedAt(), l.getUpdatedAt());
        }
    }

    public record AdminDsaCommissionView(
            Long id,
            Long dsaStaffId,
            String dsaName,
            Long leadId,
            String pan,
            Long customerId,
            Long applicationId,
            Long loanId,
            long netDisbursedPaise,
            int rateBps,
            long amountPaise,
            DsaCommissionStatus status,
            Instant accruedAt,
            Instant payableAt,
            Instant paidAt,
            String txnRef,
            String paidBy,
            String voidReason,
            Instant voidedAt) {

        public static AdminDsaCommissionView of(DsaCommission c, String dsaName, String pan) {
            return new AdminDsaCommissionView(
                    c.getId(), c.getDsaStaffId(), dsaName, c.getLeadId(), pan, c.getCustomerId(),
                    c.getApplicationId(), c.getLoanId(), c.getNetDisbursedPaise(), c.getRateBps(),
                    c.getAmountPaise(), c.getStatus(), c.getAccruedAt(), c.getPayableAt(),
                    c.getPaidAt(), c.getTxnRef(), c.getPaidBy(), c.getVoidReason(), c.getVoidedAt());
        }
    }

    public record AdminOutreachView(
            Long id,
            Long leadId,
            Long dsaStaffId,
            String channel,
            String address,
            String subject,
            String body,
            String status,
            String providerRef,
            String error,
            Instant createdAt) {

        public static AdminOutreachView of(LeadOutreach o) {
            return new AdminOutreachView(
                    o.getId(), o.getLeadId(), o.getDsaStaffId(), o.getChannel(), o.getAddress(),
                    o.getSubject(), o.getBody(), o.getStatus(), o.getProviderRef(), o.getError(),
                    o.getCreatedAt());
        }
    }

    public record PayCommissionRequest(@NotBlank String txnRef) {
    }

    public record VoidCommissionRequest(@NotBlank String reason) {
    }

    public record ReassignCommissionRequest(Long toDsaStaffId, @NotBlank String reason) {
    }

    public record CreateCommissionRequest(Long dsaId, Long leadId) {
    }

    public record AdminUpdateLeadRequest(
            @Pattern(regexp = PAN_REGEX) String pan,
            Long ownerDsaId,
            @Size(max = 160) String name,
            @Pattern(regexp = "^[0-9]{10}$") String mobile,
            @Size(max = 160) String email) {
    }

    public record CommissionEventView(
            Long id,
            Long commissionId,
            String action,
            Long fromDsaStaffId,
            Long toDsaStaffId,
            Long actorId,
            String notes,
            Instant at) {
    }

    public record LeadListResult(List<DsaLeadView> leads) {
    }
}
