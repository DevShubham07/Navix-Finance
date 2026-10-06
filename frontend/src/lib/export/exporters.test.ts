import { describe, expect, it } from "vitest";
import { csvCell } from "@/lib/export/exporters";

describe("csvCell", () => {
  it("neutralises cells a spreadsheet would run as a formula", () => {
    expect(csvCell("=HYPERLINK(\"http://x\",\"y\")")).toBe("\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\"");
    expect(csvCell("@SUM(A1)")).toBe("'@SUM(A1)");
    expect(csvCell("+cmd|' /C calc'!A0")).toBe("'+cmd|' /C calc'!A0");
    expect(csvCell("-2+3")).toBe("'-2+3");
  });

  it("leaves ordinary values and real negative numbers alone", () => {
    expect(csvCell("-1250.50")).toBe("-1250.50");
    expect(csvCell(-5)).toBe("-5");
    expect(csvCell("12 MG Road, Pune")).toBe("\"12 MG Road, Pune\"");
    expect(csvCell("—")).toBe("—");
    expect(csvCell(null)).toBe("");
  });
});
