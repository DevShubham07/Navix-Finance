import { describe, expect, it } from "vitest";
import { ledgerRowsCaption } from "./transactions-ledger";

describe("ledgerRowsCaption", () => {
  it("names the period and the row count", () => {
    expect(ledgerRowsCaption("This month", 42)).toBe("This month · 42 rows");
  });

  it("uses the singular for one row and the plural for none", () => {
    expect(ledgerRowsCaption("Today", 1)).toBe("Today · 1 row");
    expect(ledgerRowsCaption("Today", 0)).toBe("Today · 0 rows");
  });

  it("groups large counts the Indian way", () => {
    expect(ledgerRowsCaption("All time", 123456)).toBe("All time · 1,23,456 rows");
  });

  it("never prints a negative or fractional count", () => {
    expect(ledgerRowsCaption("This year", -3)).toBe("This year · 0 rows");
    expect(ledgerRowsCaption("This year", 7.9)).toBe("This year · 7 rows");
  });
});
