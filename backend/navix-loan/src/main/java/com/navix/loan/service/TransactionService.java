package com.navix.loan.service;

import com.navix.common.query.FilterSpec;
import com.navix.common.query.SqlFacets;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.loan.dto.LoanDtos.TransactionView;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The accountant's company-wide transactions ledger. Synthesized on read from existing data — no
 * dedicated table (the union lives in {@link TransactionLedgerQuery}):
 *
 * <ul>
 *   <li><b>OUTGOING</b> DISBURSAL rows from every {@link Loan} ({@code net_disbursed}, on
 *       {@code disbursed_on}, referenced by {@code disbursal_txn_ref}).</li>
 *   <li><b>INCOMING</b> REPAYMENT rows from every payment that was not rejected.</li>
 * </ul>
 *
 * Borrower name/PAN are resolved loan → application → {@link CustomerProfile}. The full PAN is
 * returned — this is a <b>staff-only</b> ledger (Accountant/Admin; enforced in {@code LoanController});
 * masking is a customer-facing concern only. Supports filtering by direction and a free-text query
 * (borrower name / mobile / loan id).
 */
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final LoanRepository loanRepository;
    private final TransactionLedgerQuery ledgerQuery;
    private final LoanApplicationRepository applicationRepository;
    private final CustomerProfileRepository profileRepository;
    private final DocumentStoragePort storage;

    /** Page-size ceiling, mirroring {@code CustomerService.MAX_PAGE_SIZE}. */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * One page of the ledger, plus the totals for the <b>whole</b> filter — the summary cards above
     * the table must show the period's real money movement, not just the page the reviewer is on.
     */
    /**
     * @param totalInPaise  verified repayments only — money the accountant has confirmed arrived
     * @param pendingInPaise repayments still awaiting verification (listed, but not yet counted in)
     */
    public record TransactionPage(List<TransactionView> rows, int page, int size, long total,
                                  long totalInPaise, long totalOutPaise, long pendingInPaise) {
    }

    /**
     * One page of the transactions ledger: OUTGOING disbursals + INCOMING repayments, newest first
     * unless a sort is asked for, filtered by direction, an optional inclusive {@code from}/{@code to}
     * statement period, a free-text {@code q} (borrower name / mobile / loan id) and the
     * {@code f.<column>} filters in {@code columnFilters} (raw request params; {@link FilterSpec} drops
     * anything that is not a registered column).
     *
     * <p>All of it — the period, the filters, the count, the card sums and the page — is one native
     * statement family in {@link TransactionLedgerQuery}, so a filtered page costs the page, not the
     * whole period read through two repositories and merged here. Borrower name/PAN arrive on the row
     * (the ledger filters on them, so they are joined in SQL); only the returned page's proofs are
     * presigned. The caller gates on ACCOUNTANT/ADMIN before this is reached; the filter is ANDed
     * after the period/direction/search clauses inside the query, so it can only narrow.
     */
    @Transactional(readOnly = true)
    public TransactionPage page(String q, String direction, LocalDate from, LocalDate to,
                                int page, int size, String sort, String dir, Map<String, String> columnFilters) {
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(1, page);
        TransactionLedgerQuery.LedgerFilter filter = ledgerFilter(q, direction, from, to);
        FilterSpec spec = FilterSpec.parse(columnFilters, TransactionLedgerQuery.FILTER_COLUMNS);

        // Totals describe the whole filter, not the page — the cards above the table are the
        // period's money movement, and they must not change as the reviewer pages through it.
        TransactionLedgerQuery.Totals totals = ledgerQuery.totals(filter, spec);

        // Presign only the page's proofs — the expensive part, done once per row actually returned
        // instead of once per payment in the table.
        List<TransactionView> pageRows = ledgerQuery
                .page(filter, spec, (safePage - 1) * safeSize, safeSize, sort, dir).stream()
                .map(t -> withProof(t, presignedProof(t.proofUrl())))
                .toList();
        return new TransactionPage(pageRows, safePage, safeSize, totals.count(),
                totals.inPaise(), totals.outPaise(), totals.pendingInPaise());
    }

    /** The unsorted, unfiltered-by-column form: the original {@code GET /transactions}. */
    @Transactional(readOnly = true)
    public TransactionPage listTransactions(String q, String direction, LocalDate from, LocalDate to,
                                            int page, int size) {
        return page(q, direction, from, to, page, size, null, null, Map.of());
    }

    /**
     * The distinct values of one faceted column, inside the same period/direction/search the list
     * uses. {@code columnFilters} are the OTHER columns' filters; the column's own is dropped so its
     * dropdown does not collapse. Identity columns are not facetable (see {@code FILTER_COLUMNS}).
     */
    @Transactional(readOnly = true)
    public SqlFacets.Facet facets(String q, String direction, LocalDate from, LocalDate to, String column,
                                  Map<String, String> columnFilters) {
        FilterSpec spec = FilterSpec.parse(columnFilters, TransactionLedgerQuery.FILTER_COLUMNS).without(column);
        return ledgerQuery.facet(ledgerFilter(q, direction, from, to), spec, column);
    }

    private static TransactionLedgerQuery.LedgerFilter ledgerFilter(String q, String direction, LocalDate from, LocalDate to) {
        return new TransactionLedgerQuery.LedgerFilter(
                q == null ? null : q.trim().toLowerCase(),
                direction == null ? null : direction.trim().toUpperCase(),
                from, to);
    }

    /**
     * loanId → the borrower's KYC profile (loan → application → customer_profile), two indexed
     * queries instead of a full scan of applications and profiles.
     */
    public Map<Long, CustomerProfile> profilesByLoanId(Collection<Long> loanIds) {
        Map<Long, CustomerProfile> profileByLoanId = new HashMap<>();
        if (loanIds == null || loanIds.isEmpty()) {
            return profileByLoanId;
        }
        List<LoanApplication> apps = applicationRepository.findByLoanIdIn(loanIds);
        List<Long> appIds = apps.stream().map(LoanApplication::getId).toList();
        Map<Long, CustomerProfile> profileByAppId = appIds.isEmpty() ? new HashMap<>()
                : profileRepository.findByApplicationIdIn(appIds).stream()
                        .collect(Collectors.toMap(CustomerProfile::getApplicationId, p -> p, (a, b) -> a));
        for (LoanApplication a : apps) { // last application (that has a profile) wins — same as before.
            if (a.getLoanId() != null) {
                CustomerProfile p = profileByAppId.get(a.getId());
                if (p != null) {
                    profileByLoanId.put(a.getLoanId(), p);
                }
            }
        }
        return profileByLoanId;
    }

    /** loanId → the borrower's customerId (from the loan) and full name (from the profile). */
    public record BorrowerRef(Long customerId, String borrowerName) {
    }

    /** Batch loan → borrower lookup for the pending-repayments queue — no ledger build required. */
    public Map<Long, BorrowerRef> borrowersByLoanId(Collection<Long> loanIds) {
        Map<Long, BorrowerRef> out = new HashMap<>();
        if (loanIds == null || loanIds.isEmpty()) {
            return out;
        }
        Map<Long, CustomerProfile> profiles = profilesByLoanId(loanIds);
        for (Loan loan : loanRepository.findAllById(loanIds)) {
            CustomerProfile p = profiles.get(loan.getId());
            out.put(loan.getId(), new BorrowerRef(loan.getCustomerId(), p != null ? p.getFullName() : null));
        }
        return out;
    }

    /** Resolve a stored S3 key to a short-lived presigned GET; null in, null out. */
    private String presignedProof(String key) {
        return key == null || key.isBlank() ? null : storage.presignDownload(key);
    }

    /** {@code t} with every field the same except {@code proofUrl}, replaced by {@code presignedUrl}. */
    private static TransactionView withProof(TransactionView t, String presignedUrl) {
        return new TransactionView(t.id(), t.type(), t.direction(), t.loanId(), t.customerId(),
                t.borrowerName(), t.pan(), t.amountPaise(), t.txnRef(), t.status(), t.date(), presignedUrl,
                t.repaymentDate(), t.graceDays());
    }
}
