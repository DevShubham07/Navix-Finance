import { describe, expect, it } from "vitest";
import {
  assignedToOthersLabel,
  completenessPercent,
  countAssignedToOthers,
  isPageFullySelected,
  togglePageSelection,
} from "./telecalling-queue";

describe("isPageFullySelected", () => {
  it("is true only when every visible id is ticked", () => {
    expect(isPageFullySelected(new Set([1, 2, 3]), [1, 2])).toBe(true);
    expect(isPageFullySelected(new Set([1]), [1, 2])).toBe(false);
  });

  it("is false for an empty page", () => {
    expect(isPageFullySelected(new Set([1]), [])).toBe(false);
  });
});

describe("togglePageSelection", () => {
  it("ticks the visible page only, never rows on other pages", () => {
    expect([...togglePageSelection(new Set(), [1, 2])].sort()).toEqual([1, 2]);
  });

  it("completes a partly ticked page", () => {
    expect([...togglePageSelection(new Set([1]), [1, 2])].sort()).toEqual([1, 2]);
  });

  it("unticks the page when it is fully ticked, keeping ticks from other pages", () => {
    expect([...togglePageSelection(new Set([1, 2, 9]), [1, 2])]).toEqual([9]);
  });

  it("does not mutate the set it was given", () => {
    const before = new Set([1]);
    togglePageSelection(before, [1, 2]);
    expect([...before]).toEqual([1]);
  });
});

describe("countAssignedToOthers", () => {
  const rows = [{ ownerStaffId: null }, { ownerStaffId: 7 }, { ownerStaffId: 8 }, { ownerStaffId: 8 }];

  it("counts rows owned by someone other than me", () => {
    expect(countAssignedToOthers(rows, 7)).toBe(2);
    expect(countAssignedToOthers(rows, 42)).toBe(3);
  });

  it("is null while my id is unknown", () => {
    expect(countAssignedToOthers(rows, null)).toBeNull();
  });
});

describe("assignedToOthersLabel", () => {
  it("pluralises", () => {
    expect(assignedToOthersLabel(1)).toBe("1 application assigned to other staff isn't shown.");
    expect(assignedToOthersLabel(4)).toBe("4 applications assigned to other staff aren't shown.");
  });
});

describe("completenessPercent", () => {
  it("is the rounded share of required steps done", () => {
    expect(completenessPercent(3, 5)).toBe(60);
    expect(completenessPercent(1, 3)).toBe(33);
    expect(completenessPercent(5, 5)).toBe(100);
    expect(completenessPercent(0, 5)).toBe(0);
  });

  it("clamps to 0–100 and never divides by zero", () => {
    expect(completenessPercent(6, 5)).toBe(100);
    expect(completenessPercent(-1, 5)).toBe(0);
    expect(completenessPercent(2, 0)).toBe(0);
  });
});
