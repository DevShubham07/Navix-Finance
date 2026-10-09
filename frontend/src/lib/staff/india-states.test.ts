import { describe, expect, it } from "vitest";
import {
  CANONICAL_STATES,
  LARGE_CASES,
  classifySegment,
  normaliseState,
  stateLabel,
} from "./india-states";

describe("normaliseState", () => {
  it.each([
    ["ORISSA", "Odisha"],
    ["pondicherry", "Puducherry"],
    ["J&K", "Jammu & Kashmir"],
    ["JAMMU AND KASHMIR", "Jammu & Kashmir"],
    ["Jammu & Kashmir", "Jammu & Kashmir"],
    ["NCT OF DELHI", "Delhi"],
    ["ANDAMAN AND NICOBAR ISLANDS", "Andaman & Nicobar"],
    ["DADRA AND NAGAR HAVELI", "Dadra and Nagar Haveli and Daman and Diu"],
    ["Daman & Diu", "Dadra and Nagar Haveli and Daman and Diu"],
    ["UTTARANCHAL", "Uttarakhand"],
    ["  tamil   nadu ", "Tamil Nadu"],
    ["KARNATAKA", "Karnataka"],
  ])("%s -> %s", (raw, want) => expect(normaliseState(raw)).toBe(want));

  it("maps every canonical name to itself", () => {
    for (const s of CANONICAL_STATES) expect(normaliseState(s)).toBe(s);
  });

  it("returns null / Unknown for anything unrecognised", () => {
    expect(normaliseState("Atlantis")).toBeNull();
    expect(normaliseState("")).toBeNull();
    expect(normaliseState(null)).toBeNull();
    expect(stateLabel("Atlantis")).toBe("Unknown");
  });
});

describe("classifySegment", () => {
  const row = (cases: number, closeRate: number | null, prevPeriodCases = 0) => ({ cases, closeRate, prevPeriodCases });

  it("large locations split on close rate", () => {
    expect(classifySegment(row(LARGE_CASES.PINCODE, 0.9))).toBe("LARGE");
    expect(classifySegment(row(LARGE_CASES.PINCODE, 0.4))).toBe("LARGE_WEAK_CLOSE");
  });

  it("uses the state-level size threshold for states", () => {
    expect(classifySegment(row(LARGE_CASES.PINCODE, 0.9), "STATE")).toBe("BEST");
    expect(classifySegment(row(LARGE_CASES.STATE, 0.9), "STATE")).toBe("LARGE");
  });

  it("small locations by close rate", () => {
    expect(classifySegment(row(5, 0.95))).toBe("BEST");
    expect(classifySegment(row(1, 0.95))).toBe("BETTER"); // one loan is not a trend
    expect(classifySegment(row(5, 0.75))).toBe("BETTER");
    expect(classifySegment(row(5, 0.3))).toBe("NEEDS_ATTENTION");
  });

  it("middling close rate is Growing only when cases rose", () => {
    expect(classifySegment(row(5, 0.6, 2))).toBe("GROWING");
    expect(classifySegment(row(5, 0.6, 9))).toBe("NEEDS_ATTENTION");
  });

  it("no measurable close rate is never punished", () => {
    expect(classifySegment(row(4, null, 1))).toBe("GROWING");
    expect(classifySegment(row(4, null, 4))).toBe("BETTER");
  });
});
