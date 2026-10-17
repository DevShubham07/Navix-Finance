/**
 * What the lender has actually earned from one customer, in integer paise — the "Earned from this
 * customer" strip at the bottom of the Loan tab.
 *
 *   processing fees    Σ loan.processingFeePaise — collected upfront out of the disbursal, so every
 *                      disbursed loan counts in full. GST is the government's, not ours: excluded.
 *   interest received  the interest the borrower has actually paid, not what has accrued. Per loan
 *   penalty received   this reads the Repayment tab's own ledger (`buildLedger`), which waterfalls
 *                      the verified money penalty → interest → principal, so the two tabs agree.
 *                      A loan closed by an approved settlement is the exception: the settlement
 *                      wrote part of the dues off, so there the principal is recovered first and
 *                      only what came in above it counts as earned (penalty first, then interest).
 *
 * Pure. `today` only feeds the ledger's day counts; the money comes from the server rows.
 */
import { buildLedger } from "@/lib/calc/loan-ledger";
import type { CustomerDetail, LoanView, OutstandingView } from "@/lib/api/applications";

export interface LoanEarnings {
  loanId: number;
  processingFeePaise: number;
  interestReceivedPaise: number;
  penaltyReceivedPaise: number;
  outstandingPaise: number;
  /** Non-null when an approved settlement capped the dues: what was written off. */
  waivedPaise: number | null;
}

export interface CustomerEarnings {
  loanCount: number;
  processingFeesPaise: number;
  interestReceivedPaise: number;
  penaltyReceivedPaise: number;
  /** fees + interest received + penalty received. */
  totalEarnedPaise: number;
  /** Still owed across the running loans (the server's penalty-aware figure). */
  outstandingPaise: number;
  /** Written off through approved settlements; 0 when none. */
  waivedPaise: number;
  settledLoanCount: number;
  perLoan: LoanEarnings[];
}

type Input = Pick<CustomerDetail, "loans" | "payments"> & Partial<Pick<CustomerDetail, "outstandingByLoanId">>;

function earningsForLoan(
  loan: LoanView,
  outstanding: OutstandingView | null,
  payments: Input["payments"],
  today: Date,
): LoanEarnings {
  const ledger = buildLedger({ loan, outstanding, payments, today });
  const row = (key: "interest" | "penalty" | "total") => ledger.rows.find((r) => r.key === key)!;
  const settled = outstanding?.settledAmountPaise != null;

  let interest = row("interest").receivedPaise;
  let penalty = row("penalty").receivedPaise;
  let waived: number | null = null;

  if (settled) {
    const accruedInterest = outstanding?.interestPaise ?? 0;
    const accruedPenalty = outstanding?.penaltyPaise ?? 0;
    const received = row("total").receivedPaise;
    // Principal first; the earnings are whatever came in above it, capped at what had accrued.
    const earned = Math.min(accruedInterest + accruedPenalty, Math.max(0, received - loan.principalPaise));
    penalty = Math.min(earned, accruedPenalty);
    interest = earned - penalty;
    const dues = loan.principalPaise + accruedInterest + accruedPenalty;
    waived = Math.max(0, dues - (outstanding?.settledAmountPaise ?? dues));
  }

  return {
    loanId: loan.id,
    processingFeePaise: loan.disbursedOn ? loan.processingFeePaise : 0,
    interestReceivedPaise: interest,
    penaltyReceivedPaise: penalty,
    outstandingPaise: row("total").outstandingPaise,
    waivedPaise: waived,
  };
}

export function customerEarnings(detail: Input, today: Date): CustomerEarnings {
  const perLoan = detail.loans.map((loan) =>
    earningsForLoan(loan, detail.outstandingByLoanId?.[String(loan.id)] ?? null, detail.payments, today),
  );
  const sum = (pick: (l: LoanEarnings) => number) => perLoan.reduce((s, l) => s + pick(l), 0);
  const fees = sum((l) => l.processingFeePaise);
  const interest = sum((l) => l.interestReceivedPaise);
  const penalty = sum((l) => l.penaltyReceivedPaise);
  return {
    loanCount: detail.loans.length,
    processingFeesPaise: fees,
    interestReceivedPaise: interest,
    penaltyReceivedPaise: penalty,
    totalEarnedPaise: fees + interest + penalty,
    outstandingPaise: sum((l) => l.outstandingPaise),
    waivedPaise: sum((l) => l.waivedPaise ?? 0),
    settledLoanCount: perLoan.filter((l) => l.waivedPaise != null).length,
    perLoan,
  };
}
