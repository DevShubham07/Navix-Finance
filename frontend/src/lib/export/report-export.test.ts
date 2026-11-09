import { describe, expect, it } from "vitest";
import type { ReportData } from "@/lib/api/applications";
import {
  buildReportCsv,
  formatReport,
  formatReportCell,
  periodLine,
  reportFileBase,
  reportTotals,
} from "@/lib/export/report-export";
import { REPORT_PRESETS, fyStartYear, reportRange } from "@/lib/reports/periods";

const report: ReportData = {
  key: "customers-overpaid",
  category: "Customers",
  title: "Customers who overpaid",
  description: "Loans where verified repayments exceed what was owed.",
  dateBasis: "Disbursal date",
  from: "2026-10-01",
  to: "2026-10-23",
  generatedAt: "2026-10-23T10:00:00Z",
  columns: [
    { key: "loanId", label: "Loan ID", type: "NUMBER" },
    { key: "name", label: "Customer", type: "TEXT" },
    { key: "disbursedOn", label: "Disbursed on", type: "DATE" },
    { key: "paid", label: "Paid, verified (Rs.)", type: "MONEY" },
    { key: "excess", label: "Excess paid (Rs.)", type: "MONEY" },
    { key: "partial", label: "Part payment", type: "BOOL" },
  ],
  rows: [
    [9001, "Asha Rao", "2026-10-01", 1_300_000, 10_000, false],
    // Borrower-typed text a spreadsheet would run as a formula.
    [9004, "=HYPERLINK(\"x\")", "2026-10-05", 250_050, 50, true],
  ],
  summary: [
    { label: "Loans", value: 2, type: "NUMBER" },
    { label: "Excess to refund", value: 10_050, type: "MONEY" },
  ],
  notes: ["Excess is owed back to the customer."],
  truncated: false,
  totals: true,
};

const actor = { name: "Meera Krishnan", role: "Admin" };

describe("report export", () => {
  it("formats cells per column type", () => {
    expect(formatReportCell("MONEY", 1_234_567)).toBe("12,345.67");
    expect(formatReportCell("MONEY", 1_234_567, "screen")).toBe("₹12,345.67");
    expect(formatReportCell("NUMBER", 12345)).toBe("12,345");
    expect(formatReportCell("DATE", "2026-10-01")).toBe("01 Oct 2026");
    expect(formatReportCell("BOOL", true)).toBe("Yes");
    expect(formatReportCell("BOOL", false)).toBe("No");
    expect(formatReportCell("TEXT", null)).toBe("");
  });

  it("totals money columns only", () => {
    expect(reportTotals(report)).toEqual(["Total", null, null, 1_550_050, 10_050, null]);
    expect(reportTotals({ ...report, rows: [] })).toBeNull();
    // A report whose money column mixes unlike figures (the platform summary) gets no totals row.
    expect(reportTotals({ ...report, totals: false })).toBeNull();
  });

  it("describes the period and names the file after it", () => {
    expect(periodLine(report)).toBe("01 Oct 2026 – 23 Oct 2026 (by disbursal date)");
    expect(periodLine({ ...report, from: null, to: null })).toBe("All time (by disbursal date)");
    expect(periodLine({ ...report, dateBasis: null })).toMatch(/Live snapshot/);
    expect(reportFileBase(report)).toBe("dhanboost-report-customers-overpaid-2026-10-01-to-2026-10-23");
    expect(reportFileBase({ ...report, dateBasis: null })).toBe("dhanboost-report-customers-overpaid-snapshot");
  });

  it("writes the CSV with the same rows, figures and notes the PDF gets", () => {
    const csv = buildReportCsv(report, actor, "23 Oct 2026, 3:30 pm").split("\n");
    expect(csv[0]).toBe("DhanBoost report,Customers who overpaid");
    expect(csv).toContain("Downloaded by,Meera Krishnan (Admin)");
    expect(csv).toContain("Excess to refund (Rs.),100.50");
    expect(csv).toContain("Loan ID,Customer,Disbursed on,\"Paid, verified (Rs.)\",Excess paid (Rs.),Part payment");
    // Money as plain numbers a spreadsheet can sum; dates as the PDF shows them.
    expect(csv).toContain("9001,Asha Rao,01 Oct 2026,13000.00,100.00,No");
    // The formula is neutralised.
    expect(csv.some((l) => l.startsWith("9004,\"'=HYPERLINK"))).toBe(true);
    expect(csv).toContain("Total,,,15500.50,100.50,");
    expect(csv[csv.length - 1]).toBe("Excess is owed back to the customer.");

    const pdf = formatReport(report);
    expect(pdf.header).toHaveLength(report.columns.length);
    expect(pdf.rows).toHaveLength(report.rows.length);
    expect(pdf.rows[0]).toEqual(["9,001", "Asha Rao", "01 Oct 2026", "13,000.00", "100.00", "No"]);
    expect(pdf.foot).toEqual(["Total", "", "", "15,500.50", "100.50", ""]);
    expect(pdf.facts).toEqual([
      { label: "Loans", value: "2" },
      { label: "Excess to refund", value: "100.50" },
    ]);
  });
});

describe("report periods", () => {
  const now = new Date(2026, 9, 23); // 23 Oct 2026, local

  it("knows the Indian financial year", () => {
    expect(fyStartYear(new Date(2026, 2, 31))).toBe(2025);
    expect(fyStartYear(new Date(2026, 3, 1))).toBe(2026);
  });

  it("resolves the finance presets", () => {
    expect(reportRange("this-quarter", {}, now)).toEqual({ from: "2026-10-01", to: "2026-10-23" });
    expect(reportRange("this-fy", {}, now)).toEqual({ from: "2026-04-01", to: "2026-10-23" });
    expect(reportRange("last-fy", {}, now)).toEqual({ from: "2025-04-01", to: "2026-03-31" });
    expect(reportRange("last-quarter", {}, now)).toEqual({ from: "2026-07-01", to: "2026-09-30" });
    expect(reportRange("last-quarter", {}, new Date(2026, 1, 10))).toEqual({ from: "2025-10-01", to: "2025-12-31" });
    expect(reportRange("last-7", {}, now)).toEqual({ from: "2026-10-17", to: "2026-10-23" });
    expect(reportRange("all", {}, now)).toEqual({});
    expect(reportRange("custom", { from: "2026-01-01" }, now)).toEqual({ from: "2026-01-01" });
    expect(new Set(REPORT_PRESETS.map((p) => p.key)).size).toBe(REPORT_PRESETS.length);
  });
});
