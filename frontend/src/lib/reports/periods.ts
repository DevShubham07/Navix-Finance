/**
 * The reports centre's period presets: the shared ones from `lib/period` (so "this week" means the same
 * as on the performance dashboard) plus the windows a finance reader asks for — rolling 7/30 days, the
 * calendar quarter and the Indian financial year (April–March), which now live in the shared list too.
 */

import { PRESETS, addDays, fyStartYear, iso, type Range } from "@/lib/period";

export type ReportPreset = { key: string; label: string; range: (now?: Date) => Range };

const shared = (key: string): ReportPreset => {
  const p = PRESETS.find((x) => x.key === key);
  if (!p) throw new Error(`Unknown preset ${key}`);
  return { key: p.key, label: p.label, range: (now) => p.range(now) };
};

/** Kept here for existing importers; the definition lives with the shared presets. */
export { fyStartYear };

export const REPORT_PRESETS: ReportPreset[] = [
  shared("today"),
  shared("yesterday"),
  shared("this-week"),
  shared("last-week"),
  { key: "last-7", label: "Last 7 days", range: (now = new Date()) => ({ from: iso(addDays(now, -6)), to: iso(now) }) },
  shared("this-month"),
  shared("last-month"),
  { key: "last-30", label: "Last 30 days", range: (now = new Date()) => ({ from: iso(addDays(now, -29)), to: iso(now) }) },
  shared("this-quarter"),
  shared("last-quarter"),
  shared("this-fy"),
  shared("last-fy"),
  shared("all"),
];

export function reportRange(preset: string, custom: Range, now?: Date): Range {
  if (preset === "custom") return custom;
  return REPORT_PRESETS.find((p) => p.key === preset)?.range(now) ?? {};
}
