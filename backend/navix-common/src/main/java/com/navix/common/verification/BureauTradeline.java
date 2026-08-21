package com.navix.common.verification;

/**
 * One credit-account ({@code CAIS_Account_DETAILS}) entry from the bureau report — the detail behind
 * the Category B/C headline totals in {@link BureauReportFacts}.
 *
 * <p>Every field is nullable and blank/missing input parses to {@code null}, never a synthetic
 * default. Two fields deserve a specific caveat, both learned from profiling 6,417 real production
 * tradelines rather than the vendor spec:
 * <ul>
 *   <li>{@code creditLimitRupees} is populated in only ~2% of real tradelines (125/6417). It is
 *       carried through as a raw display value only — <b>never</b> use it to compute a utilisation
 *       ratio (limit-vs-balance), since the ratio would be meaningless for the 98% of tradelines
 *       where the limit is simply absent, not zero.</li>
 *   <li>{@code currentBalanceRupees} can legitimately be negative (23/6417 real rows were). Do not
 *       clamp negative balances to zero — that would silently misreport a credit balance as a debt.</li>
 * </ul>
 *
 * @param lender                  {@code Subscriber_Name}
 * @param accountNumberMasked     {@code Account_Number} (already masked by the bureau)
 * @param accountTypeCode         raw {@code Account_Type} code; label via {@code BureauCodes.accountType}
 * @param portfolioTypeCode       raw {@code Portfolio_Type} code; label via {@code BureauCodes.portfolioType}
 * @param accountStatusCode       raw {@code Account_Status} code; label via {@code BureauCodes.accountStatus}
 * @param openedOn                {@code Open_Date}, normalised to {@code YYYY-MM-DD}
 * @param closedOn                {@code Date_Closed}, normalised to {@code YYYY-MM-DD} (null while open)
 * @param currentBalanceRupees    {@code Current_Balance} — may be negative; sign is meaningful, keep it
 * @param amountPastDueRupees     {@code Amount_Past_Due}
 * @param creditLimitRupees       {@code Credit_Limit_Amount} — sparsely populated (~2%); display-only,
 *                                 never denominator of a utilisation calculation
 * @param paymentHistory          the raw {@code Payment_History_Profile} string (36 chars, or a single
 *                                 {@code "N"} meaning "no history reported")
 * @param writtenOffSettledStatus {@code Written_off_Settled_Status}
 * @param settlementAmountRupees  {@code Settlement_Amount}
 * @param worstDpdMonths          the worst non-sentinel, non-blank {@code Days_Past_Due} figure found in
 *                                 this tradeline's {@code CAIS_Account_History} (the literal string
 *                                 {@code "900"} is a bureau sentinel, not a day count, and is excluded —
 *                                 see {@code ExperianFactsParser.DPD_SENTINEL})
 */
public record BureauTradeline(
        String lender,
        String accountNumberMasked,
        String accountTypeCode,
        String portfolioTypeCode,
        String accountStatusCode,
        String openedOn,
        String closedOn,
        Long currentBalanceRupees,
        Long amountPastDueRupees,
        Long creditLimitRupees,
        String paymentHistory,
        String writtenOffSettledStatus,
        Long settlementAmountRupees,
        Integer worstDpdMonths) {
}
