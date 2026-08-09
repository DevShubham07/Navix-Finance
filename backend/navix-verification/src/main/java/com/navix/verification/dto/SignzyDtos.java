package com.navix.verification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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
            String bankRrn,
            String bankName
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

    // ---- Email verification V2 (PROD account) : /api/v3/email/verificationV2 ----
    // Only takes the email id; returns deliverability (valid/status/mx/smtp) + optional person/company
    // enrichment. Booleans arrive as JSON strings ("true"/"false") — decode with ProviderJson.bool.
    public record EmailV2Request(@JsonProperty("emailId") String emailId) {
    }

    public record EmailV2Response(
            String emailId,
            Boolean validEmail,
            String status,
            Boolean freeEmail,
            String didYouMean,
            String domain,
            Boolean mxFound,
            String mxRecord,
            String smtpProvider,
            String personName,
            String companyName
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
            BureauReportFacts facts,
            String rawResponseJson
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
            Boolean noRecord,
            String rawResponseJson
    ) {
    }

    // ---- Liveness Secure : /api/v3/liveness-secure/createUrl + /getData ----
    // matchImage MUST be omitted (not null) when there's no reference photo — Signzy 400s on
    // `"matchImage":null` ("must be an array"), so @JsonInclude(NON_NULL) drops it for liveness-only.
    public record LivenessCreateUrlRequest(
            @JsonProperty("matchImage")
            @JsonInclude(JsonInclude.Include.NON_NULL) List<String> matchImage,
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
            boolean completed,
            Boolean live,
            Double livenessScore,
            Boolean faceVerified,
            String matchPercentage,
            String capturedImage,
            Boolean overallStatus
    ) {
        /** The borrower has not finished the video journey yet (Signzy getData → 404 "not completed"). */
        public static LivenessResult pending(String token) {
            return new LivenessResult(token, false, null, null, null, null, null, null);
        }
    }

    // ---- DigiLocker createUrl : /api/v3/digilocker-v2/createUrl ----
    // v2 takes a real redirectUrl (v1 had to smuggle it through internalId) and adds
    // pinlessAuthentication (skips the DigiLocker security PIN) + shortenUrl.
    public record DigiLockerCreateUrlRequest(
            @JsonProperty("signup") boolean signup,
            @JsonProperty("pinlessAuthentication") boolean pinlessAuthentication,
            @JsonProperty("redirectUrl") String redirectUrl,
            @JsonProperty("callbackUrl") String callbackUrl,
            @JsonProperty("successRedirectUrl") String successRedirectUrl,
            @JsonProperty("failureRedirectUrl") String failureRedirectUrl,
            @JsonProperty("purpose") String purpose,
            @JsonProperty("getScope") boolean getScope,
            @JsonProperty("internalId") String internalId,
            @JsonProperty("shortenUrl") boolean shortenUrl,
            @JsonProperty("getEAadhaarPdf") boolean getEAadhaarPdf,
            @JsonProperty("getEAadhaarJpeg") boolean getEAadhaarJpeg) {
    }

    public record DigiLockerSession(
            String txnId,
            String requestId,
            String url
    ) {
    }

    // ---- DigiLocker get e-Aadhaar : /api/v3/digilocker-v2/geteAadhaar ----
    // extraDigitalCertificateParams asks for the x509Data block carrying validAadhaarDSC (the PASS gate).
    public record GetEAadhaarRequest(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("extraDigitalCertificateParams") boolean extraDigitalCertificateParams,
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
            String dscSubject,
            String fullAddress,
            String state,
            String district,
            String city,
            String pincode,
            String country,
            String addressLine,
            String landmark,
            String photoUrl,
            String pdfUrl,
            String jpegUrl,
            String xmlUrl
    ) {
    }
}
