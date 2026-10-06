/**
 * The two optional rupee boxes on the new-lead form (monthly salary, loan amount interested).
 *
 * The form used to gate each one on `Number(x) > 0`, so "25,000" (NaN), "-500" and "abc" were
 * dropped from the request without a word: the lead saved with no salary and nobody was told.
 * This parser accepts the grouped forms people actually type and reports everything else as
 * invalid, so the form can say so and refuse to save.
 *
 * The result is integer paise, the unit `CreateLeadInput.monthlySalaryPaise` /
 * `loanAmountInterestedPaise` already carry. A plain whole-rupee entry gives the same value as
 * `rupeesToPaise(Number(x))`; the paise are worked out from the digits rather than from a float.
 */

export type LeadRupeeParse =
  | { kind: "empty" }
  | { kind: "ok"; paise: number }
  | { kind: "invalid" };

/** Shown under a box whose value is not empty and is not a positive rupee amount. */
export const LEAD_RUPEE_ERROR = "Enter a rupee amount above 0, e.g. 25,000";

const PLAIN = /^\d+$/;
/** Indian grouping: 1,000 · 25,000 · 2,50,000 · 12,34,567. */
const INDIAN = /^\d{1,2}(,\d{2})*,\d{3}$/;
/** Western grouping: 1,000 · 250,000 · 1,234,567. */
const WESTERN = /^\d{1,3}(,\d{3})+$/;

/**
 * Parse one rupee box.
 *
 * - blank (or only spaces) → `empty`: the field is optional, so it is simply left out;
 * - digits, optionally grouped Indian- or Western-style, optionally after a leading "₹", with at
 *   most two digits after a decimal point → `ok` with the amount in integer paise;
 * - anything else, including zero, a minus sign, letters and misplaced commas ("25,00") →
 *   `invalid`.
 */
export function parseLeadRupees(raw: string): LeadRupeeParse {
  let s = raw.trim();
  if (s === "") return { kind: "empty" };
  if (s.startsWith("₹")) s = s.slice(1).trim();

  const match = /^([\d,]+)(?:\.(\d{0,2}))?$/.exec(s);
  if (!match) return { kind: "invalid" };
  const [, whole, fraction = ""] = match;
  if (!(PLAIN.test(whole) || INDIAN.test(whole) || WESTERN.test(whole))) return { kind: "invalid" };

  const rupees = Number(whole.replace(/,/g, ""));
  const paise = rupees * 100 + Number(fraction.padEnd(2, "0"));
  // Not a safe integer = too large to hold exactly in paise; refuse it rather than round it.
  if (!Number.isSafeInteger(paise) || paise <= 0) return { kind: "invalid" };
  return { kind: "ok", paise };
}
