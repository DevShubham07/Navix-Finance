import { describe, expect, it } from "vitest";
import { ONBOARDING_STEPS } from "./onboarding-config";

describe("onboarding step order", () => {
  it("places optional password creation immediately after OTP", () => {
    expect(ONBOARDING_STEPS.slice(0, 4).map((step) => step.seg)).toEqual([
      "start",
      "otp",
      "set-password",
      "employment",
    ]);
  });
});
