import { describe, expect, it } from "vitest";
import { formatAadhaar, isValidAadhaar, maskAadhaar, normalizeAadhaar } from "./aadhaar";

// Check digit computed with the same Verhoeff tables the backend AadhaarTest uses (234567890124).
const VALID = "234567890124";

describe("aadhaar", () => {
  it("accepts a checksum-valid number, with or without card spacing", () => {
    expect(isValidAadhaar(VALID)).toBe(true);
    expect(isValidAadhaar("2345 6789 0124")).toBe(true);
  });

  it("rejects a typo, a transposition, the wrong length and a reserved leading digit", () => {
    expect(isValidAadhaar("234567890125")).toBe(false);
    expect(isValidAadhaar("234567980124")).toBe(false);
    expect(isValidAadhaar("23456789012")).toBe(false);
    expect(isValidAadhaar("134567890124")).toBe(false);
    expect(isValidAadhaar("")).toBe(false);
  });

  it("formats and masks for display", () => {
    expect(normalizeAadhaar("2345-6789-0124x")).toBe("234567890124");
    expect(formatAadhaar("23456789")).toBe("2345 6789");
    expect(formatAadhaar(VALID)).toBe("2345 6789 0124");
    expect(maskAadhaar(VALID)).toBe("XXXX XXXX 0124");
    expect(maskAadhaar("1234")).toBe("");
  });
});
