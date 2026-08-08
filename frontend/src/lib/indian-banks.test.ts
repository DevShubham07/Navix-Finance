import { describe, expect, it } from "vitest";
import { INDIAN_BANKS, isListedIndianBank } from "./indian-banks";

describe("Indian bank choices", () => {
  it("are sorted alphabetically and accept legacy saved bank values through Other", () => {
    expect(INDIAN_BANKS).toEqual([...INDIAN_BANKS].sort((a, b) => a.localeCompare(b)));
    expect(isListedIndianBank("HDFC Bank")).toBe(true);
    expect(isListedIndianBank("Legacy Cooperative Bank")).toBe(false);
  });
});
