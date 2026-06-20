package com.navix.verification.dto;

import java.util.List;

/**
 * Request/response records for the 6 Fintrix (Digitap-backed) endpoints used by NAVIX:
 * PAN comprehensive, EPFO email verification, address (lat/lng) verification,
 * Experian bureau pull, CRIF bureau pull, and penny-drop bank verification.
 *
 * <p>Field names mirror the real Fintrix API shapes; refine when wiring real calls.
 */
public final class FintrixDtos {

    private FintrixDtos() {
    }

    // ---- PAN comprehensive ----
    public record PanRequest(String pan) {
    }

    public record PanResponse(
            String status,
            String fullName,
            String dob,
            String gender,
            Boolean aadhaarLinked,
            String maskedAadhaar,
            String address
    ) {
    }

    // ---- Email / EPFO employer verification ----
    public record EmailVerificationRequest(String email, String name, String establishment) {
    }

    public record EmailVerificationResponse(
            Boolean isVerified,
            Boolean isEmailValid,
            Boolean isEstablishmentMatched,
            Boolean isIndividualMatched
    ) {
    }

    // ---- Address (geo lat/lng) verification ----
    public record AddressVerificationRequest(Double lat, Double lng) {
    }

    public record AddressVerificationResponse(
            String address,
            String pincode,
            String district,
            String state,
            String country,
            Boolean withinIndia
    ) {
    }

    // ---- Experian bureau pull (PRIMARY) ----
    public record ExperianRequest(String pan, String name, String mobile) {
    }

    public record ExperianResponse(
            Integer creditScore,
            List<Tradeline> tradelines,
            String message
    ) {
    }

    public record Tradeline(
            String accountType,
            String lender,
            Double balance,
            Double overdueAmount,
            String status
    ) {
    }

    // ---- CRIF bureau pull (FALLBACK) ----
    public record CrifRequest(String pan, String name, String mobile) {
    }

    public record CrifResponse(
            Integer score,
            CrifAccountsSummary accountsSummary,
            String message
    ) {
    }

    public record CrifAccountsSummary(
            Integer totalAccounts,
            Integer activeAccounts,
            Integer overdueAccounts,
            Double totalBalance,
            Integer enquiriesLast6m
    ) {
    }

    // ---- Penny-drop bank account verification ----
    public record PennyDropRequest(String accountNumber, String ifsc) {
    }

    public record PennyDropResponse(
            String status,
            Boolean accountExists,
            String fullName,
            IfscDetails ifscDetails
    ) {
    }

    public record IfscDetails(
            String bank,
            String branch,
            String city,
            String state
    ) {
    }
}
