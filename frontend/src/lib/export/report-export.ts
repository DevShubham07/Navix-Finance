/**
 * Admin reports centre → downloads. A generated report (`ReportData`) is one typed table plus a few
 * headline figures; this module formats it ONCE into strings and hands the same strings to both the
 * CSV and the PDF writer, so the two files always carry the same information — the PDF just lays it
 * out (header band, figure tiles, totals foot) where the CSV writes it as plain rows.
 *
 * Pure builders are exported for the unit test; `downloadReport` is the one side-effecting entry.
 */

import type { ReportCell, ReportColumnType, ReportData } from "@/lib/api/applications";
import { formatDate, formatDateTime } from "@/lib/utils";
import { csvCell, exportCsvText, exportReportPdf, type ExportActor } from "./exporters";

const INR = new Intl.NumberFormat("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const COUNT = new Intl.NumberFormat("en-IN");

/**
 * One cell as text. `file` mode is for the downloads: money as "12,500.00" with no symbol (the
 * currency lives in the "(Rs.)" header, and jsPDF's font has no ₹), so a spreadsheet and the PDF's
 * right-alignment both read it as a number. `screen` mode adds the ₹.
 */
export function formatReportCell(type: ReportColumnType, v: ReportCell, mode: "file" | "screen" = "file"): string {
  if (v == null || v === "") return "";
  switch (type) {
    case "MONEY": {
      const s = INR.format(Number(v) / 100);
      return mode === "screen" ? `₹${s}` : s;
    }
    case "NUMBER":
      return typeof v === "number" ? (Number.isInteger(v) ? COUNT.format(v) : String(v)) : String(v);
    case "DATE":
      return formatDate(String(v));
    case "DATETIME":
      return formatDateTime(String(v));
    case "BOOL":
      return v === true || v === "true" ? "Yes" : "No";
    default:
      return String(v);
  }
}

/** CSV wants plain numbers, not "1,234": a grouped figure is text to a spreadsheet. */
function csvValue(type: ReportColumnType, v: ReportCell): string {
  if (v == null || v === "") return "";
  if (type === "MONEY") return (Number(v) / 100).toFixed(2);
  if (type === "NUMBER") return String(v);
  return formatReportCell(type, v);
}

/** "01 Oct 2026 – 23 Oct 2026 (by disbursal date)", "All time", or the snapshot statement. */
export function periodLine(r: Pick<ReportData, "from" | "to" | "dateBasis">): string {
  if (!r.dateBasis) return "Live snapshot as of the time generated (not period-filtered)";
  if (!r.from || !r.to) return `All time (by ${r.dateBasis.toLowerCase()})`;
  return `${formatDate(r.from)} – ${formatDate(r.to)} (by ${r.dateBasis.toLowerCase()})`;
}

/**
 * The totals row: every MONEY column summed, the first column reading "Total"; null when the report
 * has no money column, or says its money column mixes unlike figures. IDs and ratios are never summed.
 */
export function reportTotals(r: Pick<ReportData, "columns" | "rows" | "totals">): ReportCell[] | null {
  if (r.totals === false || r.rows.length === 0 || !r.columns.some((c) => c.type === "MONEY")) return null;
  return r.columns.map((c, i) => {
    if (i === 0) return "Total";
    if (c.type !== "MONEY") return null;
    return r.rows.reduce<number>((s, row) => s + (typeof row[i] === "number" ? (row[i] as number) : 0), 0);
  });
}

/** `dhanboost-report-loan-book-2026-10-01-to-2026-10-23`. */
export function reportFileBase(r: Pick<ReportData, "key" | "from" | "to" | "dateBasis">): string {
  const span = r.dateBasis && r.from && r.to ? `${r.from}-to-${r.to}` : r.dateBasis ? "all-time" : "snapshot";
  return `dhanboost-report-${r.key}-${span}`;
}

/** Every string the downloads carry, formatted once. */
export function formatReport(r: ReportData) {
  const totals = reportTotals(r);
  return {
    header: r.columns.map((c) => c.label),
    rows: r.rows.map((row) => r.columns.map((c, i) => formatReportCell(c.type, row[i]))),
    foot: totals ? r.columns.map((c, i) => (i === 0 ? "Total" : formatReportCell(c.type, totals[i]))) : undefined,
    facts: r.summary.map((f) => ({ label: f.label, value: formatReportCell(f.type, f.value) || "0" })),
  };
}

/**
 * The CSV text: a short preamble (what, when, who, the headline figures) then the full table with
 * its totals row, then the notes — the same content as the PDF, as rows a spreadsheet can open.
 */
export function buildReportCsv(r: ReportData, actor: ExportActor, generatedAt: string): string {
  const line = (...cells: (string | number | null | undefined)[]) => cells.map(csvCell).join(",");
  const out: string[] = [
    line("DhanBoost report", r.title),
    line("Category", r.category),
    line("About", r.description),
    line("Period", periodLine(r)),
    line("Generated", generatedAt),
    line("Downloaded by", `${actor.name} (${actor.role})`),
    line("Rows", r.rows.length),
  ];
  if (r.summary.length > 0) {
    out.push("", line("Key figures"));
    for (const f of r.summary) {
      out.push(line(f.type === "MONEY" ? `${f.label} (Rs.)` : f.label, csvValue(f.type, f.value)));
    }
  }
  out.push("", line(...r.columns.map((c) => c.label)));
  for (const row of r.rows) out.push(line(...r.columns.map((c, i) => csvValue(c.type, row[i]))));
  const totals = reportTotals(r);
  if (totals) out.push(line(...r.columns.map((c, i) => (i === 0 ? "Total" : csvValue(c.type, totals[i])))));
  if (r.notes.length > 0) {
    out.push("", line("Notes"));
    for (const n of r.notes) out.push(line(n));
  }
  return out.join("\n");
}

/** Download one generated report as CSV or as the branded PDF. */
export function downloadReport(r: ReportData, format: "csv" | "pdf", actor: ExportActor): void {
  const generatedAt = new Date().toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Kolkata" });
  const fileBase = reportFileBase(r);
  if (format === "csv") {
    exportCsvText(fileBase, buildReportCsv(r, actor, generatedAt));
    return;
  }
  const f = formatReport(r);
  exportReportPdf({
    fileBase,
    title: r.title,
    subtitle: r.category,
    description: r.description,
    periodLine: `Period: ${periodLine(r)}`,
    facts: f.facts,
    header: f.header,
    rows: f.rows,
    foot: f.foot,
    notes: r.notes,
    actor,
    generatedAt,
  });
}
