import { describe, expect, it } from "vitest";
import { ADMIN_NAV_ORDER, NAV, navGroupsFor, navLabel, navVisible, pageTitleFor } from "./staff-nav";

const item = (href: string) => NAV.flatMap((g) => g.items).find((i) => i.href === href)!;

describe("navLabel", () => {
  it("uses the working-role label and falls back to the default", () => {
    const live = item("/staff/applications");
    expect(navLabel(live, "CREDIT_EXECUTIVE")).toBe("My credit files");
    expect(navLabel(live, "ACCOUNTANT")).toBe("Repayments to verify");
    expect(navLabel(live, "ADMIN")).toBe("Live applications");
    expect(pageTitleFor("/staff/collections/settlements", "COLLECTION_EXECUTIVE", "x")).toBe("My settlements");
    expect(pageTitleFor("/staff/collections/settlements", "ADMIN", "fallback")).toBe("fallback");
  });
});

describe("navGroupsFor", () => {
  it("renders ADMIN in ADMIN_NAV_ORDER and keeps NAV for other roles", () => {
    const visible = navGroupsFor("ADMIN")[0].items.filter((i) => navVisible(i, "ADMIN", "ADMIN"));
    const hrefs = visible.map((i) => i.href);
    expect(hrefs).toEqual(ADMIN_NAV_ORDER.filter((h) => hrefs.includes(h)));
    expect(hrefs.slice(0, 3)).toEqual(["/staff/dashboard", "/staff/loans", "/staff/collections"]);
    expect(hrefs.at(-1)).toBe("/staff/admin/settings");
    expect(navGroupsFor("CREDIT_HEAD")).toBe(NAV);
  });
});
