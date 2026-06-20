package com.navix.onboarding.domain;

/**
 * The 12 ordered steps of the NAVIX borrower sign-up flow.
 * TODO: confirm exact step ordering/labels against the product user-flow doc.
 */
public enum SignupStep {
    MOBILE_OTP,
    PERSONAL_EMAIL,
    OFFICIAL_EMAIL,
    PAN_DETAILS,
    EMPLOYMENT_STATUS,
    UAN_DETAILS,
    SALARY_DECLARATION,
    SALARY_BANK,
    KYC_IDENTITY,
    DIGILOCKER_AADHAAR,
    SELFIE_CAPTURE,
    REVIEW_SUBMIT
}
