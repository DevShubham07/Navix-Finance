import { describe, expect, it } from "vitest";
import { formatEventNotes } from "./event-notes";

describe("formatEventNotes", () => {
  it("renders paise amounts in rupees", () => {
    expect(formatEventNotes("amountPaise=500000")).toBe("Amount ₹5,000");
  });
  it("labels loan ids and keeps a trailing remark", () => {
    expect(formatEventNotes("loanId=214")).toBe("Loan #214");
    expect(formatEventNotes("amountPaise=1000000 salaryCreditDay=30 projectedRepaymentDate=2026-11-30 — looks fine"))
      .toBe("Amount ₹10,000 · Salary day 30 · Repayment date 30 Nov 2026 · looks fine");
  });
  it("describes a reassignment", () => {
    expect(formatEventNotes("previousExecutiveId=4 newExecutiveId=9")).toBe("From executive #4 · To executive #9");
  });
  it("leaves prose and transaction references untouched", () => {
    expect(formatEventNotes("Pre-approved returning borrower")).toBe("Pre-approved returning borrower");
    expect(formatEventNotes("Txn/ref: INDBH15100008525")).toBe("Txn/ref: INDBH15100008525");
    expect(formatEventNotes(null)).toBeNull();
  });
});
