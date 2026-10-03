/**
 * Aadhaar number shape + checksum (UIDAI Verhoeff) — the browser half of the backend's
 * `com.navix.common.util.Aadhaar`. Keep the two in step.
 *
 * Twelve digits, first digit 2–9, last digit a Verhoeff check over the first eleven. Catches every
 * single-digit typo and adjacent transposition, which is what the intake screen needs: a mistyped
 * number must be refused on the spot, because once stored it is cross-checked against the masked
 * Aadhaar the PAN record and DigiLocker return, and a disagreement rejects the application.
 */

const D = [
  [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
  [1, 2, 3, 4, 0, 6, 7, 8, 9, 5],
  [2, 3, 4, 0, 1, 7, 8, 9, 5, 6],
  [3, 4, 0, 1, 2, 8, 9, 5, 6, 7],
  [4, 0, 1, 2, 3, 9, 5, 6, 7, 8],
  [5, 9, 8, 7, 6, 0, 4, 3, 2, 1],
  [6, 5, 9, 8, 7, 1, 0, 4, 3, 2],
  [7, 6, 5, 9, 8, 2, 1, 0, 4, 3],
  [8, 7, 6, 5, 9, 3, 2, 1, 0, 4],
  [9, 8, 7, 6, 5, 4, 3, 2, 1, 0],
];
const P = [
  [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
  [1, 5, 7, 6, 2, 8, 3, 0, 9, 4],
  [5, 8, 0, 3, 7, 9, 6, 1, 4, 2],
  [8, 9, 1, 6, 0, 4, 3, 5, 2, 7],
  [9, 4, 5, 3, 1, 2, 6, 8, 7, 0],
  [4, 2, 8, 6, 5, 7, 3, 9, 0, 1],
  [2, 7, 9, 3, 8, 0, 6, 4, 1, 5],
  [7, 0, 4, 6, 9, 1, 3, 2, 5, 8],
];

/** Digits only — what the backend stores. */
export function normalizeAadhaar(raw: string): string {
  return raw.replace(/\D/g, "").slice(0, 12);
}

/** `1234 5678 9012`, the grouping printed on the card, for display while typing. */
export function formatAadhaar(digits: string): string {
  return normalizeAadhaar(digits).replace(/(\d{4})(?=\d)/g, "$1 ");
}

/** `XXXX XXXX 1234` — the only form a borrower-facing confirmation should echo back. */
export function maskAadhaar(digits: string): string {
  const d = normalizeAadhaar(digits);
  return d.length === 12 ? `XXXX XXXX ${d.slice(8)}` : "";
}

export function isValidAadhaar(raw: string): boolean {
  const n = normalizeAadhaar(raw);
  if (n.length !== 12 || n[0] < "2") return false;
  let c = 0;
  for (let i = 0; i < 12; i++) {
    c = D[c][P[i % 8][Number(n[11 - i])]];
  }
  return c === 0;
}
