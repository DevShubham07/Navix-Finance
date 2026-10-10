import type { DashBucket, DashTone } from "@/lib/api/applications";

/**
 * The ONLY place the dashboard's colours live.
 *
 * Palette = the "Monochrome + Ember" theme (src/styles/theme.css): near-black ink, ember accent,
 * mint / sun / violet / sky companions on near-white cards. The values are hex mirrors of the
 * theme's RGB channels because several callers do arithmetic on them (`hexToRgb`: the calendar heat
 * map and the India map ramp) — keep every value here a real 6-digit hex.
 *
 * Three strengths per tone:
 *   TONE_SOLID  bright chart fill (bars, dots, icons, progress) — carries no text
 *   TONE_TEXT   deep shade for figures and labels (>= 4.5:1 on white and on its TONE_TINT)
 *   TONE_TINT   pale wash behind a chip or a selected row
 * Colour is never the only signal anywhere on the dashboard — arrows and text labels always
 * accompany it.
 */

/**
 * Two deep stops per tone, kept for the few surfaces that still carry WHITE text on a tone (role
 * pills, segment pills). Both stops are >= 4.5:1 against white. KPI cards no longer use them.
 */
export const GRADIENTS: Record<DashTone, readonly [string, string]> = {
  navy: ["#222326", "#0B0B0C"],
  emerald: ["#17804F", "#116A40"],
  orange: ["#C73A18", "#A93013"],
  red: ["#C62A2F", "#A11F24"],
  violet: ["#6A4DE0", "#5136B8"],
  teal: ["#0E6A7D", "#0B5566"],
  sky: ["#1A64B3", "#154F8E"],
  royal: ["#2457B8", "#1C4594"],
  blue: ["#1F5FD1", "#1A4DA8"],
  amber: ["#8A6200", "#6F4F00"],
};

export function gradientCss(tone: DashTone, angle = 135): string {
  const [a, b] = GRADIENTS[tone];
  return `linear-gradient(${angle}deg, ${a} 0%, ${b} 100%)`;
}

/** Bright accent per tone (chart fills, dots, icons, progress bars). Never used for text. */
export const TONE_SOLID: Record<DashTone, string> = {
  navy: "#0B0B0C",
  emerald: "#5CD398",
  orange: "#FF5B37",
  red: "#E5484D",
  violet: "#8E6CF2",
  teal: "#3DB4C8",
  sky: "#5BA3F0",
  royal: "#3B6FE0",
  blue: "#4A8BEB",
  amber: "#F8CF40",
};

/** Deep shade per tone for figures and labels (>= 4.5:1 on white and on the matching TONE_TINT). */
export const TONE_TEXT: Record<DashTone, string> = {
  navy: "#0B0B0C",
  emerald: "#116A40",
  orange: "#C73A18",
  red: "#C62A2F",
  violet: "#6A4DE0",
  teal: "#0E6A7D",
  sky: "#1A64B3",
  royal: "#2457B8",
  blue: "#1F5FD1",
  amber: "#8A6200",
};

/** Pale tint per tone (cell backgrounds, soft chips). */
export const TONE_TINT: Record<DashTone, string> = {
  navy: "#ECEDEE",
  emerald: "#E3F7EC",
  orange: "#FFF1EC",
  red: "#FDECEC",
  violet: "#F1EDFE",
  teal: "#E3F5F8",
  sky: "#E8F2FD",
  royal: "#E6EDFC",
  blue: "#E7F0FD",
  amber: "#FEF6D8",
};

/** Chart series. Totals / targets sit on the pale track; the measured series is ink. */
export const SERIES = {
  good: "#5CD398",
  pending: "#F8CF40",
  bad: "#E5484D",
  tloan: "#D8DADB",
  collected: "#0B0B0C",
  pctLine: "#FF5B37",
  interest: "#5BA3F0",
  pf: "#5CD398",
  penalty: "#E5484D",
  reloan: "#8E6CF2",
  neutral: "#0B0B0C",
  target: "#D8DADB",
} as const;

/** DPD tones: running = mint, 1-30 = sky, 31-60 = sun, 61-90 = ember, 90+ = deep red. */
export const DPD_COLORS: Record<DashBucket, string> = {
  RUNNING: "#5CD398",
  D1_30: "#5BA3F0",
  D31_60: "#F8CF40",
  D61_90: "#FF5B37",
  D90_PLUS: "#A11F24",
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

/** Percent tone as TEXT (>= 4.5:1 on white and on PCT_TONE_TINT). */
export const PCT_TONE_COLOR: Record<PctTone, string> = {
  red: "#C62A2F",
  amber: "#8A6200",
  emerald: "#116A40",
  none: "#6E727A",
};

/** Percent tone as a FILL (bars, progress bars, gauge arcs). */
export const PCT_TONE_FILL: Record<PctTone, string> = {
  red: "#E5484D",
  amber: "#F8CF40",
  emerald: "#5CD398",
  none: "#D8DADB",
};

export const PCT_TONE_TINT: Record<PctTone, string> = {
  red: "#FDECEC",
  amber: "#FEF6D8",
  emerald: "#E3F7EC",
  none: "#F1F2F2",
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
  axis: "#6E727A",
  good: "#116A40",
  bad: "#C62A2F",
  badDeep: "#A11F24",
  reloan: "#6A4DE0",
  blueDark: "#1A64B3",
} as const;

/** Chart furniture: hairline grid, a barely-there hover band, pale track for "not measured". */
export const CHART = {
  grid: "#E5E7E8",
  cursor: "rgba(17,19,24,0.04)",
  unmeasured: "#D8DADB",
} as const;

export const MEDAL = { 1: "#F5C531", 2: "#A3A6AB", 3: "#C9824A" } as const;
export const MEDAL_GLOW = "0 0 0 4px rgba(245,197,49,.35)";
/** Soft white corner highlight (legacy vivid cards; the light KPI cards no longer use it). */
export const CARD_HIGHLIGHT = "radial-gradient(circle, rgba(255,255,255,.30) 0%, rgba(255,255,255,0) 70%)";
export const PODIUM_BG = "linear-gradient(to bottom, #F3F4F4, #FFFFFF)";
export const SUNDAY_STRIPE = "repeating-linear-gradient(135deg, #F7F8F8 0 6px, #F1F2F2 6px 12px)";

/** Records-drawer segment pills (white text) and chips. */
export const SEGMENT_PILL = { ALL: NAVY, FRESH: TONE_TEXT.emerald, RELOAN: TONE_TEXT.violet } as const;
export const SEGMENT_CHIP = {
  FRESH: { color: TEXT.good, background: TONE_TINT.emerald },
  RELOAN: { color: TEXT.reloan, background: TONE_TINT.violet },
} as const;

/** Header stat chips ({ color, tint }) — `color` is the dot AND the value text, so it is a deep shade. */
export const STAT_CHIP = {
  blue: { color: TONE_TEXT.sky, tint: TONE_TINT.sky },
  amber: { color: TONE_TEXT.amber, tint: TONE_TINT.amber },
  green: { color: TEXT.good, tint: TONE_TINT.emerald },
  violet: { color: TONE_TEXT.violet, tint: TONE_TINT.violet },
  navy: { color: NAVY, tint: TONE_TINT.navy },
} as const;

/** India map: no-data fill, selection stroke, mint -> ink ramp. */
export const MAP = {
  noData: "#F1F2F2",
  noDataBorder: "#D8DADB",
  highlight: "#FF5B37",
  border: WHITE,
  rampFrom: "#BDEDD3",
  rampTo: NAVY,
} as const;

/** "#0B0B0C" -> [11, 11, 12]. */
export function hexToRgb(hex: string): [number, number, number] {
  const n = parseInt(hex.replace("#", ""), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}
