import { describe, expect, it } from "vitest";
import type { DecisionView } from "@/lib/api/applications";
import {
  decisionActionLabel,
  decisionMatchesSearch,
  placeholderForSameIdentity,
  toDecisionRow,
  withDecisionHistoryParams,
} from "./decision-history";

function decision(overrides: Partial<DecisionView> = {}): DecisionView {
  return {
    applicationId: 318,
    customerId: 42,
    customerName: "Asha Verma",
    pan: "ABCDE1234F",
    action: "SANCTION",
    fromStatus: "CREDIT_EXEC_PENDING",
    toStatus: "SANCTIONED",
    at: "2026-10-01T05:30:00Z",
    amountPaise: 1_000_000,
    salaryCreditDay: 30,
    repaymentDate: "2026-10-30",
    assigneeId: null,
    assigneeName: null,
    txnRef: null,
    remark: null,
    notes: null,
    ...overrides,
  };
}

describe("decisionMatchesSearch", () => {
  it("matches everything for a blank term", () => {
    expect(decisionMatchesSearch(decision(), "")).toBe(true);
    expect(decisionMatchesSearch(decision(), "   ")).toBe(true);
  });

  it("matches application id, customer id, name and PAN", () => {
    expect(decisionMatchesSearch(decision(), "318")).toBe(true);
    expect(decisionMatchesSearch(decision(), "#318")).toBe(true);
    expect(decisionMatchesSearch(decision(), "42")).toBe(true);
    expect(decisionMatchesSearch(decision(), "asha")).toBe(true);
    expect(decisionMatchesSearch(decision(), "abcde1234f")).toBe(true);
  });

  it("does not match fields outside the search scope", () => {
    expect(decisionMatchesSearch(decision({ txnRef: "UTR999" }), "UTR999")).toBe(false);
    expect(decisionMatchesSearch(decision(), "sanction")).toBe(false);
  });

  it("tolerates missing customer fields", () => {
    const bare = decision({ customerId: null, customerName: null, pan: null });
    expect(decisionMatchesSearch(bare, "asha")).toBe(false);
    expect(decisionMatchesSearch(bare, "318")).toBe(true);
  });
});

describe("toDecisionRow", () => {
  it("derives a numeric instant and the on-screen label", () => {
    const r = toDecisionRow(decision());
    expect(r.atMs).toBe(Date.parse("2026-10-01T05:30:00Z"));
    expect(r.decisionLabel).toBe("Accepted lead (sanctioned)");
  });

  it("keeps an unknown action readable and an unparseable time null", () => {
    const r = toDecisionRow(decision({ action: "NEW_THING", at: "not a date" }));
    expect(r.decisionLabel).toBe("NEW_THING");
    expect(r.atMs).toBeNull();
    expect(decisionActionLabel("REASSIGN")).toBe("Reassigned to another executive");
  });

  it("orders instants correctly where the text would not", () => {
    // Same second, different fractional precision: as text "…00Z" sorts after "…00.5Z".
    const a = toDecisionRow(decision({ at: "2026-10-01T05:30:00Z" }));
    const b = toDecisionRow(decision({ at: "2026-10-01T05:30:00.5Z" }));
    expect(b.atMs! > a.atMs!).toBe(true);
  });
});

describe("withDecisionHistoryParams", () => {
  it("writes set values and deletes blank ones, keeping other params", () => {
    expect(withDecisionHistoryParams("x=1&from=2026-01-01", { staffId: "7", from: undefined, to: "2026-10-06" })).toBe(
      "x=1&staffId=7&to=2026-10-06",
    );
  });

  it("clears everything for own decisions over all time", () => {
    expect(withDecisionHistoryParams("staffId=7&from=2026-10-01&to=2026-10-06", { staffId: "" })).toBe("");
  });

  it("is stable when nothing changed", () => {
    const current = "staffId=7&from=2026-10-01&to=2026-10-06";
    expect(withDecisionHistoryParams(current, { staffId: "7", from: "2026-10-01", to: "2026-10-06" })).toBe(current);
  });
});

describe("placeholderForSameIdentity", () => {
  const prev = [decision()];

  it("keeps previous data when only the period changed", () => {
    const fn = placeholderForSameIdentity<DecisionView[]>(1, "7");
    expect(fn(prev, { queryKey: ["decisions", "7", "2026-09-01", "2026-09-30"] })).toBe(prev);
  });

  it("drops previous data when the person changed", () => {
    const fn = placeholderForSameIdentity<DecisionView[]>(1, "8");
    expect(fn(prev, { queryKey: ["decisions", "7", "", ""] })).toBeUndefined();
  });

  it("drops previous data when there was no previous query", () => {
    const fn = placeholderForSameIdentity<DecisionView[]>(1, "7");
    expect(fn(prev, undefined)).toBeUndefined();
  });
});
