package com.navix.common.verification;

/**
 * Enquiry-velocity counts from {@code TotalCAPS_Summary}. Today the raw Experian report only reliably
 * carries {@code TotalCAPSLast30Days} in production (mirrored on {@link BureauReportFacts#recentInquiries30d()}
 * for backward compatibility) — the 7/90/180-day windows are parsed defensively from the same summary
 * node for when/if the bureau starts populating them, and are {@code null} (not {@code 0}) until then.
 *
 * @param last7   {@code TotalCAPSLast7Days}
 * @param last30  {@code TotalCAPSLast30Days}
 * @param last90  {@code TotalCAPSLast90Days}
 * @param last180 {@code TotalCAPSLast180Days}
 */
public record BureauEnquiryVelocity(
        Integer last7,
        Integer last30,
        Integer last90,
        Integer last180) {
}
