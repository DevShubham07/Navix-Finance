import { describe, expect, it } from "vitest";
import { formatInrCompact } from "./format-inr";

const rs = (r: number) => r * 100; // rupees -> paise

describe("formatInrCompact", () => {
  it("returns an em dash for null / undefined / NaN", () => {
    expect(formatInrCompact(null)).toBe("—");
    expect(formatInrCompact(undefined)).toBe("—");
    expect(formatInrCompact(Number.NaN)).toBe("—");
  });

  it("shows full grouped rupees below one lakh", () => {
    expect(formatInrCompact(0)).toBe("₹0");
    expect(formatInrCompact(rs(999))).toBe("₹999");
    expect(formatInrCompact(rs(12_500))).toBe("₹12,500");
    expect(formatInrCompact(rs(99_999))).toBe("₹99,999");
  });

  it("switches to lakh at 1,00,000", () => {
    expect(formatInrCompact(rs(100_000))).toBe("₹1 L");
    expect(formatInrCompact(rs(1_330_000))).toBe("₹13.3 L");
    expect(formatInrCompact(rs(9_950_000))).toBe("₹99.5 L");
  });

  it("switches to crore at 1,00,00,000 and promotes a rounded-up 100 L", () => {
    expect(formatInrCompact(rs(10_000_000))).toBe("₹1 Cr");
    expect(formatInrCompact(rs(39_000_000))).toBe("₹3.9 Cr");
    expect(formatInrCompact(rs(9_999_000))).toBe("₹1 Cr");
  });

  it("keeps the sign on negatives", () => {
    expect(formatInrCompact(-rs(1_330_000))).toBe("-₹13.3 L");
    expect(formatInrCompact(-rs(500))).toBe("-₹500");
  });
});
