import { describe, expect, it } from "vitest";
import type { PaymentView } from "@/lib/api/applications";
import {
  collectedPct, dpdDays, lastVerifiedPayment, loanCycle, ordinal, penaltyHeadroom, recoveredVsDisbursed,
} from "@/lib/calc/loan-kpis";

describe("loan-kpis", () => {
  it("ordinals", () => {
    expect([1, 2, 3, 4, 11, 12, 13, 21, 22].map(ordinal)).toEqual(["1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd"]);
  });
  it("cycle is the id-order position", () => {
    expect(loanCycle([{ id: 9 }, { id: 3 }], 9)).toBe(2);
    expect(loanCycle([{ id: 3 }], 4)).toBeNull();
  });
  it("dpd: open vs closed vs not due", () => {
    const today = new Date(2026, 9, 10);
    expect(dpdDays({ dueDate: "2026-10-05", closedOn: null, status: "OVERDUE" }, today)).toBe(5);
    expect(dpdDays({ dueDate: "2026-10-05", closedOn: "2026-10-07", status: "CLOSED" }, today)).toBe(2);
    expect(dpdDays({ dueDate: "2026-10-20", closedOn: null, status: "ACTIVE" }, today)).toBe(0);
  });
  it("penalty headroom in paise", () => {
    expect(penaltyHeadroom(1_000_000, 3)).toEqual({ used: 3, remainingDays: 27, remainingPaise: 540_000 });
    expect(penaltyHeadroom(1_000_000, 45)).toEqual({ used: 30, remainingDays: 0, remainingPaise: 0 });
  });
  it("last verified payment ignores pending and other loans", () => {
    const p = (id: number, status: string, paidOn: string, loanId = 1) => ({ id, status, paidOn, loanId, amountPaise: 1 }) as PaymentView;
    expect(lastVerifiedPayment([p(1, "VERIFIED", "2026-10-01"), p(2, "VERIFIED", "2026-10-03"), p(3, "PENDING_VERIFICATION", "2026-10-09"), p(4, "VERIFIED", "2026-10-09", 2)], 1)?.id).toBe(2);
    expect(lastVerifiedPayment([], 1)).toBeNull();
  });
  it("collected % and recovered", () => {
    expect(collectedPct(332_500, 665_000)).toBe(50);
    expect(collectedPct(1, 0)).toBeNull();
    expect(recoveredVsDisbursed(441_000, 441_000)).toBe(0);
    expect(recoveredVsDisbursed(100_000, 441_000)).toBe(-341_000);
  });
});
