import { describe, expect, it } from "vitest";
import { normalizeMobile } from "./utils";

describe("normalizeMobile", () => {
  it.each([
    ["+917417682036", "7417682036"],
    ["07417682036", "7417682036"],
    ["+91 74176 82036", "7417682036"],
    ["74176-82036", "7417682036"],
    ["7417682036", "7417682036"],
  ])("normalizes %s", (input, expected) => {
    expect(normalizeMobile(input)).toBe(expected);
  });
});
