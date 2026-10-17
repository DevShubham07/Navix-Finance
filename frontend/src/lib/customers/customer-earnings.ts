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
 * Alongside the profit, the lending position: how much was lent, how much of it is still out, what
 * is due now, and the cash view — money received back minus money that left (net disbursal + the
 * GST paid over on its fee). The cash figure is negative while loans are running and equals the
 * profit once every loan is repaid in full; it is the honest "are we ahead on this customer" line.
 *
 * Pure. `today` only feeds the ledger's day counts; the money comes from the server rows.
 */
import { buildLedger } from "@/lib/calc/loan-ledger";
import type { CustomerDetail, LoanView, OutstandingView } from "@/lib/api/applications";

export interface LoanEarnings {
  loanId: number;
  /** False for a loan never disbursed — it counts nowhere below. */
  disbursed: boolean;
  /** Still running (not closed). */
  open: boolean;
  principalPaise: number;
  /** Cash that left: the net disbursal plus the GST paid over on its fee. */
  cashOutPaise: number;
  /** Verified money received back on the loan. */
  cashInPaise: number;
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
  /** Disbursed loans, and how many of them are repaid (closed) / still running. */
  disbursedLoanCount: number;
  closedLoanCount: number;
  openLoanCount: number;
  /** Σ principal over every disbursed loan. */
  totalLentPaise: number;
  /** Σ principal over the loans still running — money currently out with this customer. */
  currentlyDisbursedPaise: number;
  /** Verified money received back, across every loan. */
  cashInPaise: number;
  /** Money that left: net disbursals + the GST paid over on their fees. */
  cashOutPaise: number;
  /** cashIn − cashOut. Negative while loans run; equals the profit once all are repaid. */
  netCashPaise: number;
  /** totalEarned ÷ totalLent, as a fraction (0.27 = 27%); null when nothing was lent. */
  profitOnLentRatio: number | null;
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

  const disbursed = loan.disbursedOn != null;
  return {
    loanId: loan.id,
    disbursed,
    open: disbursed && loan.status !== "CLOSED" && loan.closedOn == null,
    principalPaise: disbursed ? loan.principalPaise : 0,
    cashOutPaise: disbursed ? loan.netDisbursedPaise + loan.gstPaise : 0,
    cashInPaise: row("total").receivedPaise,
    processingFeePaise: disbursed ? loan.processingFeePaise : 0,
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
  const disbursedLoans = perLoan.filter((l) => l.disbursed);
  const totalLent = sum((l) => l.principalPaise);
  const cashIn = sum((l) => l.cashInPaise);
  const cashOut = sum((l) => l.cashOutPaise);
  return {
    loanCount: detail.loans.length,
    processingFeesPaise: fees,
    interestReceivedPaise: interest,
    penaltyReceivedPaise: penalty,
    totalEarnedPaise: fees + interest + penalty,
    outstandingPaise: sum((l) => l.outstandingPaise),
    waivedPaise: sum((l) => l.waivedPaise ?? 0),
    settledLoanCount: perLoan.filter((l) => l.waivedPaise != null).length,
    disbursedLoanCount: disbursedLoans.length,
    closedLoanCount: disbursedLoans.filter((l) => !l.open).length,
    openLoanCount: disbursedLoans.filter((l) => l.open).length,
    totalLentPaise: totalLent,
    currentlyDisbursedPaise: sum((l) => (l.open ? l.principalPaise : 0)),
    cashInPaise: cashIn,
    cashOutPaise: cashOut,
    netCashPaise: cashIn - cashOut,
    profitOnLentRatio: totalLent > 0 ? (fees + interest + penalty) / totalLent : null,
    perLoan,
  };
}
