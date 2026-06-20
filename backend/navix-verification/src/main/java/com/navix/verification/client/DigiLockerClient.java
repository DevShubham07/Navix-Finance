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

    /**
     * Start a DigiLocker consent session; returns the redirect URL + a clientId handle.
     * TODO: POST redirectUrl/expiryMinutes/signupFlow to the DigiLocker initialize endpoint.
     */
    public InitializeResponse initialize(String redirectUrl, int expiryMinutes, boolean signupFlow) {
        throw new UnsupportedOperationException("TODO: call DigiLocker initialize endpoint");
    }

    /**
     * Poll the consent session status.
     * TODO: GET the DigiLocker status endpoint for the given session clientId.
     */
    public StatusResponse status(String clientId) {
        throw new UnsupportedOperationException("TODO: call DigiLocker status endpoint");
    }

    /**
     * List the documents the user shared in the session.
     * TODO: GET the DigiLocker list-documents endpoint.
     */
    public ListDocumentsResponse listDocuments(String clientId) {
        throw new UnsupportedOperationException("TODO: call DigiLocker list-documents endpoint");
    }

    /**
     * Fetch a single shared document by fileId.
     * TODO: GET the DigiLocker document endpoint.
     */
    public DocumentResponse document(String clientId, String fileId) {
        throw new UnsupportedOperationException("TODO: call DigiLocker document endpoint");
    }

    /**
     * Fetch + parse the Aadhaar XML for the session.
     * TODO: GET the DigiLocker Aadhaar XML endpoint and parse into AadhaarXmlResponse.
     */
    public AadhaarXmlResponse aadhaarXml(String clientId) {
        throw new UnsupportedOperationException("TODO: call DigiLocker Aadhaar XML endpoint");
    }
}
