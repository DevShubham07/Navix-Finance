import { describe, expect, it } from "vitest";
import { inSegment, segmentCounts, segmentOf } from "./segments";

describe("segmentOf", () => {
  it("ACTIVE → active", () => {
    expect(segmentOf({ status: "ACTIVE" })).toBe("active");
  });

  it("OVERDUE → overdue", () => {
    expect(segmentOf({ status: "OVERDUE" })).toBe("overdue");
  });

  it("IN_COLLECTIONS → overdue", () => {
    expect(segmentOf({ status: "IN_COLLECTIONS" })).toBe("overdue");
  });

  it("REPAID → closed", () => {
    expect(segmentOf({ status: "REPAID" })).toBe("closed");
  });

  it("CLOSED → closed", () => {
    expect(segmentOf({ status: "CLOSED" })).toBe("closed");
  });

  it("an unrecognized/null status defaults to active, not dropped from the queue", () => {
    expect(segmentOf({ status: null })).toBe("active");
    expect(segmentOf({ status: "SOMETHING_NEW" })).toBe("active");
  });
});

describe("inSegment / segmentCounts", () => {
  const rows = [
    { status: "ACTIVE" },
    { status: "OVERDUE" },
    { status: "IN_COLLECTIONS" },
    { status: "REPAID" },
    { status: "CLOSED" },
  ];

  it("segmentCounts(...).all === rows.length", () => {
    expect(segmentCounts(rows).all).toBe(rows.length);
  });

  it("tallies each bucket correctly", () => {
    const counts = segmentCounts(rows);
    expect(counts.active).toBe(1);
    expect(counts.overdue).toBe(2);
    expect(counts.closed).toBe(2);
  });

  it("inSegment('all') is always true", () => {
    for (const row of rows) expect(inSegment(row, "all")).toBe(true);
  });

  it("inSegment matches segmentOf for a non-all segment", () => {
    expect(inSegment({ status: "OVERDUE" }, "overdue")).toBe(true);
    expect(inSegment({ status: "OVERDUE" }, "active")).toBe(false);
  });
});
