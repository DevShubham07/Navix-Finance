package com.navix.common.verification;

/**
 * Delinquency aggregates derived across every tradeline's {@code CAIS_Account_History}.
 *
 * <p><b>Every field is nullable, and {@code null} means "could not be determined" — never {@code 0}.</b>
 * A real report with, say, no enquiry-worthy history at all must not be misreported as "zero accounts
 * ever 30+ days past due"; it must say "unknown". Conflating absent-data with zero is exactly the kind
 * of thing that makes a credit brief actively misleading rather than merely incomplete, so every
 * aggregation method here must leave a field {@code null} unless it found at least one qualifying,
 * non-sentinel data point to aggregate.
 *
 * @param worstDpd12m                worst DPD across all tradelines in the trailing 12 reporting months
 * @param worstDpd24m                worst DPD across all tradelines in the trailing 24 reporting months
 * @param worstDpd36m                worst DPD across all tradelines in the trailing 36 reporting months
 * @param accountsEver30Plus         count of tradelines whose worst-ever DPD (any month) reached 30+
 * @param accountsCurrentlyPastDue   count of tradelines with a non-null, non-zero {@code Amount_Past_Due}
 * @param accountsWrittenOffOrSettled count of tradelines carrying a {@code Written_off_Settled_Status}
 * @param oldestAccountOpenedOn      the earliest {@code Open_Date} across all tradelines ({@code YYYY-MM-DD})
 */
public record BureauDelinquency(
        Integer worstDpd12m,
        Integer worstDpd24m,
        Integer worstDpd36m,
        Integer accountsEver30Plus,
        Integer accountsCurrentlyPastDue,
        Integer accountsWrittenOffOrSettled,
        String oldestAccountOpenedOn) {
}
