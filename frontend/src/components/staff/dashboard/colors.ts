import type { DashBucket, DashTone } from "@/lib/api/applications";

/**
 * The ONLY place the dashboard's colours live.
 *
 * Cards are two-stop gradients carrying white text, so both stops are the deeper shades (>= 4.5:1
 * against white). Chart series carry no text and use the brighter ones. Colour is never the only
 * signal anywhere on the dashboard — arrows and text labels always accompany it.
 */

export const GRADIENTS: Record<DashTone, readonly [string, string]> = {
  navy: ["#12365C", "#0C2540"],
  emerald: ["#0E8557", "#0B6B46"],
  orange: ["#C2410C", "#9A3412"],
  red: ["#B3261E", "#8F1E18"],
  violet: ["#6D28D9", "#5B21B6"],
  teal: ["#0F766E", "#115E59"],
  sky: ["#0369A1", "#075985"],
  royal: ["#1D4ED8", "#1E3A8A"],
  blue: ["#2563EB", "#1E40AF"],
  amber: ["#B45309", "#92400E"],
};

export function gradientCss(tone: DashTone, angle = 135): string {
  const [a, b] = GRADIENTS[tone];
  return `linear-gradient(${angle}deg, ${a} 0%, ${b} 100%)`;
}

/** Flat accent per tone (borders, dots, icon squares, pills). */
export const TONE_SOLID: Record<DashTone, string> = {
  navy: "#0C2540",
  emerald: "#14A06B",
  orange: "#EA580C",
  red: "#D33C32",
  violet: "#7C3AED",
  teal: "#0F766E",
  sky: "#0284C7",
  royal: "#1D4ED8",
  blue: "#2563EB",
  amber: "#F59E0B",
};

/** Pale tint per tone (cell backgrounds, soft chips). */
export const TONE_TINT: Record<DashTone, string> = {
  navy: "#EAEFF6",
  emerald: "#E3F6EC",
  orange: "#FFEDD5",
  red: "#FCE8E6",
  violet: "#EDE9FE",
  teal: "#CCFBF1",
  sky: "#E0F2FE",
  royal: "#DBEAFE",
  blue: "#DBEAFE",
  amber: "#FEF3C7",
};

/** Chart series. */
export const SERIES = {
  good: "#14A06B",
  pending: "#F59E0B",
  bad: "#D33C32",
  tloan: "#93C5FD",
  collected: "#2563EB",
  pctLine: "#EA580C",
  interest: "#2563EB",
  pf: "#14A06B",
  penalty: "#D33C32",
  reloan: "#7C3AED",
  neutral: "#0C2540",
  target: "#BFD3EA",
} as const;

/** DPD tones: running = emerald, 1-30 = blue, 31-60 = amber, 61-90 = orange, 90+ = red. */
export const DPD_COLORS: Record<DashBucket, string> = {
  RUNNING: "#14A06B",
  D1_30: "#2563EB",
  D31_60: "#F59E0B",
  D61_90: "#EA580C",
  D90_PLUS: "#D33C32",
};
export const DPD_LABELS: Record<DashBucket, string> = {
  RUNNING: "Running",
  D1_30: "1-30 days",
  D31_60: "31-60 days",
  D61_90: "61-90 days",
  D90_PLUS: "90+ days",
};

/** Percent tone thresholds (chips and progress bars): < 50 red, 50-80 amber, >= 80 emerald. */
export const PCT_LOW = 0.5;
export const PCT_HIGH = 0.8;

export type PctTone = "red" | "amber" | "emerald" | "none";

/** `ratio` is 0..1; null ("cannot measure") has no tone. */
export function pctTone(ratio: number | null | undefined): PctTone {
  if (ratio == null || !Number.isFinite(ratio)) return "none";
  if (ratio < PCT_LOW) return "red";
  if (ratio < PCT_HIGH) return "amber";
  return "emerald";
}

export const PCT_TONE_COLOR: Record<PctTone, string> = {
  red: "#D33C32",
  amber: "#D97706",
  emerald: "#14A06B",
  none: "#8593A6",
};

export const PCT_TONE_TINT: Record<PctTone, string> = {
  red: "#FCE8E6",
  amber: "#FEF3C7",
  emerald: "#E3F6EC",
  none: "#F1F3F6",
};

/** Domain colour of each role-toggle pill. */
export const ROLE_TONE: Record<string, DashTone> = {
  ADMIN: "navy",
  CREDIT_HEAD: "emerald",
  CREDIT_EXECUTIVE: "emerald",
  COLLECTION_HEAD: "red",
  COLLECTION_EXECUTIVE: "red",
  TELECALLER: "orange",
  DISBURSEMENT_HEAD: "violet",
  ACCOUNTANT: "sky",
};

/** Ratio (0..1) -> "86.2%"; null -> an em dash, never a fabricated 0. */
export function fmtPct(ratio: number | null | undefined, digits = 1): string {
  return ratio == null || !Number.isFinite(ratio) ? "—" : `${(ratio * 100).toFixed(digits)}%`;
}

/** Whole-number ratio (0..1) -> "86%". */
export function fmtPct0(ratio: number | null | undefined): string {
  return fmtPct(ratio, 0);
}

/* ---- neutrals, text shades and one-off surfaces: no component hardcodes a hex ---- */

export const WHITE = "#FFFFFF";
export const NAVY = TONE_SOLID.navy;

/** Text shades on white surfaces (dark enough for 4.5:1). */
export const TEXT = {
  axis: "#46566E",
  good: "#0B6B46",
  bad: "#B3261E",
  badDeep: "#8F1E18",
  reloan: "#5B21B6",
  blueDark: "#1E40AF",
} as const;

/** Chart furniture. */
export const CHART = {
  grid: "#EAE1D0",
  cursor: "rgba(12,37,64,0.06)",
  unmeasured: "#CBD5E1",
} as const;

export const MEDAL = { 1: "#F59E0B", 2: "#94A3B8", 3: "#B45309" } as const;
export const MEDAL_GLOW = "0 0 0 4px rgba(245,158,11,.35)";
/** Soft white corner highlight on the vivid KPI cards. */
export const CARD_HIGHLIGHT = "radial-gradient(circle, rgba(255,255,255,.30) 0%, rgba(255,255,255,0) 70%)";
export const PODIUM_BG = "linear-gradient(to bottom, #F4F8FD, #FFFFFF)";
export const SUNDAY_STRIPE = "repeating-linear-gradient(135deg, #F3F4F7 0 6px, #ECEEF3 6px 12px)";

/** Records-drawer segment pills and chips. */
export const SEGMENT_PILL = { ALL: NAVY, FRESH: GRADIENTS.emerald[0], RELOAN: TONE_SOLID.violet } as const;
export const SEGMENT_CHIP = {
  FRESH: { color: TEXT.good, background: TONE_TINT.emerald },
  RELOAN: { color: TEXT.reloan, background: TONE_TINT.violet },
} as const;

/** Header stat chips ({ color, tint }). */
export const STAT_CHIP = {
  blue: { color: TONE_SOLID.blue, tint: TONE_TINT.blue },
  amber: { color: GRADIENTS.amber[0], tint: TONE_TINT.amber },
  green: { color: TEXT.good, tint: TONE_TINT.emerald },
  violet: { color: TONE_SOLID.violet, tint: TONE_TINT.violet },
  navy: { color: NAVY, tint: TONE_TINT.navy },
} as const;

/** India map: no-data fill, selection stroke, emerald -> navy ramp. */
export const MAP = {
  noData: "#EEF2F6",
  noDataBorder: "#D5DBE3",
  highlight: "#F59E0B",
  border: WHITE,
  rampFrom: "#B7E8D2",
  rampTo: NAVY,
} as const;

/** "#0C2540" -> [12, 37, 64]. */
export function hexToRgb(hex: string): [number, number, number] {
  const n = parseInt(hex.replace("#", ""), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}
