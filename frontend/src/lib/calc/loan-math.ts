import type { DpdBucket } from "@/lib/domain/collections";
import type { LoanCostBreakdown } from "@/lib/domain/loan";

/**
 * Loan math for the salary-linked single-repayment product.
 *
 * Worked example for a principal of ₹10,000 over 30 days:
 *  - processingFee  = 10,000 * 0.10            = ₹1,000
 *  - gstOnFee       = 1,000 * 0.18             = ₹180
 *  - netDisbursed   = 10,000 - 1,000 - 180     = ₹8,820
 *  - interest (30d) = 10,000 * 0.01 * 30       = ₹3,000
 *  - totalRepayable = 10,000 + 3,000           = ₹13,000
 *    (processing fee + GST are taken up-front from disbursal, not added to
 *     the repayment; repayment = principal + accrued interest)
 */

/** Up-front processing fee rate on principal. */
export const PROCESSING_FEE_RATE = 0.1;
/** GST rate applied to the processing fee. */
export const GST_RATE = 0.18;
/** Interest accrual per day on principal. */
export const DAILY_INTEREST_RATE = 0.01;
/** Late penalty per day after the due date. */
export const LATE_PENALTY_RATE = 0.02;
/** Maximum days the late penalty accrues before collections. */
export const LATE_PENALTY_CAP_DAYS = 30;
/** Flat instant-loan ceiling: ₹10,00,000 (salary no longer caps the amount). */
export const MAX_INSTANT_LOAN_AMOUNT = 1_000_000;

/** Floor on a sanctioned advance (working value — finalised in credit policy). */
export const MIN_LOAN_AMOUNT = 1000;

/** Round to whole rupees (amounts are presented and settled in INR). */
function inr(value: number): number {
  return Math.round(value);
}

/** Up-front 10% processing fee on the principal. */
export function processingFee(amount: number): number {
  return inr(amount * PROCESSING_FEE_RATE);
}

/** 18% GST charged on the processing fee. */
export function gstOnFee(fee: number): number {
  return inr(fee * GST_RATE);
}

/** Amount credited to the borrower = principal - fee - GST. */
export function netDisbursed(amount: number): number {
  const fee = processingFee(amount);
  return inr(amount - fee - gstOnFee(fee));
}

/** Interest accrued over `days` at 1%/day on principal. */
export function dailyInterest(amount: number, days: number): number {
  return inr(amount * DAILY_INTEREST_RATE * Math.max(0, days));
}

/** Total amount due on the repayment date = principal + interest. */
export function totalRepayable(amount: number, days: number): number {
  return inr(amount + dailyInterest(amount, days));
}

/** Late penalty accrued for `daysLate`, capped at 30 days. */
export function latePenalty(amount: number, daysLate: number): number {
  const cappedDays = Math.min(Math.max(0, daysLate), LATE_PENALTY_CAP_DAYS);
  return inr(amount * LATE_PENALTY_RATE * cappedDays);
}

/** Eligible loan limit — a flat ₹10,00,000 instant cap (salary drives the due date, not the amount). */
export function eligibleLimit(_monthlySalary: number): number {
  return MAX_INSTANT_LOAN_AMOUNT;
}

/** Map days-past-due to a {@link DpdBucket}. */
export function dpdBucket(daysPastDue: number): DpdBucket {
  if (daysPastDue <= 0) return "UPCOMING";
  if (daysPastDue <= 7) return "T0_T7";
  if (daysPastDue <= 30) return "T8_T30";
  if (daysPastDue <= 60) return "T30_T60";
  if (daysPastDue <= 90) return "T60_T90";
  return "T90_PLUS";
}

/** Whole days between two dates (b - a), ignoring time-of-day. */
export function daysBetween(a: Date, b: Date): number {
  const ms = 24 * 60 * 60 * 1000;
  const da = Date.UTC(a.getFullYear(), a.getMonth(), a.getDate());
  const db = Date.UTC(b.getFullYear(), b.getMonth(), b.getDate());
  return Math.round((db - da) / ms);
}

/** Salary-due-date window working values (spec: exact rule TBD). */
export const SALARY_DUE_MIN_CYCLE_DAYS = 15;
export const SALARY_DUE_MAX_WINDOW_DAYS = 40;

/** Clamp a day-of-month to a valid date within the given month. */
function salaryDateInMonth(year: number, monthIndex: number, salaryDay: number): Date {
  const lastDay = new Date(year, monthIndex + 1, 0).getDate();
  return new Date(year, monthIndex, Math.min(salaryDay, lastDay));
}

/**
 * Derive the single-repayment due date from the borrower's salary credit.
 * Repayment lands on the salary day — the next salary credit that gives a
 * reasonable cycle (>= ~15 days) while staying within the ~40-day window.
 *
 * Examples (salary on 30th): disbursed 3 Jun → due 30 Jun (~27d);
 * disbursed 25 Jun → 30 Jun is only ~5d out, so roll to 30 Jul (~35d).
 */
export function dueDateFromSalary(params: {
  disbursedOn: Date;
  /** Day-of-month the salary is typically credited. */
  salaryDay: number;
  /** Recent salary credit dates observed (bank statement). */
  recentSalaryDates?: Date[];
}): Date {
  const { disbursedOn, salaryDay } = params;
  let candidate = salaryDateInMonth(
    disbursedOn.getFullYear(),
    disbursedOn.getMonth(),
    salaryDay,
  );

  // Roll forward until the candidate is strictly after disbursement and gives
  // at least a minimum cycle, without exceeding the max window.
  for (let i = 0; i < 3; i += 1) {
    const gap = daysBetween(disbursedOn, candidate);
    if (gap >= SALARY_DUE_MIN_CYCLE_DAYS) break;
    const next = new Date(candidate);
    next.setMonth(next.getMonth() + 1);
    const rolled = salaryDateInMonth(next.getFullYear(), next.getMonth(), salaryDay);
    if (daysBetween(disbursedOn, rolled) > SALARY_DUE_MAX_WINDOW_DAYS) break;
    candidate = rolled;
  }
  return candidate;
}

/**
 * Build a full itemized {@link LoanCostBreakdown} for a principal and tenure.
 */
export function buildCostBreakdown(amount: number, tenureDays: number): LoanCostBreakdown {
  const fee = processingFee(amount);
  const gst = gstOnFee(fee);
  const interest = dailyInterest(amount, tenureDays);
  return {
    principal: amount,
    processingFee: fee,
    gstOnFee: gst,
    netDisbursed: inr(amount - fee - gst),
    interest,
    tenureDays,
    totalRepayable: inr(amount + interest),
  };
}
