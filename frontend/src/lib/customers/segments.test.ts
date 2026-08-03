import { describe, expect, it } from "vitest";
import { inSegment, segmentCounts, segmentOf } from "./segments";

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
