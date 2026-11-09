package com.navix.loan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** DTOs for ADMIN customer messaging and the allow-re-application action (V95). */
public final class MessagingDtos {

    private MessagingDtos() {
    }

    /** Exactly one of the two ids is set. */
    public record Target(Long applicationId, Long customerId) {
    }

    public record InputView(String name, String label, String type) {
    }

    /** {@code category} is SERVICE or MARKETING; {@code confirmText} non-null means a confirmation is required. */
    public record TemplateView(String key, String label, String category, List<String> channels,
                               List<InputView> inputs, boolean singleOnly, String confirmText) {
    }

    public record PreviewRequest(@NotNull Target target, @NotBlank String templateKey,
                                 @NotNull List<String> channels, Map<String, String> inputs) {
    }

    /** {@code text} is the rendered message where we hold the wording (stored templates and SMS), else null. */
    public record ChannelPreview(String channel, String providerTemplate, List<String> params, String skipReason,
                                 String text) {
    }

    public record PreviewView(Long customerId, String recipientName, String maskedMobile,
                              List<ChannelPreview> channels) {
    }

    public record SendRequest(@NotNull Target target, @NotBlank String templateKey,
                              @NotNull List<String> channels, Map<String, String> inputs, boolean confirmed) {
    }

    /** {@code status} is SENT, FAILED or SKIPPED; {@code messageId} is the ledger row. */
    public record ChannelResult(String channel, String status, String reason, Long messageId) {
    }

    public record SendResult(Long customerId, List<ChannelResult> results) {
    }

    /** One ledger row; the mobile is masked to its last four digits. */
    public record LogRow(Long id, Long customerId, String customerName, Long applicationId, Long loanId,
                         String templateKey, String providerTemplate, String channel, String mobile,
                         List<String> params, String status, String reason, Integer discountPercent,
                         LocalDate repayBy, String sentByName, Instant createdAt) {
    }

    public record LogPage(List<LogRow> rows, int page, int size, long total) {
    }

    // ---- Templates library (V97) ----------------------------------------------------

    /** {@code status}, {@code providerId} and {@code remarks} are honoured for SMS only. */
    public record StoredTemplateRequest(@NotBlank String channel, @NotBlank String name, String subject, String body,
                                        @NotBlank String category, String status, String providerId, String remarks,
                                        List<String> variables, String rule) {
    }

    /** {@code unsendableReason} null means it can be sent now. */
    public record StoredTemplateView(Long id, String key, String channel, String name, String subject, String body,
                                     String category, String status, String providerId, String rejectionReason,
                                     List<String> variables, String rule, String confirmText, boolean systemManaged,
                                     String unsendableReason, String createdByName, Instant createdAt,
                                     Instant updatedAt) {
    }

    public record WhatsAppRefreshResult(int updated, List<String> notFound) {
    }

    // ---- allow re-application ------------------------------------------------------

    public record ReapplyAllowanceRequest(@NotBlank String reason, @NotNull Long documentId) {
    }

    public record AllowanceView(Long id, Long customerId, String reason, Long documentId, String grantedByName, Instant grantedAt) {
    }

    /** {@code allowance} is the unused allowance, or null. */
    public record ReapplyAllowanceStatus(boolean disqualified, AllowanceView allowance) {
    }
}
