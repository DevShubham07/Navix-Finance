import { describe, expect, it } from "vitest";
import { employerNamesAgree, lastFour, matchesMasked, nameSimilarity } from "@/lib/customers/bre/matching";

describe("nameSimilarity (port of ApplicationVerificationService.nameSimilarity)", () => {
  it("is order-insensitive and counts initials in either direction", () => {
    expect(nameSimilarity("ELANGO SAIPRASATH", "SAIPRASATH E")).toBe(1);
    expect(nameSimilarity("Rahul Kumar Sharma", "R K Sharma")).toBe(1);
    expect(nameSimilarity("ASHA VERMA", "verma asha")).toBe(1);
  });

  it("falls back to Jaccard for a one-word name, and is 0 for nothing in common or a blank side", () => {
    expect(nameSimilarity("ASHA", "ASHA VERMA")).toBe(0.5);
    expect(nameSimilarity("ASHA VERMA", "RAVI KUMAR")).toBe(0);
    expect(nameSimilarity(null, "ASHA")).toBe(0);
  });
});

describe("employerNamesAgree (port of ApplicationVerificationService.employerNamesAgree)", () => {
  it("accepts a short exact name against the full legal name, and spacing differences", () => {
    expect(employerNamesAgree("sprinklr", "SPRINKLR INDIA PVT LTD")).toBe(true);
    expect(employerNamesAgree("Accenture", "ACCENTURE SOLUTIONS PVT. LTD.")).toBe(true);
    expect(employerNamesAgree("Hygro Chemicals", "HY GRO CHEMICALS PHARMTEK PRIVATE LIMITED")).toBe(true);
  });

  it("rejects a different employer and boilerplate-only names", () => {
    expect(employerNamesAgree("Accenture", "M/S ECLERX SERVICES LIMITED")).toBe(false);
    expect(employerNamesAgree("Pvt Ltd", "ACCENTURE PRIVATE LIMITED")).toBe(false);
  });
});

describe("matchesMasked (port of Aadhaar.matchesMasked)", () => {
  it("compares a 12-character mask positionally", () => {
    expect(matchesMasked("123456781234", "XXXXXXXX1234")).toBe("MATCH");
    expect(matchesMasked("123456781234", "xxxxxxxx9999")).toBe("MISMATCH");
    expect(matchesMasked("653456781290", "65XXXXXXXX90")).toBe("MATCH");
    expect(matchesMasked("753456781290", "65XXXXXXXX90")).toBe("MISMATCH");
    expect(matchesMasked("1234 5678 1234", "XXXX-XXXX-1234")).toBe("MATCH");
  });

  it("is UNKNOWN — never a mismatch — when there is nothing to compare", () => {
    expect(matchesMasked(null, "XXXXXXXX1234")).toBe("UNKNOWN");
    expect(matchesMasked("123456781234", null)).toBe("UNKNOWN");
    expect(matchesMasked("123456781234", "XXXXXXXXXXXX")).toBe("UNKNOWN");
    expect(matchesMasked("12345", "XXXXXXXX1234")).toBe("UNKNOWN");
  });

  it("lastFour reads masked and full forms", () => {
    expect(lastFour("XXXXXXXX1234")).toBe("1234");
    expect(lastFour("12")).toBeNull();
  });
});
