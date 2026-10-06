import { describe, expect, it } from "vitest";
import { parsePositiveRupees, sanitizeRupeeInput } from "./rupee-amount-input";

describe("sanitizeRupeeInput", () => {
  it("keeps plain digits", () => {
    expect(sanitizeRupeeInput("25000")).toBe("25000");
  });

  it("drops symbols, separators and spaces", () => {
    expect(sanitizeRupeeInput("₹1,000.50")).toBe("1000.50");
    expect(sanitizeRupeeInput(" 5 000 ")).toBe("5000");
    expect(sanitizeRupeeInput("-200")).toBe("200");
  });

  it("collapses to one decimal point", () => {
    expect(sanitizeRupeeInput("12.5.0")).toBe("12.50");
    expect(sanitizeRupeeInput("1..2")).toBe("1.2");
  });

  it("keeps at most two fraction digits", () => {
    expect(sanitizeRupeeInput("10.999")).toBe("10.99");
    expect(sanitizeRupeeInput("0.001")).toBe("0.00");
  });

  it("leaves a half-typed amount alone so the user can keep typing", () => {
    expect(sanitizeRupeeInput("100.")).toBe("100.");
    expect(sanitizeRupeeInput(".")).toBe(".");
    expect(sanitizeRupeeInput(".5")).toBe(".5");
    expect(sanitizeRupeeInput("")).toBe("");
  });
});

describe("parsePositiveRupees", () => {
  it("parses a whole or decimal amount", () => {
    expect(parsePositiveRupees("25000")).toBe(25000);
    expect(parsePositiveRupees("10.29")).toBe(10.29);
    expect(parsePositiveRupees("100.")).toBe(100);
    expect(parsePositiveRupees(".5")).toBe(0.5);
  });

  it("is null for nothing to submit", () => {
    expect(parsePositiveRupees("")).toBeNull();
    expect(parsePositiveRupees(".")).toBeNull();
    expect(parsePositiveRupees("abc")).toBeNull();
  });

  it("is null for zero", () => {
    expect(parsePositiveRupees("0")).toBeNull();
    expect(parsePositiveRupees("0.00")).toBeNull();
    expect(parsePositiveRupees("0.001")).toBeNull();
  });

  it("sanitises before parsing", () => {
    expect(parsePositiveRupees("₹1,000.505")).toBe(1000.5);
  });

  it("is null when the paise would not be an exact integer", () => {
    expect(parsePositiveRupees("99999999999999999")).toBeNull();
  });
});
