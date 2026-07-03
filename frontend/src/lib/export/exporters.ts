/**
 * Client-side exporters for staff dashboards: a plain CSV writer and a NAVIX-branded PDF built with
 * jsPDF + jspdf-autotable. Both take the same column model (a header + an accessor) so a page can
 * declare its columns once and offer both formats. The PDF carries the NAVIX wordmark, the document
 * title, a "Downloaded by …" provenance line, and a per-page confidential footer.
 */

import { jsPDF } from "jspdf";
import autoTable from "jspdf-autotable";
import { NAVIX_MARK_PNG } from "./brand-mark";

/** A column: a header label and an accessor that pulls a cell value from a row. */
export interface ExportColumn<Row> {
  header: string;
  value: (row: Row) => string | number | null | undefined;
}

/** Who triggered the export — stamped onto the PDF for provenance. */
export interface ExportActor {
  name: string;
  role: string;
}

/**
 * Optional statement-period metadata (e.g. for the transactions ledger): the period label and the
 * inclusive from/to dates the rows cover, plus the timezone the dates are read in. Rendered on the PDF
 * so the document states exactly which window it represents.
 */
export interface ExportMeta {
  periodLabel: string;
  /** Human "from" / "to" (e.g. "30 Jun 2026"); omitted for an all-time export. */
  from?: string;
  to?: string;
  timezone?: string;
}

// NAVIX design tokens (2026 "calendar" system) — navy #0C2540 · emerald accent
// #14A06B (token still named GOLD) · warm-cream row #F7F2E9. Keeps branded PDFs
// aligned with the re-skinned UI.
const NAVY: [number, number, number] = [12, 37, 64];
const GOLD: [number, number, number] = [20, 160, 107];
const ROW_ALT: [number, number, number] = [247, 242, 233];

function cell(v: string | number | null | undefined): string {
  return v == null ? "" : String(v);
}

function triggerDownload(content: BlobPart, filename: string, mime: string): void {
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

/** Write rows to a CSV download. Values are quoted/escaped per RFC-4180 when needed. */
export function exportCsv<Row>(fileBase: string, columns: ExportColumn<Row>[], rows: Row[]): void {
  const esc = (v: string | number | null | undefined) => {
    const s = cell(v);
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const header = columns.map((c) => esc(c.header)).join(",");
  const body = rows.map((r) => columns.map((c) => esc(c.value(r))).join(",")).join("\n");
  // BOM so Excel reads UTF-8 (₹ etc.) correctly.
  triggerDownload("﻿" + header + "\n" + body, `${fileBase}.csv`, "text/csv;charset=utf-8;");
}

/** Write rows to a NAVIX-branded PDF download (landscape A4), stamped with who downloaded it. */
export function exportPdf<Row>(opts: {
  fileBase: string;
  title: string;
  subtitle?: string;
  columns: ExportColumn<Row>[];
  rows: Row[];
  actor: ExportActor;
  /** ISO-ish timestamp string; the caller passes one so this stays deterministic/testable. */
  generatedAt: string;
  /** Optional statement-period block (period label + from/to + timezone). */
  meta?: ExportMeta;
}): void {
  const { fileBase, title, subtitle, columns, rows, actor, generatedAt, meta } = opts;
  const doc = new jsPDF({ orientation: "landscape", unit: "pt", format: "a4" });
  const pageWidth = doc.internal.pageSize.getWidth();
  const pageHeight = doc.internal.pageSize.getHeight();

  // --- header band + gold rule ---
  doc.setFillColor(NAVY[0], NAVY[1], NAVY[2]);
  doc.rect(0, 0, pageWidth, 58, "F");
  doc.setFillColor(GOLD[0], GOLD[1], GOLD[2]);
  doc.rect(0, 58, pageWidth, 3, "F");

  // emblem (transparent PNG sits on the navy band)
  try {
    doc.addImage(NAVIX_MARK_PNG, "PNG", 36, 12, 36, 36);
  } catch {
    // addImage can throw in non-DOM/test environments — the wordmark still renders.
  }

  doc.setTextColor(255, 255, 255);
  doc.setFont("helvetica", "bold");
  doc.setFontSize(20);
  doc.text("NAVIX", 82, 30);
  doc.setFont("helvetica", "normal");
  doc.setFontSize(8);
  doc.setTextColor(GOLD[0], GOLD[1], GOLD[2]);
  doc.text("FINANCE", 83, 44);

  doc.setTextColor(255, 255, 255);
  doc.setFont("helvetica", "bold");
  doc.setFontSize(14);
  doc.text(title, pageWidth - 40, 28, { align: "right" });
  if (subtitle) {
    doc.setFont("helvetica", "normal");
    doc.setFontSize(9);
    doc.setTextColor(210, 220, 230);
    doc.text(subtitle, pageWidth - 40, 44, { align: "right" });
  }

  // --- provenance line ---
  doc.setFont("helvetica", "normal");
  doc.setFontSize(8);
  doc.setTextColor(90, 90, 90);
  doc.text(
    `Downloaded by ${actor.name} · ${actor.role}  ·  Generated ${generatedAt}  ·  ${rows.length} row${rows.length === 1 ? "" : "s"}`,
    40,
    78,
  );

  // --- statement-period block (optional) ---
  let tableStart = 90;
  if (meta) {
    const period = meta.from && meta.to
      ? `Statement period: ${meta.periodLabel}  ·  ${meta.from} – ${meta.to}`
      : `Statement period: ${meta.periodLabel}`;
    const tz = meta.timezone ? `  ·  Timezone: ${meta.timezone}` : "";
    doc.setTextColor(NAVY[0], NAVY[1], NAVY[2]);
    doc.setFont("helvetica", "bold");
    doc.text(`${period}${tz}  ·  ${rows.length} transaction${rows.length === 1 ? "" : "s"}`, 40, 90);
    tableStart = 102;
  }

  // --- table ---
  autoTable(doc, {
    startY: tableStart,
    head: [columns.map((c) => c.header)],
    body: rows.map((r) => columns.map((c) => cell(c.value(r)))),
    styles: { fontSize: 8, cellPadding: 4, overflow: "linebreak" },
    headStyles: { fillColor: NAVY, textColor: 255, fontStyle: "bold" },
    alternateRowStyles: { fillColor: ROW_ALT },
    margin: { left: 40, right: 40 },
  });

  // --- per-page confidential footer ---
  const pageCount = doc.getNumberOfPages();
  for (let i = 1; i <= pageCount; i++) {
    doc.setPage(i);
    doc.setFont("helvetica", "normal");
    doc.setFontSize(7.5);
    doc.setTextColor(130, 130, 130);
    doc.text("NAVIX Finance · Confidential", 40, pageHeight - 18);
    doc.text(`Page ${i} of ${pageCount}`, pageWidth - 40, pageHeight - 18, { align: "right" });
  }

  doc.save(`${fileBase}.pdf`);
}
