import { describe, expect, it } from "vitest";
import type { LoanView, OutstandingView, PaymentView } from "@/lib/api/applications";
import { customerEarnings } from "@/lib/customers/customer-earnings";

// ₹10,000 · fee ₹1,000 · GST ₹180 · 27 days → ₹12,700 repayable (CLAUDE.md §9 worked example).
const base: LoanView = {
  id: 1, customerId: 7, principalPaise: 1_000_000, processingFeePaise: 100_000, gstPaise: 18_000,
  netDisbursedPaise: 882_000, dailyInterestRate: 0.01, disbursedOn: "2026-06-03", dueDate: "2026-06-30",
  totalRepayablePaise: 1_270_000, outstandingPaise: 0, status: "CLOSED", disbursalTxnRef: "T1", closedOn: "2026-06-30",
};
const out = (loanId: number, o: Partial<OutstandingView>): OutstandingView => ({ loanId, asOf: "2026-07-15", outstandingPaise: 0, ...o });
const pay = (loanId: number, amountPaise: number, status: PaymentView["status"] = "VERIFIED"): PaymentView =>
  ({ id: loanId * 10, loanId, amountPaise, method: "UPI", status, txnRef: null, proofUrl: null, paidOn: "2026-06-30", partial: false });
const today = new Date(2026, 6, 15);

describe("customerEarnings", () => {
  it("fully repaid loan: fee + the whole interest, nothing outstanding; GST excluded", () => {
    const e = customerEarnings({
      loans: [base],
      payments: [pay(1, 1_270_000)],
      outstandingByLoanId: { "1": out(1, { interestPaise: 270_000, penaltyPaise: 0, verifiedPaise: 1_270_000 }) },
    }, today);
    expect(e).toMatchObject({
      loanCount: 1, processingFeesPaise: 100_000, interestReceivedPaise: 270_000, penaltyReceivedPaise: 0,
      totalEarnedPaise: 370_000, outstandingPaise: 0, waivedPaise: 0, settledLoanCount: 0,
    });
    // GST (₹180) appears in no figure: fee + interest only.
    expect(e.totalEarnedPaise).toBe(e.processingFeesPaise + e.interestReceivedPaise + e.penaltyReceivedPaise);
    expect(e.totalEarnedPaise).not.toBe(370_000 + base.gstPaise);
  });

  it("open loan: the fee counts (taken upfront) but accrued-not-paid interest counts 0", () => {
    const running: LoanView = { ...base, id: 2, status: "ACTIVE", closedOn: null, outstandingPaise: 1_120_000 };
    const e = customerEarnings({
      loans: [running],
      payments: [],
      outstandingByLoanId: { "2": out(2, { outstandingPaise: 1_120_000, interestPaise: 120_000, penaltyPaise: 0, verifiedPaise: 0 }) },
    }, today);
    expect(e).toMatchObject({
      processingFeesPaise: 100_000, interestReceivedPaise: 0, penaltyReceivedPaise: 0,
      totalEarnedPaise: 100_000, outstandingPaise: 1_120_000, waivedPaise: 0,
    });
  });

  it("open loan with a part-payment: the money received settles penalty, then interest, then principal", () => {
    const running: LoanView = { ...base, id: 3, status: "OVERDUE", closedOn: null, outstandingPaise: 1_000_000 };
    const e = customerEarnings({
      loans: [running],
      payments: [pay(3, 350_000)],
      outstandingByLoanId: { "3": out(3, { outstandingPaise: 1_000_000, interestPaise: 300_000, penaltyPaise: 50_000, verifiedPaise: 350_000 }) },
    }, today);
    expect(e).toMatchObject({ penaltyReceivedPaise: 50_000, interestReceivedPaise: 300_000, totalEarnedPaise: 450_000, outstandingPaise: 1_000_000 });
  });

  it("settled loan: principal is recovered first, the shortfall is waived", () => {
    // Dues ₹10,000 + ₹3,000 interest + ₹1,500 penalty = ₹14,500; settled full-and-final at ₹11,000.
    const settled: LoanView = { ...base, id: 4, closedOn: "2026-08-20", outstandingPaise: 0 };
    const e = customerEarnings({
      loans: [settled],
      payments: [pay(4, 1_100_000)],
      outstandingByLoanId: {
        "4": out(4, { outstandingPaise: 0, settledAmountPaise: 1_100_000, interestPaise: 300_000, penaltyPaise: 150_000, verifiedPaise: 1_100_000 }),
      },
    }, today);
    expect(e).toMatchObject({
      processingFeesPaise: 100_000,
      penaltyReceivedPaise: 100_000, // the ₹1,000 above principal goes to penalty first
      interestReceivedPaise: 0,
      totalEarnedPaise: 200_000,
      waivedPaise: 350_000,
      settledLoanCount: 1,
      outstandingPaise: 0,
    });
  });

  it("settled below principal earns nothing beyond the fee, never negative", () => {
    const settled: LoanView = { ...base, id: 5, closedOn: "2026-08-20" };
    const e = customerEarnings({
      loans: [settled],
      payments: [pay(5, 900_000)],
      outstandingByLoanId: { "5": out(5, { settledAmountPaise: 900_000, interestPaise: 300_000, penaltyPaise: 150_000, verifiedPaise: 900_000 }) },
    }, today);
    expect(e).toMatchObject({ interestReceivedPaise: 0, penaltyReceivedPaise: 0, totalEarnedPaise: 100_000, waivedPaise: 550_000 });
  });

  it("sums across loans; a loan never disbursed contributes no fee", () => {
    const undisbursed: LoanView = { ...base, id: 6, status: "SANCTIONED", disbursedOn: null, dueDate: null, closedOn: null, outstandingPaise: 0 };
    const e = customerEarnings({
      loans: [base, undisbursed],
      payments: [pay(1, 1_270_000)],
      outstandingByLoanId: { "1": out(1, { interestPaise: 270_000, penaltyPaise: 0, verifiedPaise: 1_270_000 }) },
    }, today);
    expect(e.loanCount).toBe(2);
    expect(e.processingFeesPaise).toBe(100_000);
    expect(e.totalEarnedPaise).toBe(370_000);
  });

  it("no outstanding rows: a closed loan falls back to the contract figures", () => {
    const e = customerEarnings({ loans: [base], payments: [] }, today);
    expect(e).toMatchObject({ interestReceivedPaise: 270_000, totalEarnedPaise: 370_000, outstandingPaise: 0 });
  });

  it("adds the lending position and the cash view", () => {
    const running: LoanView = { ...base, id: 2, status: "ACTIVE", closedOn: null };
    const e = customerEarnings(
      { loans: [base, running], payments: [], outstandingByLoanId: {} },
      new Date(2026, 9, 17),
    );
    expect(e.disbursedLoanCount).toBe(2);
    expect(e.closedLoanCount + e.openLoanCount).toBe(2);
    expect(e.totalLentPaise).toBe(2 * base.principalPaise);
    expect(e.currentlyDisbursedPaise).toBe(running.principalPaise);
    expect(e.cashOutPaise).toBe(2 * (base.netDisbursedPaise + base.gstPaise));
    expect(e.netCashPaise).toBe(e.cashInPaise - e.cashOutPaise);
    expect(e.profitOnLentRatio).toBeCloseTo(e.totalEarnedPaise / e.totalLentPaise);
  });

  it("has no profit ratio when nothing was ever lent", () => {
    expect(customerEarnings({ loans: [], payments: [] }, new Date()).profitOnLentRatio).toBeNull();
  });
});
