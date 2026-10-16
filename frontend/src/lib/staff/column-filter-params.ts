/**
 * The wire format for a column-filter set — one encoding shared by the URL (so a filtered register
 * is a link you can send someone), localStorage (so your last view comes back) and the server query
 * string (so a server-paged register filters in SQL). One format means one parser here and one in
 * Java, rather than three that drift.
 *
 * One param per filtered column, `f.`-prefixed, the value always tag-prefixed:
 *
 *   f.status=in:SANCTIONED,ACTIVE      f.officer=in:__NULL__
 *   f.amount=num:100000..500000        f.dpd=num:30..        f.salary=num:..2500000
 *   f.dueDate=date:2026-01-01..2026-02-01                    f.name=text:contains:ravi
 *
 * Tagged rather than shape-sniffed: a stored value can legitimately contain `..` and a borrower's
 * name can begin `eq:`, so an untagged parser has to guess — and a parser guessing at borrower-typed
 * text is the kind of thing that pages you at 3am. The tag costs four characters and turns parsing
 * into a switch. Untagged forms are still *accepted* (read-lenient, write-canonical) so older links
 * keep working.
 */

import {
  normaliseFilter,
  type ColumnFilter,
  type ColumnFilterSet,
  type TextOp,
} from "@/lib/staff/column-filter-match";

export const FILTER_PREFIX = "f.";

const TEXT_OPS: readonly TextOp[] = ["contains", "starts", "eq"];

/**
 * Escape a single operand. Only two characters are reserved, and the order matters: `%` first, or
 * unescaping would turn a literal `%2C` the user typed into a comma.
 */
function esc(v: string): string {
  return v.replace(/%/g, "%25").replace(/,/g, "%2C");
}

function unesc(v: string): string {
  return v.replace(/%2C/gi, ",").replace(/%25/gi, "%");
}

/** A number, or `undefined` for a blank/garbage bound (lenient: a bad bound is dropped, not fatal). */
function num(v: string): number | undefined {
  if (v.trim() === "") return undefined;
  const n = Number(v);
  return Number.isFinite(n) ? n : undefined;
}

/**
 * A real `yyyy-mm-dd`, or `undefined`. Anything else is dropped rather than passed on.
 *
 * Shape alone is not enough: `2026-13-99` matches the pattern and is not a date, and a bound like
 * that reaching a SQL `where` is a 500 rather than an empty table.
 */
function day(v: string): string | undefined {
  const t = v.trim();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(t)) return undefined;
  const [y, m, d] = t.split("-").map(Number);
  if (m < 1 || m > 12 || d < 1) return undefined;
  // Round-tripping through Date catches month lengths and leap years without a table of them.
  const probe = new Date(Date.UTC(y, m - 1, d));
  return probe.getUTCFullYear() === y && probe.getUTCMonth() === m - 1 && probe.getUTCDate() === d
    ? t
    : undefined;
}

export function serialiseFilter(f: ColumnFilter): string {
  switch (f.type) {
    case "in":
      return `in:${f.values.map(esc).join(",")}`;
    case "num":
      return `num:${f.min ?? ""}..${f.max ?? ""}`;
    case "date":
      return `date:${f.from ?? ""}..${f.to ?? ""}`;
    case "text":
      return `text:${f.op}:${f.q}`;
  }
}

export function parseFilterValue(raw: string): ColumnFilter | undefined {
  const value = raw.trim();
  if (!value) return undefined;

  const colon = value.indexOf(":");
  const tag = colon === -1 ? "" : value.slice(0, colon);
  const body = colon === -1 ? value : value.slice(colon + 1);

  switch (tag) {
    case "in":
      return normaliseFilter({ type: "in", values: body.split(",").filter(Boolean).map(unesc) });
    case "num": {
      const [lo = "", hi = ""] = body.split("..");
      return normaliseFilter({ type: "num", min: num(lo), max: num(hi) });
    }
    case "date": {
      const [from = "", to = ""] = body.split("..");
      return normaliseFilter({ type: "date", from: day(from), to: day(to) });
    }
    case "text": {
      const opEnd = body.indexOf(":");
      const op = opEnd === -1 ? "" : body.slice(0, opEnd);
      // Split on the FIRST colon only, so `text:contains:a:b` searches for "a:b".
      const q = opEnd === -1 ? body : body.slice(opEnd + 1);
      if (!TEXT_OPS.includes(op as TextOp)) return undefined;
      return normaliseFilter({ type: "text", op: op as TextOp, q });
    }
    default:
      break;
  }

  // Untagged legacy / hand-written forms.
  if (value.includes("..")) {
    const [lo = "", hi = ""] = value.split("..");
    const asDays = { from: day(lo), to: day(hi) };
    if (asDays.from || asDays.to) return normaliseFilter({ type: "date", ...asDays });
    return normaliseFilter({ type: "num", min: num(lo), max: num(hi) });
  }
  return normaliseFilter({ type: "in", values: value.split(",").filter(Boolean).map(unesc) });
}

/**
 * The filter set as query params, keys **sorted** — two equal sets must serialise byte-identically
 * or React Query caches them twice and the two halves of one view drift apart.
 */
export function serialiseFilters(filters: ColumnFilterSet): Record<string, string> {
  const out: Record<string, string> = {};
  for (const key of Object.keys(filters).sort()) {
    const f = normaliseFilter(filters[key]);
    if (f) out[FILTER_PREFIX + key] = serialiseFilter(f);
  }
  return out;
}

/**
 * Read every `f.*` param. Deliberately lenient: an unknown column, a malformed range or a bad text
 * operator is dropped silently rather than thrown. A link shared last month must not blank the page
 * because the register gained or lost a column since.
 *
 * `allowed`, when given, is the register's own column list — anything else is ignored, which keeps a
 * hand-edited URL from reaching the server with a column it has no business filtering on.
 */
export function parseFilters(
  params: URLSearchParams,
  allowed?: readonly string[],
): ColumnFilterSet {
  const allow = allowed ? new Set(allowed) : null;
  const out: ColumnFilterSet = {};
  for (const [name, value] of params.entries()) {
    if (!name.startsWith(FILTER_PREFIX)) continue;
    const key = name.slice(FILTER_PREFIX.length);
    if (!key || (allow && !allow.has(key))) continue;
    const parsed = parseFilterValue(value);
    if (parsed) out[key] = parsed;
  }
  return out;
}

/** How many columns a query string filters on — for the "Clear filters (n)" affordance. */
export function filterParamCount(params: URLSearchParams): number {
  return Object.keys(parseFilters(params)).length;
}

/**
 * Write the filter set into an existing query string, dropping every `f.*` that is no longer active.
 * Returns the new query string so the caller can compare it with the current one and skip a no-op
 * `router.replace` — the convention `withPerformanceSort` already set.
 */
export function writeFilters(current: string, filters: ColumnFilterSet): string {
  const params = new URLSearchParams(current);
  for (const name of [...params.keys()]) {
    if (name.startsWith(FILTER_PREFIX)) params.delete(name);
  }
  for (const [name, value] of Object.entries(serialiseFilters(filters))) {
    params.set(name, value);
  }
  return params.toString();
}

/**
 * One line naming what is filtered, for the PDF subtitle.
 *
 * An exported file that does not say what it was filtered by is a compliance problem, not a cosmetic
 * one: it leaves the reader to assume it is the whole book. `labels` maps a column key to its header
 * and, optionally, a stored value to the word on screen.
 */
export function describeFilters(
  filters: ColumnFilterSet,
  labels?: {
    column?: (key: string) => string;
    value?: (key: string, raw: string) => string;
  },
): string {
  const parts: string[] = [];
  for (const key of Object.keys(filters).sort()) {
    const f = normaliseFilter(filters[key]);
    if (!f) continue;
    parts.push(describeFilter(labels?.column?.(key) ?? key, f, (v) => labels?.value?.(key, v) ?? v));
  }
  return parts.length ? `Filtered: ${parts.join(" · ")}` : "";
}

/**
 * One column's predicate in words — `Status in Active, Closed`, `Amount ≥ 1000`. Shared by the export
 * subtitle and the filter-bar chips so the two can never phrase the same filter differently.
 * `FilterColumn.num` is display units already, so nothing here formats a number.
 */
export function describeFilter(name: string, f: ColumnFilter, label: (raw: string) => string = (v) => v): string {
  switch (f.type) {
    case "in":
      return `${name} in ${f.values.map(label).join(", ")}`;
    case "num":
      return f.min != null && f.max != null
        ? `${name} ${f.min}–${f.max}`
        : f.min != null
          ? `${name} ≥ ${f.min}`
          : `${name} ≤ ${f.max}`;
    case "date":
      return f.from && f.to
        ? `${name} ${f.from} to ${f.to}`
        : f.from
          ? `${name} from ${f.from}`
          : `${name} until ${f.to}`;
    case "text":
      return `${name} ${f.op === "eq" ? "is" : f.op} “${f.q}”`;
  }
}
