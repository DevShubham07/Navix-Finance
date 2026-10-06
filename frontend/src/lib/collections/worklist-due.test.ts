import { describe, expect, it } from "vitest";
import { worklistDueCue, worklistDueLabel } from "./worklist-due";

// 2026-10-06 00:30 IST — still the 5th in UTC, so a UTC "today" would get the cases below wrong.
const JUST_AFTER_IST_MIDNIGHT = new Date("2026-10-05T19:00:00Z");
// 2026-10-05 23:59 IST.
const JUST_BEFORE_IST_MIDNIGHT = new Date("2026-10-05T18:29:00Z");

describe("worklistDueCue", () => {
  it("a positive server DPD is overdue by that many days, whatever the due date says", () => {
    expect(worklistDueCue({ dpd: 12, dueDate: "2026-09-24" }, JUST_AFTER_IST_MIDNIGHT)).toEqual({
      kind: "overdue",
      days: 12,
    });
    expect(worklistDueCue({ dpd: 3, dueDate: null }, JUST_AFTER_IST_MIDNIGHT)).toEqual({ kind: "overdue", days: 3 });
  });

  it("is due today on the IST day, even while UTC is still the day before", () => {
    expect(worklistDueCue({ dpd: 0, dueDate: "2026-10-06" }, JUST_AFTER_IST_MIDNIGHT)).toEqual({ kind: "due-today" });
  });

  it("counts whole IST days to a future due date", () => {
    expect(worklistDueCue({ dpd: 0, dueDate: "2026-10-11" }, JUST_AFTER_IST_MIDNIGHT)).toEqual({
      kind: "due-in",
      days: 5,
    });
    // One IST minute earlier it is still the 5th, so the same due date is a day further away.
    expect(worklistDueCue({ dpd: 0, dueDate: "2026-10-11" }, JUST_BEFORE_IST_MIDNIGHT)).toEqual({
      kind: "due-in",
      days: 6,
    });
  });

  it("counts across a month boundary", () => {
    expect(worklistDueCue({ dpd: 0, dueDate: "2026-11-01" }, new Date("2026-10-30T06:00:00Z"))).toEqual({
      kind: "due-in",
      days: 2,
    });
  });

  it("accepts a timestamp-shaped due date by its calendar day", () => {
    expect(worklistDueCue({ dpd: 0, dueDate: "2026-10-07T00:00:00" }, JUST_AFTER_IST_MIDNIGHT)).toEqual({
      kind: "due-in",
      days: 1,
    });
  });

  it("does not invent a state for a missing, malformed or stale past due date", () => {
    expect(worklistDueCue({ dpd: 0, dueDate: null }, JUST_AFTER_IST_MIDNIGHT)).toEqual({ kind: "none" });
    expect(worklistDueCue({ dpd: 0, dueDate: "soon" }, JUST_AFTER_IST_MIDNIGHT)).toEqual({ kind: "none" });
    expect(worklistDueCue({ dpd: 0, dueDate: "2026-10-05" }, JUST_AFTER_IST_MIDNIGHT)).toEqual({ kind: "none" });
  });
});

describe("worklistDueLabel", () => {
  it("renders each state", () => {
    expect(worklistDueLabel({ kind: "overdue", days: 9 })).toBe("9");
    expect(worklistDueLabel({ kind: "due-today" })).toBe("due today");
    expect(worklistDueLabel({ kind: "due-in", days: 5 })).toBe("due in 5 d");
    expect(worklistDueLabel({ kind: "none" })).toBe("0");
  });
});
