"use client";

/** A single switch row. */
export function ToggleRow({
  label,
  description,
  on,
  disabled,
  onChange,
}: {
  label: string;
  description: string;
  on: boolean;
  disabled?: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <div className="flex items-center justify-between gap-4 border-b border-grey-200 py-3 last:border-0">
      <div className="min-w-0">
        <div className="text-sm font-semibold text-ink">{label}</div>
        <div className="text-xs text-muted">{description}</div>
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={on}
        aria-label={label}
        disabled={disabled}
        onClick={() => onChange(!on)}
        className={`relative h-6 w-11 flex-shrink-0 rounded-full transition-colors disabled:opacity-50 ${on ? "bg-navy" : "bg-neutral-300"}`}
      >
        <span className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-all ${on ? "left-[22px]" : "left-0.5"}`} />
      </button>
    </div>
  );
}
