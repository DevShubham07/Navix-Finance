import { describe, expect, it } from "vitest";
import { NAV, navGroupsFor, navLabel, navVisible, pageTitleFor } from "./staff-nav";

const item = (href: string) => NAV.flatMap((g) => g.items).find((i) => i.href === href)!;

describe("navLabel", () => {
  it("uses the working-role label and falls back to the default", () => {
    const live = item("/staff/applications");
    expect(navLabel(live, "CREDIT_EXECUTIVE")).toBe("My credit files");
    expect(navLabel(live, "ACCOUNTANT")).toBe("Repayments to verify");
    expect(navLabel(live, "ADMIN")).toBe("Live applications");
    expect(pageTitleFor("/staff/collections/settlements", "COLLECTION_EXECUTIVE", "x")).toBe("My settlements");
    expect(pageTitleFor("/staff/collections/settlements", "ADMIN", "fallback")).toBe("Total settlements");
    expect(pageTitleFor("/staff/collections/settlements", "ACCOUNTANT", "fallback")).toBe("fallback");
  });
});

describe("navGroupsFor", () => {
  it("groups ADMIN's sidebar by use case, with Total settlements under Collections", () => {
    const groups = navGroupsFor("ADMIN")
      .map((g) => ({ heading: g.heading, hrefs: g.items.filter((i) => navVisible(i, "ADMIN", "ADMIN")).map((i) => i.href) }))
      .filter((g) => g.hrefs.length);
    expect(groups.map((g) => g.heading)).toEqual([
      "Overview",
      "Customers & credit",
      "Leads & sales",
      "Collections",
      "Finance & reports",
      "Team & settings",
    ]);
    expect(groups[0].hrefs[0]).toBe("/staff/dashboard");
    expect(groups.find((g) => g.heading === "Collections")!.hrefs).toContain("/staff/collections/settlements");
    expect(navLabel(item("/staff/collections/settlements"), "ADMIN")).toBe("Total settlements");
    expect(groups.at(-1)!.hrefs.at(-1)).toBe("/staff/admin/settings");
  });

  it("keeps every nav href unique across groups", () => {
    const hrefs = NAV.flatMap((g) => g.items.map((i) => i.href));
    expect(new Set(hrefs).size).toBe(hrefs.length);
    expect(navGroupsFor("CREDIT_HEAD")).toBe(NAV);
  });
});
