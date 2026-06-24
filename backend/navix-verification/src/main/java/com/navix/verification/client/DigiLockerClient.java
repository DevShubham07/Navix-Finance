package com.navix.verification.client;

import com.navix.verification.config.FintrixClientConfig;
import com.navix.verification.dto.DigiLockerDtos.AadhaarXmlResponse;
import com.navix.verification.dto.DigiLockerDtos.DocumentResponse;
import com.navix.verification.dto.DigiLockerDtos.InitializeResponse;
import com.navix.verification.dto.DigiLockerDtos.ListDocumentsResponse;
import com.navix.verification.dto.DigiLockerDtos.StatusResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * DigiLocker partner API client (X-Client-ID / X-Client-Secret auth).
 * Drives the Aadhaar/document consent flow used during KYC.
 */
@Component
public class DigiLockerClient {

    private final RestClient digiLocker;

    public DigiLockerClient(@Qualifier(FintrixClientConfig.DIGILOCKER_CLIENT) RestClient digiLocker) {
        this.digiLocker = digiLocker;
    }

    // DEMO MOCKS: every method below returns a deterministic canned result without any network
    // call, pending live DigiLocker partner credentials. The injected {@code digiLocker}
    // RestClient is intentionally left unused for the demo.

    /**
     * Start a DigiLocker consent session; returns the redirect URL + a clientId handle.
     *
     * <p>DEMO MOCK: returns a fixed clientId + consent URL in INITIATED state.
     */
    public InitializeResponse initialize(String redirectUrl, int expiryMinutes, boolean signupFlow) {
        return new InitializeResponse(
                "DL-DEMO-CLIENT-001",
                "https://digilocker.demo/consent/DL-DEMO-CLIENT-001",
                "INITIATED");
    }

    /**
     * Poll the consent session status.
     *
     * <p>DEMO MOCK: reports the session as COMPLETED/linked.
     */
    public StatusResponse status(String clientId) {
        return new StatusResponse(clientId, "COMPLETED", Boolean.TRUE);
    }

    /**
     * List the documents the user shared in the session.
     *
     * <p>DEMO MOCK: returns a single shared Aadhaar document.
     */
    public ListDocumentsResponse listDocuments(String clientId) {
        return new ListDocumentsResponse(java.util.List.of(
                new com.navix.verification.dto.DigiLockerDtos.DocumentMeta(
                        "AADHAAR-FILE-001", "Aadhaar Card", "AADHAAR", "application/xml", "UIDAI")));
    }

    /**
     * Fetch a single shared document by fileId.
     *
     * <p>DEMO MOCK: returns a tiny base64 placeholder payload.
     */
    public DocumentResponse document(String clientId, String fileId) {
        return new DocumentResponse(
                fileId,
                "application/xml",
                java.util.Base64.getEncoder().encodeToString(
                        "<Aadhaar>demo</Aadhaar>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /**
     * Fetch + parse the Aadhaar XML for the session.
     *
     * <p>DEMO MOCK: returns parsed demographics with a masked Aadhaar reference (full number
     * is never returned).
     */
    public AadhaarXmlResponse aadhaarXml(String clientId) {
        return new AadhaarXmlResponse(
                "RAVI KUMAR",
                "1990-05-14",
                "M",
                "XXXXXXXX1234",
                null,
                new com.navix.verification.dto.DigiLockerDtos.AadhaarAddress(
                        "12", "MG ROAD", "Near Metro", "Shivajinagar",
                        "BENGALURU URBAN", "KARNATAKA", "560001", "INDIA"));
    }
}
