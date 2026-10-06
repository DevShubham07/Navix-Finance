/**
 * The rupee amount boxes on the collections case page (record a payment, propose a settlement).
 *
 * The old filter (`/[^\d.]/g`) kept every dot, so "12.5.0" and a lone "." got through; the lone dot
 * parsed to NaN, which JSON sends as `null`. These two helpers keep the box to a well-formed amount
 * and tell the caller whether it is one worth submitting.
 */

/**
 * Keep only what a rupee amount can contain: digits and ONE decimal point, with at most two digits
 * (paise) after it. Pasted separators and symbols ("₹1,000.50") are dropped; later dots are dropped
 * rather than splitting the number.
 */
export function sanitizeRupeeInput(raw: string): string {
  const cleaned = raw.replace(/[^\d.]/g, "");
  const dot = cleaned.indexOf(".");
  if (dot === -1) return cleaned;
  const whole = cleaned.slice(0, dot);
  const fraction = cleaned.slice(dot + 1).replace(/\./g, "").slice(0, 2);
  return `${whole}.${fraction}`;
}

/** Largest rupee value whose paise still fit in a safe integer. */
const MAX_SAFE_RUPEES = Math.floor(Number.MAX_SAFE_INTEGER / 100);

/**
 * The amount the box holds as a positive rupee number, or `null` when it is empty, a lone ".",
 * zero, or too large to convert to integer paise exactly. Callers disable submit on `null`.
 */
export function parsePositiveRupees(value: string): number | null {
  const s = sanitizeRupeeInput(value);
  if (!/\d/.test(s)) return null;
  const n = Number(s);
  if (!Number.isFinite(n) || n <= 0 || n > MAX_SAFE_RUPEES) return null;
  return n;
}
