import { describe, expect, it } from "vitest";
import { ApplicationApiError } from "@/lib/api/applications";
import { entityFor } from "./performance-entities";
import {
  isPerformanceAccessDenied,
  isPerformanceSortKey,
  parsePerformanceSort,
  performanceEmptyKind,
  withPerformanceEntity,
  withPerformanceSort,
} from "./performance-view";

const CREDIT = entityFor("CREDIT_EXECUTIVE")!;
const COLLECTIONS = entityFor("COLLECTION_EXECUTIVE")!;

describe("parsePerformanceSort", () => {
  it("falls back to the entity's headline metric for a missing or unknown key", () => {
    expect(parsePerformanceSort(null, null, CREDIT)).toEqual({ key: "sanctioned", dir: "desc" });
    expect(parsePerformanceSort("password", "asc", CREDIT)).toEqual({ key: "sanctioned", dir: "asc" });
    expect(parsePerformanceSort(null, null, COLLECTIONS)).toEqual({ key: "interactions", dir: "desc" });
  });

  it("accepts the entity's own columns and both directions", () => {
    expect(parsePerformanceSort("staffName", "asc", CREDIT)).toEqual({ key: "staffName", dir: "asc" });
    expect(parsePerformanceSort("recoveredPaise", "desc", COLLECTIONS)).toEqual({ key: "recoveredPaise", dir: "desc" });
    expect(parsePerformanceSort("contactRate", "sideways", COLLECTIONS)).toEqual({ key: "contactRate", dir: "desc" });
  });

  it("rejects another entity's column", () => {
    expect(isPerformanceSortKey("recoveredPaise", CREDIT)).toBe(false);
    expect(isPerformanceSortKey("avgTurnaroundMinutes", CREDIT)).toBe(true);
    expect(isPerformanceSortKey("", CREDIT)).toBe(false);
    expect(isPerformanceSortKey(undefined, CREDIT)).toBe(false);
  });
});

describe("withPerformanceSort", () => {
  it("omits the entity's default sort and keeps unrelated params", () => {
    expect(withPerformanceSort("entity=CREDIT_EXECUTIVE&sort=staffName&dir=asc", "sanctioned", "desc", CREDIT)).toBe(
      "entity=CREDIT_EXECUTIVE",
    );
  });

  it("writes a non-default sort", () => {
    expect(withPerformanceSort("", "staffName", "asc", CREDIT)).toBe("sort=staffName&dir=asc");
    expect(withPerformanceSort("", "parked", "desc", CREDIT)).toBe("sort=parked");
  });

  it("round-trips through parsePerformanceSort", () => {
    const qs = new URLSearchParams(withPerformanceSort("", "promisesToPay", "asc", COLLECTIONS));
    expect(parsePerformanceSort(qs.get("sort"), qs.get("dir"), COLLECTIONS)).toEqual({ key: "promisesToPay", dir: "asc" });
  });
});

describe("withPerformanceEntity", () => {
  it("switches the entity and drops a sort that belonged to the old one", () => {
    expect(withPerformanceEntity("entity=CREDIT_EXECUTIVE&sort=parked&dir=asc&x=1", "COLLECTION_HEAD")).toBe(
      "entity=COLLECTION_HEAD&x=1",
    );
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
