import { describe, expect, it } from "vitest";
import { can, canWorkAs, effectivePermissions, workingRolesFor } from "./rbac";

describe("working roles", () => {
  it("ADMIN can work as Admin (first, the default) plus 7 operational roles, never DSA", () => {
    const roles = workingRolesFor("ADMIN");
    expect(roles).toHaveLength(8);
    expect(roles[0]).toBe("ADMIN");
    expect(roles).not.toContain("DSA");
  });

  it("canWorkAs matrix", () => {
    expect(canWorkAs("CREDIT_HEAD", "CREDIT_EXECUTIVE")).toBe(true);
    expect(canWorkAs("CREDIT_HEAD", "COLLECTION_HEAD")).toBe(false);
    expect(canWorkAs("COLLECTION_HEAD", "COLLECTION_EXECUTIVE")).toBe(true);
    expect(canWorkAs("CREDIT_EXECUTIVE", "CREDIT_HEAD")).toBe(false);
    expect(canWorkAs("ACCOUNTANT", "ACCOUNTANT")).toBe(true);
    expect(canWorkAs("DSA", "DSA")).toBe(true);
    expect(canWorkAs("ADMIN", "ADMIN")).toBe(true);
  });
});

describe("effectivePermissions", () => {
  it("ADMIN working as Credit Head has exactly Credit Head's permissions", () => {
    const p = effectivePermissions("ADMIN", "CREDIT_HEAD");
    expect(new Set(p)).toEqual(new Set(effectivePermissions("CREDIT_HEAD", "CREDIT_HEAD")));
    for (const x of ["staff:manage", "customer:manage", "verification:retry", "loan:register"] as const) {
      expect(p).not.toContain(x);
    }
  });

  it("ADMIN working as Admin has the admin pages but no stage work", () => {
    const p = effectivePermissions("ADMIN", "ADMIN");
    expect(p).toContain("staff:manage");
    expect(p).toContain("verification:retry");
    for (const x of ["loan:review", "loan:pipeline", "collections:manage"] as const) {
      expect(p).not.toContain(x);
    }
  });

  it("Credit Head assigns but does not decide", () => {
    const p = effectivePermissions("CREDIT_HEAD", "CREDIT_HEAD");
    expect(p).not.toContain("loan:review");
    expect(p).not.toContain("kyc:approve");
    expect(p).toContain("loan:approve");
  });

  it("Credit Head working as Executive decides but loses the book-wide view", () => {
    const p = effectivePermissions("CREDIT_HEAD", "CREDIT_EXECUTIVE");
    expect(p).toContain("loan:review");
    expect(p).not.toContain("customer:view:all");
  });

  it("Collection Head has no field-work permission", () => {
    expect(effectivePermissions("COLLECTION_HEAD", "COLLECTION_HEAD")).not.toContain("collections:interact");
    expect(effectivePermissions("COLLECTION_HEAD", "COLLECTION_EXECUTIVE")).toContain("collections:interact");
  });

  it("has no duplicates", () => {
    const p = effectivePermissions("ADMIN", "CREDIT_HEAD");
    expect(new Set(p).size).toBe(p.length);
  });
});

describe("can", () => {
  it("is false while the session is unknown", () => {
    expect(can(undefined, "CREDIT_HEAD", "loan:approve")).toBe(false);
    expect(can("CREDIT_HEAD", undefined, "loan:approve")).toBe(false);
    expect(can("CREDIT_HEAD", "CREDIT_HEAD", "loan:approve")).toBe(true);
  });
});
