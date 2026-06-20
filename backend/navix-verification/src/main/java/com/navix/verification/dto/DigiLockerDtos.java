package com.navix.verification.dto;

import java.util.List;

/**
 * Records for the 5 DigiLocker calls (initialize, status, listDocuments, document, aadhaarXml)
 * plus the parsed Aadhaar XML structure.
 *
 * <p>Field names mirror the DigiLocker partner API; refine when wiring real calls.
 */
public final class DigiLockerDtos {

    private DigiLockerDtos() {
    }

    // ---- initialize ----
    public record InitializeRequest(String redirectUrl, Integer expiryMinutes, Boolean signupFlow) {
    }

    public record InitializeResponse(String clientId, String url, String status) {
    }

    // ---- status ----
    public record StatusResponse(String clientId, String status, Boolean completed) {
    }

    // ---- listDocuments ----
    public record ListDocumentsResponse(List<DocumentMeta> documents) {
    }

    public record DocumentMeta(
            String fileId,
            String name,
            String type,
            String mime,
            String issuer
    ) {
    }

    // ---- document (single file) ----
    public record DocumentResponse(
            String fileId,
            String mime,
            String base64Content
    ) {
    }

    // ---- aadhaarXml (parsed) ----
    public record AadhaarXmlResponse(
            String name,
            String dob,
            String gender,
            String maskedAadhaar,
            String photoBase64,
            AadhaarAddress address
    ) {
    }

    public record AadhaarAddress(
            String house,
            String street,
            String landmark,
            String locality,
            String district,
            String state,
            String pincode,
            String country
    ) {
    }
}
