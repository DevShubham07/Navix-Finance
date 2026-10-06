import { describe, expect, it } from "vitest";
import { safeNextPath } from "./safe-redirect";

describe("safeNextPath", () => {
  it("keeps the deep links the app actually sends", () => {
    // NotificationEventListener / VerificationOutreachService links, already URL-decoded by get().
    expect(safeNextPath("/credit-question?appId=123", "/dashboard")).toBe("/credit-question?appId=123");
    expect(safeNextPath("/loan/selfie", "/dashboard")).toBe("/loan/selfie");
    expect(safeNextPath("/staff/applications", "/staff/dashboard", "/staff/")).toBe("/staff/applications");
    expect(safeNextPath("/staff", "/staff/dashboard", "/staff/")).toBe("/staff");
  });

  it("falls back when nothing usable was given", () => {
    expect(safeNextPath(null, "/dashboard")).toBe("/dashboard");
    expect(safeNextPath("", "/dashboard")).toBe("/dashboard");
    expect(safeNextPath("dashboard", "/dashboard")).toBe("/dashboard");
  });

  it("refuses every way of leaving the site", () => {
    for (const evil of [
      "https://evil.example",
      "//evil.example",
      "/\\evil.example",
      "/\t/evil.example",
      "/\n/evil.example",
      "javascript:alert(1)",
      " /dashboard",
    ]) {
      expect(safeNextPath(evil, "/dashboard")).toBe("/dashboard");
    }
  });

  it("keeps a staff redirect inside the staff section", () => {
    expect(safeNextPath("/dashboard", "/staff/dashboard", "/staff/")).toBe("/staff/dashboard");
    expect(safeNextPath("/staffing", "/staff/dashboard", "/staff/")).toBe("/staff/dashboard");
  });
});
