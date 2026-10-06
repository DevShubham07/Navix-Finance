import { describe, expect, it } from "vitest";
import { referralPayoutsGate } from "./dashboard-referral-gate";

describe("referralPayoutsGate", () => {
  it("is on when the flag is on or absent (kill switch: only an explicit false turns it off)", () => {
    expect(referralPayoutsGate({ referral: true }, false)).toBe(true);
    expect(referralPayoutsGate({ "global-search": true }, false)).toBe(true);
    expect(referralPayoutsGate({}, false)).toBe(true);
  });

  it("is off when the flag is explicitly false", () => {
    expect(referralPayoutsGate({ referral: false }, false)).toBe(false);
  });

  it("is unknown while the flags are still loading, so the caller waits", () => {
    expect(referralPayoutsGate(undefined, false)).toBeUndefined();
  });

  it("fails open when the flag read failed with nothing in hand (the old in-queue behaviour)", () => {
    expect(referralPayoutsGate(undefined, true)).toBe(true);
  });

  it("keeps a known-off flag off through a later failed refetch", () => {
    expect(referralPayoutsGate({ referral: false }, true)).toBe(false);
  });
});
