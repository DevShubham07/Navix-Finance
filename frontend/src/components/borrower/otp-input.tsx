"use client";

import * as React from "react";

/**
 * Six-box one-time-code input. Auto-advances on entry, supports paste and
 * backspace, and reports the joined value up.
 */
export interface OtpInputProps {
  length?: number;
  value: string;
  onChange: (value: string) => void;
  onComplete?: (value: string) => void;
  disabled?: boolean;
}

export function OtpInput({ length = 6, value, onChange, onComplete, disabled }: OtpInputProps) {
  const refs = React.useRef<Array<HTMLInputElement | null>>([]);
  const chars = React.useMemo(() => value.padEnd(length, " ").slice(0, length).split(""), [value, length]);

  const set = (next: string) => {
    const clean = next.replace(/\D/g, "").slice(0, length);
    onChange(clean);
    if (clean.length === length) onComplete?.(clean);
  };

  const handleChange = (idx: number, raw: string) => {
    const digit = raw.replace(/\D/g, "").slice(-1);
    const arr = value.split("");
    arr[idx] = digit ?? "";
    set(arr.join(""));
    if (digit && idx < length - 1) refs.current[idx + 1]?.focus();
  };

  const handleKey = (idx: number, e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Backspace" && !value[idx] && idx > 0) refs.current[idx - 1]?.focus();
  };

  const handlePaste = (e: React.ClipboardEvent) => {
    e.preventDefault();
    set(e.clipboardData.getData("text"));
    refs.current[Math.min(length - 1, value.length)]?.focus();
  };

  return (
    <div className="flex gap-2 sm:gap-3" onPaste={handlePaste}>
      {Array.from({ length }).map((_, i) => (
        <input
          key={i}
          ref={(el) => {
            refs.current[i] = el;
          }}
          inputMode="numeric"
          autoComplete={i === 0 ? "one-time-code" : "off"}
          maxLength={1}
          disabled={disabled}
          aria-label={`Digit ${i + 1}`}
          value={chars[i]?.trim() ?? ""}
          onChange={(e) => handleChange(i, e.target.value)}
          onKeyDown={(e) => handleKey(i, e)}
          className="h-14 w-full rounded border border-line bg-white text-center font-serif text-2xl font-semibold text-navy outline-none transition focus:border-navy focus:shadow-focus disabled:opacity-50"
        />
      ))}
    </div>
  );
}
