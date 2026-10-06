import { describe, expect, it } from "vitest";
import { bulkAssignProgressLabel, perLoanAssignOutcomes } from "./bulk-assign-outcomes";

describe("perLoanAssignOutcomes", () => {
  it("merges the two lists back into submission order", () => {
    expect(
      perLoanAssignOutcomes([5, 3, 9], { ok: [5, 9], failed: [{ id: 3, message: "Loan is closed" }] }),
    ).toEqual([
      { loanId: 5, ok: true },
      { loanId: 3, ok: false, message: "Loan is closed" },
      { loanId: 9, ok: true },
    ]);
  });

  it("never claims an unreported loan was assigned", () => {
    expect(perLoanAssignOutcomes([1, 2], { ok: [1], failed: [] })).toEqual([
      { loanId: 1, ok: true },
      { loanId: 2, ok: false, message: "No result recorded" },
    ]);
  });

  it("is empty for no loans", () => {
    expect(perLoanAssignOutcomes([], { ok: [], failed: [] })).toEqual([]);
  });
});

describe("bulkAssignProgressLabel", () => {
  it("counts successes against the total", () => {
    expect(bulkAssignProgressLabel({ ok: 12, failed: 0, total: 40 })).toBe("12 / 40 assigned");
  });

  it("adds the failures once there are any", () => {
    expect(bulkAssignProgressLabel({ ok: 12, failed: 2, total: 40 })).toBe("12 / 40 assigned, 2 failed");
  });
});
