package com.navix.verification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.navix.common.verification.BureauReportFacts;
import java.util.List;

/**
 * Request/response records for the Signzy verification APIs used by DhanBoost (the PRIMARY provider):
 * Hybrid Bank Account Verification (penny-drop), PAN 206AB compliance, Experian-Lite + CRIF bureau,
 * Liveness Secure (createUrl + getData), and DigiLocker (createUrl + get-eAadhaar-with-XML).
 *
 * <p>Request records carry {@link JsonProperty} so they serialise to Signzy's exact (camelCase) wire
 * field names. Response records are parsed defensively from {@code JsonNode} in the clients (every
 * field nullable). Field/sample source of truth: {@code docs/signzy/signzy-apis.json}.
 */
public final class SignzyDtos {

    private SignzyDtos() {
    }

    // ---- Penny-drop bank account verification : /api/v3/bankaccountverification/pennydrop-v1 ----
    public record BankVerificationRequest(
            @JsonProperty("beneficiaryAccount") String beneficiaryAccount,
            @JsonProperty("beneficiaryIFSC") String beneficiaryIFSC,
            @JsonProperty("beneficiaryName") String beneficiaryName,
            @JsonProperty("nameFuzzy") String nameFuzzy) {
    }

    public record BankVerificationResponse(
            String txnId,
            Boolean active,
            String reason,
            String nameMatch,
            String beneName,
            String beneIfsc,
            String bankRrn
    ) {
    }

    // ---- PAN 206AB compliance : /api/v3/pan/compliance-206-individual-search ----
    // maskedName="false" makes Signzy return the full name in the `unMaskedName` field (alongside the
    // still-masked `entityName`).
    public record PanRequest(
            @JsonProperty("panNumber") String panNumber,
            @JsonProperty("maskedName") String maskedName) {
    }

    public record PanResponse(
            String txnId,
            String number,
            String entityName,
            String unMaskedName,
            String panAllotmentDate,
            String panAadhaarLinkStatus,
            Boolean compliant,
            String isSpecified,
            String panStatus
    ) {
    }

    // ---- Reverse geocoding (address) : /api/v3/geocoding/reverse-geocode ----
    public record GeocodeRequest(
            @JsonProperty("latitude") String latitude,
            @JsonProperty("longitude") String longitude,
            @JsonProperty("pincodePriority") boolean pincodePriority,
            @JsonProperty("districtRequired") boolean districtRequired,
            @JsonProperty("blockRequired") boolean blockRequired) {
    }

    public record GeocodeResponse(
            String address,
            String city,
            String state,
            String stateCode,
            String zipcode,
            String countryCode,
            Double confidenceScore
    ) {
    }

    // ---- Consent block shared by the bureau calls ----
    // consentTimestamp MUST serialise as a JSON number (epoch millis) — Signzy 400s on a string.
    public record Consent(
            @JsonProperty("consentFlag") boolean consentFlag,
            @JsonProperty("consentTimestamp") long consentTimestamp,
            @JsonProperty("consentIpAddress") String consentIpAddress,
            @JsonProperty("consentMessageId") String consentMessageId) {
    }

    // ---- Experian Lite bureau : /api/v3/bureau/experian-lite ----
    public record ExperianRequest(
            @JsonProperty("phoneNumber") String phoneNumber,
            @JsonProperty("pan") String pan,
            @JsonProperty("firstName") String firstName,
            @JsonProperty("lastName") String lastName,
            @JsonProperty("dateOfBirth") String dateOfBirth,
            @JsonProperty("consent") Consent consent) {
    }

    public record ExperianResponse(
            String txnId,
            Integer creditScore,
            Boolean noRecord,
            BureauReportFacts facts
    ) {
    }

    // ---- CRIF bureau (fallback) : /api/v3/bureau/crif ----
    public record CrifRequest(
            @JsonProperty("phoneNumber") String phoneNumber,
            @JsonProperty("pan") String pan,
            @JsonProperty("firstName") String firstName,
            @JsonProperty("lastName") String lastName,
            @JsonProperty("dateOfBirth") String dateOfBirth,
            @JsonProperty("gender") String gender,
            @JsonProperty("address") String address,
            @JsonProperty("pincode") String pincode,
            @JsonProperty("consent") Consent consent) {
    }

    public record CrifResponse(
            String txnId,
            Integer score,
            Boolean noRecord
    ) {
    }

    // ---- Liveness Secure : /api/v3/liveness-secure/createUrl + /getData ----
    public record LivenessCreateUrlRequest(
            @JsonProperty("matchImage") List<String> matchImage,
            @JsonProperty("faceMatchThreshold") Double faceMatchThreshold) {
    }

    public record LivenessSession(
            String token,
            String consumerId,
            String videoUrl
    ) {
    }

    public record LivenessGetDataRequest(
            @JsonProperty("token") String token) {
    }

    public record LivenessResult(
            String txnId,
            Boolean live,
            Double livenessScore,
            Boolean faceVerified,
            Boolean overallStatus
    ) {
    }

    // ---- DigiLocker createUrl : /api/v3/digilocker/createUrl ----
    public record DigiLockerCreateUrlRequest(
            @JsonProperty("signup") boolean signup,
            @JsonProperty("callbackUrl") String callbackUrl,
            @JsonProperty("successRedirectUrl") String successRedirectUrl,
            @JsonProperty("docType") List<String> docType,
            @JsonProperty("purpose") String purpose,
            @JsonProperty("getScope") boolean getScope,
            @JsonProperty("internalId") String internalId,
            @JsonProperty("getEAadhaarPdf") boolean getEAadhaarPdf,
            @JsonProperty("getEAadhaarJpeg") boolean getEAadhaarJpeg) {
    }

    public record DigiLockerSession(
            String txnId,
            String requestId,
            String url
    ) {
    }

    // ---- DigiLocker get e-Aadhaar with XML : /api/v3/digilocker/geteaadhaarwithxml ----
    public record GetEAadhaarRequest(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("getEAadhaarPdf") boolean getEAadhaarPdf,
            @JsonProperty("getEAadhaarJpeg") boolean getEAadhaarJpeg) {
    }

    public record AadhaarResponse(
            String txnId,
            String fullName,
            String maskedUid,
            String dob,
            String gender,
            Boolean validDsc,
            String fullAddress,
            String state,
            String pincode,
            String photoUrl,
            String xmlUrl
    ) {
    }
}
