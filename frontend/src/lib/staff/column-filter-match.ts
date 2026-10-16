/**
 * The column-filter model and its predicates — pure, React-free, so the panel, the client-side
 * register adapter and the tests all share one definition of what "matches" means.
 *
 * Four kinds, because a register column is one of four things: a closed set of values you tick
 * (status, role, bucket, officer), a quantity you bound (amount, salary, outstanding, DPD), a day
 * you window (due, disbursed, created), or free text you search (name, PAN, employer).
 */

export type TextOp = "contains" | "starts" | "eq";

export type ColumnFilter =
  | { type: "in"; values: string[] }
  | { type: "num"; min?: number; max?: number }
  | { type: "date"; from?: string; to?: string }
  | { type: "text"; op: TextOp; q: string };

/** `undefined` for a column means unfiltered. */
export type ColumnFilterSet = Record<string, ColumnFilter | undefined>;

/**
 * The sentinel for "this cell is empty" in an `in` filter.
 *
 * A checklist can show a "(blank)" row, but an empty string cannot survive a query string and means
 * nothing to a server — "no officer assigned" is a real thing people filter for, so it needs a value
 * of its own. Chosen to be something no stored enum or name can collide with.
 */
export const BLANK = "__NULL__";

/** What one row contributes to one column: the label on screen, the stored value, and comparables. */
export interface FilterCell {
  /** What the cell renders — what the checklist shows. */
  text: string;
  /** The stored value behind it, which is what filters carry. Equal to `text` for plain columns. */
  raw: string;
  /** Numeric comparable in display units (rupees, days), for `num`. */
  num?: number | null;
  /** `yyyy-mm-dd` in IST, for `date`. */
  date?: string | null;
}

/** An empty bound on both sides (or a blank search) is not a filter — callers normalise to this. */
export function isEmptyFilter(f: ColumnFilter | undefined): boolean {
  if (!f) return true;
  switch (f.type) {
    case "in":
      return f.values.length === 0;
    case "num":
      return f.min == null && f.max == null;
    case "date":
      return !f.from && !f.to;
    case "text":
      return f.q.trim() === "";
  }
}

/** Collapse a no-op filter to `undefined` so a column stops reading as filtered. */
export function normaliseFilter(f: ColumnFilter | undefined): ColumnFilter | undefined {
  return isEmptyFilter(f) ? undefined : f;
}

export function matchesFilter(f: ColumnFilter, cell: FilterCell): boolean {
  switch (f.type) {
    case "in":
      return f.values.includes(cell.raw === "" ? BLANK : cell.raw);

    case "num": {
      // A row with no number cannot satisfy a bound. Excluding it is the honest answer: "amount
      // over ₹5,000" should not hand back the rows whose amount nobody has set yet.
      if (cell.num == null || Number.isNaN(cell.num)) return false;
      if (f.min != null && cell.num < f.min) return false;
      if (f.max != null && cell.num > f.max) return false;
      return true;
    }

    case "date": {
      if (!cell.date) return false;
      // Both bounds inclusive, and plain string comparison is correct for zero-padded ISO days.
      if (f.from && cell.date < f.from) return false;
      if (f.to && cell.date > f.to) return false;
      return true;
    }

    case "text": {
      const needle = f.q.trim().toLowerCase();
      if (!needle) return true;
      // Matched against the LABEL, not the stored value: someone typing "credit exec" is reading
      // the screen, and `CREDIT_EXECUTIVE` is not what they see there.
      const hay = cell.text.toLowerCase();
      if (f.op === "contains") return hay.includes(needle);
      if (f.op === "starts") return hay.startsWith(needle);
      return hay === needle;
    }
  }
}

/**
 * The IST day a timestamp falls on, as `yyyy-mm-dd`.
 *
 * Not `iso.slice(0, 10)`: that is the UTC day, and an application created at 20:00 IST carries a UTC
 * timestamp on the previous date, so slicing files it under a day the console never showed. Not
 * `toLocalISO` either — that is the *viewer's* day, and the backend windows every date filter in IST
 * (`CustomerService: from.atStartOfDay(IST)`), so a staffer abroad would filter against a different
 * calendar from the one the server uses.
 */
export function istDay(value: string | number | Date | null | undefined): string | null {
  if (value == null || value === "") return null;
  // A bare yyyy-mm-dd is already a calendar day — parsing it would re-introduce a timezone.
  if (typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value)) return value;
  const d = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(d.getTime())) return null;
  // en-CA renders ISO-ordered yyyy-mm-dd, which is what makes this a one-liner.
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Kolkata",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(d);
}
