/**
 * Business figures for the Repayment / Collections tabs, in integer paise. Interest and penalty
 * come from the server (OutstandingView); nothing here recomputes them — only lays out counts,
 * dates and ratios around them.
 */
import { daysBetween, dpdBucket } from "@/lib/calc/loan-math";
import { isoDayToLocalDate } from "@/lib/customers/customer-360";
import type { DpdBucket, LoanView, PaymentView } from "@/lib/api/applications";

export const LATE_PENALTY_CAP_DAYS = 30;

export function ordinal(n: number): string {
  const r = n % 100;
  if (r >= 11 && r <= 13) return `${n}th`;
  return `${n}${({ 1: "st", 2: "nd", 3: "rd" } as Record<number, string>)[n % 10] ?? "th"}`;
}

/** 1-based position of the loan in the customer's history (ids are issued in disbursal order). */
export function loanCycle(loans: ReadonlyArray<{ id: number }>, loanId: number): number | null {
  const i = [...loans].sort((a, b) => a.id - b.id).findIndex((l) => l.id === loanId);
  return i >= 0 ? i + 1 : null;
}

/** Days past due; a closed loan is measured to the day it closed. Null without a due date. */
export function dpdDays(loan: Pick<LoanView, "dueDate" | "closedOn" | "status">, today: Date): number | null {
  const due = isoDayToLocalDate(loan.dueDate);
  if (!due) return null;
  const end = (loan.closedOn ? isoDayToLocalDate(loan.closedOn) : null) ?? today;
  return Math.max(0, daysBetween(due, end));
}

export const bucketOf = (dpd: number): DpdBucket => dpdBucket(dpd);

/**
 * Penalty days used (of the 30-day cap) and the penalty that can still accrue.
 * Penalty is 2%/day of principal, so remaining = principal * 2 * remainingDays / 100; paise stay
 * integral except for sub-paise remainders, hence Math.round.
 */
export function penaltyHeadroom(principalPaise: number, penaltyDays: number) {
  const used = Math.min(Math.max(0, penaltyDays), LATE_PENALTY_CAP_DAYS);
  const remainingDays = LATE_PENALTY_CAP_DAYS - used;
  return { used, remainingDays, remainingPaise: Math.round((principalPaise * 2 * remainingDays) / 100) };
}

/** Latest verified payment (by paid-on date, then id). */
export function lastVerifiedPayment(payments: PaymentView[], loanId: number): PaymentView | null {
  const v = payments.filter((p) => p.loanId === loanId && p.status === "VERIFIED");
  if (v.length === 0) return null;
  return v.reduce((a, b) => ((b.paidOn ?? "") > (a.paidOn ?? "") || ((b.paidOn ?? "") === (a.paidOn ?? "") && b.id > a.id) ? b : a));
}

/** Whole-percent collected of the total repayable; null when there is no total. */
export function collectedPct(collectedPaise: number, totalPaise: number): number | null {
  return totalPaise > 0 ? Math.round((collectedPaise * 100) / totalPaise) : null;
}

/** Collected minus what the borrower actually received; negative = still under water. */
export const recoveredVsDisbursed = (collectedPaise: number, netDisbursedPaise: number) =>
  collectedPaise - netDisbursedPaise;
