import { describe, expect, it } from "vitest";
import { SEGMENTS, inSegment, segmentCounts, segmentOf } from "./segments";

describe("segmentOf", () => {
  it("overdue loan beats a still-ACTIVE application", () => {
    expect(segmentOf({ loanStatus: "OVERDUE", latestStatus: "ACTIVE" })).toBe("overdue");
  });

  it("IN_COLLECTIONS loan maps to overdue", () => {
    expect(segmentOf({ loanStatus: "IN_COLLECTIONS", latestStatus: "ACTIVE" })).toBe("overdue");
  });

  it("DISBURSEMENT_FAILED → hold", () => {
    expect(segmentOf({ loanStatus: null, latestStatus: "DISBURSEMENT_FAILED" })).toBe("hold");
  });

  it("null status → pending", () => {
    expect(segmentOf({ loanStatus: null, latestStatus: null })).toBe("pending");
  });

  it("DRAFT → incomplete, not pending (abandoned mid-onboarding, no staff action)", () => {
    expect(segmentOf({ loanStatus: null, latestStatus: "DRAFT" })).toBe("incomplete");
  });

  it("KYC_PENDING stays pending (a real staff queue item)", () => {
    expect(segmentOf({ loanStatus: null, latestStatus: "KYC_PENDING" })).toBe("pending");
  });

  it("ACTIVE loan → active", () => {
    expect(segmentOf({ loanStatus: "ACTIVE", latestStatus: "ACTIVE" })).toBe("active");
  });

  it("CREDIT_EXEC_PENDING → review", () => {
    expect(segmentOf({ loanStatus: null, latestStatus: "CREDIT_EXEC_PENDING" })).toBe("review");
  });

  it("REJECTED → rejected", () => {
    expect(segmentOf({ loanStatus: null, latestStatus: "REJECTED" })).toBe("rejected");
  });

  // Regression guard: SANCTIONED is a V45 status. Missing from APPROVED_APP it falls through
  // segmentOf's default and a decided customer silently reads as "pending" in the CRM.
  it("SANCTIONED → approved, not the pending fallback", () => {
    expect(segmentOf({ loanStatus: null, latestStatus: "SANCTIONED" })).toBe("approved");
  });

  // DISBURSEMENT_PENDING gets its own segment, distinct from "approved" — sanctioned but money not
  // yet released is a different staff action than a still-undecided or already-active customer.
  it("DISBURSEMENT_PENDING → disbursementPending, not approved", () => {
    expect(segmentOf({ loanStatus: null, latestStatus: "DISBURSEMENT_PENDING" })).toBe("disbursementPending");
  });

  // ACCOUNTANT_PENDING is @Deprecated (retired V48, historical rows only) but represents the same
  // "waiting for money to move out" stage as DISBURSEMENT_PENDING — grouped with it, not "approved".
  it("ACCOUNTANT_PENDING (historical) → disbursementPending, same stage as its successor", () => {
    expect(segmentOf({ loanStatus: null, latestStatus: "ACCOUNTANT_PENDING" })).toBe("disbursementPending");
  });
});

describe("inSegment / segmentCounts", () => {
  const rows = [
    { loanStatus: "OVERDUE", latestStatus: "ACTIVE", ownerStaffId: 1 },
    { loanStatus: null, latestStatus: "DRAFT", ownerStaffId: null },
    { loanStatus: "ACTIVE", latestStatus: "ACTIVE", ownerStaffId: 2 },
  ];

  it("segmentCounts(...).all === rows.length", () => {
    expect(segmentCounts(rows).all).toBe(rows.length);
  });

  it("the DRAFT row counts under incomplete, leaving pending empty", () => {
    const counts = segmentCounts(rows);
    expect(counts.incomplete).toBe(1);
    expect(counts.pending).toBe(0);
    expect(inSegment(rows[1], "incomplete")).toBe(true);
  });

  it("unallocated = null owner", () => {
    expect(inSegment(rows[1], "unallocated")).toBe(true);
    expect(inSegment(rows[0], "unallocated")).toBe(false);
    expect(segmentCounts(rows).unallocated).toBe(1);
  });
});

describe("reloan overlay", () => {
  const rs = [
    { latestStatus: "SANCTIONED", reloan: true },
    { latestStatus: "CLOSED", reloan: false },
    { latestStatus: "DRAFT" },
  ];
  it("filters and counts reloan without changing segmentOf", () => {
    expect(rs.filter((c) => inSegment(c, "reloan"))).toHaveLength(1);
    expect(segmentCounts(rs).reloan).toBe(1);
    expect(segmentOf(rs[0])).toBe("approved");
  });
});

describe("blocked overlay segment", () => {
  it("is an overlay like unallocated/reloan: the row keeps its lifecycle segment", () => {
    const row = { loanStatus: "CLOSED", latestStatus: "CLOSED", ownerStaffId: 3, blocked: true };
    expect(segmentOf(row)).toBe("closed");
    expect(inSegment(row, "blocked")).toBe(true);
    expect(inSegment(row, "closed")).toBe(true);
  });

  it("only a true flag is blocked (absent / null / false are not)", () => {
    expect(inSegment({ latestStatus: "CLOSED" }, "blocked")).toBe(false);
    expect(inSegment({ latestStatus: "CLOSED", blocked: null }, "blocked")).toBe(false);
    expect(inSegment({ latestStatus: "CLOSED", blocked: false }, "blocked")).toBe(false);
  });

  it("segmentCounts counts blocked rows in both their lifecycle segment and blocked", () => {
    const counts = segmentCounts([
      { loanStatus: "CLOSED", latestStatus: "CLOSED", ownerStaffId: 3, blocked: true },
      { loanStatus: "ACTIVE", latestStatus: "ACTIVE", ownerStaffId: 3, blocked: false },
    ]);
    expect(counts.all).toBe(2);
    expect(counts.closed).toBe(1);
    expect(counts.active).toBe(1);
    expect(counts.blocked).toBe(1);
  });

  it("sits right after rejected in the chip order", () => {
    expect(SEGMENTS.indexOf("blocked")).toBe(SEGMENTS.indexOf("rejected") + 1);
  });
});
