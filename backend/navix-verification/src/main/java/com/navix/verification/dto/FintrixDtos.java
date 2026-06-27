package com.navix.verification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request/response records for the 7 Fintrix (Digitap-backed) endpoints used by NAVIX:
 * PAN comprehensive, EPFO email verification, address (lat/lng) verification, Experian bureau pull,
 * CRIF bureau pull, penny-drop bank verification, and VKYC face-liveness.
 *
 * <p>Request records carry {@link JsonProperty} so they serialise to the exact wire field names.
 * Responses are parsed defensively from {@code JsonNode} in the clients (every response field is a
 * nullable object type so a missing/blank value maps to {@code null} rather than throwing).
 */
public final class FintrixDtos {

    private FintrixDtos() {
    }

    // ---- PAN comprehensive ----
    public record PanRequest(
            @JsonProperty("id_number") String idNumber,
            @JsonProperty("remark") String remark) {
    }

    public record PanResponse(
            String txnId,
            String status,
            String fullName,
            String firstName,
            String middleName,
            String lastName,
            String dob,
            String gender,
            String category,
            Boolean aadhaarLinked,
            String maskedAadhaar,
            String phoneMasked,
            String emailMasked,
            String panNumber,
            String addressFull,
            String addressState,
            String addressZip
    ) {
    }

    // ---- Email / EPFO employer verification ----
    public record EmailVerificationRequest(
            @JsonProperty("email") String email,
            @JsonProperty("client_ref_num") String clientRefNum,
            @JsonProperty("individual_name") String individualName,
            @JsonProperty("establishment_name") String establishmentName) {
    }

    public record EmailVerificationResponse(
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

    // ---- Address (geo lat/lng) verification ----
    public record AddressVerificationRequest(
            @JsonProperty("latitude") String latitude,
            @JsonProperty("longitude") String longitude,
            @JsonProperty("uniqueId") String uniqueId) {
    }

    public record AddressVerificationResponse(
            String code,
            String address,
            String pincode,
            String district,
            String state,
            String country,
            Boolean withinIndia
    ) {
    }

    // ---- Experian bureau pull (PRIMARY) ----
    public record ExperianRequest(
            @JsonProperty("pan") String pan,
            @JsonProperty("name") String name,
            @JsonProperty("mobile") String mobile,
            @JsonProperty("consent") String consent,
            @JsonProperty("remark") String remark) {
    }

    public record ExperianResponse(
            String txnId,
            String status,
            Integer creditScore,
            Boolean noRecord,
            String message
    ) {
    }

    // ---- CRIF bureau pull (FALLBACK) ----
    public record CrifRequest(
            @JsonProperty("pan_number") String panNumber,
            @JsonProperty("first_name") String firstName,
            @JsonProperty("mobile_number") String mobileNumber,
            @JsonProperty("dob") String dob,
            @JsonProperty("consent") String consent,
            @JsonProperty("remark") String remark) {
    }

    public record CrifResponse(
            String txnId,
            String status,
            String headerStatus,
            Integer score,
            Integer activeAccounts,
            Integer overdueAccounts,
            Double totalBalance,
            Integer enquiriesLast6m
    ) {
    }

    // ---- Penny-drop bank account verification ----
    public record PennyDropRequest(
            @JsonProperty("account_number") String accountNumber,
            @JsonProperty("ifsc") String ifsc,
            @JsonProperty("ifsc_details") boolean ifscDetails,
            @JsonProperty("remark") String remark) {
    }

    public record PennyDropResponse(
            String txnId,
            Boolean status,
            Boolean accountExists,
            String fullName,
            IfscDetails ifscDetails
    ) {
    }

    public record IfscDetails(
            String bank,
            String branch,
            String city,
            String state,
            String ifsc
    ) {
    }

    // ---- VKYC face liveness ----
    public record FaceLivenessRequest(
            @JsonProperty("p_image") String pImage) {
    }

    public record FaceLivenessResponse(
            String txnId,
            Boolean isLive,
            Double livenessConfidence,
            Boolean personImageCorrectlyIdentified,
            Boolean multipleFaceDetected,
            Boolean isFaceOccluded
    ) {
    }
}
