import { describe, expect, it } from "vitest";
import { buildLedger } from "@/lib/calc/loan-ledger";
import type { LoanView, OutstandingView, PaymentView } from "@/lib/api/applications";

const loan = (over: Partial<LoanView> = {}): LoanView => ({
  id: 208, customerId: 1, principalPaise: 500_000, processingFeePaise: 50_000, gstPaise: 9_000,
  netDisbursedPaise: 441_000, dailyInterestRate: 0.01, disbursedOn: "2026-10-08", dueDate: "2026-11-10",
  totalRepayablePaise: 665_000, outstandingPaise: 505_000, status: "ACTIVE", disbursalTxnRef: "X", closedOn: null,
  ...over,
});
const out = (over: Partial<OutstandingView> = {}): OutstandingView => ({
  loanId: 208, asOf: "2026-10-09", outstandingPaise: 505_000, interestPaise: 5_000, penaltyPaise: 0,
  verifiedPaise: 0, interestDays: 1, penaltyDays: 0, ...over,
});
const row = (l: ReturnType<typeof buildLedger>, k: string) => l.rows.find((r) => r.key === k)!;

describe("buildLedger", () => {
  it("active, 1 day held", () => {
    const l = buildLedger({ loan: loan(), outstanding: out(), payments: [], today: new Date(2026, 9, 9) });
    expect(row(l, "interest")).toMatchObject({ payablePaise: 165_000, dueTodayPaise: 5_000, days: 33 });
    expect(row(l, "total")).toMatchObject({ payablePaise: 665_000, dueTodayPaise: 505_000, receivedPaise: 0, outstandingPaise: 505_000 });
    expect(l.daysSinceDisbursal).toBe(1);
    expect(l.daysToDue).toBe(32);
    expect(l.graceDay).toEqual(new Date(2026, 10, 11));
    expect(l.isOverdue).toBe(false);
  });

  it("overdue: the server figure wins for outstanding", () => {
    const l = buildLedger({
      loan: loan({ status: "OVERDUE" }),
      outstanding: out({ interestPaise: 170_000, penaltyPaise: 90_000, outstandingPaise: 765_000, penaltyDays: 9 }),
      payments: [], today: new Date(2026, 10, 20),
    });
    expect(row(l, "penalty")).toMatchObject({ dueTodayPaise: 90_000, dueDays: 9 });
    expect(row(l, "total").outstandingPaise).toBe(765_000);
    expect(l.isOverdue).toBe(true);
    expect(l.daysOverdue).toBe(10);
  });

  it("closed: received = payable, outstanding 0", () => {
    const l = buildLedger({
      loan: loan({ status: "CLOSED", outstandingPaise: 0 }), outstanding: null, payments: [], today: new Date(2026, 10, 20),
    });
    expect(row(l, "total")).toMatchObject({ receivedPaise: 665_000, outstandingPaise: 0 });
    expect(l.isOverdue).toBe(false);
  });

  it("closed early: received is the frozen server figure, not the contract", () => {
    const l = buildLedger({
      loan: loan({ status: "CLOSED", outstandingPaise: 0 }),
      outstanding: out({ outstandingPaise: 0, interestPaise: 0, penaltyPaise: 0, verifiedPaise: 525_000 }),
      payments: [], today: new Date(2026, 10, 20),
    });
    expect(row(l, "total")).toMatchObject({ receivedPaise: 525_000, outstandingPaise: 0 });
    expect(row(l, "total").receivedPaise).toBeLessThan(665_000);
  });

  it("falls back to verified payments when the server breakdown is absent", () => {
    const p = [
      { id: 1, loanId: 208, amountPaise: 100_000, status: "VERIFIED" },
      { id: 2, loanId: 208, amountPaise: 50_000, status: "PENDING_VERIFICATION" },
    ] as PaymentView[];
    const l = buildLedger({ loan: loan(), outstanding: null, payments: p, today: new Date(2026, 9, 9) });
    expect(row(l, "total").receivedPaise).toBe(100_000);
  });
});
