package com.navix.common.verification;

/**
 * Provider-neutral, categorized snapshot of a credit-bureau report — the fields DhanBoost harvests from
 * the (Experian) credit report to build the one-page credit brief and the 1–5★ recommendation rating.
 *
 * <p>Parsed inside {@code navix-verification} (the only place provider-shaped JSON is touched) and
 * carried across the {@link VerificationPort} seam as this neutral record — nothing Fintrix/Experian
 * shaped reaches the loan classpath. Monetary amounts are in <b>rupees</b> (the bureau's native unit,
 * <i>not</i> DhanBoost's internal paise) and are display-only. Any field may be {@code null} when the
 * bureau response is thin / a value was blank.
 *
 * @param name               Category A — customer name ({@code data.name})
 * @param pan                Category A — PAN ({@code data.pan})
 * @param mobile             Category A — mobile ({@code data.mobile})
 * @param dob                Category A — date of birth, normalised to {@code YYYY-MM-DD}
 * @param city               Category A — city
 * @param pin                Category A — PIN code
 * @param creditScore        Category B — bureau score ({@code data.credit_score})
 * @param totalAccounts      Category B — total credit accounts
 * @param activeAccounts     Category B — active credit accounts
 * @param closedAccounts     Category B — closed credit accounts
 * @param defaults           Category B — defaulted credit accounts
 * @param totalBalanceRupees     Category C — total outstanding balance (₹)
 * @param securedBalanceRupees   Category C — secured outstanding balance (₹)
 * @param unsecuredBalanceRupees Category C — unsecured outstanding balance (₹)
 * @param recentInquiries30d Category C — total CAPS enquiries in the last 30 days
 * @param reportNumber       bureau report number (for the brief footer/audit), optional
 */
public record BureauReportFacts(
        String name,
        String pan,
        String mobile,
        String dob,
        String city,
        String pin,
        Integer creditScore,
        Integer totalAccounts,
        Integer activeAccounts,
        Integer closedAccounts,
        Integer defaults,
        Long totalBalanceRupees,
        Long securedBalanceRupees,
        Long unsecuredBalanceRupees,
        Integer recentInquiries30d,
        String reportNumber) {
}
