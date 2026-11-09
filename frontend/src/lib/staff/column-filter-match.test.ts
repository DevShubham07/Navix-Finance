/**
 * The filter predicates. Pure, so these are the cheap tests that pin the semantics the panel, the
 * URL and (later) the SQL builder all have to agree on.
 */
import { describe, expect, it } from "vitest";
import {
  BLANK,
  isEmptyFilter,
  istDay,
  matchesFilter,
  normaliseFilter,
  type ColumnFilter,
  type FilterCell,
} from "@/lib/staff/column-filter-match";

const cell = (over: Partial<FilterCell> = {}): FilterCell => ({
  text: "Credit Executive",
  raw: "CREDIT_EXECUTIVE",
  ...over,
});

describe("matchesFilter · in", () => {
  it("matches on the stored value, never the label", () => {
    const f: ColumnFilter = { type: "in", values: ["CREDIT_EXECUTIVE"] };
    expect(matchesFilter(f, cell())).toBe(true);
    // The label is what the reader ticks, but it is not what travels.
    expect(matchesFilter({ type: "in", values: ["Credit Executive"] }, cell())).toBe(false);
  });

  it("keeps two values apart when their labels collide", () => {
    // The repayment queue renders BANK_TRANSFER and NACH identically; filtering must not merge them.
    const bank = cell({ text: "Bank transfer", raw: "BANK_TRANSFER" });
    const nach = cell({ text: "Bank transfer", raw: "NACH" });
    const f: ColumnFilter = { type: "in", values: ["NACH"] };
    expect(matchesFilter(f, bank)).toBe(false);
    expect(matchesFilter(f, nach)).toBe(true);
  });

  it("matches an empty cell through the blank sentinel", () => {
    const empty = cell({ text: "", raw: "" });
    expect(matchesFilter({ type: "in", values: [BLANK] }, empty)).toBe(true);
    expect(matchesFilter({ type: "in", values: [BLANK] }, cell())).toBe(false);
  });
});

describe("matchesFilter · num", () => {
  const at = (n: number | null) => cell({ num: n });

  it("treats both bounds as inclusive", () => {
    const f: ColumnFilter = { type: "num", min: 1000, max: 5000 };
    expect(matchesFilter(f, at(1000))).toBe(true);
    expect(matchesFilter(f, at(5000))).toBe(true);
    expect(matchesFilter(f, at(999))).toBe(false);
    expect(matchesFilter(f, at(5001))).toBe(false);
  });

  it("supports an open end on either side", () => {
    expect(matchesFilter({ type: "num", min: 1000 }, at(1e9))).toBe(true);
    expect(matchesFilter({ type: "num", max: 1000 }, at(-5))).toBe(true);
  });

  it("excludes a row with no number", () => {
    // "amount over ₹5,000" must not return the rows whose amount nobody has set.
    expect(matchesFilter({ type: "num", min: 5000 }, at(null))).toBe(false);
  });
});

describe("matchesFilter · date", () => {
  const on = (d: string | null) => cell({ date: d });

  it("includes both endpoints", () => {
    const f: ColumnFilter = { type: "date", from: "2026-01-01", to: "2026-01-31" };
    expect(matchesFilter(f, on("2026-01-01"))).toBe(true);
    expect(matchesFilter(f, on("2026-01-31"))).toBe(true);
    expect(matchesFilter(f, on("2025-12-31"))).toBe(false);
    expect(matchesFilter(f, on("2026-02-01"))).toBe(false);
  });

  it("excludes a row with no date", () => {
    expect(matchesFilter({ type: "date", from: "2026-01-01" }, on(null))).toBe(false);
  });
});

describe("matchesFilter · text", () => {
  it("matches the label, which is what the reader is looking at", () => {
    expect(matchesFilter({ type: "text", op: "contains", q: "credit exec" }, cell())).toBe(true);
    expect(matchesFilter({ type: "text", op: "starts", q: "Credit" }, cell())).toBe(true);
    expect(matchesFilter({ type: "text", op: "starts", q: "Executive" }, cell())).toBe(false);
    expect(matchesFilter({ type: "text", op: "eq", q: "credit executive" }, cell())).toBe(true);
    expect(matchesFilter({ type: "text", op: "eq", q: "credit" }, cell())).toBe(false);
  });

  it("a blank term filters nothing out", () => {
    expect(matchesFilter({ type: "text", op: "contains", q: "   " }, cell())).toBe(true);
  });
});

describe("normaliseFilter", () => {
  it("collapses a filter that excludes nothing", () => {
    expect(isEmptyFilter({ type: "num" })).toBe(true);
    expect(normaliseFilter({ type: "num" })).toBeUndefined();
    expect(normaliseFilter({ type: "date" })).toBeUndefined();
    expect(normaliseFilter({ type: "text", op: "contains", q: " " })).toBeUndefined();
    expect(normaliseFilter({ type: "in", values: [] })).toBeUndefined();
  });

  it("keeps a filter that does exclude something", () => {
    expect(normaliseFilter({ type: "num", min: 1 })).toEqual({ type: "num", min: 1 });
  });
});

describe("istDay", () => {
  it("files an evening IST timestamp under the Indian day, not the UTC one", () => {
    // 2026-10-15T20:00+05:30 is 14:30Z — same day. 2026-10-15T01:00+05:30 is the 14th in UTC.
    expect(istDay("2026-10-14T19:30:00.000Z")).toBe("2026-10-15");
    expect(istDay("2026-10-15T18:29:00.000Z")).toBe("2026-10-15");
    expect(istDay("2026-10-15T18:31:00.000Z")).toBe("2026-10-16");
  });

  it("passes a bare calendar day through untouched", () => {
    expect(istDay("2026-10-15")).toBe("2026-10-15");
  });

  it("returns null for nothing and for garbage", () => {
    expect(istDay(null)).toBeNull();
    expect(istDay("")).toBeNull();
    expect(istDay("not a date")).toBeNull();
  });
});
