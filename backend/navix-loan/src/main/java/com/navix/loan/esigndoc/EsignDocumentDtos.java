package com.navix.loan.esigndoc;

import java.time.Instant;
import java.util.List;

/** Wire shapes for /api/admin/esign-documents. */
public final class EsignDocumentDtos {

    private EsignDocumentDtos() {
    }

    /** pages: All | First | Last | comma list such as "1,3"; position: TopLeft ... BottomRight. */
    public record SignerInput(String name, String email, boolean self, String pages, String position) {
    }

    public record CreateRequest(String title, String objectKey, String fileName, boolean sendEmail,
                                List<SignerInput> signers) {
    }

    public record Summary(Long id, String title, String status, int signerCount, int signedCount,
                          Instant createdAt) {
    }

    public record SignerView(Long id, int order, String name, String email, boolean self, String pages,
                             String position, String status, String signUrl, String errorMessage,
                             Instant signedAt, String emailStatus, Instant emailSentAt) {
    }

    public record Detail(Long id, String title, String fileName, int pageCount, String status,
                         String contractId, String errorMessage, Instant createdAt, Instant completedAt,
                         String originalUrl, String signedUrl, String auditCertificateUrl,
                         List<SignerView> signers) {
    }
}
