package com.navix.kyc.domain;

/**
 * Types of individual KYC checks performed within a {@code KycCase}.
 * SELFIE is captured for records only (no liveness API).
 */
public enum KycCheckType {
    PAN,
    AADHAAR,
    SELFIE,
    ADDRESS
}
