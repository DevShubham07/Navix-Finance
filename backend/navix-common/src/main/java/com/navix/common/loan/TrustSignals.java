package com.navix.common.loan;

/**
 * The three small stars shown beside a customer in every staff table. Each is a tri-state string:
 * {@code PASS}, {@code FAIL} or {@code NOT_CHECKED}.
 *
 * @param bureau no days-past-due on any account in the last 6 months of the latest bureau report
 * @param uan    a valid EPFO/UAN record was found (see {@code CustomerTrustSignalsService})
 * @param email  the work-email provider check passed
 */
public record TrustSignals(String bureau, String uan, String email) {

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String NOT_CHECKED = "NOT_CHECKED";

    public static final TrustSignals NONE = new TrustSignals(NOT_CHECKED, NOT_CHECKED, NOT_CHECKED);
}
