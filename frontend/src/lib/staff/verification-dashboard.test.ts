import { describe, expect, it } from "vitest";
import {
  ApplicationApiError,
  VerificationRetryTimeoutError,
  type VerificationNotStarted,
  type VerificationOverviewRow,
} from "@/lib/api/applications";
import {
  REQUIRED_CHECKS,
  buildVerificationCards,
  formatCheckedAgo,
  groupVerificationCards,
  isRetryOutcomeUnknown,
  isRetryStillRunning,
  providerAttribution,
  retryGuardKey,
} from "./verification-dashboard";

function row(
  applicationId: number,
  checkType: string,
  status: VerificationOverviewRow["status"],
  updatedAt: string | null = "2026-10-06T08:00:00Z",
  extra: Partial<VerificationOverviewRow> = {},
): VerificationOverviewRow {
  return {
    applicationId,
    customerId: 500 + applicationId,
    borrowerName: `Borrower ${applicationId}`,
    borrowerMobile: "9000000000",
    checkType,
    status,
    provider: "DIGITAP",
    message: null,
    updatedAt,
    applicationStatus: "KYC_PENDING",
    ...extra,
  };
}

const notStarted = (applicationId: number): VerificationNotStarted => ({
  applicationId,
  customerId: 900 + applicationId,
  borrowerName: `Untouched ${applicationId}`,
  borrowerMobile: null,
});

describe("buildVerificationCards", () => {
  it("builds 'Not started' cards only from the server's notStarted list", () => {
    const cards = buildVerificationCards([row(1, "PAN", "FAIL")], [notStarted(7), notStarted(8)]);
    const ns = cards.filter((c) => c.bucket === "notStarted");
    expect(ns.map((c) => c.applicationId)).toEqual([7, 8]);
    expect(ns[0]).toMatchObject({
      customerId: 907,
      borrowerName: "Untouched 7",
      total: 0,
      lastUpdate: null,
    });
  });

  it("never calls a file 'Not started' just because its rows are not on this page", () => {
    // The old page synthesised the bucket from a KYC_PENDING list minus this page's rows. Now an
    // empty notStarted list means no such cards, however many files are missing from `rows`.
    const cards = buildVerificationCards([row(1, "PAN", "FAIL")], []);
    expect(cards.some((c) => c.bucket === "notStarted")).toBe(false);
  });

  it("does not duplicate an application that has rows and is also listed as not started", () => {
    const cards = buildVerificationCards([row(1, "PAN", "FAIL")], [notStarted(1)]);
    expect(cards).toHaveLength(1);
    expect(cards[0].bucket).toBe("failures");
  });

  it("buckets a fully-cleared file as passed and a partial one as awaiting", () => {
    const cleared = REQUIRED_CHECKS.map((t) => row(1, t, "PASS"));
    const partial = [row(2, "PAN", "PASS"), row(2, "EMAIL", "PASS")];
    const cards = buildVerificationCards([...cleared, ...partial], []);
    const byId = Object.fromEntries(cards.map((c) => [c.applicationId, c]));
    expect(byId[1]).toMatchObject({ bucket: "passed", passed: 8, total: 8 });
    expect(byId[2]).toMatchObject({ bucket: "awaiting", passed: 2, total: 8, pendingReview: 6 });
  });

  it("keeps EPFO out of the gating maths but carries its status as a chip", () => {
    const rows = [...REQUIRED_CHECKS.map((t) => row(1, t, "PASS")), row(1, "EMPLOYMENT", "REVIEW")];
    const [card] = buildVerificationCards(rows, []);
    expect(card).toMatchObject({ bucket: "passed", total: 8, employmentStatus: "REVIEW" });
  });

  it("takes lastUpdate from the newest row, non-gating rows included", () => {
    const [card] = buildVerificationCards(
      [row(1, "PAN", "FAIL", "2026-10-06T08:00:00Z"), row(1, "EMPLOYMENT", "PASS", "2026-10-06T09:30:00Z")],
      [],
    );
    expect(card.lastUpdate).toBe("2026-10-06T09:30:00Z");
  });

  it("drops rows of decided applications", () => {
    const cards = buildVerificationCards([row(1, "PAN", "FAIL", null, { applicationStatus: "ACTIVE" })], []);
    expect(cards).toEqual([]);
  });
});

describe("groupVerificationCards", () => {
  it("sorts newest activity first and keeps the server order of not-started cards", () => {
    const cards = buildVerificationCards(
      [row(1, "PAN", "FAIL", "2026-10-06T08:00:00Z"), row(2, "PAN", "FAIL", "2026-10-06T10:00:00Z")],
      [notStarted(9), notStarted(3)],
    );
    const g = groupVerificationCards(cards);
    expect(g.failures.map((c) => c.applicationId)).toEqual([2, 1]);
    expect(g.notStarted.map((c) => c.applicationId)).toEqual([9, 3]);
    expect(g.passed).toEqual([]);
  });
});

describe("formatCheckedAgo", () => {
  const now = Date.parse("2026-10-06T12:00:00Z");

  it("reads in the largest whole unit", () => {
    expect(formatCheckedAgo("2026-10-06T11:59:30Z", now)).toBe("just now");
    expect(formatCheckedAgo("2026-10-06T11:15:00Z", now)).toBe("45 min ago");
    expect(formatCheckedAgo("2026-10-06T10:00:00Z", now)).toBe("2 h ago");
    expect(formatCheckedAgo("2026-10-03T11:00:00Z", now)).toBe("3 d ago");
  });

  it("is timezone-independent: the same instant written with an IST offset reads the same", () => {
    expect(formatCheckedAgo("2026-10-06T15:30:00+05:30", now)).toBe("2 h ago");
  });

  it("treats a future timestamp as just now and rejects missing or bad input", () => {
    expect(formatCheckedAgo("2026-10-06T12:05:00Z", now)).toBe("just now");
    expect(formatCheckedAgo(null, now)).toBeNull();
    expect(formatCheckedAgo(undefined, now)).toBeNull();
    expect(formatCheckedAgo("not a date", now)).toBeNull();
  });
});

describe("isRetryOutcomeUnknown", () => {
  it("is true for the client give-up and a gateway timeout", () => {
    expect(isRetryOutcomeUnknown(new VerificationRetryTimeoutError())).toBe(true);
    expect(isRetryOutcomeUnknown(new ApplicationApiError("Gateway Timeout", "HTTP_504", 504))).toBe(true);
  });

  it("is false for an answer from the server or anything else", () => {
    expect(isRetryOutcomeUnknown(new ApplicationApiError("Bad input", "VALIDATION", 422))).toBe(false);
    expect(isRetryOutcomeUnknown(new ApplicationApiError("Down", "NETWORK_ERROR", 0))).toBe(false);
    expect(isRetryOutcomeUnknown(new Error("Verification retry timed out after 120 seconds."))).toBe(false);
    expect(isRetryOutcomeUnknown(null)).toBe(false);
  });
});

describe("isRetryStillRunning", () => {
  it("holds while the row timestamp is unchanged and lifts once it moves", () => {
    const guards = { BUREAU: "2026-10-06T08:00:00Z" };
    expect(isRetryStillRunning(guards, "BUREAU", "2026-10-06T08:00:00Z")).toBe(true);
    expect(isRetryStillRunning(guards, "BUREAU", "2026-10-06T08:02:10Z")).toBe(false);
  });

  it("guards a never-run check until its first row appears", () => {
    const guards: Record<string, string | null> = { PENNY_DROP: null };
    expect(isRetryStillRunning(guards, "PENNY_DROP", undefined)).toBe(true);
    expect(isRetryStillRunning(guards, "PENNY_DROP", null)).toBe(true);
    expect(isRetryStillRunning(guards, "PENNY_DROP", "2026-10-06T08:02:10Z")).toBe(false);
  });

  it("does not guard a check that never timed out", () => {
    expect(isRetryStillRunning({ BUREAU: null }, "PAN", null)).toBe(false);
    expect(isRetryStillRunning({}, "toString", null)).toBe(false);
  });

  it("keys the guard by application as well as check type", () => {
    const guards = { [retryGuardKey(42, "BUREAU")]: null };
    expect(isRetryStillRunning(guards, retryGuardKey(42, "BUREAU"), null)).toBe(true);
    // Another file's never-run BUREAU (null on both sides) is not this file's in-flight retry.
    expect(isRetryStillRunning(guards, retryGuardKey(43, "BUREAU"), null)).toBe(false);
  });
});

describe("providerAttribution", () => {
  it("names the vendor that answered", () => {
    expect(providerAttribution("DIGITAP")).toBe("Answered by DIGITAP");
  });

  it("words the non-vendor values the backend writes", () => {
    expect(providerAttribution("MANUAL")).toBe("Manual override");
    expect(providerAttribution("MANUAL_PROOF")).toBe("Manual proof upload");
    expect(providerAttribution("SYSTEM")).toBe("Raised by the system");
  });

  it("is null when nobody is named", () => {
    expect(providerAttribution(null)).toBeNull();
    expect(providerAttribution("   ")).toBeNull();
  });
});
