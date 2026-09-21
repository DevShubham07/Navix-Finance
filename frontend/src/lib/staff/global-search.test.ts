import { describe, expect, it } from "vitest";
import {
  buildFeatureIndex,
  interpretQuery,
  isSearchable,
  matchFeatures,
} from "@/lib/staff/global-search";

const titles = (role: Parameters<typeof buildFeatureIndex>[0]) =>
  buildFeatureIndex(role).map((h) => h.title);

describe("buildFeatureIndex — RBAC", () => {
  it("gives a DSA only its own portal pages and the shared import screen", () => {
    // The DSA palette is not mounted at all (the shell gates on the role, and the endpoint rejects
    // DSA), but the index must still be correct in case it is ever reused for a DSA surface.
    expect(titles("DSA").sort()).toEqual(["Import leads", "My earnings", "My leads"]);
  });

  it("never offers a page the role's permission does not cover", () => {
    const telecaller = titles("TELECALLER");
    expect(telecaller).toContain("Leads");
    expect(telecaller).not.toContain("Loans");
    expect(telecaller).not.toContain("Staff");
    expect(telecaller).not.toContain("Blocklist");

    const collectionExec = titles("COLLECTION_EXECUTIVE");
    expect(collectionExec).toContain("DPD buckets");
    // Loans register is loan:register — COLLECTION_HEAD and ADMIN only.
    expect(collectionExec).not.toContain("Loans");
    expect(titles("COLLECTION_HEAD")).toContain("Loans");
  });

  it("hides a flagged page when its feature flag is explicitly off", () => {
    expect(titles("ADMIN")).toContain("Referral payouts");
    const withFlagOff = buildFeatureIndex("ADMIN", { referral: false }).map((h) => h.title);
    expect(withFlagOff).not.toContain("Referral payouts");
  });

  it("expands segment children so a segment name is findable", () => {
    const admin = buildFeatureIndex("ADMIN");
    const overdue = admin.find((h) => h.href === "/staff/loans?seg=overdue");
    expect(overdue?.title).toMatch(/^Loans · /);
  });
});

describe("matchFeatures", () => {
  const index = buildFeatureIndex("ADMIN");

  it("ranks a token-prefix hit above a mid-word one", () => {
    const hits = matchFeatures(index, "cust", 5).map((h) => h.title);
    expect(hits[0]).toBe("Customers");
  });

  it("matches on the URL path as well as the label", () => {
    expect(matchFeatures(index, "blocklist").map((h) => h.title)).toContain("Blocklist");
  });

  it("returns nothing for an empty query and respects the cap", () => {
    expect(matchFeatures(index, "")).toEqual([]);
    expect(matchFeatures(index, "s", 3).length).toBeLessThanOrEqual(3);
  });
});

describe("interpretQuery", () => {
  it("reads a bare, +91 and 91-prefixed mobile as the same 10 digits", () => {
    expect(interpretQuery("9876543210")).toEqual({ kind: "mobile", value: "9876543210" });
    expect(interpretQuery("+91 98765 43210")).toEqual({ kind: "mobile", value: "9876543210" });
    expect(interpretQuery("919876543210")).toEqual({ kind: "mobile", value: "9876543210" });
  });

  it("upper-cases a PAN", () => {
    expect(interpretQuery("abcde1234f")).toEqual({ kind: "pan", value: "ABCDE1234F" });
  });

  it("treats a short number as an id, with or without the # shorthand", () => {
    expect(interpretQuery("#1042")).toEqual({ kind: "id", value: "1042" });
    expect(interpretQuery("1042")).toEqual({ kind: "id", value: "1042" });
  });

  it("falls back to text", () => {
    expect(interpretQuery("  Rajesh   Kumar ")).toEqual({ kind: "text", value: "Rajesh Kumar" });
  });
});

describe("isSearchable", () => {
  it("needs two characters of text but only one digit", () => {
    expect(isSearchable("")).toBe(false);
    expect(isSearchable("r")).toBe(false);
    expect(isSearchable("ra")).toBe(true);
    expect(isSearchable("7")).toBe(true);
    expect(isSearchable("#7")).toBe(true);
  });
});
