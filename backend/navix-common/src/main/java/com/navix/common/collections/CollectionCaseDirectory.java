package com.navix.common.collections;

import java.util.Collection;
import java.util.Map;

/**
 * Port for reading a loan's assigned collections officer from modules that must not depend on
 * {@code navix-collections} internals (notably {@code navix-loan}'s staff loan register).
 * Implemented by the collections module ({@code CollectionCaseDirectoryAdapter}); the bootable app
 * wires the bean by component scan — the mirror of {@link SettlementDirectory}, which covers the
 * same "loan → collection_case" seam for the approved-settlement figure instead of the assignee.
 *
 * <p>Deliberately <b>batched</b>, not per-loan: the one caller of this port ({@code
 * LoanRegisterService}) renders a whole register page — potentially every loan the platform has
 * ever disbursed — in one read, and a per-loan lookup would reintroduce the N+1 that service was
 * built to avoid.
 */
public interface CollectionCaseDirectory {

    /**
     * The assigned collections officer's staff id for each of {@code loanIds} that has an open
     * collection case with an assignee. Loans with no case, or a case with no assignee yet, are
     * simply absent from the returned map — callers should treat a missing key the same as "no
     * officer" rather than distinguishing "no case" from "unassigned case".
     *
     * @param loanIds the real (bigint) loan ids to resolve; an empty collection yields an empty map
     * @return loan id → assigned officer's staff id, only for loans that have one
     */
    Map<Long, Long> assignedOfficerByLoanId(Collection<Long> loanIds);
}
