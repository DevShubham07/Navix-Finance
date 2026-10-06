"use client";

import * as React from "react";
import { PRESETS, type Range } from "@/lib/period";
import { CustomRangeInputs } from "@/components/staff/custom-range-inputs";

/**
 * The reporting-period pill row + custom date inputs, shared by the staff-performance dashboard, the
 * decision history and the dashboard. Same control on all three so a period means the same thing
 * wherever it is set.
 *
 * Visually it matches `QueueDateFilter` (square navy pills on a grey track) — the console used to
 * carry two competing period controls, this one rounded-full and gold, that one square and navy, and
 * the square style won because it already sits inside the toolbars of nine pages.
 *
 * A custom range is committed with **Apply**, not on every keystroke; see `CustomRangeInputs`.
 */
export function PeriodPicker({
  preset,
  onPreset,
  custom,
  onCustom,
}: {
  preset: string;
  onPreset: (key: string) => void;
  custom: Range;
  onCustom: (r: Range) => void;
}) {
  const pill = (active: boolean) =>
    `rounded px-2.5 py-1 text-xs font-semibold transition-colors ${
      active ? "bg-navy text-white" : "text-muted hover:text-ink"
    }`;

  return (
    <div className="mb-4 flex flex-wrap items-center gap-2">
      <span className="text-xs font-semibold uppercase tracking-wide text-muted">Period</span>
      <div className="flex flex-wrap gap-1 rounded border border-line bg-grey-50 p-1">
        {PRESETS.map((p) => (
          <button
            key={p.key}
            type="button"
            aria-pressed={preset === p.key}
            onClick={() => onPreset(p.key)}
            className={pill(preset === p.key)}
          >
            {p.label}
          </button>
        ))}
        <button
          type="button"
          aria-pressed={preset === "custom"}
          onClick={() => onPreset("custom")}
          className={pill(preset === "custom")}
        >
          Custom
        </button>
      </div>
      {preset === "custom" && <CustomRangeInputs value={custom} onApply={onCustom} />}
    </div>
  );
}
