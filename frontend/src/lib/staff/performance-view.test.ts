import { describe, expect, it } from "vitest";
import { ApplicationApiError, type StaffPerformanceRow } from "@/lib/api/applications";
import {
  isPerformanceAccessDenied,
  isPerformanceSortKey,
  parsePerformanceSort,
  performanceEmptyKind,
  performanceTotals,
  withPerformanceSort,
} from "./performance-view";

function row(overrides: Partial<StaffPerformanceRow> = {}): StaffPerformanceRow {
  return {
    staffId: 1,
    staffName: "A",
    role: "CREDIT_EXECUTIVE",
    active: true,
    accepted: 0,
    rejected: 0,
    totalActions: 0,
    activeDays: 0,
    avgTurnaroundMinutes: null,
    pendingNow: 0,
    moneyPaise: 0,
    firstActionAt: null,
    lastActionAt: null,
    verifiedCount: null,
    verifiedPaise: null,
    rejectedPaymentCount: null,
    callsMade: 0,
    ...overrides,
  };
}

describe("parsePerformanceSort", () => {
  it("falls back to the default for a missing or unknown key", () => {
    expect(parsePerformanceSort(null, null)).toEqual({ key: "totalActions", dir: "desc" });
    expect(parsePerformanceSort("password", "asc")).toEqual({ key: "totalActions", dir: "asc" });
  });

  it("accepts every register column and both directions", () => {
    expect(parsePerformanceSort("staffName", "asc")).toEqual({ key: "staffName", dir: "asc" });
    expect(parsePerformanceSort("moneyPaise", "desc")).toEqual({ key: "moneyPaise", dir: "desc" });
    expect(parsePerformanceSort("callsMade", "sideways")).toEqual({ key: "callsMade", dir: "desc" });
  });

  it("validates keys strictly", () => {
    expect(isPerformanceSortKey("avgTurnaroundMinutes")).toBe(true);
    expect(isPerformanceSortKey("")).toBe(false);
    expect(isPerformanceSortKey(undefined)).toBe(false);
  });
});

describe("withPerformanceSort", () => {
  it("omits the default sort and keeps unrelated params", () => {
    expect(withPerformanceSort("foo=1&sort=staffName&dir=asc", "totalActions", "desc")).toBe("foo=1");
  });

  it("writes a non-default sort", () => {
    expect(withPerformanceSort("", "staffName", "asc")).toBe("sort=staffName&dir=asc");
    expect(withPerformanceSort("", "accepted", "desc")).toBe("sort=accepted");
  });

  it("round-trips through parsePerformanceSort", () => {
    const qs = new URLSearchParams(withPerformanceSort("", "role", "asc"));
    expect(parsePerformanceSort(qs.get("sort"), qs.get("dir"))).toEqual({ key: "role", dir: "asc" });
  });
});

describe("performanceTotals", () => {
  it("is null — never zeros — when nothing has loaded", () => {
    expect(performanceTotals(undefined)).toBeNull();
  });

  it("is real zeros for a loaded, empty roster", () => {
    expect(performanceTotals([])).toEqual({ accepted: 0, rejected: 0, actions: 0, pending: 0, calls: 0 });
  });

  it("sums the rows it is given", () => {
    const totals = performanceTotals([
      row({ accepted: 2, rejected: 1, totalActions: 5, pendingNow: 3, callsMade: 4 }),
      row({ staffId: 2, accepted: 1, rejected: 0, totalActions: 2, pendingNow: 1, callsMade: 0 }),
    ]);
    expect(totals).toEqual({ accepted: 3, rejected: 1, actions: 7, pending: 4, calls: 4 });
  });
});

describe("isPerformanceAccessDenied", () => {
  it("treats a 403 and the backend's role rejections as no access", () => {
    expect(isPerformanceAccessDenied(new ApplicationApiError("no", "FORBIDDEN", 403))).toBe(true);
    expect(isPerformanceAccessDenied(new ApplicationApiError("no", "FORBIDDEN_ROLE", 422))).toBe(true);
    expect(isPerformanceAccessDenied(new ApplicationApiError("no", "FORBIDDEN", 422))).toBe(true);
  });

  it("treats everything else as a retryable error", () => {
    expect(isPerformanceAccessDenied(new ApplicationApiError("down", "HTTP_502", 502))).toBe(false);
    expect(isPerformanceAccessDenied(new ApplicationApiError("offline", "NETWORK_ERROR", 0))).toBe(false);
    expect(isPerformanceAccessDenied(new Error("boom"))).toBe(false);
    expect(isPerformanceAccessDenied(undefined)).toBe(false);
  });
});

describe("performanceEmptyKind", () => {
  it("distinguishes a filtered-out roster from an empty one", () => {
    expect(performanceEmptyKind(5, 0)).toBe("filtered");
    expect(performanceEmptyKind(0, 0)).toBe("no-roster");
    expect(performanceEmptyKind(5, 2)).toBeNull();
  });
});
