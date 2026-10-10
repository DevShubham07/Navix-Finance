import { describe, expect, it } from "vitest";
import type { CreditBriefView } from "@/lib/api/applications";
import type { JsonValue } from "@/lib/credit/provider-report";
import {
  inWindow,
  isPersonalLoanType,
  lookbackWindow,
  parseBureauReport,
  parseCrifHistory,
  reportFromTradelines,
  worstDpdInWindow,
} from "@/lib/customers/bre/bureau-report";
import combine from "@/lib/customers/bre/__fixtures__/crif-combine-sample.json";
import kba from "@/lib/customers/bre/__fixtures__/crif-auth-answer-sample.json";
import { EXPERIAN } from "@/lib/customers/bre/__fixtures__/experian";

const TODAY = new Date(2026, 9, 10);

describe("parseBureauReport — CRIF (Fintrix crif_combine sample)", () => {
  const r = parseBureauReport(combine as unknown as JsonValue, 318)!;

  it("reads the report root, date and score", () => {
    expect(r.kind).toBe("CRIF");
    expect(r.source).toBe("RAW");
    expect(r.reportDate).toBe("2026-08-22");
    expect(r.score).toBe(799);
    expect(r.accounts).toHaveLength(7);
  });

  it("keeps ownership, lender type and the dated payment history the parsed tradeline drops", () => {
    const stpl = r.accounts.find((a) => a.accountType === "Short Term Personal Loan")!;
    expect(stpl).toMatchObject({
      lender: "ADITYA BIRLA CAPITAL LIMITED", isPersonalLoan: true, ownership: "INDIVIDUAL", lenderType: "NBF",
      isNbfc: true, status: "CLOSED", openedOn: "2023-02-18", closedOn: "2024-03-28",
    });
    expect(stpl.history[0]).toEqual({ month: "2024-03", dpd: 0, npa: false });
  });

  it("does not take 'Auto Loan (Personal)' for a personal loan", () => {
    expect(r.accounts.some((a) => a.accountType === "Auto Loan (Personal)" && !a.isPersonalLoan)).toBe(true);
  });

  it("collects the reported occupations and the bureau's addresses", () => {
    expect(r.occupations.map((o) => o.value)).toContain("SALARIED");
    expect(r.addresses.some((a) => a.value.includes("SAMPLE EMPLOYER PRIVATE LIMITED"))).toBe(true);
  });
});

describe("parseBureauReport — CRIF KBA answer (report flat under data)", () => {
  const r = parseBureauReport(kba as unknown as JsonValue, 9)!;
  it("reads the flat shape", () => {
    expect(r.kind).toBe("CRIF");
    expect(r.score).toBe(510);
    const consumer = r.accounts.find((a) => a.accountType === "Consumer Loan")!;
    expect(consumer).toMatchObject({ isNbfc: true, status: "ACTIVE" });
    const w = lookbackWindow(r, null, TODAY);
    expect(worstDpdInWindow(consumer, w)).toBe(900);
  });
});

describe("parseBureauReport — Experian (Digitap)", () => {
  const r = parseBureauReport(EXPERIAN, 5)!;
  it("maps holder codes, the NBF identification prefix and dated DPD", () => {
    expect(r.kind).toBe("EXPERIAN");
    expect(r.reportDate).toBe("2026-08-09");
    expect(r.score).toBe(742);
    const [pl, card] = r.accounts;
    expect(pl).toMatchObject({ isPersonalLoan: true, ownership: "INDIVIDUAL", isNbfc: true, status: "ACTIVE", historyExact: true });
    expect(pl.history.map((h) => h.month)).toEqual(["2026-07", "2026-06"]);
    expect(card).toMatchObject({ isPersonalLoan: false, ownership: "GUARANTOR", isNbfc: false, status: "CLOSED", historyExact: false });
    expect(r.occupations[0].value).toBe("Salaried (code S)");
  });
});

describe("non-report shapes", () => {
  it("returns null for a REVIEW snapshot, a KBA challenge and null", () => {
    expect(parseBureauReport({ provider: "FINTRIX", txnId: "t", status: "REVIEW", fields: {} }, 1)).toBeNull();
    expect(parseBureauReport({ status: "error", data: { question: "Q?", options: ["a"], order_id: "o" } }, 1)).toBeNull();
    expect(parseBureauReport(null, 1)).toBeNull();
  });
});

describe("reportFromTradelines", () => {
  it("rebuilds a CRIF report from parsed tradelines without inventing ownership", () => {
    const brief = {
      applicationId: 12,
      creditScore: 700,
      bureauSource: "FINTRIX_CRIF",
      facts: {
        detail: {
          tradelines: [{
            lender: "X FINANCE", accountNumberMasked: null, accountTypeCode: "Personal Loan", portfolioTypeCode: "NBF",
            accountStatusCode: "Active", openedOn: "2026-01-01", closedOn: null, currentBalanceRupees: 1000,
            amountPastDueRupees: 0, creditLimitRupees: null, settlementAmountRupees: null,
            paymentHistory: "Jul:2026,000/STD|", writtenOffSettledStatus: null, worstDpdMonths: 0,
          }],
        },
      },
    } as unknown as CreditBriefView;
    const r = reportFromTradelines(brief)!;
    expect(r).toMatchObject({ kind: "CRIF", source: "PARSED", score: 700 });
    expect(r.accounts[0]).toMatchObject({ isPersonalLoan: true, isNbfc: true, ownership: null, status: "ACTIVE" });
  });
});

describe("window helpers", () => {
  it("ends on the report date and clamps the start day to the month", () => {
    expect(lookbackWindow(null, "2026-11-30T10:00:00Z", TODAY)).toEqual({ end: "2026-11-30", start: "2026-02-28", startMonth: "2026-02" });
    expect(lookbackWindow(null, null, TODAY).end).toBe("2026-10-10");
  });

  it("counts an account closed on/after the start, or still open", () => {
    const w = lookbackWindow(null, "2026-08-22", TODAY);
    const base = { history: [], historyExact: true } as never;
    expect(inWindow({ ...(base as object), status: "CLOSED", closedOn: "2025-11-22", openedOn: "2025-01-01" } as never, w)).toBe(true);
    expect(inWindow({ ...(base as object), status: "CLOSED", closedOn: "2025-11-21", openedOn: "2025-01-01" } as never, w)).toBe(false);
    expect(inWindow({ ...(base as object), status: "ACTIVE", closedOn: null, openedOn: "2019-01-01" } as never, w)).toBe(true);
  });

  it("parses CRIF history and personal-loan types", () => {
    expect(parseCrifHistory("Aug:2026,027/XXX|Jul:2026,XXX/SUB|")).toEqual([
      { month: "2026-08", dpd: 27, npa: false },
      { month: "2026-07", dpd: null, npa: true },
    ]);
    expect(isPersonalLoanType("Personal Loan")).toBe(true);
    expect(isPersonalLoanType("69")).toBe(true);
    expect(isPersonalLoanType("Auto Loan (Personal)")).toBe(false);
    expect(isPersonalLoanType("Credit Card")).toBe(false);
  });
});
