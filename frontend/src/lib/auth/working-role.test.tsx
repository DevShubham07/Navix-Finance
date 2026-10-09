import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  clearWorkingRole,
  currentActingRole,
  readWorkingRole,
  workingRoleKey,
  writeWorkingRole,
} from "./working-role";

beforeEach(() => {
  localStorage.clear();
  clearWorkingRole("7");
});

describe("working role", () => {
  it("defaults to the first allowed role", () => {
    expect(readWorkingRole("7", "ADMIN")).toBe("CREDIT_HEAD");
    expect(readWorkingRole("7", "ACCOUNTANT")).toBe("ACCOUNTANT");
  });

  it("falls back when the stored value is stale or illegal", () => {
    localStorage.setItem(workingRoleKey("7"), "DSA");
    expect(readWorkingRole("7", "ADMIN")).toBe("CREDIT_HEAD");
    localStorage.setItem(workingRoleKey("7"), "TELECALLER");
    expect(readWorkingRole("7", "CREDIT_HEAD")).toBe("CREDIT_HEAD");
    localStorage.setItem(workingRoleKey("7"), "nonsense");
    expect(readWorkingRole("7", "CREDIT_HEAD")).toBe("CREDIT_HEAD");
  });

  it("write persists and notifies", () => {
    const fn = vi.fn();
    window.addEventListener("navix-staff-session", fn);
    writeWorkingRole("7", "ADMIN", "TELECALLER");
    window.removeEventListener("navix-staff-session", fn);
    expect(localStorage.getItem(workingRoleKey("7"))).toBe("TELECALLER");
    expect(fn).toHaveBeenCalledTimes(1);
    expect(readWorkingRole("7", "ADMIN")).toBe("TELECALLER");
  });

  it("ignores an illegal write", () => {
    writeWorkingRole("7", "CREDIT_HEAD", "ADMIN");
    expect(localStorage.getItem(workingRoleKey("7"))).toBeNull();
  });

  it("acting role is null when working == real and after clear", () => {
    readWorkingRole("7", "ACCOUNTANT");
    expect(currentActingRole()).toBeNull();
    writeWorkingRole("7", "ADMIN", "CREDIT_EXECUTIVE");
    expect(currentActingRole()).toBe("CREDIT_EXECUTIVE");
    writeWorkingRole("7", "CREDIT_HEAD", "CREDIT_HEAD");
    expect(currentActingRole()).toBeNull();
    writeWorkingRole("7", "CREDIT_HEAD", "CREDIT_EXECUTIVE");
    clearWorkingRole("7");
    expect(localStorage.getItem(workingRoleKey("7"))).toBeNull();
    expect(currentActingRole()).toBeNull();
  });
});
