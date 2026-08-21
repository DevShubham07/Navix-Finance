/**
 * Named reporting periods, shared by the staff-performance dashboard and the decision history it
 * links into. One definition so the two pages cannot disagree about where a week starts — a
 * "last week" that meant different things on either side of a click-through would make the totals
 * look wrong when they were not.
 */

export type Range = { from?: string; to?: string };

/** Local-time ISO yyyy-mm-dd — never UTC, which shifts the day at either end of the window. */
export function iso(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export function addDays(d: Date, days: number): Date {
  const copy = new Date(d);
  copy.setDate(copy.getDate() + days);
  return copy;
}

/** Monday of the week containing `d` (ISO week start, the Indian business convention). */
export function mondayOf(d: Date): Date {
  // getDay(): 0=Sun..6=Sat. Sunday belongs to the week that started six days earlier, not the next one.
  return addDays(d, -((d.getDay() + 6) % 7));
}

/**
 * Bounded windows, not rolling ones: "Last week" must END last Sunday, otherwise it overlaps this
 * week and the two periods double-count the same actions.
 */
export const PRESETS: { key: string; label: string; range: () => Range }[] = [
  { key: "today", label: "Today", range: () => ({ from: iso(new Date()), to: iso(new Date()) }) },
  {
    key: "yesterday",
    label: "Yesterday",
    range: () => ({ from: iso(addDays(new Date(), -1)), to: iso(addDays(new Date(), -1)) }),
  },
  {
    key: "this-week",
    label: "This week",
    range: () => ({ from: iso(mondayOf(new Date())), to: iso(new Date()) }),
  },
  {
    key: "last-week",
    label: "Last week",
    range: () => {
      const lastMonday = addDays(mondayOf(new Date()), -7);
      return { from: iso(lastMonday), to: iso(addDays(lastMonday, 6)) };
    },
  },
  {
    key: "this-month",
    label: "This month",
    range: () => {
      const now = new Date();
      return { from: iso(new Date(now.getFullYear(), now.getMonth(), 1)), to: iso(now) };
    },
  },
  {
    key: "last-month",
    label: "Last month",
    range: () => {
      const now = new Date();
      return {
        from: iso(new Date(now.getFullYear(), now.getMonth() - 1, 1)),
        to: iso(new Date(now.getFullYear(), now.getMonth(), 0)),
      };
    },
  },
  { key: "all", label: "All time", range: () => ({}) },
];

/** The range for a preset key; `custom` (or anything unknown) resolves to the caller's own range. */
export function rangeFor(preset: string, custom: Range): Range {
  if (preset === "custom") return custom;
  return PRESETS.find((p) => p.key === preset)?.range() ?? {};
}

export function periodLabelFor(preset: string, custom: Range): string {
  if (preset === "custom") return `${custom.from ?? "start"} → ${custom.to ?? "today"}`;
  return PRESETS.find((p) => p.key === preset)?.label ?? "";
}

/**
 * The preset whose range matches `range` exactly, else "custom" — used to restore the pill selection
 * from a URL so a click-through from another page lands on the period the user actually picked
 * rather than resetting to a default that contradicts the numbers they just saw.
 */
export function presetMatching(range: Range): string {
  if (!range.from && !range.to) return "all";
  const hit = PRESETS.find((p) => {
    const r = p.range();
    return r.from === range.from && r.to === range.to;
  });
  return hit?.key ?? "custom";
}
