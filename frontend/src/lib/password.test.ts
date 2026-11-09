import { describe, expect, it } from "vitest";
import { passwordOk } from "./password";

describe("passwordOk", () => {
  it("accepts 8–72 chars with a letter, a digit and a special character", () => {
    expect(passwordOk("abcd123!")).toBe(true);
    expect(passwordOk("Admin@12345")).toBe(true);
    expect(passwordOk("a1!" + "x".repeat(69))).toBe(true); // 72 bytes
  });

  it("rejects a missing character class", () => {
    expect(passwordOk("abc123")).toBe(false); // no special
    expect(passwordOk("abcdef!")).toBe(false); // no digit
    expect(passwordOk("123456!")).toBe(false); // no letter
  });

  it("rejects lengths outside the window", () => {
    expect(passwordOk("abc12!x")).toBe(false); // 7, too short
    expect(passwordOk("a1!" + "x".repeat(70))).toBe(false); // 73 bytes
    expect(passwordOk("é1!" + "é".repeat(35))).toBe(false); // 39 chars but 74 bytes
  });

  it("does not count whitespace as a special character", () => {
    expect(passwordOk("abc 123")).toBe(false);
  });
});
