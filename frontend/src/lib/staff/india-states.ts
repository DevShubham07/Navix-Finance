/**
 * India state names + the location segment classifier behind the dashboard Map tab.
 * Canonical names are the `ST_NM` values of frontend/public/maps/india-states.topo.json.
 */

export const CANONICAL_STATES = [
  "Andaman & Nicobar",
  "Andhra Pradesh",
  "Arunachal Pradesh",
  "Assam",
  "Bihar",
  "Chandigarh",
  "Chhattisgarh",
  "Dadra and Nagar Haveli and Daman and Diu",
  "Delhi",
  "Goa",
  "Gujarat",
  "Haryana",
  "Himachal Pradesh",
  "Jammu & Kashmir",
  "Jharkhand",
  "Karnataka",
  "Kerala",
  "Ladakh",
  "Lakshadweep",
  "Madhya Pradesh",
  "Maharashtra",
  "Manipur",
  "Meghalaya",
  "Mizoram",
  "Nagaland",
  "Odisha",
  "Puducherry",
  "Punjab",
  "Rajasthan",
  "Sikkim",
  "Tamil Nadu",
  "Telangana",
  "Tripura",
  "Uttar Pradesh",
  "Uttarakhand",
  "West Bengal",
] as const;

export const UNKNOWN_STATE = "Unknown";

/** lower-case, "&" -> "and", punctuation -> space, single spaces. */
function norm(s: string): string {
  return s
    .toLowerCase()
    .replace(/&/g, " and ")
    .replace(/[^a-z0-9]+/g, " ")
    .trim()
    .replace(/\s+/g, " ");
}

const MERGED_UT = "Dadra and Nagar Haveli and Daman and Diu";

/** normalised spelling -> canonical name. Canonical names map to themselves automatically. */
const ALIASES: Record<string, string> = {
  orissa: "Odisha",
  pondicherry: "Puducherry",
  "j and k": "Jammu & Kashmir",
  jk: "Jammu & Kashmir",
  "jammu and kashmir": "Jammu & Kashmir",
  "jammu kashmir": "Jammu & Kashmir",
  "nct of delhi": "Delhi",
  "new delhi": "Delhi",
  "delhi ncr": "Delhi",
  "andaman and nicobar islands": "Andaman & Nicobar",
  "andaman and nicobar": "Andaman & Nicobar",
  "andaman nicobar": "Andaman & Nicobar",
  "dadra and nagar haveli": MERGED_UT,
  "daman and diu": MERGED_UT,
  "dadra and nagar haveli and daman and diu": MERGED_UT,
  "dadra nagar haveli": MERGED_UT,
  "daman diu": MERGED_UT,
  uttaranchal: "Uttarakhand",
  telengana: "Telangana",
  chattisgarh: "Chhattisgarh",
  chhatisgarh: "Chhattisgarh",
  tamilnadu: "Tamil Nadu",
  "tamil nadu state": "Tamil Nadu",
  andhra: "Andhra Pradesh",
  "west bengal state": "West Bengal",
  bengal: "West Bengal",
};

const LOOKUP: Map<string, string> = new Map([
  ...CANONICAL_STATES.map((s) => [norm(s), s] as [string, string]),
  ...Object.entries(ALIASES),
]);

/** A backend state string -> the canonical map name, or null when it cannot be recognised. */
export function normaliseState(raw: string | null | undefined): string | null {
  if (!raw) return null;
  return LOOKUP.get(norm(raw)) ?? null;
}

/** Same, but unrecognised values become "Unknown" (the bucket shown under the map). */
export function stateLabel(raw: string | null | undefined): string {
  return normaliseState(raw) ?? UNKNOWN_STATE;
}

/* ------------------------------------------------------------------ segments */

export type LocationSegment =
  | "BEST"
  | "BETTER"
  | "LARGE"
  | "LARGE_WEAK_CLOSE"
  | "GROWING"
  | "NEEDS_ATTENTION";

export interface SegmentInfo {
  key: LocationSegment;
  label: string;
  color: string;
}

/** Display order of the segment chips. */
export const SEGMENTS: SegmentInfo[] = [
  { key: "BEST", label: "Best", color: "#F59E0B" },
  { key: "BETTER", label: "Better", color: "#14A06B" },
  { key: "LARGE", label: "Large users", color: "#2563EB" },
  { key: "LARGE_WEAK_CLOSE", label: "Large, weak close", color: "#7C3AED" },
  { key: "GROWING", label: "Growing", color: "#64748B" },
  { key: "NEEDS_ATTENTION", label: "Needs attention", color: "#D33C32" },
];

export const SEGMENT_BY_KEY: Record<LocationSegment, SegmentInfo> = Object.fromEntries(
  SEGMENTS.map((s) => [s.key, s]),
) as Record<LocationSegment, SegmentInfo>;

/** Close rate (closed ÷ due, 0..1) at or above which a location is "Best". */
export const BEST_CLOSE_RATE = 0.85;
/** ...at or above which it is "Better" (and a large location stays "Large users"). */
export const BETTER_CLOSE_RATE = 0.7;
/** Below this a non-large location needs attention. */
export const ATTENTION_CLOSE_RATE = 0.5;
/** Cases at/above which a location counts as large, per level (a state is far bigger than a pincode). */
export const LARGE_CASES = { PINCODE: 20, STATE: 200 } as const;
/** Fewer cases than this cannot be "Best" — one lucky loan is not a trend. */
export const MIN_CASES_FOR_BEST = 3;

export interface SegmentInput {
  cases: number;
  closeRate: number | null;
  prevPeriodCases: number;
}

/**
 * Classify one location. Order matters: size first (large + healthy close = Large users, large +
 * weak close = Large weak close), then by close rate, then growth. A location with no due loans yet
 * (closeRate null) cannot be judged on repayment, so it is Growing when it gained cases, otherwise
 * Better (nothing is wrong that can be measured).
 */
export function classifySegment(row: SegmentInput, level: keyof typeof LARGE_CASES = "PINCODE"): LocationSegment {
  const { cases, closeRate, prevPeriodCases } = row;
  const growing = cases > prevPeriodCases;
  if (cases >= LARGE_CASES[level]) {
    return closeRate == null || closeRate >= BETTER_CLOSE_RATE ? "LARGE" : "LARGE_WEAK_CLOSE";
  }
  if (closeRate == null) return growing ? "GROWING" : "BETTER";
  if (closeRate < ATTENTION_CLOSE_RATE) return "NEEDS_ATTENTION";
  if (closeRate >= BEST_CLOSE_RATE && cases >= MIN_CASES_FOR_BEST) return "BEST";
  if (closeRate >= BETTER_CLOSE_RATE) return "BETTER";
  return growing ? "GROWING" : "NEEDS_ATTENTION";
}
