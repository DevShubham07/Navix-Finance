package com.navix.common.loan;

import java.util.Collection;
import java.util.Map;

/**
 * Port for answering "who handled this file" from the append-only {@code application_event} trail:
 * the credit executive who made the decision, and the staffer who released the money.
 *
 * <p>Every staff register wants those two names next to the borrower, and until now none of them
 * carried either — a queue could say a file was <em>assigned</em> to someone, never who actually
 * decided it. Implemented in {@code navix-loan} (which owns the event trail) and consumed from
 * {@code navix-collections} across this seam, the same way {@link LoanDirectory} and
 * {@code StaffDirectory} are.
 *
 * <p><b>Batched by contract.</b> Both methods take a whole page of ids and issue one query: these
 * feed list views of hundreds of rows, and a per-row lookup would be an N+1 against the largest
 * table in the schema.
 */
public interface ApplicationActorDirectory {

    /**
     * The two names a register shows beside a file. Any field is {@code null} when the trail has no
     * such event yet (a file still in credit has no disburser) or when the actor id no longer
     * resolves to a staff row — callers render a blank cell, never a placeholder.
     */
    record HandledBy(Long creditDecidedById, String creditDecidedByName,
                     Long disbursedById, String disbursedByName) {

        public static final HandledBy NONE = new HandledBy(null, null, null, null);
    }

    /** Resolve by application id. Applications with no matching events are absent from the map. */
    Map<Long, HandledBy> byApplicationId(Collection<Long> applicationIds);

    /**
     * Resolve by the loan the application minted — for surfaces that only hold a loan id (the
     * collections worklist). Loans with no application, or none with matching events, are absent.
     */
    Map<Long, HandledBy> byLoanId(Collection<Long> loanIds);
}
