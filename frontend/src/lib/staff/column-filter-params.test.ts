/**
 * The wire format. These are the tests that stop the URL, localStorage and the server query string
 * drifting apart — all three use this one encoding.
 */
import { describe, expect, it } from "vitest";
import {
  describeFilter,
  describeFilters,
  filterParamCount,
  parseFilters,
  serialiseFilters,
  writeFilters,
} from "@/lib/staff/column-filter-params";
import type { ColumnFilterSet } from "@/lib/staff/column-filter-match";

const roundTrip = (filters: ColumnFilterSet): ColumnFilterSet =>
  parseFilters(new URLSearchParams(serialiseFilters(filters)));

describe("round-trip", () => {
  it("survives every kind", () => {
    const filters: ColumnFilterSet = {
      status: { type: "in", values: ["SANCTIONED", "ACTIVE"] },
      amount: { type: "num", min: 100000, max: 500000 },
      dueDate: { type: "date", from: "2026-01-01", to: "2026-02-01" },
      name: { type: "text", op: "contains", q: "ravi" },
    };
    expect(roundTrip(filters)).toEqual(filters);
  });

  it("keeps an open-ended range open", () => {
    expect(roundTrip({ dpd: { type: "num", min: 30 } })).toEqual({ dpd: { type: "num", min: 30 } });
    expect(roundTrip({ dpd: { type: "num", max: 90 } })).toEqual({ dpd: { type: "num", max: 90 } });
    expect(roundTrip({ due: { type: "date", from: "2026-01-01" } })).toEqual({
      due: { type: "date", from: "2026-01-01" },
    });
  });

  it("survives values carrying the format's own punctuation", () => {
    const filters: ColumnFilterSet = {
      // A comma would split the list; a percent would eat the escape.
      employer: { type: "in", values: ["Acme, Inc.", "100% Fresh", "A~B"] },
      // A colon in the operand must not be read as the operator separator.
      note: { type: "text", op: "eq", q: "re: payment" },
      // `..` inside a value must not make it look like a range.
      code: { type: "in", values: ["A..B"] },
    };
    expect(roundTrip(filters)).toEqual(filters);
  });

  it("emits keys in sorted order so one filter set has one encoding", () => {
    const a = serialiseFilters({ zeta: { type: "in", values: ["1"] }, alpha: { type: "in", values: ["2"] } });
    const b = serialiseFilters({ alpha: { type: "in", values: ["2"] }, zeta: { type: "in", values: ["1"] } });
    expect(Object.keys(a)).toEqual(["f.alpha", "f.zeta"]);
    expect(JSON.stringify(a)).toBe(JSON.stringify(b));
  });

  it("omits a filter that excludes nothing", () => {
    expect(serialiseFilters({ amount: { type: "num" }, q: { type: "text", op: "eq", q: "" } })).toEqual({});
  });
});

describe("parseFilters is lenient", () => {
  it("ignores params that are not filters", () => {
    const p = new URLSearchParams("page=3&sort=name&f.status=in:ACTIVE");
    expect(parseFilters(p)).toEqual({ status: { type: "in", values: ["ACTIVE"] } });
  });

  it("drops a column the register does not have", () => {
    const p = new URLSearchParams("f.status=in:ACTIVE&f.nonsense=in:X");
    expect(parseFilters(p, ["status"])).toEqual({ status: { type: "in", values: ["ACTIVE"] } });
  });

  it("drops a malformed bound rather than throwing", () => {
    const p = new URLSearchParams("f.a=num:abc..def&f.b=date:nonsense..2026-13-99&f.c=text:bogus:x");
    expect(parseFilters(p)).toEqual({});
  });

  it("rejects a date that fits the pattern but is not a day", () => {
    const p = new URLSearchParams("f.a=date:2026-02-30..&f.b=date:2025-02-29..&f.c=date:2024-02-29..");
    // 2024 is a leap year, so only the third survives.
    expect(parseFilters(p)).toEqual({ c: { type: "date", from: "2024-02-29" } });
  });

  it("accepts the untagged legacy forms", () => {
    const p = new URLSearchParams("f.status=ACTIVE,CLOSED&f.amount=100..200&f.due=2026-01-01..2026-02-01");
    expect(parseFilters(p)).toEqual({
      status: { type: "in", values: ["ACTIVE", "CLOSED"] },
      amount: { type: "num", min: 100, max: 200 },
      due: { type: "date", from: "2026-01-01", to: "2026-02-01" },
    });
  });

  it("counts the filtered columns", () => {
    expect(filterParamCount(new URLSearchParams("page=2&f.a=in:X&f.b=num:1.."))).toBe(2);
  });
});

describe("writeFilters", () => {
  it("keeps unrelated params and clears filters that are gone", () => {
    const next = writeFilters("page=2&sort=name&f.old=in:X", { status: { type: "in", values: ["ACTIVE"] } });
    const p = new URLSearchParams(next);
    expect(p.get("page")).toBe("2");
    expect(p.get("sort")).toBe("name");
    expect(p.get("f.old")).toBeNull();
    expect(p.get("f.status")).toBe("in:ACTIVE");
  });

  it("leaves a clean query string when nothing is filtered", () => {
    expect(writeFilters("f.old=in:X", {})).toBe("");
  });
});

describe("describeFilters", () => {
  it("names the filters for the PDF subtitle, labelling stored values", () => {
    const text = describeFilters(
      {
        status: { type: "in", values: ["SANCTIONED"] },
        amount: { type: "num", min: 1000, max: 5000 },
        due: { type: "date", from: "2026-01-01" },
        name: { type: "text", op: "contains", q: "ravi" },
      },
      {
        column: (k) => ({ status: "Status", amount: "Amount", due: "Due", name: "Borrower" })[k] ?? k,
        value: (_k, v) => (v === "SANCTIONED" ? "Sanctioned" : v),
      },
    );
    // Ordered by column key, not by label — the output has to be stable, and the key is the only
    // thing that cannot change under a re-skin.
    expect(text).toBe(
      "Filtered: Amount 1000–5000 · Due from 2026-01-01 · Borrower contains “ravi” · Status in Sanctioned",
    );
  });

  it("is empty when nothing is filtered, so no subtitle is added", () => {
    expect(describeFilters({})).toBe("");
  });
});

describe("describeFilter", () => {
  const id = (v: string) => v;
  it("phrases each kind", () => {
    expect(describeFilter("Status", { type: "in", values: ["A", "B"] }, (v) => v.toLowerCase())).toBe("Status in a, b");
    expect(describeFilter("Amt", { type: "num", min: 1, max: 2 }, id)).toBe("Amt 1–2");
    expect(describeFilter("Amt", { type: "num", min: 1 }, id)).toBe("Amt ≥ 1");
    expect(describeFilter("Amt", { type: "num", max: 2 }, id)).toBe("Amt ≤ 2");
    expect(describeFilter("Due", { type: "date", from: "2026-01-01", to: "2026-02-01" }, id)).toBe("Due 2026-01-01 to 2026-02-01");
    expect(describeFilter("Due", { type: "date", from: "2026-01-01" }, id)).toBe("Due from 2026-01-01");
    expect(describeFilter("Due", { type: "date", to: "2026-02-01" }, id)).toBe("Due until 2026-02-01");
    expect(describeFilter("Name", { type: "text", op: "contains", q: "ravi" }, id)).toBe("Name contains “ravi”");
    expect(describeFilter("Name", { type: "text", op: "eq", q: "ravi" }, id)).toBe("Name is “ravi”");
  });

  it("leaves describeFilters' export subtitle byte-identical", () => {
    expect(
      describeFilters({
        a: { type: "in", values: ["X"] },
        b: { type: "num", min: 5 },
        c: { type: "text", op: "starts", q: "q" },
      }),
    ).toBe("Filtered: a in X · b ≥ 5 · c starts “q”");
  });
});
