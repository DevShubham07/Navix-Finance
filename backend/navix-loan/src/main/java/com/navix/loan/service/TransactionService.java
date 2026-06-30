package com.navix.loan.service;

import com.navix.loan.dto.LoanDtos.TransactionView;
import com.navix.loan.entity.ApplicantProfile;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.entity.Payment;
import com.navix.loan.repository.ApplicantProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PaymentRepository;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * Borrower name/PAN are resolved loan → application → {@link ApplicantProfile}. The full PAN is
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
    private final ApplicantProfileRepository profileRepository;

    @Transactional(readOnly = true)
    public List<TransactionView> listTransactions(String q, String direction) {
        return listTransactions(q, direction, null, null);
    }

    /**
     * As {@link #listTransactions(String, String)} with an optional inclusive date range ({@code from}
     * / {@code to}, by the transaction's {@code date}). A row with no date is excluded once either bound
     * is set. Lets the ledger filter a statement period server-side (timezone-free — dates are LocalDate).
     */
    @Transactional(readOnly = true)
    public List<TransactionView> listTransactions(String q, String direction, LocalDate from, LocalDate to) {
        Map<Long, Loan> loanById = new HashMap<>();
        for (Loan l : loanRepository.findAll()) {
            loanById.put(l.getId(), l);
        }

        // loanId -> the borrower's profile (loan → application → applicant_profile).
        Map<Long, ApplicantProfile> profileByAppId = profileRepository.findAll().stream()
                .collect(Collectors.toMap(ApplicantProfile::getApplicationId, p -> p, (a, b) -> a));
        Map<Long, ApplicantProfile> profileByLoanId = new HashMap<>();
        for (LoanApplication a : applicationRepository.findAll()) {
            if (a.getLoanId() != null) {
                ApplicantProfile p = profileByAppId.get(a.getId());
                if (p != null) {
                    profileByLoanId.put(a.getLoanId(), p);
                }
            }
        }

        List<TransactionView> out = new ArrayList<>();

        // Outgoing: each loan is one disbursal.
        for (Loan loan : loanById.values()) {
            ApplicantProfile p = profileByLoanId.get(loan.getId());
            out.add(new TransactionView(
                    "D-" + loan.getId(), "DISBURSAL", "OUTGOING",
                    loan.getId(), loan.getApplicantId(),
                    p != null ? p.getFullName() : null,
                    p != null ? p.getPan() : null,
                    loan.getNetDisbursed() != null ? loan.getNetDisbursed() : 0L,
                    loan.getDisbursalTxnRef(),
                    loan.getStatus() != null ? loan.getStatus().name() : null,
                    loan.getDisbursedOn()));
        }

        // Incoming: each payment is one repayment.
        for (Payment pay : paymentRepository.findAll()) {
            Loan loan = loanById.get(pay.getLoanId());
            ApplicantProfile p = profileByLoanId.get(pay.getLoanId());
            out.add(new TransactionView(
                    "P-" + pay.getId(), "REPAYMENT", "INCOMING",
                    pay.getLoanId(),
                    loan != null ? loan.getApplicantId() : null,
                    p != null ? p.getFullName() : null,
                    p != null ? p.getPan() : null,
                    pay.getAmount() != null ? pay.getAmount() : 0L,
                    pay.getTxnRef(),
                    pay.getStatus() != null ? pay.getStatus().name() : null,
                    pay.getPaidOn()));
        }

        String dir = direction != null ? direction.trim().toUpperCase() : null;
        if ("INCOMING".equals(dir) || "OUTGOING".equals(dir)) {
            out.removeIf(t -> !t.direction().equals(dir));
        }

        // Inclusive statement-period window (by transaction date). Null-date rows drop once bounded.
        if (from != null) {
            out.removeIf(t -> t.date() == null || t.date().isBefore(from));
        }
        if (to != null) {
            out.removeIf(t -> t.date() == null || t.date().isAfter(to));
        }

        if (q != null && !q.isBlank()) {
            String needle = q.trim().toLowerCase();
            out.removeIf(t -> !matches(t, needle, profileByLoanId));
        }

        // Most recent first; rows without a date sort to the end.
        out.sort(Comparator.comparing(TransactionView::date,
                Comparator.nullsLast(Comparator.<LocalDate>reverseOrder())));
        return out;
    }

    /** Match the search needle against borrower name, mobile, or loan id. */
    private boolean matches(TransactionView t, String needle, Map<Long, ApplicantProfile> profileByLoanId) {
        if (t.borrowerName() != null && t.borrowerName().toLowerCase().contains(needle)) {
            return true;
        }
        if (t.loanId() != null && String.valueOf(t.loanId()).contains(needle)) {
            return true;
        }
        ApplicantProfile p = t.loanId() != null ? profileByLoanId.get(t.loanId()) : null;
        return p != null && p.getMobile() != null && p.getMobile().contains(needle);
    }
}
