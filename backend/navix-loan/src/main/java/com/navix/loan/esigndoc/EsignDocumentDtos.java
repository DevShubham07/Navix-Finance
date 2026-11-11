package com.navix.loan.esigndoc;

import java.time.Instant;
import java.util.List;

/** Wire shapes for /api/admin/esign-documents. */
public final class EsignDocumentDtos {

    private EsignDocumentDtos() {
    }

    /** A signature box: 1-based page; x/y/width/height in PDF points, origin top-left of the page as displayed. */
    public record Box(int page, double x, double y, double width, double height) {
    }

    public record SignerInput(String name, String email, boolean self, List<Box> boxes) {
    }

    public record CreateRequest(String title, String objectKey, String fileName, boolean sendEmail,
                                List<SignerInput> signers) {
    }

    public record Summary(Long id, String title, String status, int signerCount, int signedCount,
                          Instant createdAt) {
    }

    public record SignerView(Long id, int order, String name, String email, boolean self, List<Box> boxes,
                             String pages, String position, String status, String signUrl, String errorMessage,
                             Instant signedAt, String emailStatus, Instant emailSentAt) {
    }

    public record Detail(Long id, String title, String fileName, int pageCount, String status,
                         String contractId, String errorMessage, Instant createdAt, Instant completedAt,
                         String originalUrl, String signedUrl, String auditCertificateUrl,
                         List<SignerView> signers) {
    }
}
