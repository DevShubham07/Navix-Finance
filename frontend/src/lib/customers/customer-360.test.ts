import { describe, expect, it } from "vitest";
import { dueDateFromSalary } from "@/lib/calc/loan-math";
import type { ActivityEntry, LoanView, OutstandingView, PaymentView } from "@/lib/api/applications";
import {
  AUDIT_FILTER_ALL,
  SALARY_DUE_MAX_DAYS,
  auditTypeChips,
  auditTypeLabel,
  customerExposure,
  filterActivityByType,
  isoDayToLocalDate,
  istCalendarToday,
  limitBasisOf,
  LIMIT_BASIS_LABEL,
  resolveAuditFilter,
  rupeeInputPreview,
  salaryDueWindow,
} from "./customer-360";

const ymd = (d: Date) => [d.getFullYear(), d.getMonth() + 1, d.getDate()];

describe("istCalendarToday", () => {
  it("returns the IST day when UTC is still on the previous day", () => {
    // 19:00 UTC on 5 Oct is 00:30 IST on 6 Oct.
    expect(ymd(istCalendarToday(new Date("2026-10-05T19:00:00Z")))).toEqual([2026, 10, 6]);
  });

  it("stays on the same IST day just before IST midnight", () => {
    // 18:29 UTC is 23:59 IST.
    expect(ymd(istCalendarToday(new Date("2026-10-05T18:29:00Z")))).toEqual([2026, 10, 5]);
  });

  it("returns a local-midnight date (no time-of-day)", () => {
    const d = istCalendarToday(new Date("2026-03-01T06:00:00Z"));
    expect([d.getHours(), d.getMinutes(), d.getSeconds()]).toEqual([0, 0, 0]);
  });
});

describe("salaryDueWindow", () => {
  it("is within the window for the CLAUDE.md §9 worked example (27 days)", () => {
    expect(salaryDueWindow(new Date(2026, 5, 3), new Date(2026, 5, 30))).toEqual({ days: 27, exceeds: false });
  });

  it("treats exactly 40 days as allowed", () => {
    expect(salaryDueWindow(new Date(2026, 5, 3), new Date(2026, 6, 13))).toEqual({ days: 40, exceeds: false });
  });

  it("flags 41 days as beyond the 40-day rule", () => {
    expect(salaryDueWindow(new Date(2026, 5, 3), new Date(2026, 6, 14))).toEqual({ days: 41, exceeds: true });
  });

  it("uses 40 as the default limit", () => {
    expect(SALARY_DUE_MAX_DAYS).toBe(40);
  });

  it("never fires for dueDateFromSalary across a full year of disbursal days and every salary day", () => {
    // The warning is a guard on an invariant: the projection is built to stay inside the window.
    const start = new Date(2026, 0, 1);
    for (let offset = 0; offset < 366; offset++) {
      const disbursedOn = new Date(start.getFullYear(), start.getMonth(), start.getDate() + offset);
      for (let salaryDay = 1; salaryDay <= 31; salaryDay++) {
        const due = dueDateFromSalary({ disbursedOn, salaryDay });
        const { days, exceeds } = salaryDueWindow(disbursedOn, due);
        expect(exceeds).toBe(false);
        expect(days).toBeGreaterThan(0);
      }
    }
  });
});

describe("isoDayToLocalDate", () => {
  it("keeps the calendar day of a LocalDate string", () => {
    expect(ymd(isoDayToLocalDate("2026-10-03")!)).toEqual([2026, 10, 3]);
  });

  it("returns null for a missing or malformed value", () => {
    expect(isoDayToLocalDate(null)).toBeNull();
    expect(isoDayToLocalDate("")).toBeNull();
    expect(isoDayToLocalDate("03/10/2026")).toBeNull();
  });
});

describe("limitBasisOf", () => {
  it("is an admin override whenever an override is stored, including zero", () => {
    expect(limitBasisOf(5_000_000)).toBe("ADMIN_OVERRIDE");
    expect(limitBasisOf(0)).toBe("ADMIN_OVERRIDE");
  });

  it("falls back to the salary rule when no override is stored", () => {
    expect(limitBasisOf(null)).toBe("SALARY_RULE");
    expect(limitBasisOf(undefined)).toBe("SALARY_RULE");
  });

  it("labels both bases", () => {
    expect(LIMIT_BASIS_LABEL.ADMIN_OVERRIDE).toBe("Admin override");
    expect(LIMIT_BASIS_LABEL.SALARY_RULE).toBe("Salary rule");
  });
});

describe("rupeeInputPreview", () => {
  it("formats with Indian digit grouping", () => {
    expect(rupeeInputPreview("42000")).toBe("₹ 42,000");
    expect(rupeeInputPreview("420000")).toBe("₹ 4,20,000");
    expect(rupeeInputPreview("12345678")).toBe("₹ 1,23,45,678");
  });

  it("leaves three digits or fewer ungrouped", () => {
    expect(rupeeInputPreview("7")).toBe("₹ 7");
    expect(rupeeInputPreview("999")).toBe("₹ 999");
    expect(rupeeInputPreview("1000")).toBe("₹ 1,000");
  });

  it("returns null for an empty field", () => {
    expect(rupeeInputPreview("")).toBeNull();
    expect(rupeeInputPreview(null)).toBeNull();
    expect(rupeeInputPreview("abc")).toBeNull();
  });

  it("ignores non-digits and leading zeros without rounding a long value", () => {
    expect(rupeeInputPreview("00042,000")).toBe("₹ 42,000");
    expect(rupeeInputPreview("000")).toBe("₹ 0");
    expect(rupeeInputPreview("12345678901234567890")).toBe("₹ 1,23,45,67,89,01,23,45,67,890");
  });
});

const loan = (id: number, principalPaise: number) => ({ id, principalPaise }) as unknown as LoanView;
const out = (loanId: number, outstandingPaise: number) => ({ loanId, outstandingPaise }) as OutstandingView;
const payment = (id: number, status: PaymentView["status"], paidOn: string | null) =>
  ({ id, loanId: 1, amountPaise: 100, status, paidOn }) as unknown as PaymentView;

describe("customerExposure", () => {
  it("sums principal and outstanding across every loan", () => {
    const result = customerExposure({
      loans: [loan(2, 2_000_000), loan(1, 1_000_000)],
      payments: [],
      outstandingByLoanId: { "2": out(2, 1_500_000), "1": out(1, 0) },
    });
    expect(result).toEqual({
      loanCount: 2,
      totalPrincipalPaise: 3_000_000,
      totalOutstandingPaise: 1_500_000,
      lastVerifiedPaymentOn: null,
    });
  });

  it("omits outstanding when the payload has no breakdown map", () => {
    const result = customerExposure({ loans: [loan(1, 1_000_000)], payments: [] });
    expect(result.totalOutstandingPaise).toBeNull();
  });

  it("omits outstanding rather than under-reporting when a loan is missing from the map", () => {
    const result = customerExposure({
      loans: [loan(1, 1_000_000), loan(2, 500_000)],
      payments: [],
      outstandingByLoanId: { "1": out(1, 200_000) },
    });
    expect(result.totalOutstandingPaise).toBeNull();
  });

  it("takes the latest paidOn among VERIFIED payments only", () => {
    const result = customerExposure({
      loans: [loan(1, 1_000_000)],
      payments: [
        payment(1, "VERIFIED", "2026-08-30"),
        payment(2, "VERIFIED", "2026-09-30"),
        payment(3, "PENDING_VERIFICATION", "2026-10-04"),
        payment(4, "REJECTED", "2026-10-05"),
        payment(5, "VERIFIED", null),
      ],
      outstandingByLoanId: { "1": out(1, 0) },
    });
    expect(result.lastVerifiedPaymentOn).toBe("2026-09-30");
  });

  it("handles a customer with no loans", () => {
    expect(customerExposure({ loans: [], payments: [], outstandingByLoanId: {} })).toEqual({
      loanCount: 0,
      totalPrincipalPaise: 0,
      totalOutstandingPaise: null,
      lastVerifiedPaymentOn: null,
    });
  });
});

const entry = (type: string) => ({ type }) as ActivityEntry;

describe("auditTypeChips", () => {
  it("derives one chip per type present, with counts, in the canonical order", () => {
    const chips = auditTypeChips([
      entry("REMARK"),
      entry("LIFECYCLE"),
      entry("PROFILE"),
      entry("LIFECYCLE"),
      entry("REVERIFY"),
    ]);
    expect(chips).toEqual([
      { type: "LIFECYCLE", label: "Lifecycle", count: 2 },
      { type: "REVERIFY", label: "Re-verify", count: 1 },
      { type: "PROFILE", label: "Profile edit", count: 1 },
      { type: "REMARK", label: "Remark", count: 1 },
    ]);
  });

  it("offers no chip for a type that is absent", () => {
    expect(auditTypeChips([entry("CALL")]).map((c) => c.type)).toEqual(["CALL"]);
    expect(auditTypeChips([])).toEqual([]);
  });

  it("keeps an unknown future type, after the known ones, under its raw name", () => {
    const chips = auditTypeChips([entry("ZETA"), entry("ALPHA"), entry("UPLOAD")]);
    expect(chips.map((c) => c.type)).toEqual(["UPLOAD", "ALPHA", "ZETA"]);
    expect(auditTypeLabel("ZETA")).toBe("ZETA");
  });
});

describe("filterActivityByType / resolveAuditFilter", () => {
  const items = [entry("LIFECYCLE"), entry("REMARK"), entry("LIFECYCLE")];

  it("returns everything for All, and only the chosen type otherwise", () => {
    expect(filterActivityByType(items, AUDIT_FILTER_ALL)).toBe(items);
    expect(filterActivityByType(items, "LIFECYCLE")).toHaveLength(2);
    expect(filterActivityByType(items, "REMARK")).toHaveLength(1);
  });

  it("keeps a selected type that is present", () => {
    expect(resolveAuditFilter(auditTypeChips(items), "REMARK")).toBe("REMARK");
  });

  it("falls back to All when the selected type is no longer in the feed", () => {
    expect(resolveAuditFilter(auditTypeChips(items), "CALL")).toBe(AUDIT_FILTER_ALL);
    expect(resolveAuditFilter(auditTypeChips(items), AUDIT_FILTER_ALL)).toBe(AUDIT_FILTER_ALL);
  });
});
