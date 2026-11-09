import { describe, expect, it } from "vitest";
import type { LoanView, OutstandingView } from "@/lib/api/applications";
import { loanBehaviour } from "@/lib/customers/loan-behaviour";

const base: LoanView = {
  id: 169, customerId: 7, principalPaise: 1_000_000, processingFeePaise: 100_000, gstPaise: 18_000,
  netDisbursedPaise: 882_000, dailyInterestRate: 0.01, disbursedOn: "2026-06-03", dueDate: "2026-06-30",
  totalRepayablePaise: 1_270_000, outstandingPaise: 0, status: "CLOSED", disbursalTxnRef: "T1", closedOn: "2026-06-30",
};
const closedOn = (iso: string): LoanView => ({ ...base, closedOn: iso });
const open = (status = "ACTIVE"): LoanView => ({ ...base, status, closedOn: null, outstandingPaise: 1_270_000 });
const d = (iso: string) => {
  const [y, m, dd] = iso.split("-").map(Number);
  return new Date(y, m - 1, dd);
};
const today = d("2026-07-15");

describe("loanBehaviour — closed loans", () => {
  it("3 days before due is early (boundary)", () => {
    expect(loanBehaviour(closedOn("2026-06-27"), today)).toMatchObject({ kind: "early", label: "Paid 3 days early", daysVsDue: -3 });
  });
  it("well before due is early with the day count", () => {
    expect(loanBehaviour(closedOn("2026-06-25"), today)).toMatchObject({ kind: "early", label: "Paid 5 days early", daysVsDue: -5 });
  });
  it("2 days before due is on time (boundary)", () => {
    expect(loanBehaviour(closedOn("2026-06-28"), today)).toMatchObject({ kind: "onTime", label: "Paid on time", daysVsDue: -2 });
  });
  it("on the due date is on time", () => {
    expect(loanBehaviour(closedOn("2026-06-30"), today)).toMatchObject({ kind: "onTime", daysVsDue: 0 });
  });
  it("the grace day (due + 1) is still on time (boundary)", () => {
    expect(loanBehaviour(closedOn("2026-07-01"), today)).toMatchObject({ kind: "onTime", daysVsDue: 1 });
  });
  it("due + 1 is late on a loan written without a grace day", () => {
    expect(loanBehaviour({ ...closedOn("2026-07-01"), graceDays: 0 }, today)).toMatchObject({ kind: "late", label: "Paid 1 day late", daysVsDue: 1 });
  });
  it("2 days after due is late (boundary)", () => {
    expect(loanBehaviour(closedOn("2026-07-02"), today)).toMatchObject({ kind: "late", label: "Paid 2 days late", daysVsDue: 2 });
  });
  it("7 days after due is still late (boundary)", () => {
    expect(loanBehaviour(closedOn("2026-07-07"), today)).toMatchObject({ kind: "late", label: "Paid 7 days late", daysVsDue: 7 });
  });
  it("8 days after due is very late (boundary)", () => {
    expect(loanBehaviour(closedOn("2026-07-08"), today)).toMatchObject({ kind: "veryLate", label: "Paid 8 days late", daysVsDue: 8 });
  });
  it("12 days late reads as very late", () => {
    expect(loanBehaviour(closedOn("2026-07-12"), today)).toMatchObject({ kind: "veryLate", label: "Paid 12 days late" });
  });
  it("an approved settlement is very late / Settled, even if it closed early", () => {
    const out: OutstandingView = { loanId: 169, asOf: "2026-06-20", outstandingPaise: 900_000, settledAmountPaise: 900_000 };
    expect(loanBehaviour(closedOn("2026-06-20"), today, out)).toMatchObject({ kind: "veryLate", label: "Settled", daysVsDue: -10 });
  });
  it("settledAmountPaise null is not a settlement", () => {
    const out: OutstandingView = { loanId: 169, asOf: "2026-06-20", outstandingPaise: 0, settledAmountPaise: null };
    expect(loanBehaviour(closedOn("2026-06-25"), today, out).kind).toBe("early");
  });
  it("CLOSED with no closedOn falls back to a neutral Paid", () => {
    expect(loanBehaviour({ ...base, closedOn: null }, today)).toMatchObject({ kind: "onTime", label: "Paid", daysVsDue: null });
  });
});

describe("loanBehaviour — running loans", () => {
  it("before the due date is open with the countdown", () => {
    expect(loanBehaviour(open(), d("2026-06-18"))).toMatchObject({ kind: "open", label: "Due in 12 days", daysVsDue: -12 });
  });
  it("one day before due is singular", () => {
    expect(loanBehaviour(open(), d("2026-06-29")).label).toBe("Due in 1 day");
  });
  it("on the due date is open, 'Due today' (boundary)", () => {
    expect(loanBehaviour(open(), d("2026-06-30"))).toMatchObject({ kind: "open", label: "Due today", daysVsDue: 0 });
  });
  it("the day after due is overdue (boundary)", () => {
    expect(loanBehaviour(open("OVERDUE"), d("2026-07-01"))).toMatchObject({ kind: "overdue", label: "1 day overdue", daysVsDue: 1 });
  });
  it("well past due counts the days", () => {
    expect(loanBehaviour(open("OVERDUE"), today)).toMatchObject({ kind: "overdue", label: "15 days overdue", daysVsDue: 15 });
  });
  it("status alone does not decide: an ACTIVE loan past due is overdue, a DEFAULTED one too", () => {
    expect(loanBehaviour(open("ACTIVE"), today).kind).toBe("overdue");
    expect(loanBehaviour(open("DEFAULTED"), today).kind).toBe("overdue");
  });
  it("no due date yet is open", () => {
    expect(loanBehaviour({ ...open(), dueDate: null }, today)).toMatchObject({ kind: "open", label: "No due date yet", daysVsDue: null });
  });
});

describe("loanBehaviour — the short wording on the pill face", () => {
  const short = (l: LoanView, out?: OutstandingView) => loanBehaviour(l, today, out).short;
  it("reads days early / on time / days late / settled", () => {
    expect(short(closedOn("2026-06-25"))).toBe("5d early");
    expect(short(closedOn("2026-06-30"))).toBe("on time");
    expect(short(closedOn("2026-07-04"))).toBe("4d late");
    expect(short(closedOn("2026-07-12"))).toBe("12d late");
    const out: OutstandingView = { loanId: 169, asOf: "2026-06-20", outstandingPaise: 0, settledAmountPaise: 900_000 };
    expect(short(closedOn("2026-06-20"), out)).toBe("settled");
    expect(short({ ...base, closedOn: null })).toBe("paid");
  });
  it("reads the countdown or the overdue days on a running loan", () => {
    expect(loanBehaviour({ ...open(), dueDate: "2026-07-20" }, today).short).toBe("due in 5d");
    expect(loanBehaviour({ ...open(), dueDate: "2026-07-15" }, today).short).toBe("due today");
    expect(loanBehaviour(open(), today).short).toBe("15d overdue");
    expect(loanBehaviour({ ...open(), dueDate: null }, today).short).toBe("no due date");
  });
});
