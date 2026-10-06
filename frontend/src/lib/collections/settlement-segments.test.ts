import { describe, expect, it } from "vitest";
import {
  isOwnSettlementProposal,
  settlementSegmentCounts,
  settlementsInSegment,
} from "./settlement-segments";

const rows = [
  { id: "a", status: "APPROVED" },
  { id: "b", status: "PROPOSED" },
  { id: "c", status: "REJECTED" },
  { id: "d", status: "PROPOSED" },
  { id: "e", status: "APPROVED" },
];

describe("settlementSegmentCounts", () => {
  it("counts each status and the total", () => {
    expect(settlementSegmentCounts(rows)).toEqual({ ALL: 5, PROPOSED: 2, APPROVED: 2, REJECTED: 1 });
  });

  it("counts an unknown status only under All", () => {
    expect(settlementSegmentCounts([{ status: "WITHDRAWN" }])).toEqual({
      ALL: 1,
      PROPOSED: 0,
      APPROVED: 0,
      REJECTED: 0,
    });
  });

  it("is all zeros for no rows", () => {
    expect(settlementSegmentCounts([])).toEqual({ ALL: 0, PROPOSED: 0, APPROVED: 0, REJECTED: 0 });
  });
});

describe("settlementsInSegment", () => {
  it("All puts PROPOSED first and otherwise keeps server order", () => {
    expect(settlementsInSegment(rows, "ALL").map((r) => r.id)).toEqual(["b", "d", "a", "c", "e"]);
  });

  it("a status chip filters to that status in server order", () => {
    expect(settlementsInSegment(rows, "APPROVED").map((r) => r.id)).toEqual(["a", "e"]);
    expect(settlementsInSegment(rows, "PROPOSED").map((r) => r.id)).toEqual(["b", "d"]);
    expect(settlementsInSegment(rows, "REJECTED").map((r) => r.id)).toEqual(["c"]);
  });

  it("does not mutate its input", () => {
    const copy = rows.map((r) => r.id);
    settlementsInSegment(rows, "ALL");
    expect(rows.map((r) => r.id)).toEqual(copy);
  });
});

describe("isOwnSettlementProposal", () => {
  it("matches the session's string id against the numeric proposer", () => {
    expect(isOwnSettlementProposal({ proposedBy: 42 }, "42")).toBe(true);
    expect(isOwnSettlementProposal({ proposedBy: 42 }, 42)).toBe(true);
  });

  it("does not match another staffer", () => {
    expect(isOwnSettlementProposal({ proposedBy: 42 }, "7")).toBe(false);
    expect(isOwnSettlementProposal({ proposedBy: 4 }, "42")).toBe(false);
  });

  it("never matches when either side is unknown", () => {
    expect(isOwnSettlementProposal({ proposedBy: null }, "42")).toBe(false);
    expect(isOwnSettlementProposal({ proposedBy: 42 }, null)).toBe(false);
    expect(isOwnSettlementProposal({ proposedBy: 42 }, undefined)).toBe(false);
    expect(isOwnSettlementProposal({ proposedBy: 42 }, "  ")).toBe(false);
  });
});
