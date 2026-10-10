"use client";

import * as React from "react";
import { Check, ChevronDown } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * <Dropdown> — the kit's listbox popover. A pill trigger opens an animated white menu; full
 * keyboard support (↑/↓/Home/End to move, Enter/Space to pick, Esc/Tab to close), closes on an
 * outside click, and announces itself as a listbox. Used by <PillSelect>, and directly wherever a
 * page needs a styled single-choice menu instead of the OS-native <select> popup.
 */
export interface DropdownOption<T extends string> {
  value: T;
  label: string;
  hint?: string;
}

export function Dropdown<T extends string>({
  value,
  onChange,
  options,
  label,
  align = "end",
  className,
  triggerClassName,
}: {
  value: T;
  onChange: (v: T) => void;
  options: readonly DropdownOption<T>[];
  /** Accessible name (visually hidden). */
  label: string;
  align?: "start" | "end";
  className?: string;
  triggerClassName?: string;
}) {
  const [open, setOpen] = React.useState(false);
  const [active, setActive] = React.useState(0);
  const wrapRef = React.useRef<HTMLDivElement>(null);
  const triggerRef = React.useRef<HTMLButtonElement>(null);
  const listRef = React.useRef<HTMLUListElement>(null);
  const id = React.useId();
  const selectedIndex = Math.max(0, options.findIndex((o) => o.value === value));
  const current = options[selectedIndex];

  React.useEffect(() => {
    if (!open) return;
    setActive(selectedIndex);
    listRef.current?.focus();
    const onDown = (e: PointerEvent) => {
      if (!wrapRef.current?.contains(e.target as Node)) setOpen(false);
    };
    window.addEventListener("pointerdown", onDown);
    return () => window.removeEventListener("pointerdown", onDown);
  }, [open, selectedIndex]);

  const pick = (i: number) => {
    const o = options[i];
    if (o) onChange(o.value);
    setOpen(false);
    triggerRef.current?.focus();
  };

  const onListKey = (e: React.KeyboardEvent) => {
    const last = options.length - 1;
    if (e.key === "ArrowDown") { e.preventDefault(); setActive((a) => Math.min(last, a + 1)); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setActive((a) => Math.max(0, a - 1)); }
    else if (e.key === "Home") { e.preventDefault(); setActive(0); }
    else if (e.key === "End") { e.preventDefault(); setActive(last); }
    else if (e.key === "Enter" || e.key === " ") { e.preventDefault(); pick(active); }
    else if (e.key === "Escape") { e.preventDefault(); setOpen(false); triggerRef.current?.focus(); }
    else if (e.key === "Tab") setOpen(false);
  };

  return (
    <div ref={wrapRef} className={cn("relative inline-flex", className)}>
      <button
        ref={triggerRef}
        type="button"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={`${id}-list`}
        aria-label={`${label}: ${current?.label ?? ""}`}
        onClick={() => setOpen((o) => !o)}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown" || e.key === "ArrowUp") { e.preventDefault(); setOpen(true); }
        }}
        className={cn(
          "inline-flex h-[38px] items-center gap-2 rounded-full border border-line bg-paper pl-4 pr-3 text-[0.72rem] font-medium text-ink shadow-pill",
          "transition-[box-shadow,transform] duration-200 hover:-translate-y-px hover:shadow-base",
          open && "shadow-base",
          triggerClassName,
        )}
      >
        {current?.label}
        <ChevronDown aria-hidden size={14} className={cn("transition-transform duration-300 ease-out-expo", open && "rotate-180")} />
      </button>

      <ul
        ref={listRef}
        id={`${id}-list`}
        role="listbox"
        tabIndex={-1}
        aria-label={label}
        aria-activedescendant={open ? `${id}-opt-${active}` : undefined}
        onKeyDown={onListKey}
        className={cn(
          "absolute top-[calc(100%+8px)] z-50 m-0 min-w-[10rem] list-none rounded-2xl border border-line bg-paper p-1.5 shadow-lg outline-none",
          align === "end" ? "right-0 origin-top-right" : "left-0 origin-top-left",
          "transition-[opacity,transform] duration-200 ease-out-expo",
          open ? "visible scale-100 opacity-100" : "invisible pointer-events-none scale-95 opacity-0",
        )}
      >
        {options.map((o, i) => {
          const selected = o.value === value;
          return (
            <li
              key={o.value}
              id={`${id}-opt-${i}`}
              role="option"
              aria-selected={selected}
              onPointerEnter={() => setActive(i)}
              onClick={() => pick(i)}
              className={cn(
                "flex cursor-pointer items-center justify-between gap-3 rounded-xl px-3 py-2 text-[0.72rem] transition-colors",
                i === active ? "bg-grey-100 text-ink" : "text-slate",
                selected && "font-semibold text-ink",
              )}
            >
              <span>
                {o.label}
                {o.hint && <span className="block text-[10px] font-normal text-muted">{o.hint}</span>}
              </span>
              {selected && <Check size={14} aria-hidden />}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
