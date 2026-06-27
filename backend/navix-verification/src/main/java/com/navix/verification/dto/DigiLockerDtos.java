package com.navix.verification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Records for the 5 DigiLocker calls (initialize, status, listDocuments, document, aadhaarXml).
 *
 * <p>Request records carry {@link JsonProperty} so they serialise to the exact wire field names.
 * Responses are parsed defensively from {@code JsonNode} in {@code DigiLockerClient}. Note that
 * {@code document} now yields a short-lived presigned download URL (not inline bytes) and
 * {@code aadhaarXml} returns flattened demographics plus the XML URL.
 */
public final class DigiLockerDtos {

    private DigiLockerDtos() {
    }

    // ---- initialize ----
    public record InitializeRequest(
            @JsonProperty("redirect_url") String redirectUrl,
            @JsonProperty("expiry_minutes") Integer expiryMinutes,
            @JsonProperty("signup_flow") Boolean signupFlow,
            @JsonProperty("remark") String remark) {
    }

    public record InitializeResponse(
            String txnId,
            String clientId,
            String token,
            String url,
            Integer expirySeconds
    ) {
    }

    // ---- status ----
    public record StatusRequest(
            @JsonProperty("client_id") String clientId) {
    }

    public record StatusResponse(
            String txnId,
            String status,
            Boolean completed,
            Boolean failed,
            Boolean aadhaarLinked,
            String errorDescription
    ) {
    }

    // ---- listDocuments ----
    public record ListDocumentsResponse(String txnId, List<DocumentMeta> documents) {
    }

    public record DocumentMeta(
            String fileId,
            String name,
            String docType,
            String fileType,
            Boolean downloaded,
            String issuer,
            String description
    ) {
    }

    // ---- document (single file) ----
    public record DocumentRequest(
            @JsonProperty("client_id") String clientId,
            @JsonProperty("file_id") String fileId) {
    }

    public record DocumentResponse(
            String txnId,
            String downloadUrl,
            String mimeType
    ) {
    }

    // ---- aadhaarXml (parsed) ----
    public record AadhaarXmlResponse(
            String txnId,
            String fullName,
            String dob,
            String gender,
            String maskedAadhaar,
            String fullAddress,
            String state,
            String pincode,
            String fatherName,
            String profileImageBase64,
            String xmlUrl
    ) {
    }
}
