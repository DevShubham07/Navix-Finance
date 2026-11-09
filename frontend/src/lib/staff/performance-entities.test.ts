import { describe, expect, it } from "vitest";
import {
  PERFORMANCE_ENTITIES,
  availableEntities,
  dailyFor,
  entityFor,
  entityForRole,
  formatMetric,
  metricTotal,
  resolveEntity,
  rowsForEntity,
  toViewRows,
} from "./performance-entities";
import { perfRow } from "./performance-fixtures";

const exec = perfRow({ staffId: 1, staffName: "Asha", role: "CREDIT_EXECUTIVE", actionCounts: { SANCTION: 3, REJECT_LEAD: 1 } });
const collector = perfRow({
  staffId: 2,
  staffName: "Bilal",
  role: "COLLECTION_EXECUTIVE",
  collections: { interactions: 10, noAnswer: 4, promisesToPay: 2, paymentsRecoveredPaise: 500_00 },
});
const collector2 = perfRow({
  staffId: 3,
  staffName: "Chitra",
  role: "COLLECTION_EXECUTIVE",
  collections: { interactions: 0, casesNow: 5 },
});

describe("entity config", () => {
  it("gives every entity unique metric keys, tiles drawn from them, and a sortable default", () => {
    for (const e of PERFORMANCE_ENTITIES) {
      const keys = e.metrics.map((m) => m.key);
      expect(new Set(keys).size, e.key).toBe(keys.length);
      for (const t of e.tiles) expect(keys, `${e.key} tile ${t}`).toContain(t);
      expect(keys, e.key).toContain(e.defaultSort);
    }
  });

  it("maps each staff role to the entity that measures its job", () => {
    expect(entityForRole("COLLECTION_HEAD").key).toBe("COLLECTION_HEAD");
    expect(entityForRole("ACCOUNTANT").key).toBe("ACCOUNTANT");
    expect(entityForRole("DSA").key).toBe("ALL");
  });
});

describe("availableEntities / resolveEntity", () => {
  it("offers only entities with someone in the roster, with counts, plus All staff when mixed", () => {
    const opts = availableEntities([exec, collector, collector2]);
    expect(opts.map((o) => [o.entity.key, o.count])).toEqual([
      ["CREDIT_EXECUTIVE", 1],
      ["COLLECTION_EXECUTIVE", 2],
      ["ALL", 3],
    ]);
  });

  it("has no All staff tab for a single-entity roster", () => {
    expect(availableEntities([exec]).map((o) => o.entity.key)).toEqual(["CREDIT_EXECUTIVE"]);
  });

  it("honours a requested entity only when it is on offer", () => {
    expect(resolveEntity("COLLECTION_EXECUTIVE", [exec, collector])?.key).toBe("COLLECTION_EXECUTIVE");
    expect(resolveEntity("ACCOUNTANT", [exec, collector])?.key).toBe("CREDIT_EXECUTIVE");
    expect(resolveEntity(null, [])).toBeNull();
  });

  it("slices the roster by role", () => {
    expect(rowsForEntity([exec, collector], entityFor("COLLECTION_EXECUTIVE")!).map((r) => r.staffId)).toEqual([2]);
    expect(rowsForEntity([exec, collector], entityFor("ALL")!)).toHaveLength(2);
  });
});

describe("metrics", () => {
  const ce = entityFor("COLLECTION_EXECUTIVE")!;
  const metric = (k: string) => ce.metrics.find((m) => m.key === k)!;

  it("flattens every metric onto the view row for sorting", () => {
    const [v] = toViewRows([collector], ce);
    expect(v.interactions).toBe(10);
    expect(v.contactRate).toBe(60);
    expect(v.recoveredPaise).toBe(500_00);
  });

  it("reads a rate with no denominator as unmeasured, never 0%", () => {
    expect(metric("contactRate").value(collector2)).toBeNull();
  });

  it("totals a rate as Σnum/Σden and sums everything else", () => {
    expect(metricTotal(metric("contactRate"), [collector, collector2])).toBe(60);
    expect(metricTotal(metric("casesNow"), [collector, collector2])).toBe(5);
    expect(metricTotal(metric("interactions"), undefined)).toBeNull();
  });

  it("counts credit decisions off the action breakdown", () => {
    const ce2 = entityFor("CREDIT_EXECUTIVE")!;
    const [v] = toViewRows([exec], ce2);
    expect(v.sanctioned).toBe(3);
    expect(v.creditRejected).toBe(1);
    expect(v.approvalRate).toBe(75);
  });

  it("formats by kind", () => {
    expect(formatMetric("pct", 62.5)).toBe("62.5%");
    expect(formatMetric("minutes", 200)).toBe("3h 20m");
    expect(formatMetric("money", 0)).toBe("—");
    expect(formatMetric("count", null)).toBe("—");
    expect(formatMetric("count", 1200)).toBe("1,200");
  });
});

describe("dailyFor", () => {
  it("sums the per-person series by day, in date order", () => {
    const a = perfRow({ daily: [{ date: "2026-10-02", actions: 2 }, { date: "2026-10-01", actions: 1 }] });
    const b = perfRow({ staffId: 2, daily: [{ date: "2026-10-02", actions: 3 }] });
    expect(dailyFor([a, b])).toEqual([
      { date: "2026-10-01", actions: 1 },
      { date: "2026-10-02", actions: 5 },
    ]);
  });
});
