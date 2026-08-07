import { describe, expect, it } from "vitest";
import { passwordOk } from "./password";

describe("passwordOk", () => {
  it("accepts 6–10 chars with a letter, a digit and a special character", () => {
    expect(passwordOk("abc12!")).toBe(true);
    expect(passwordOk("Abcd1234!@")).toBe(true);
  });

  it("rejects a missing character class", () => {
    expect(passwordOk("abc123")).toBe(false); // no special
    expect(passwordOk("abcdef!")).toBe(false); // no digit
    expect(passwordOk("123456!")).toBe(false); // no letter
  });

  it("rejects lengths outside the window", () => {
    expect(passwordOk("ab1!")).toBe(false); // 4, too short
    expect(passwordOk("Admin@12345")).toBe(false); // 11, too long
  });

  it("does not count whitespace as a special character", () => {
    expect(passwordOk("abc 123")).toBe(false);
  });
});
