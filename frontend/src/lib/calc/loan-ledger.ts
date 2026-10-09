/**
 * The Repayment tab's "how it adds up" ledger, in integer paise.
 *
 * The server's OutstandingView is the source of interest and penalty — nothing here recomputes
 * them; this only lays the server's figures out against the contract (payable at term) and the
 * verified payments (received).
 */
import { daysBetween } from "@/lib/calc/loan-math";
import { isoDayToLocalDate } from "@/lib/customers/customer-360";
import type { LoanView, OutstandingView, PaymentView } from "@/lib/api/applications";

export type LedgerKey = "principal" | "interest" | "penalty" | "total";

export interface LedgerRow {
  key: LedgerKey;
  label: string;
  /** If held to the due date. */
  payablePaise: number;
  /** Owed as of today. */
  dueTodayPaise: number;
  /** Verified payments. */
  receivedPaise: number;
  outstandingPaise: number;
  /** Days the amount was charged over (interest / penalty rows). */
  days?: number;
  /** Days the "due today" figure was charged over (server interestDays / penaltyDays). */
  dueDays?: number;
}

export interface Ledger {
  rows: LedgerRow[];
  daysSinceDisbursal: number | null;
  daysToDue: number | null;
  /** Due date + 1 day: interest still runs, no penalty. */
  graceDay: Date | null;
  isOverdue: boolean;
  daysOverdue: number;
}

export function buildLedger(input: {
  loan: LoanView;
  outstanding: OutstandingView | null;
  payments: PaymentView[];
  today: Date;
}): Ledger {
  const { loan, outstanding, payments, today } = input;
  const closed = loan.status === "CLOSED";

  const principal = loan.principalPaise;
  const payableInterest = loan.totalRepayablePaise - principal;
  const payableTotal = loan.totalRepayablePaise;

  const verifiedSum = payments
    .filter((p) => p.loanId === loan.id && p.status === "VERIFIED")
    .reduce((s, p) => s + p.amountPaise, 0);
  // A closed loan's figures are frozen server-side at closedOn (an early payer owes less than the
  // contract), so they are NOT assumed equal to the contract; contract figures only if no server data.
  const received = outstanding?.verifiedPaise ?? (closed ? payableTotal : verifiedSum);

  // Without the server breakdown, the best we have is the loan's own balance: interest is whatever
  // remains above principal, penalty unknown (0).
  const dueInterest =
    outstanding?.interestPaise ?? (closed ? payableInterest : Math.max(0, loan.outstandingPaise + received - principal));
  const duePenalty = outstanding?.penaltyPaise ?? 0;
  const dueTotal = principal + dueInterest + duePenalty;
  const dueInterestShown = dueInterest;
  const duePenaltyShown = duePenalty;

  // The server reports one received total, not an allocation. Convention here: it settles penalty,
  // then interest, then principal. Display only — the total row carries the server's figure.
  let left = received;
  const take = (due: number) => {
    const t = Math.min(left, due);
    left -= t;
    return t;
  };
  const rPenalty = take(duePenaltyShown);
  const rInterest = take(dueInterestShown);
  const rPrincipal = take(principal);

  const totalOutstanding = closed ? 0 : (outstanding?.outstandingPaise ?? Math.max(0, dueTotal - received));

  const disbursed = isoDayToLocalDate(loan.disbursedOn);
  const due = isoDayToLocalDate(loan.dueDate);
  const daysToDue = due ? daysBetween(today, due) : null;
  const isOverdue = !closed && daysToDue != null && daysToDue < 0;
  const graceDay = due ? new Date(due.getFullYear(), due.getMonth(), due.getDate() + 1) : null;

  return {
    rows: [
      {
        key: "principal", label: "Principal", payablePaise: principal,
        dueTodayPaise: principal, receivedPaise: rPrincipal, outstandingPaise: closed ? 0 : principal - rPrincipal,
      },
      {
        key: "interest", label: "Interest (1%/day)", payablePaise: payableInterest,
        dueTodayPaise: dueInterestShown, receivedPaise: rInterest, outstandingPaise: closed ? 0 : dueInterestShown - rInterest,
        days: disbursed && due ? daysBetween(disbursed, due) : undefined,
        dueDays: outstanding?.interestDays,
      },
      {
        key: "penalty", label: "Late penalty (2%/day, ≤30d)", payablePaise: 0,
        dueTodayPaise: duePenaltyShown, receivedPaise: rPenalty, outstandingPaise: closed ? 0 : duePenaltyShown - rPenalty,
        dueDays: outstanding?.penaltyDays,
      },
      {
        key: "total", label: "Grand total", payablePaise: payableTotal,
        dueTodayPaise: dueTotal, receivedPaise: received, outstandingPaise: totalOutstanding,
      },
    ],
    daysSinceDisbursal: disbursed ? daysBetween(disbursed, today) : null,
    daysToDue,
    graceDay,
    isOverdue,
    daysOverdue: isOverdue && daysToDue != null ? -daysToDue : 0,
  };
}
