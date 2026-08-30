import type { ImportIssue, ImportRow } from "@/lib/api/applications";

/**
 * Compact RFC-4180 CSV parser: quoted fields, `""` escapes, CRLF/LF line endings, a leading BOM
 * stripped, and a wholly-blank trailing line ignored. Returns raw string cells — no header
 * handling, no type coercion; see {@link mapLeadCsv} for that.
 */
export function parseCsv(text: string): string[][] {
  const input = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;

  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let inQuotes = false;
  let i = 0;
  const n = input.length;

  const pushField = () => {
    row.push(field);
    field = "";
  };
  const pushRow = () => {
    pushField();
    rows.push(row);
    row = [];
  };

  while (i < n) {
    const ch = input[i];
    if (inQuotes) {
      if (ch === '"') {
        if (input[i + 1] === '"') {
          field += '"';
          i += 2;
          continue;
        }
        inQuotes = false;
        i += 1;
        continue;
      }
      field += ch;
      i += 1;
      continue;
    }
    if (ch === '"') {
      inQuotes = true;
      i += 1;
      continue;
    }
    if (ch === ",") {
      pushField();
      i += 1;
      continue;
    }
    if (ch === "\r") {
      if (input[i + 1] === "\n") i += 1;
      pushRow();
      i += 1;
      continue;
    }
    if (ch === "\n") {
      pushRow();
      i += 1;
      continue;
    }
    field += ch;
    i += 1;
  }
  if (field.length > 0 || row.length > 0) {
    pushRow();
  }

  // A trailing wholly-blank line parses as a single "" cell — drop it rather than importing it
  // as a phantom last row.
  while (rows.length > 0 && rows[rows.length - 1].length === 1 && rows[rows.length - 1][0] === "") {
    rows.pop();
  }

  return rows;
}

/**
 * The mobile rule — ONE definition, implemented identically here and in the backend's
 * `LeadImportService`. Deliberately NOT `normalizeMobile` from `@/lib/utils`: that helper
 * truncates with `slice(0, 10)` and would silently accept an 11-digit number.
 */
function normalizeMobileForImport(raw: string): string {
  let digits = (raw ?? "").replace(/\D/g, "");
  if (digits.length === 12 && digits.startsWith("91")) {
    digits = digits.slice(2);
  }
  digits = digits.replace(/^0+/, "");
  return digits;
}

const MOBILE_RE = /^[6-9][0-9]{9}$/;

const HEADER_ALIASES: Record<"name" | "mobile" | "pan" | "pincode" | "email", string[]> = {
  name: ["name"],
  mobile: ["contact number", "contact", "mobile", "phone", "mobile number"],
  pan: ["pan card", "pan", "pan number"],
  pincode: ["pincode", "pin code", "pin", "postal code"],
  email: ["emailid", "email id", "email", "e-mail"],
};

/** Human-readable expected header, shown in the format-error dialog. */
export const EXPECTED_HEADER = "name, contact number, pan card, pincode, emailid";

const MAX_DATA_ROWS = 2000;
const MAX_ISSUES = 50;

function normalizeHeaderCell(h: string): string {
  return h.trim().toLowerCase().replace(/_/g, " ").replace(/\s+/g, " ");
}

/**
 * Maps parsed CSV rows to `ImportRow`s per the admin lead-import header contract, running the
 * same required/optional field rules the backend re-validates. A client-side pre-check only — an
 * obviously wrong file never leaves the browser — the server is still authoritative.
 */
export function mapLeadCsv(rows: string[][]): { rows: ImportRow[]; issues: ImportIssue[] } {
  if (rows.length === 0) {
    return { rows: [], issues: [{ row: 0, field: "file", message: "The file is empty." }] };
  }

  const header = rows[0].map(normalizeHeaderCell);
  const colIndex: Partial<Record<keyof typeof HEADER_ALIASES, number>> = {};
  (Object.keys(HEADER_ALIASES) as (keyof typeof HEADER_ALIASES)[]).forEach((field) => {
    const idx = header.findIndex((h) => HEADER_ALIASES[field].includes(h));
    if (idx >= 0) colIndex[field] = idx;
  });

  const structuralIssues: ImportIssue[] = [];
  if (colIndex.name === undefined) {
    structuralIssues.push({ row: 0, field: "name", message: 'Missing required column "name".' });
  }
  if (colIndex.mobile === undefined) {
    structuralIssues.push({
      row: 0,
      field: "contact number",
      message: 'Missing required column "contact number".',
    });
  }
  if (structuralIssues.length > 0) {
    return { rows: [], issues: structuralIssues };
  }

  const dataRows = rows.slice(1);
  if (dataRows.length === 0) {
    return { rows: [], issues: [{ row: 0, field: "file", message: "No data rows found." }] };
  }
  if (dataRows.length > MAX_DATA_ROWS) {
    return {
      rows: [],
      issues: [
        { row: 0, field: "file", message: `Too many rows — the limit is ${MAX_DATA_ROWS}.` },
      ],
    };
  }

  const headerLen = rows[0].length;
  const nameIdx = colIndex.name as number;
  const mobileIdx = colIndex.mobile as number;
  const out: ImportRow[] = [];
  const issues: ImportIssue[] = [];

  for (let i = 0; i < dataRows.length; i++) {
    const rowNum = i + 1;
    const cols = dataRows[i];
    if (cols.length !== headerLen) {
      issues.push({
        row: rowNum,
        field: "row",
        message: `Expected ${headerLen} columns, found ${cols.length}.`,
      });
      continue;
    }

    const name = (cols[nameIdx] ?? "").trim();
    const mobile = normalizeMobileForImport(cols[mobileIdx] ?? "");
    const pan = colIndex.pan !== undefined ? (cols[colIndex.pan] ?? "").trim() : "";
    const pincode = colIndex.pincode !== undefined ? (cols[colIndex.pincode] ?? "").trim() : "";
    const email = colIndex.email !== undefined ? (cols[colIndex.email] ?? "").trim() : "";

    if (!name) {
      issues.push({ row: rowNum, field: "name", message: "is required" });
    } else if (name.length > 160) {
      issues.push({ row: rowNum, field: "name", message: "must be at most 160 characters" });
    }
    if (!MOBILE_RE.test(mobile)) {
      issues.push({
        row: rowNum,
        field: "mobile",
        message: "must be a valid 10-digit mobile number",
      });
    }

    out.push({
      name,
      mobile,
      pan: pan ? pan.toUpperCase() : null,
      pincode: pincode || null,
      email: email || null,
    });
  }

  if (issues.length > 0) {
    return { rows: [], issues: issues.slice(0, MAX_ISSUES) };
  }

  return { rows: out, issues: [] };
}
