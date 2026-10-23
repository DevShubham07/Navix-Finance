/**
 * The reports centre's period presets: the shared ones from `lib/period` (so "this week" means the same
 * as on the performance dashboard) plus the windows a finance reader asks for — rolling 7/30 days, the
 * calendar quarter and the Indian financial year (April–March).
 */

import { PRESETS, addDays, iso, type Range } from "@/lib/period";

export type ReportPreset = { key: string; label: string; range: (now?: Date) => Range };

const shared = (key: string): ReportPreset => {
  const p = PRESETS.find((x) => x.key === key);
  if (!p) throw new Error(`Unknown preset ${key}`);
  return { key: p.key, label: p.label, range: () => p.range() };
};

/** The financial year (1 Apr – 31 Mar) that `d` falls in, as its starting calendar year. */
export function fyStartYear(d: Date): number {
  return d.getMonth() >= 3 ? d.getFullYear() : d.getFullYear() - 1;
}

export const REPORT_PRESETS: ReportPreset[] = [
  shared("today"),
  shared("yesterday"),
  shared("this-week"),
  shared("last-week"),
  { key: "last-7", label: "Last 7 days", range: (now = new Date()) => ({ from: iso(addDays(now, -6)), to: iso(now) }) },
  shared("this-month"),
  shared("last-month"),
  { key: "last-30", label: "Last 30 days", range: (now = new Date()) => ({ from: iso(addDays(now, -29)), to: iso(now) }) },
  {
    key: "this-quarter",
    label: "This quarter",
    range: (now = new Date()) => ({
      from: iso(new Date(now.getFullYear(), Math.floor(now.getMonth() / 3) * 3, 1)),
      to: iso(now),
    }),
  },
  {
    key: "this-fy",
    label: "This financial year",
    range: (now = new Date()) => ({ from: iso(new Date(fyStartYear(now), 3, 1)), to: iso(now) }),
  },
  {
    key: "last-fy",
    label: "Last financial year",
    range: (now = new Date()) => {
      const y = fyStartYear(now) - 1;
      return { from: iso(new Date(y, 3, 1)), to: iso(new Date(y + 1, 2, 31)) };
    },
  },
  shared("all"),
];

export function reportRange(preset: string, custom: Range, now?: Date): Range {
  if (preset === "custom") return custom;
  return REPORT_PRESETS.find((p) => p.key === preset)?.range(now) ?? {};
}
