package com.navix.verification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.navix.common.verification.BureauReportFacts;

/**
 * Request/response records for the Digitap verification APIs used by DhanBoost (the FALLBACK provider):
 * PAN Details Plus, Credit Analytics (Experian), Face Match, Email Verification, and Address
 * verification. Digitap's standard envelope is
 * {@code {http_response_code, result_code(101=ok), request_id, client_ref_num, result:{...}}};
 * Face-Match uses {@code {status, statusCode, result:{...}}} and Address uses {@code {code, model:{...}}}.
 *
 * <p>Request records carry {@link JsonProperty} (snake_case) so they serialise to Digitap's exact wire
 * field names. Response records are parsed defensively from {@code JsonNode} in the clients. Field/sample
 * source of truth: {@code docs/digitap/digitap-apis.json}.
 */
public final class DigitapDtos {

    private DigitapDtos() {
    }

    // ---- PAN Details Plus : /validation/kyc/v1/pan_details_plus (svc host) ----
    public record PanRequest(
            @JsonProperty("client_ref_num") String clientRefNum,
            @JsonProperty("pan") String pan) {
    }

    public record PanResponse(
            String txnId,
            Boolean valid,
            String fullName,
            String firstName,
            String lastName,
            String dob,
            String gender,
            Boolean aadhaarLinked,
            String panStatus,
            String addressState,
            String addressZip
    ) {
    }

    // ---- Credit Analytics (Experian) : /credit_analytics/request (api host) ----
    public record CreditRequest(
            @JsonProperty("client_ref_num") String clientRefNum,
            @JsonProperty("mobile_no") String mobileNo,
            @JsonProperty("name_lookup") int nameLookup,
            @JsonProperty("first_name") String firstName,
            @JsonProperty("last_name") String lastName,
            @JsonProperty("pan") String pan,
            @JsonProperty("date_of_birth") String dateOfBirth,
            @JsonProperty("consent_message") String consentMessage,
            @JsonProperty("consent_acceptance") String consentAcceptance,
            @JsonProperty("device_type") String deviceType,
            @JsonProperty("otp") String otp,
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("device_ip") String deviceIp,
            @JsonProperty("device_id") String deviceId) {
    }

    public record CreditResponse(
            String txnId,
            Integer creditScore,
            Boolean noRecord,
            BureauReportFacts facts
    ) {
    }

    // ---- Face Match : /fmfl/v2/face-match (api host) ----
    public record FaceMatchRequest(
            @JsonProperty("person") String person,
            @JsonProperty("card") String card,
            @JsonProperty("clientRefId") String clientRefId) {
    }

    public record FaceMatchResponse(
            String txnId,
            Boolean sameFace,
            Double confidence,
            Boolean personImageBlurry
    ) {
    }

    // ---- Email Verification : /cv/email_verification/v1 (svc host) ----
    public record EmailRequest(
            @JsonProperty("client_ref_num") String clientRefNum,
            @JsonProperty("email") String email,
            @JsonProperty("individual_name") String individualName,
            @JsonProperty("establishment_name") String establishmentName) {
    }

    public record EmailResponse(
            String txnId,
            Integer resultCode,
            Boolean isVerified,
            Boolean isEmailValid,
            Boolean isEstablishmentMatched,
            Boolean isIndividualMatched,
            Boolean isGenericEmail,
            String matchedEstablishment,
            Double individualScore
    ) {
    }

    // ---- Address (lat/long → address) : /ent/v1/address-verification (api host) ----
    public record AddressRequest(
            @JsonProperty("uniqueId") String uniqueId,
            @JsonProperty("latitude") String latitude,
            @JsonProperty("longitude") String longitude) {
    }

    public record AddressResponse(
            String code,
            String address,
            String pincode,
            String district,
            String state,
            String country,
            Boolean withinIndia
    ) {
    }
}
