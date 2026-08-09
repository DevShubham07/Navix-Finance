import { describe, expect, it } from "vitest";

async function subject() {
  return import("./borrower-flow").catch(() => ({}));
}

describe("borrower flow presentation rules", () => {
  it("adds calendar days without shifting the date in positive UTC offsets", async () => {
    const mod = await subject() as Record<string, unknown>;
    expect(typeof mod.addIsoCalendarDays).toBe("function");
    expect((mod.addIsoCalendarDays as (iso: string, days: number) => string)("2026-09-13", 1))
      .toBe("2026-09-14");
  });

  it("shows the credit-team sanction before the salary-derived eligible limit", async () => {
    const mod = await subject() as Record<string, unknown>;
    expect(typeof mod.preferredApprovedAmountPaise).toBe("function");
    expect((mod.preferredApprovedAmountPaise as (value: {
      sanctionedAmountPaise?: number | null;
      eligibleLimitPaise?: number | null;
    }) => number | null)({ sanctionedAmountPaise: 2_000_000, eligibleLimitPaise: 1_250_000 }))
      .toBe(2_000_000);
  });

  it("falls back to the eligible limit only for a legacy application without a sanction", async () => {
    const mod = await subject() as Record<string, unknown>;
    expect(typeof mod.preferredApprovedAmountPaise).toBe("function");
    expect((mod.preferredApprovedAmountPaise as (value: {
      sanctionedAmountPaise?: number | null;
      eligibleLimitPaise?: number | null;
    }) => number | null)({ sanctionedAmountPaise: null, eligibleLimitPaise: 1_250_000 }))
      .toBe(1_250_000);
  });

  it("reveals manual address only when geo resolution did not produce an address", async () => {
    const mod = await subject() as Record<string, unknown>;
    expect(typeof mod.needsManualAddressFallback).toBe("function");
    const fn = mod.needsManualAddressFallback as (result: {
      status: string;
      derived?: Record<string, unknown>;
    }) => boolean;
    expect(fn({ status: "PASS", derived: { address: "Delhi, India" } })).toBe(false);
    expect(fn({ status: "REVIEW", derived: { providerError: true } })).toBe(true);
    expect(fn({ status: "REVIEW", derived: {} })).toBe(true);
  });
});
