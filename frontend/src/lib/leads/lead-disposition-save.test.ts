import { describe, expect, it } from "vitest";
import { leadOutcomePatch, leadSaveToast, settableOutcome } from "./lead-disposition-save";

describe("settableOutcome", () => {
  it("shows a derived CONFIRMED as NEW in the picker and leaves the rest alone", () => {
    expect(settableOutcome("CONFIRMED")).toBe("NEW");
    expect(settableOutcome("NEW")).toBe("NEW");
    expect(settableOutcome("OUTREACHED")).toBe("OUTREACHED");
    expect(settableOutcome("REJECTED")).toBe("REJECTED");
  });
});

describe("leadOutcomePatch", () => {
  it("returns null when neither field changed, so the outcome PUT is skipped", () => {
    expect(leadOutcomePatch({ leadOutcome: "OUTREACHED", dsaNote: "call back" }, "OUTREACHED", "call back")).toBeNull();
    expect(leadOutcomePatch({ leadOutcome: "NEW", dsaNote: null }, "NEW", "")).toBeNull();
  });

  it("does not count whitespace the server would trim as a change", () => {
    expect(leadOutcomePatch({ leadOutcome: "NEW", dsaNote: "hi" }, "NEW", "  hi  ")).toBeNull();
    expect(leadOutcomePatch({ leadOutcome: "NEW", dsaNote: null }, "NEW", "   ")).toBeNull();
  });

  it("sends only the outcome when only the outcome changed", () => {
    expect(leadOutcomePatch({ leadOutcome: "NEW", dsaNote: "x" }, "REJECTED", "x")).toEqual({ leadOutcome: "REJECTED" });
  });

  it("sends only the note (trimmed) when only the note changed", () => {
    expect(leadOutcomePatch({ leadOutcome: "OUTREACHED", dsaNote: null }, "OUTREACHED", " new note ")).toEqual({
      dsaNote: "new note",
    });
  });

  it("sends an empty note to clear one", () => {
    expect(leadOutcomePatch({ leadOutcome: "NEW", dsaNote: "old" }, "NEW", "")).toEqual({ dsaNote: "" });
  });

  it("sends both when both changed", () => {
    expect(leadOutcomePatch({ leadOutcome: "NEW", dsaNote: null }, "OUTREACHED", "rang twice")).toEqual({
      leadOutcome: "OUTREACHED",
      dsaNote: "rang twice",
    });
  });

  it("does not write NEW over a CONFIRMED lead when only its note changed", () => {
    expect(leadOutcomePatch({ leadOutcome: "CONFIRMED", dsaNote: null }, "NEW", "applied")).toEqual({
      dsaNote: "applied",
    });
  });
});

describe("leadSaveToast", () => {
  it("names the disposition and every field the outcome PUT carried", () => {
    expect(leadSaveToast(true, { leadOutcome: "OUTREACHED", dsaNote: "x" })).toBe(
      "Disposition, outcome & DSA note saved",
    );
    expect(leadSaveToast(true, null)).toBe("Disposition saved");
    expect(leadSaveToast(false, { leadOutcome: "REJECTED", dsaNote: "" })).toBe("Outcome & DSA note saved");
  });

  it("does not claim a field the outcome PUT never sent", () => {
    expect(leadSaveToast(true, { dsaNote: "rang twice" })).toBe("Disposition & DSA note saved");
    expect(leadSaveToast(true, { leadOutcome: "REJECTED" })).toBe("Disposition & outcome saved");
    expect(leadSaveToast(false, { leadOutcome: "NEW" })).toBe("Outcome saved");
    expect(leadSaveToast(false, { dsaNote: "" })).toBe("DSA note saved");
  });

  it("is null when nothing was saved", () => {
    expect(leadSaveToast(false, null)).toBeNull();
    expect(leadSaveToast(false, {})).toBeNull();
  });
});
