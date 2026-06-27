package com.navix.verification.client;

import static com.navix.verification.support.FintrixJson.bool;
import static com.navix.verification.support.FintrixJson.integer;
import static com.navix.verification.support.FintrixJson.post;
import static com.navix.verification.support.FintrixJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.FintrixClientConfig;
import com.navix.verification.dto.DigiLockerDtos.AadhaarXmlResponse;
import com.navix.verification.dto.DigiLockerDtos.DocumentMeta;
import com.navix.verification.dto.DigiLockerDtos.DocumentRequest;
import com.navix.verification.dto.DigiLockerDtos.DocumentResponse;
import com.navix.verification.dto.DigiLockerDtos.InitializeRequest;
import com.navix.verification.dto.DigiLockerDtos.InitializeResponse;
import com.navix.verification.dto.DigiLockerDtos.ListDocumentsResponse;
import com.navix.verification.dto.DigiLockerDtos.StatusRequest;
import com.navix.verification.dto.DigiLockerDtos.StatusResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * DigiLocker partner API client (X-Client-ID / X-Client-Secret auth). All calls are POST and carry
 * {@code client_id} in the body (except {@code initialize}, which mints the session). Drives the
 * Aadhaar/document consent flow used during KYC.
 */
@Component
public class DigiLockerClient {

    private final RestClient digiLocker;

    public DigiLockerClient(@Qualifier(FintrixClientConfig.DIGILOCKER_CLIENT) RestClient digiLocker) {
        this.digiLocker = digiLocker;
    }

    /** Start a DigiLocker consent session; returns the redirect URL + a clientId handle. */
    public InitializeResponse initialize(String redirectUrl, int expiryMinutes, boolean signupFlow) {
        JsonNode root = post(digiLocker, "digilocker_initialize",
                new InitializeRequest(redirectUrl, expiryMinutes, signupFlow, "navix kyc"));
        JsonNode data = root.path("data");
        return new InitializeResponse(
                text(root.path("transaction_id")),
                text(data.path("client_id")),
                text(data.path("token")),
                text(data.path("url")),
                integer(data.path("expiry_seconds")));
    }

    /** Poll the consent session status. */
    public StatusResponse status(String clientId) {
        JsonNode root = post(digiLocker, "digilocker_status", new StatusRequest(clientId));
        JsonNode data = root.path("data");
        return new StatusResponse(
                text(root.path("transaction_id")),
                text(data.path("status")),
                bool(data.path("completed")),
                bool(data.path("failed")),
                bool(data.path("aadhaar_linked")),
                text(data.path("error_description")));
    }

    /** List the documents the user shared in the session. */
    public ListDocumentsResponse listDocuments(String clientId) {
        JsonNode root = post(digiLocker, "digilocker_list_documents", new StatusRequest(clientId));
        List<DocumentMeta> documents = new ArrayList<>();
        for (JsonNode doc : root.path("data")) {
            documents.add(new DocumentMeta(
                    text(doc.path("file_id")),
                    text(doc.path("name")),
                    text(doc.path("doc_type")),
                    text(doc.path("file_type")),
                    bool(doc.path("downloaded")),
                    text(doc.path("issuer")),
                    text(doc.path("description"))));
        }
        return new ListDocumentsResponse(text(root.path("transaction_id")), documents);
    }

    /** Fetch a single shared document by fileId (returns a short-lived presigned download URL). */
    public DocumentResponse document(String clientId, String fileId) {
        JsonNode root = post(digiLocker, "digilocker_document",
                new DocumentRequest(clientId, fileId));
        JsonNode data = root.path("data");
        return new DocumentResponse(
                text(root.path("transaction_id")),
                text(data.path("download_url")),
                text(data.path("mime_type")));
    }

    /** Fetch + parse the Aadhaar XML for the session (returns masked demographics). */
    public AadhaarXmlResponse aadhaarXml(String clientId) {
        JsonNode root = post(digiLocker, "digilocker_aadhar_xml", new StatusRequest(clientId));
        JsonNode data = root.path("data");
        JsonNode xml = data.path("aadhaar_xml_data");
        return new AadhaarXmlResponse(
                text(root.path("transaction_id")),
                text(xml.path("full_name")),
                text(xml.path("dob")),
                text(xml.path("gender")),
                text(xml.path("masked_aadhaar")),
                text(xml.path("full_address")),
                text(xml.path("address").path("state")),
                text(xml.path("zip")),
                text(xml.path("father_name")),
                text(xml.path("profile_image")),
                text(data.path("xml_url")));
    }
}
