package com.navix.iam.dto;

/**
 * Request/response payloads for the admin-managed company payment block (the payee shown on the
 * borrower repay screen).
 */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    /**
     * The payee surfaced to clients. {@code qrUrl} / {@code accountInfoUrl} are short-lived presigned
     * GET URLs for the uploaded QR image / account-info PDF, or {@code null} when no asset is uploaded
     * (the UI then falls back to a bundled static asset). Object keys are never returned.
     */
    public record PaymentSettingsResponse(
            String upiId,
            String accountName,
            String accountNumber,
            String ifsc,
            String bankName,
            String qrUrl,
            String accountInfoUrl
    ) {
    }

    /**
     * ADMIN edit of the payee. All fields optional — only non-null fields are applied. The two
     * {@code *ObjectKey} fields carry S3 keys obtained from {@code POST /api/storage/presign-upload}
     * after the admin uploads a QR image / account-info PDF.
     */
    public record UpdatePaymentSettingsRequest(
            String upiId,
            String accountName,
            String accountNumber,
            String ifsc,
            String bankName,
            String qrObjectKey,
            String accountInfoObjectKey
    ) {
    }
}
