import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchStaffSession, loginStaff } from "./staff-session";
import { readWorkingRole, workingRoleKey } from "./working-role";

describe("loginStaff", () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => vi.unstubAllGlobals());

  it("clears a stored working role so every login starts on Admin", async () => {
    localStorage.setItem(workingRoleKey("7"), "COLLECTION_HEAD");
    expect(readWorkingRole("7", "ADMIN")).toBe("COLLECTION_HEAD");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ id: "7", name: "A", role: "ADMIN" }),
      }),
    );
    const s = await loginStaff("a@b.c", "pw");
    expect(s.role).toBe("ADMIN");
    expect(localStorage.getItem(workingRoleKey("7"))).toBeNull();
    expect(readWorkingRole("7", "ADMIN")).toBe("ADMIN");
  });
});

describe("fetchStaffSession", () => {
  it("keeps a stored working role (a refresh does not reset it)", async () => {
    localStorage.clear();
    localStorage.setItem(workingRoleKey("7"), "COLLECTION_HEAD");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ session: { id: "7", name: "A", role: "ADMIN" } }),
      }),
    );
    expect((await fetchStaffSession())?.role).toBe("COLLECTION_HEAD");
    vi.unstubAllGlobals();
  });
});
