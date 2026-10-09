import { describe, expect, it } from "vitest";
import { STAFF_ROLES } from "@/lib/auth/rbac";
import { allowedViews, defaultView, resolveView, showsStrip } from "./views";

describe("allowedViews", () => {
  it("ADMIN gets all eight, led by the admin overview", () => {
    const v = allowedViews("ADMIN");
    expect(v).toHaveLength(8);
    expect(v[0]).toBe("ADMIN");
  });

  it("a Head gets its own view plus its executive view, nothing else", () => {
    expect(allowedViews("CREDIT_HEAD")).toEqual(["CREDIT_HEAD", "CREDIT_EXECUTIVE"]);
    expect(allowedViews("COLLECTION_HEAD")).toEqual(["COLLECTION_HEAD", "COLLECTION_EXECUTIVE"]);
  });

  it("every other role gets only itself; DSA gets none", () => {
    for (const r of STAFF_ROLES) {
      if (r === "ADMIN" || r === "CREDIT_HEAD" || r === "COLLECTION_HEAD" || r === "DSA") continue;
      expect(allowedViews(r)).toEqual([r]);
    }
    expect(allowedViews("DSA")).toEqual([]);
  });

  it("the strip shows only where there is a choice", () => {
    expect(showsStrip("ADMIN")).toBe(true);
    expect(showsStrip("CREDIT_HEAD")).toBe(true);
    expect(showsStrip("ACCOUNTANT")).toBe(false);
    expect(showsStrip("DSA")).toBe(false);
  });
});

describe("defaultView", () => {
  it("a real ADMIN defaults to the admin overview whatever the working role", () => {
    expect(defaultView("ADMIN", "CREDIT_HEAD")).toBe("ADMIN");
  });

  it("a Head defaults to its working role", () => {
    expect(defaultView("CREDIT_HEAD", "CREDIT_EXECUTIVE")).toBe("CREDIT_EXECUTIVE");
    expect(defaultView("COLLECTION_HEAD", "COLLECTION_HEAD")).toBe("COLLECTION_HEAD");
  });

  it("falls back to the real role when the working role is not allowed, null for DSA", () => {
    expect(defaultView("ACCOUNTANT", "ADMIN")).toBe("ACCOUNTANT");
    expect(defaultView("DSA", "DSA")).toBeNull();
  });
});

describe("resolveView", () => {
  it("honours an allowed ?view= and ignores a forbidden one", () => {
    expect(resolveView("CREDIT_HEAD", "CREDIT_HEAD", "CREDIT_EXECUTIVE")).toBe("CREDIT_EXECUTIVE");
    expect(resolveView("CREDIT_HEAD", "CREDIT_HEAD", "COLLECTION_HEAD")).toBe("CREDIT_HEAD");
    expect(resolveView("ACCOUNTANT", "ACCOUNTANT", "ADMIN")).toBe("ACCOUNTANT");
    expect(resolveView("ADMIN", "ADMIN", null)).toBe("ADMIN");
  });
});
