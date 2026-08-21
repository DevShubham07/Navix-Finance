package com.navix.common.verification;

/**
 * One credit-enquiry ({@code CAPS.CAPS_Application_Details}) entry from the bureau report.
 *
 * @param requestedOn           {@code Date}, normalised to {@code YYYY-MM-DD}
 * @param subscriber             {@code Subscriber_Name}
 * @param reasonCode              raw {@code Enquiry_Reason} code; label via {@code BureauCodes.enquiryReason}
 * @param amountFinancedRupees   {@code Amount_Financed} — present in ~85% of real enquiries (424/498)
 * @param durationMonths          {@code Duration_Of_Agreement} in months, when present
 */
public record BureauEnquiry(
        String requestedOn,
        String subscriber,
        String reasonCode,
        Long amountFinancedRupees,
        Integer durationMonths) {
}
