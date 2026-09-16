package com.navix.loan.service;

import com.navix.common.storage.DocumentStoragePort;
import com.navix.loan.dto.LoanDtos.TransactionView;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.entity.Payment;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PaymentRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The accountant's company-wide transactions ledger. Synthesized on read from existing data — no
 * dedicated table:
 *
 * <ul>
 *   <li><b>OUTGOING</b> DISBURSAL rows from every {@link Loan} ({@code net_disbursed}, on
 *       {@code disbursed_on}, referenced by {@code disbursal_txn_ref}).</li>
 *   <li><b>INCOMING</b> REPAYMENT rows from every {@link Payment}.</li>
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
    private final PaymentRepository paymentRepository;
    private final LoanApplicationRepository applicationRepository;
    private final CustomerProfileRepository profileRepository;
    private final DocumentStoragePort storage;

    /** Page-size ceiling, mirroring {@code CustomerService.MAX_PAGE_SIZE}. */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * One page of the ledger, plus the totals for the <b>whole</b> filter — the summary cards above
     * the table must show the period's real money movement, not just the page the reviewer is on.
     */
    public record TransactionPage(List<TransactionView> rows, int page, int size, long total,
                                  long totalInPaise, long totalOutPaise) {
    }

    /**
     * One page of the transactions ledger: OUTGOING disbursals + INCOMING repayments, newest first,
     * filtered by direction, an optional inclusive {@code from}/{@code to} statement period and a
     * free-text {@code q} (borrower name / mobile / loan id).
     *
     * <p>The period is applied in SQL on both halves ({@code loan.disbursed_on} and
     * {@code payment.paid_on}) rather than by loading every loan and every payment the company has
     * ever had and filtering in memory, and only the returned page's proofs are presigned. A row with
     * no date is excluded once either bound is set, as before.
     */
    @Transactional(readOnly = true)
    public TransactionPage listTransactions(String q, String direction, LocalDate from, LocalDate to,
                                            int page, int size) {
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(1, page);
        String dir = direction != null ? direction.trim().toUpperCase() : null;

        // Each half is windowed in SQL on its own date column. A bound also drops null-date rows,
        // which is what the old in-memory `date() == null` removal did.
        List<Loan> disbursals = "INCOMING".equals(dir) ? List.of() : loanRepository.findAllForRegister(from, to);
        List<Payment> repayments = "OUTGOING".equals(dir) ? List.of() : paymentRepository.findAllForLedger(from, to);
        if (from != null || to != null) {
            disbursals = disbursals.stream().filter(l -> l.getDisbursedOn() != null).toList();
            repayments = repayments.stream().filter(p -> p.getPaidOn() != null).toList();
        }

        // Every loan either half references: the disbursals themselves, plus the loans the in-window
        // repayments belong to (which may have been disbursed before the period started).
        Map<Long, Loan> loanById = new HashMap<>();
        for (Loan l : disbursals) {
            loanById.put(l.getId(), l);
        }
        List<Long> missing = repayments.stream()
                .map(Payment::getLoanId)
                .filter(id -> id != null && !loanById.containsKey(id))
                .distinct()
                .toList();
        if (!missing.isEmpty()) {
            for (Loan l : loanRepository.findAllById(missing)) {
                loanById.put(l.getId(), l);
            }
        }

        // loanId -> the borrower's profile (loan → application → customer_profile).
        Map<Long, CustomerProfile> profileByLoanId = profilesByLoanId(loanById.keySet());

        List<TransactionView> out = new ArrayList<>();

        // Outgoing: each loan is one disbursal.
        for (Loan loan : disbursals) {
            CustomerProfile p = profileByLoanId.get(loan.getId());
            out.add(new TransactionView(
                    "D-" + loan.getId(), "DISBURSAL", "OUTGOING",
                    loan.getId(), loan.getCustomerId(),
                    p != null ? p.getFullName() : null,
                    p != null ? p.getPan() : null,
                    loan.getNetDisbursed() != null ? loan.getNetDisbursed() : 0L,
                    loan.getDisbursalTxnRef(),
                    loan.getStatus() != null ? loan.getStatus().name() : null,
                    loan.getDisbursedOn(), null));
        }

        // Incoming: each payment is one repayment.
        // proofUrl carries the raw S3 key here — presigning happens once, for the page, below.
        for (Payment pay : repayments) {
            Loan loan = loanById.get(pay.getLoanId());
            CustomerProfile p = profileByLoanId.get(pay.getLoanId());
            out.add(new TransactionView(
                    "P-" + pay.getId(), "REPAYMENT", "INCOMING",
                    pay.getLoanId(),
                    loan != null ? loan.getCustomerId() : null,
                    p != null ? p.getFullName() : null,
                    p != null ? p.getPan() : null,
                    pay.getAmount() != null ? pay.getAmount() : 0L,
                    pay.getTxnRef(),
                    pay.getStatus() != null ? pay.getStatus().name() : null,
                    pay.getPaidOn(), pay.getProofUrl()));
        }

        if (q != null && !q.isBlank()) {
            String needle = q.trim().toLowerCase();
            out.removeIf(t -> !matches(t, needle, profileByLoanId));
        }

        // Totals describe the whole filter, not the page — the cards above the table are the
        // period's money movement, and they must not change as the reviewer pages through it.
        long totalIn = 0L;
        long totalOut = 0L;
        for (TransactionView t : out) {
            if ("INCOMING".equals(t.direction())) {
                totalIn += t.amountPaise();
            } else {
                totalOut += t.amountPaise();
            }
        }

        // Most recent first; rows without a date sort to the end.
        out.sort(Comparator.comparing(TransactionView::date,
                Comparator.nullsLast(Comparator.<LocalDate>reverseOrder())));

        // Presign only the page's proofs — the expensive part, done once per row actually returned
        // instead of once per payment in the table.
        List<TransactionView> pageRows = out.stream()
                .skip((long) (safePage - 1) * safeSize)
                .limit(safeSize)
                .map(t -> withProof(t, presignedProof(t.proofUrl())))
                .toList();
        return new TransactionPage(pageRows, safePage, safeSize, out.size(), totalIn, totalOut);
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
                t.borrowerName(), t.pan(), t.amountPaise(), t.txnRef(), t.status(), t.date(), presignedUrl);
    }

    /** Match the search needle against borrower name, mobile, or loan id. */
    private boolean matches(TransactionView t, String needle, Map<Long, CustomerProfile> profileByLoanId) {
        if (t.borrowerName() != null && t.borrowerName().toLowerCase().contains(needle)) {
            return true;
        }
        if (t.loanId() != null && String.valueOf(t.loanId()).contains(needle)) {
            return true;
        }
        CustomerProfile p = t.loanId() != null ? profileByLoanId.get(t.loanId()) : null;
        return p != null && p.getMobile() != null && p.getMobile().contains(needle);
    }
}
