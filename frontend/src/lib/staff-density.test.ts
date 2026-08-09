import { describe, expect, it } from "vitest";

async function subject() {
  return import("./staff-density").catch(() => ({}));
}

describe("applyStaffDesktopDensity", () => {
  it("adds the staff-only density marker and removes it during cleanup", async () => {
    const mod = await subject() as Record<string, unknown>;
    expect(typeof mod.applyStaffDesktopDensity).toBe("function");
    const values = new Set<string>();
    const classList = {
      add: (...tokens: string[]) => tokens.forEach((token) => values.add(token)),
      remove: (...tokens: string[]) => tokens.forEach((token) => values.delete(token)),
    };

    const apply = mod.applyStaffDesktopDensity as (value: typeof classList) => () => void;
    const cleanup = apply(classList);
    expect(values.has("staff-desktop-density")).toBe(true);

    cleanup();
    expect(values.has("staff-desktop-density")).toBe(false);
  });
});
