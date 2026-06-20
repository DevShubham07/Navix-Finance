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
/** Eligible loan limit as a fraction of monthly salary. */
export const LIMIT_PCT_OF_SALARY = 0.25;

/** Up-front 10% processing fee on the principal. */
export function processingFee(amount: number): number {
  // TODO: implement (amount * PROCESSING_FEE_RATE) with rounding policy.
  throw new Error("Not implemented");
}

/** 18% GST charged on the processing fee. */
export function gstOnFee(fee: number): number {
  // TODO: implement (fee * GST_RATE).
  throw new Error("Not implemented");
}

/** Amount credited to the borrower = principal - fee - GST. */
export function netDisbursed(amount: number): number {
  // TODO: amount - processingFee(amount) - gstOnFee(processingFee(amount)).
  throw new Error("Not implemented");
}

/** Interest accrued over `days` at 1%/day on principal. */
export function dailyInterest(amount: number, days: number): number {
  // TODO: amount * DAILY_INTEREST_RATE * days.
  throw new Error("Not implemented");
}

/** Total amount due on the repayment date = principal + interest. */
export function totalRepayable(amount: number, days: number): number {
  // TODO: amount + dailyInterest(amount, days).
  throw new Error("Not implemented");
}

/** Eligible loan limit = 25% of monthly salary. */
export function eligibleLimit(monthlySalary: number): number {
  // TODO: monthlySalary * LIMIT_PCT_OF_SALARY (apply min/max policy).
  throw new Error("Not implemented");
}

/** Map days-past-due to a {@link DpdBucket}. */
export function dpdBucket(daysPastDue: number): DpdBucket {
  // TODO: bucket by 0-7 / 8-30 / 30-60 / 60-90 / 90+, UPCOMING when <= 0.
  throw new Error("Not implemented");
}

/**
 * Derive the single-repayment due date from the borrower's salary credit.
 * Repayment lands on the salary day (last salary credit within ~40 days of
 * disbursement).
 *
 * TODO: compute the next salary date after disbursement given the salary-day
 * signal and the ~40-day window.
 */
export function dueDateFromSalary(_params: {
  disbursedOn: Date;
  /** Day-of-month the salary is typically credited. */
  salaryDay: number;
  /** Recent salary credit dates observed (bank statement). */
  recentSalaryDates?: Date[];
}): Date {
  // TODO: implement salary-day due-date resolution.
  throw new Error("Not implemented");
}

/**
 * Build a full itemized {@link LoanCostBreakdown} for a principal and tenure.
 * TODO: compose the pure functions above.
 */
export function buildCostBreakdown(_amount: number, _tenureDays: number): LoanCostBreakdown {
  // TODO: assemble breakdown using the functions above.
  throw new Error("Not implemented");
}
