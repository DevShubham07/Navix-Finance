/**
 * Identity comparisons for the BRE tab — TypeScript ports of the backend helpers, kept
 * behaviour-identical so the tab never disagrees with what the server decided:
 *  - {@link nameSimilarity}     ← ApplicationVerificationService.nameSimilarity (threshold 0.60)
 *  - {@link employerNamesAgree} ← ApplicationVerificationService.employerNamesAgree
 *  - {@link matchesMasked}      ← com.navix.common.util.Aadhaar.matchesMasked
 * Keep both sides in sync.
 */

/** Below this, two names are "not the same person" (backend NAME_MATCH_THRESHOLD). */
export const NAME_MATCH_THRESHOLD = 0.6;

function nameTokens(s: string | null | undefined): Set<string> {
  const out = new Set<string>();
  if (!s) return out;
  for (const t of s.toLowerCase().split(/[^a-z0-9]+/)) if (t.trim() !== "") out.add(t);
  return out;
}

/** One token is a single letter and the other begins with it: "r" ≡ "rahul". */
function initialMatches(x: string, y: string): boolean {
  return (x.length === 1 && y.startsWith(x)) || (y.length === 1 && x.startsWith(y));
}

/**
 * 0..1. Order-insensitive token containment of the shorter name in the longer one (initials count),
 * or plain Jaccard when the shorter name is a single word.
 */
export function nameSimilarity(a: string | null | undefined, b: string | null | undefined): number {
  const ta = nameTokens(a);
  const tb = nameTokens(b);
  if (ta.size === 0 || tb.size === 0) return 0;
  const shorter = ta.size <= tb.size ? ta : tb;
  const longer = shorter === ta ? tb : ta;
  if (shorter.size < 2) {
    let inter = 0;
    for (const t of ta) if (tb.has(t)) inter++;
    const union = new Set([...ta, ...tb]).size;
    return inter / union;
  }
  const unused = new Set(longer);
  let matched = 0;
  // Exact tokens first, so a real word is never consumed by an initial that also fits.
  const pending: string[] = [];
  for (const t of shorter) {
    if (unused.delete(t)) matched++;
    else pending.push(t);
  }
  for (const t of pending) {
    const hit = [...unused].find((u) => initialMatches(t, u));
    if (hit !== undefined) {
      unused.delete(hit);
      matched++;
    }
  }
  return matched / shorter.size;
}

/** Corporate boilerplate that carries no identity (backend EMPLOYER_NOISE). */
const EMPLOYER_NOISE = new Set([
  "PVT", "PVTLTD", "PRIVATE", "LTD", "LTDS", "LIMITED", "LLP", "LLC", "INC", "CORP",
  "CORPORATION", "CO", "COMPANY", "MS", "THE", "AND", "OF",
]);

/** Upper-cased alphanumeric words, boilerplate removed, in written order. */
function employerTokens(name: string | null | undefined): string[] {
  if (!name || name.trim() === "") return [];
  const out: string[] = [];
  for (const raw of name.toUpperCase().split(/[^A-Z0-9]+/)) {
    if (raw !== "" && !EMPLOYER_NOISE.has(raw) && !out.includes(raw)) out.push(raw);
  }
  return out;
}

/**
 * True only on a positive identification of the same employer: one name's distinctive words are
 * wholly contained in the other's, or a de-spaced prefix of ≥5 characters agrees. False means
 * "not established", not "different".
 */
export function employerNamesAgree(declared: string | null | undefined, onRecord: string | null | undefined): boolean {
  const a = employerTokens(declared);
  const b = employerTokens(onRecord);
  if (a.length === 0 || b.length === 0) return false;
  const smaller = a.length <= b.length ? a : b;
  const larger = a.length <= b.length ? b : a;
  if (smaller.every((t) => larger.includes(t)) && (smaller.length > 1 || smaller[0].length >= 3)) return true;
  const flatA = a.join("");
  const flatB = b.join("");
  const shortFlat = flatA.length <= flatB.length ? flatA : flatB;
  const longFlat = flatA.length <= flatB.length ? flatB : flatA;
  return shortFlat.length >= 5 && longFlat.startsWith(shortFlat);
}

export type MaskMatch = "MATCH" | "MISMATCH" | "UNKNOWN";

function normalizeDigits(raw: string | null | undefined): string | null {
  if (raw == null) return null;
  const s = raw.replace(/[\s-]/g, "");
  return s === "" ? null : s;
}

/** The last four digits, or null when fewer than four digits are present (masked form accepted). */
export function lastFour(aadhaarOrMasked: string | null | undefined): string | null {
  if (aadhaarOrMasked == null) return null;
  const digits = aadhaarOrMasked.replace(/\D/g, "");
  return digits.length >= 4 ? digits.slice(-4) : null;
}

/**
 * Does a full 12-digit Aadhaar agree with a provider's masked copy? A 12-character mask
 * ("XXXXXXXX1234", "65XXXXXXXX90") is compared positionally wherever it shows a digit; any other
 * length falls back to its last four. UNKNOWN when there is nothing to compare — never a mismatch.
 */
export function matchesMasked(full: string | null | undefined, masked: string | null | undefined): MaskMatch {
  const number = normalizeDigits(full);
  const mask = normalizeDigits(masked);
  if (number == null || number.length !== 12 || mask == null) return "UNKNOWN";
  if (mask.length === 12) {
    let compared = false;
    for (let i = 0; i < 12; i++) {
      const m = mask[i];
      if (m < "0" || m > "9") continue;
      compared = true;
      if (m !== number[i]) return "MISMATCH";
    }
    return compared ? "MATCH" : "UNKNOWN";
  }
  const tail = lastFour(mask);
  if (tail == null) return "UNKNOWN";
  return number.endsWith(tail) ? "MATCH" : "MISMATCH";
}
