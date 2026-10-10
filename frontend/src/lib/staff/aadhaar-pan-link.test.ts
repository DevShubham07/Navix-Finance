import { describe, expect, it } from "vitest";
import type { StepResult } from "@/lib/api/applications";
import { AADHAAR_PAN_LINK, aadhaarPanLinkStep, withAadhaarPanLink } from "@/lib/staff/aadhaar-pan-link";

const pan = {
  checkType: "PAN",
  status: "PASS",
  message: "PAN valid",
  provider: "FINTRIX",
  checkedAt: "2026-10-01T10:00:00Z",
  derived: { panNumber: "ABCDE1234F", maskedAadhaar: "XXXXXXXX1234", aadhaarLinked: true },
} as StepResult;
const email = { checkType: "EMAIL", status: "PASS", message: null, derived: {} } as StepResult;

describe("aadhaarPanLinkStep", () => {
  it("lifts the PAN response's link status into a PASS step with both numbers", () => {
    const s = aadhaarPanLinkStep([pan], "123456781234");
    expect(s).toMatchObject({
      checkType: AADHAAR_PAN_LINK,
      status: "PASS",
      provider: "FINTRIX",
      checkedAt: pan.checkedAt,
      derived: { aadhaarNumber: "123456781234", panNumber: "ABCDE1234F", linked: true },
    });
  });

  it("falls back to the PAN record's masked Aadhaar", () => {
    expect(aadhaarPanLinkStep([pan])?.derived.aadhaarNumber).toBe("XXXXXXXX1234");
  });

  it("fails when the provider reports no link", () => {
    expect(aadhaarPanLinkStep([{ ...pan, derived: { ...pan.derived, aadhaarLinked: false } }])?.status).toBe("FAIL");
  });

  it("is absent until PAN has answered a link status", () => {
    expect(aadhaarPanLinkStep([email])).toBeNull();
    expect(aadhaarPanLinkStep([{ ...pan, derived: { panNumber: "ABCDE1234F" } }])).toBeNull();
  });
});

describe("withAadhaarPanLink", () => {
  it("slots the step in right after PAN", () => {
    expect(withAadhaarPanLink([email, pan, email]).map((s) => s.checkType)).toEqual(["EMAIL", "PAN", AADHAAR_PAN_LINK, "EMAIL"]);
  });

  it("leaves steps unchanged without a PAN row", () => {
    expect(withAadhaarPanLink([email]).map((s) => s.checkType)).toEqual(["EMAIL"]);
  });
});
