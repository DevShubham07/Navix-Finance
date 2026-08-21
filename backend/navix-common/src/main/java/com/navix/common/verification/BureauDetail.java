package com.navix.common.verification;

import java.util.List;

/**
 * The full itemised bureau detail behind {@link BureauReportFacts}'s Category B/C headline totals —
 * every tradeline, every enquiry, delinquency aggregates, and enquiry velocity.
 *
 * <p>Carried as the single 17th component of {@link BureauReportFacts} rather than flattened into it,
 * specifically so that a {@code customer_profile.credit_brief_facts} jsonb row written before this
 * field existed deserialises with {@code detail == null} instead of failing — see
 * {@link BureauReportFacts}'s class javadoc. New pulls populate it; old stored briefs simply render
 * without the detail section until the borrower's bureau data is refreshed.
 *
 * @param tradelines      every parsed {@code CAIS_Account_DETAILS} entry, capped at
 *                        {@code ExperianFactsParser.MAX_TRADELINES} (543 is the largest real file seen)
 * @param tradelineCount  the true tradeline count in the source report, even when the list above was
 *                        capped — so a capped brief can say "showing 543 of N" rather than silently
 *                        under-report
 * @param enquiries       every parsed {@code CAPS.CAPS_Application_Details} entry
 * @param delinquency     cross-tradeline delinquency aggregates
 * @param enquiryVelocity 7/30/90/180-day enquiry counts
 */
public record BureauDetail(
        List<BureauTradeline> tradelines,
        Integer tradelineCount,
        List<BureauEnquiry> enquiries,
        BureauDelinquency delinquency,
        BureauEnquiryVelocity enquiryVelocity) {
}
