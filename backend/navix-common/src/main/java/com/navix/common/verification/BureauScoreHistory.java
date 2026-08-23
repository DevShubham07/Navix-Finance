package com.navix.common.verification;

import java.util.List;

/**
 * Score-trend + credit-age detail from a CRIF Highmark report's {@code TRENDS} block and the
 * {@code ACCOUNTS-SUMMARY.DERIVED-ATTRIBUTES} credit-age/velocity figures. Experian responses carry no
 * equivalent trend series, so this is populated only for CRIF-sourced facts — {@code null} on an
 * Experian-sourced {@link BureauDetail} (and on any brief stored before this field existed).
 *
 * @param points                       the score trend, one point per reporting period, <b>newest
 *                                     first</b> (matches the vendor's {@code TRENDS.DATES}/{@code VALUES}
 *                                     ordering — 12 quarters in the probed report)
 * @param lengthOfCreditHistoryMonths  {@code LENGTH-OF-CREDIT-HISTORY-YEAR}*12 + {@code -MONTH}
 * @param averageAccountAgeMonths      {@code AVERAGE-ACCOUNT-AGE-YEAR}*12 + {@code -MONTH}
 * @param newAccountsLast6m            {@code NEW-ACCOUNTS-IN-LAST-SIX-MONTHS}
 * @param newDelinquentAccountsLast6m  {@code NEW-DELINQ-ACCOUNT-IN-LAST-SIX-MONTHS}
 * @param inquiriesLast6m              {@code INQURIES-IN-LAST-SIX-MONTHS} (vendor's own spelling)
 */
public record BureauScoreHistory(
        List<Point> points,
        Integer lengthOfCreditHistoryMonths,
        Integer averageAccountAgeMonths,
        Integer newAccountsLast6m,
        Integer newDelinquentAccountsLast6m,
        Integer inquiriesLast6m) {

    /**
     * One reporting period's score.
     *
     * @param asOf  the period end date, normalised to {@code YYYY-MM-DD}
     * @param score the bureau score reported for that period
     */
    public record Point(String asOf, Integer score) {
    }
}
