import { describe, expect, it } from "vitest";
import { ApplicationApiError, VerificationRetryTimeoutError } from "@/lib/api/applications";
import {
  isRetryOutcomeUnknown,
  isRetryStillRunning,
  providerAttribution,
  retryGuardKey,
} from "./verification-dashboard";

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
