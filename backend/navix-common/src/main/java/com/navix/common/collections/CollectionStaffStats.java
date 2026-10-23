package com.navix.common.collections;

/**
 * One staffer's collections work over a window — the collections half of the staff-performance
 * dashboard, so a Collection Executive or Head is measured on what they actually do (calls,
 * promises, money recovered, settlements) rather than on credit-decision columns they never touch.
 *
 * <p>Every windowed count is attributable to the staffer by a stored actor id. {@code casesNow} is
 * the one live snapshot: open cases (loan not CLOSED/REPAID/WRITTEN_OFF) assigned to them right now.
 *
 * @param interactions             borrower interactions they logged
 * @param connected                of those, outcome {@code CONNECTED}
 * @param promisesToPay            of those, outcome {@code PROMISE_TO_PAY}
 * @param paidOutcomes             of those, outcome {@code PAID}
 * @param noAnswer                 of those, outcome {@code NO_ANSWER}
 * @param disputed                 of those, outcome {@code DISPUTED}
 * @param casesNow                 open cases assigned to them now (live, not windowed)
 * @param paymentsRaised           collections payments they recorded
 * @param paymentsRaisedPaise      total paise across {@code paymentsRaised}
 * @param paymentsRecovered        of those, validated by the Accountant (money actually credited)
 * @param paymentsRecoveredPaise   total paise across {@code paymentsRecovered}
 * @param paymentsPending          of those, still waiting on the Head or the Accountant
 * @param paymentsRejected         of those, rejected by the Accountant
 * @param settlementsProposed      settlements they proposed
 * @param settlementsProposedPaise total paise across {@code settlementsProposed}
 * @param settlementsApproved      settlements they approved (a Head's decision)
 * @param settlementsApprovedPaise total paise across {@code settlementsApproved}
 * @param settlementsRejected      settlements they rejected (a Head's decision)
 * @param paymentsValidated        collections payments they validated (an Accountant's decision)
 * @param paymentsValidatedPaise   total paise across {@code paymentsValidated}
 * @param paymentsValidationRejected collections payments they rejected at validation (Accountant)
 */
public record CollectionStaffStats(
        long interactions, long connected, long promisesToPay, long paidOutcomes, long noAnswer,
        long disputed, long casesNow,
        long paymentsRaised, long paymentsRaisedPaise, long paymentsRecovered, long paymentsRecoveredPaise,
        long paymentsPending, long paymentsRejected,
        long settlementsProposed, long settlementsProposedPaise,
        long settlementsApproved, long settlementsApprovedPaise, long settlementsRejected,
        long paymentsValidated, long paymentsValidatedPaise, long paymentsValidationRejected) {

    /** Nothing attributable — for a staffer with no collections footprint at all. */
    public static final CollectionStaffStats NONE = new CollectionStaffStats(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
}
