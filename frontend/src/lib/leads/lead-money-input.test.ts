import { describe, expect, it } from "vitest";
import { rupeesToPaise } from "@/lib/api/applications";
import { parseLeadRupees } from "./lead-money-input";

describe("parseLeadRupees", () => {
  it("treats a blank box as empty (the field is optional)", () => {
    expect(parseLeadRupees("")).toEqual({ kind: "empty" });
    expect(parseLeadRupees("   ")).toEqual({ kind: "empty" });
  });

  it("accepts plain whole rupees and returns paise", () => {
    expect(parseLeadRupees("25000")).toEqual({ kind: "ok", paise: 2_500_000 });
    expect(parseLeadRupees(" 25000 ")).toEqual({ kind: "ok", paise: 2_500_000 });
  });

  it("accepts Indian grouping", () => {
    expect(parseLeadRupees("25,000")).toEqual({ kind: "ok", paise: 2_500_000 });
    expect(parseLeadRupees("2,50,000")).toEqual({ kind: "ok", paise: 25_000_000 });
    expect(parseLeadRupees("12,34,567")).toEqual({ kind: "ok", paise: 123_456_700 });
  });

  it("accepts Western grouping", () => {
    expect(parseLeadRupees("250,000")).toEqual({ kind: "ok", paise: 25_000_000 });
    expect(parseLeadRupees("1,234,567")).toEqual({ kind: "ok", paise: 123_456_700 });
  });

  it("accepts a leading rupee sign", () => {
    expect(parseLeadRupees("₹25,000")).toEqual({ kind: "ok", paise: 2_500_000 });
    expect(parseLeadRupees("₹ 25000")).toEqual({ kind: "ok", paise: 2_500_000 });
  });

  it("keeps up to two decimal places as paise, worked out from the digits", () => {
    expect(parseLeadRupees("25000.5")).toEqual({ kind: "ok", paise: 2_500_050 });
    expect(parseLeadRupees("25,000.75")).toEqual({ kind: "ok", paise: 2_500_075 });
    expect(parseLeadRupees("25000.")).toEqual({ kind: "ok", paise: 2_500_000 });
    // 0.29 * 100 is 28.999… in floating point; the digit-based path cannot round it the wrong way.
    expect(parseLeadRupees("0.29")).toEqual({ kind: "ok", paise: 29 });
  });

  it("sends what the form sent before for any plain whole-rupee value", () => {
    for (const v of ["1", "999", "25000", "150000", "9999999"]) {
      expect(parseLeadRupees(v)).toEqual({ kind: "ok", paise: rupeesToPaise(Number(v)) });
    }
  });

  it("rejects values the old Number(x) > 0 gate silently dropped", () => {
    expect(parseLeadRupees("-500")).toEqual({ kind: "invalid" });
    expect(parseLeadRupees("abc")).toEqual({ kind: "invalid" });
    expect(parseLeadRupees("25k")).toEqual({ kind: "invalid" });
  });

  it("rejects zero, which is not a positive amount", () => {
    expect(parseLeadRupees("0")).toEqual({ kind: "invalid" });
    expect(parseLeadRupees("0.00")).toEqual({ kind: "invalid" });
    expect(parseLeadRupees("0,000")).toEqual({ kind: "invalid" });
  });

  it("rejects misplaced or doubled separators rather than guessing", () => {
    expect(parseLeadRupees("25,00")).toEqual({ kind: "invalid" });
    expect(parseLeadRupees("2,5000")).toEqual({ kind: "invalid" });
    expect(parseLeadRupees(",25000")).toEqual({ kind: "invalid" });
    expect(parseLeadRupees("25,000,")).toEqual({ kind: "invalid" });
    expect(parseLeadRupees("25,,000")).toEqual({ kind: "invalid" });
    expect(parseLeadRupees("25000.505")).toEqual({ kind: "invalid" });
    expect(parseLeadRupees("1.2.3")).toEqual({ kind: "invalid" });
    expect(parseLeadRupees(".")).toEqual({ kind: "invalid" });
    expect(parseLeadRupees("25 000")).toEqual({ kind: "invalid" });
  });

  it("rejects an amount too large to hold as exact integer paise", () => {
    expect(parseLeadRupees("999999999999999999")).toEqual({ kind: "invalid" });
  });
});
