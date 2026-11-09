/**
 * Customer data export (ADMIN) — formatting and download logic, kept apart from the screen so the
 * rules that matter are unit-testable: which PDF layout, how a cell is written to CSV so Excel keeps
 * an Aadhaar number intact, how pages are fetched (one at a time, 200 per request, cancellable).
 */

import {
  customerExportApi,
  type ExportCell,
  type ExportColumn,
  type ExportField,
  type ExportFieldType,
  type ExportGrain,
  type ExportRows,
} from "@/lib/api/customer-export";
import {
  csvCell,
  csvTextNumberCell,
  exportCsvText,
  exportCustomerCardsPdf,
  exportCustomerTablePdf,
  type CustomerPdfCard,
  type ExportActor,
} from "./exporters";

/** Rows per request; the server refuses more. */
export const PAGE_SIZE = 200;
/** Most rows a CSV holds, and most customers a PDF holds (the server caps the ids the same way). */
export const CSV_MAX_ROWS = 50_000;
export const PDF_MAX_ROWS = 500;
/** Up to this many fields the PDF is a table; above it, one card per customer. */
export const PDF_TABLE_MAX_FIELDS = 12;
export const PREVIEW_ROWS = 20;

export function pdfLayout(fieldCount: number): "table" | "cards" {
  return fieldCount <= PDF_TABLE_MAX_FIELDS ? "table" : "cards";
}

const MONEY = new Intl.NumberFormat("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "2026-11-09" -> "09 Nov 2026" without going through a Date (no time-zone shift). */
function fmtDate(iso: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  return m ? `${m[3]} ${MONTHS[Number(m[2]) - 1] ?? m[2]} ${m[1]}` : iso;
}

/** One cell for the screen and the PDF: money from paise, real dates, Yes/No. Empty stays empty. */
export function displayCell(type: ExportFieldType, v: ExportCell): string {
  if (v == null || v === "") return "";
  switch (type) {
    case "MONEY":
      return `₹${MONEY.format(Number(v) / 100)}`;
    case "NUMBER":
      return String(v);
    case "DATE":
      return fmtDate(String(v));
    case "DATETIME": {
      const [d, t] = String(v).split(" ");
      return t ? `${fmtDate(d)}, ${t}` : fmtDate(d);
    }
    case "BOOL":
      return v === true || v === "true" ? "Yes" : "No";
    default:
      return String(v);
  }
}

/**
 * One cell for the CSV. Money is plain rupees ("12500.00", never grouped, so a spreadsheet sums it);
 * dates stay ISO so they sort; a TEXTNUM that is all digits is written so Excel cannot turn it into
 * 1.23E+11 or drop leading zeros. Everything else goes through `csvCell`'s formula guard.
 */
export function csvDataCell(type: ExportFieldType, v: ExportCell): string {
  if (v == null || v === "") return "";
  switch (type) {
    case "MONEY":
      return csvCell((Number(v) / 100).toFixed(2));
    case "BOOL":
      return v === true || v === "true" ? "Yes" : "No";
    case "TEXTNUM":
      return csvTextNumberCell(String(v));
    default:
      return csvCell(typeof v === "string" || typeof v === "number" ? v : String(v));
  }
}

export function csvHeader(columns: ExportColumn[]): string {
  return columns.map((c) => csvCell(c.type === "MONEY" ? `${c.label} (Rs.)` : c.label)).join(",");
}

export function csvLine(columns: ExportColumn[], row: ExportCell[]): string {
  return columns.map((c, i) => csvDataCell(c.type, row[i] ?? null)).join(",");
}

export type FetchOptions = {
  grain: ExportGrain;
  ids: number[];
  fields: string[];
  includeIdNumbers: boolean;
  signal?: AbortSignal;
  /** Called after every page with how many ids are done. */
  onProgress?: (done: number, total: number) => void;
};

/**
 * Pull the rows for `ids`, one request at a time (a micro database and a single small server: never
 * in parallel), PAGE_SIZE per request. Each page is handed to `onPage` as it arrives, so a CSV can
 * be assembled without keeping the raw rows. A cancel (abort) stops after the page in flight.
 */
export async function fetchRowPages(opts: FetchOptions, onPage: (page: ExportRows) => void): Promise<void> {
  const { ids, signal } = opts;
  for (let i = 0; i < ids.length; i += PAGE_SIZE) {
    if (signal?.aborted) throw new DOMException("Cancelled", "AbortError");
    const page = await customerExportApi.rows(
      { grain: opts.grain, ids: ids.slice(i, i + PAGE_SIZE), fields: opts.fields, includeIdNumbers: opts.includeIdNumbers },
      signal,
    );
    onPage(page);
    opts.onProgress?.(Math.min(i + PAGE_SIZE, ids.length), ids.length);
  }
}

export type ExportContext = {
  /** Short line in the PDF header band: "Overdue loans". */
  subtitle: string;
  /** Wrapped under the band: "Overdue loans, 1–7 days past due. Came between 1 Nov 2026 and 9 Nov 2026." */
  summary: string;
  actor: ExportActor;
  /** The file name without its extension. */
  fileBase: string;
};

const stamp = () => new Date().toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Kolkata" });

/** `dhanboost-customer-export-2026-11-09`. */
export function exportFileBase(now = new Date()): string {
  const ist = new Date(now.getTime() + 5.5 * 3_600_000).toISOString().slice(0, 10);
  return `dhanboost-customer-export-${ist}`;
}

/** Fetch every page and write the CSV. */
export async function downloadCsv(opts: FetchOptions, ctx: ExportContext): Promise<number> {
  const lines: string[] = [];
  let header: string | null = null;
  let count = 0;
  await fetchRowPages(opts, (page) => {
    if (header == null) {
      header = csvHeader(page.columns);
      lines.push(header);
    }
    for (const row of page.rows) {
      lines.push(csvLine(page.columns, row));
      count++;
    }
  });
  exportCsvText(ctx.fileBase, lines.join("\n"));
  return count;
}

/** The card title: "#1042  Name" from whichever identity columns were chosen. */
function cardTitle(columns: ExportColumn[], row: ExportCell[], n: number): { title: string; badge: string } {
  const at = (key: string) => {
    const i = columns.findIndex((c) => c.key === key);
    return i < 0 ? "" : String(row[i] ?? "");
  };
  const id = at("customer_id") || at("application_id") || at("loan_id");
  const name = at("full_name");
  const title = [id && `#${id}`, name].filter(Boolean).join("  ") || `Row ${n}`;
  return { title, badge: at("application_status") || at("loan_status") };
}

/** Cards: each customer's non-empty fields, grouped under the field group they belong to. */
export function buildCards(columns: ExportColumn[], rows: ExportCell[][], fields: ExportField[]): CustomerPdfCard[] {
  const groupOf = new Map(fields.map((f) => [f.key, f.group]));
  const groupOrder = [...new Set(fields.map((f) => f.group))];
  return rows.map((row, n) => {
    const byGroup = new Map<string, [string, string][]>();
    columns.forEach((c, i) => {
      const text = displayCell(c.type, row[i] ?? null);
      if (text === "") return; // empty values are left out of the cards
      const g = groupOf.get(c.key) ?? "Other";
      byGroup.set(g, [...(byGroup.get(g) ?? []), [c.label, text]]);
    });
    const { title, badge } = cardTitle(columns, row, n + 1);
    return {
      title,
      badge,
      sections: groupOrder.filter((g) => byGroup.has(g)).map((g) => ({ title: g, rows: byGroup.get(g)! })),
    };
  });
}

/** Fetch up to PDF_MAX_ROWS rows and write the PDF: a table for 12 fields or fewer, cards above that. */
export async function downloadPdf(opts: FetchOptions, ctx: ExportContext, fields: ExportField[]): Promise<number> {
  const capped = { ...opts, ids: opts.ids.slice(0, PDF_MAX_ROWS) };
  const cols: ExportColumn[][] = [];
  const rows: ExportCell[][] = [];
  await fetchRowPages(capped, (page) => {
    if (cols.length === 0) cols.push(page.columns);
    rows.push(...page.rows);
  });
  const columns = cols[0] ?? [];
  const common = {
    fileBase: ctx.fileBase,
    title: "DhanBoost customer data export",
    subtitle: ctx.subtitle,
    summary: ctx.summary,
    actor: ctx.actor,
    generatedAt: stamp(),
  };
  if (pdfLayout(opts.fields.length) === "table") {
    exportCustomerTablePdf({
      ...common,
      header: columns.map((c) => c.label),
      rows: rows.map((r) => columns.map((c, i) => displayCell(c.type, r[i] ?? null))),
    });
  } else {
    exportCustomerCardsPdf({ ...common, cards: buildCards(columns, rows, fields) });
  }
  return rows.length;
}
