import { describe, expect, it } from "vitest";
import { customerRowTarget, dateWindowChanged } from "./customers-register";

describe("customerRowTarget", () => {
  it("opens the row's latest application when it has one", () => {
    expect(customerRowTarget({ customerId: 42, latestApplicationId: 318 })).toEqual({
      kind: "application",
      applicationId: 318,
    });
  });

  it("falls back to the customer when the row has no application", () => {
    expect(customerRowTarget({ customerId: 42, latestApplicationId: null })).toEqual({
      kind: "customer",
      customerId: 42,
    });
    expect(customerRowTarget({ customerId: 42 })).toEqual({ kind: "customer", customerId: 42 });
  });

  it("treats application id 0 as a real id, not as missing", () => {
    expect(customerRowTarget({ customerId: 42, latestApplicationId: 0 })).toEqual({
      kind: "application",
      applicationId: 0,
    });
  });
});

describe("dateWindowChanged", () => {
  it("is false for two open-ended windows, however they are spelled", () => {
    expect(dateWindowChanged({}, {})).toBe(false);
    expect(dateWindowChanged({ from: "", to: "" }, {})).toBe(false);
    expect(dateWindowChanged({ from: undefined }, { to: "" })).toBe(false);
  });

  it("is false for the same bounded window", () => {
    expect(dateWindowChanged({ from: "2026-10-06", to: "2026-10-06" }, { from: "2026-10-06", to: "2026-10-06" })).toBe(
      false,
    );
  });

  it("is true when either bound moves, appears or disappears", () => {
    expect(dateWindowChanged({}, { from: "2026-10-06", to: "2026-10-06" })).toBe(true);
    expect(dateWindowChanged({ from: "2026-10-05", to: "2026-10-05" }, { from: "2026-10-06", to: "2026-10-05" })).toBe(
      true,
    );
    expect(dateWindowChanged({ from: "2026-10-01", to: "2026-10-06" }, { from: "2026-10-01" })).toBe(true);
  });
});
