"use client";

import * as React from "react";
import { Input } from "@/components/ui";

/**
 * From/To date inputs that commit on **Apply**, shared by `PeriodPicker` and `QueueDateFilter`.
 *
 * Both controls used to call their `onChange` straight from each `<input type="date">`. A native date
 * input fires `change` every time its value becomes a *valid date*, and typing a year produces a valid
 * date on each digit — 0002, 0020, 0202, 2026 — so entering one custom range fired a refetch for every
 * one of those nonsense windows (and on the dashboard, that is several queries at a time). Holding a
 * draft and committing it once makes a custom range cost exactly one fetch.
 *
 * Apply is disabled when the draft is unchanged or inverted (from after to), so it can neither fire a
 * no-op request nor ask the backend for an empty window. Enter in either field applies. When the
 * committed value changes from outside (a preset, a URL deep link) the draft follows it.
 */
export interface DateRange {
  from?: string;
  to?: string;
}

export function CustomRangeInputs({
  value,
  onApply,
}: {
  value: DateRange;
  onApply: (r: DateRange) => void;
}) {
  const [draft, setDraft] = React.useState<DateRange>(value);

  // Follow the committed value when it changes from outside this control.
  React.useEffect(() => {
    setDraft(value);
  }, [value.from, value.to]); // eslint-disable-line react-hooks/exhaustive-deps

  const unchanged = (draft.from ?? "") === (value.from ?? "") && (draft.to ?? "") === (value.to ?? "");
  const inverted = !!draft.from && !!draft.to && draft.from > draft.to;
  const canApply = !unchanged && !inverted;

  const apply = () => {
    if (canApply) onApply(draft);
  };
  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") {
      e.preventDefault();
      apply();
    }
  };

  return (
    <div className="flex flex-wrap items-center gap-2">
      <Input
        type="date"
        value={draft.from ?? ""}
        onChange={(e) => setDraft((d) => ({ ...d, from: e.target.value || undefined }))}
        onKeyDown={onKeyDown}
        className="!mb-0 !w-40"
        aria-label="From date"
      />
      <span className="text-xs text-muted">to</span>
      <Input
        type="date"
        value={draft.to ?? ""}
        onChange={(e) => setDraft((d) => ({ ...d, to: e.target.value || undefined }))}
        onKeyDown={onKeyDown}
        className="!mb-0 !w-40"
        aria-label="To date"
      />
      <button
        type="button"
        onClick={apply}
        disabled={!canApply}
        className="btn btn-sm btn-navy disabled:cursor-not-allowed disabled:opacity-50"
      >
        Apply
      </button>
      {inverted && (
        <span role="alert" className="text-xs text-error-700">
          “From” is after “to”.
        </span>
      )}
    </div>
  );
}
