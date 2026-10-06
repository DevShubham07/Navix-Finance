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

  it("places the Aadhaar and PAN card screens between salary slips and consent (V75)", () => {
    const segs = ONBOARDING_STEPS.map((step) => step.seg);
    expect(segs.slice(segs.indexOf("payslips"), segs.indexOf("consent") + 1)).toEqual([
      "payslips",
      "aadhaar",
      "pan-card",
      "consent",
    ]);
    // Mirrors JourneyService.Step on the backend — twelve screens, in this order.
    expect(ONBOARDING_STEPS.map((step) => step.step)).toEqual([
      "START", "OTP", "SET_PASSWORD", "EMPLOYMENT", "EMPLOYER", "EMAIL", "BANK", "PAYSLIPS",
      "AADHAAR", "PAN_CARD", "CONSENT", "SUBMITTED",
    ]);
  });
});
