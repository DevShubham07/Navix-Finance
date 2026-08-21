"use client";

import * as React from "react";
import { PRESETS, type Range } from "@/lib/period";

/**
 * The reporting-period pill row + custom date inputs, shared by the staff-performance dashboard and
 * the decision history. Same control on both so a period means the same thing wherever it is set.
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
    `rounded-full px-3 py-1 text-sm font-semibold transition ${
      active ? "bg-gold text-white" : "text-muted hover:text-navy"
    }`;

  return (
    <div className="mb-4 flex flex-wrap items-center gap-2">
      <span className="text-xs font-semibold uppercase tracking-wide text-muted">Period</span>
      <div className="flex flex-wrap items-center gap-1 rounded-full border border-line bg-white p-1">
        {PRESETS.map((p) => (
          <button key={p.key} onClick={() => onPreset(p.key)} className={pill(preset === p.key)}>
            {p.label}
          </button>
        ))}
        <button onClick={() => onPreset("custom")} className={pill(preset === "custom")}>
          Custom
        </button>
      </div>
      {preset === "custom" && (
        <div className="flex items-center gap-2">
          <input
            type="date"
            aria-label="From"
            value={custom.from ?? ""}
            onChange={(e) => onCustom({ ...custom, from: e.target.value || undefined })}
            className="rounded border border-line px-2 py-1 text-sm"
          />
          <span className="text-xs text-muted">→</span>
          <input
            type="date"
            aria-label="To"
            value={custom.to ?? ""}
            onChange={(e) => onCustom({ ...custom, to: e.target.value || undefined })}
            className="rounded border border-line px-2 py-1 text-sm"
          />
        </div>
      )}
    </div>
  );
}
