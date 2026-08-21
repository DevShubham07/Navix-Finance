"use client";

import * as React from "react";
import { AlertCircle, ChevronDown } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * Combobox — a type-to-filter dropdown, styled to the same `.field` contract as `Input`/`Select`
 * so it drops into existing forms without new CSS. Domain-agnostic (`options: string[]`) — no
 * bank-specific knowledge lives here.
 *
 * Replaces a browser-native `<input list> + <datalist>` (OS-rendered, unstyleable, inconsistent
 * across browsers) with a fully app-styled, keyboard-navigable panel.
 *
 * `value` mirrors the raw typed text at all times (like a plain `Input`) — the dropdown is a
 * picking convenience layered on top, not a gate. Choosing a suggestion just overwrites the text;
 * free typing is a valid, live value on every keystroke, exactly like the `Input`/`<datalist>`
 * combo this replaces. That keeps callers' existing "is this non-empty" validation working
 * unchanged and avoids any commit-on-blur/revert-on-outside-click raciness.
 */
export interface ComboboxProps {
  label?: string;
  required?: boolean;
  error?: string;
  helperText?: string;
  options: string[];
  /** The current text — a picked option OR free-typed text. */
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  leftIcon?: React.ReactNode;
  /** When true (default), typed text with no exact match shows a reassuring "Use '<text>'" row. */
  allowCustom?: boolean;
  /** Wrapper className (the `.field`). */
  className?: string;
  name?: string;
}

export function Combobox({
  label,
  required,
  error,
  helperText,
  options,
  value,
  onChange,
  placeholder,
  leftIcon,
  allowCustom = true,
  className,
  name,
}: ComboboxProps) {
  const autoId = React.useId();
  const inputId = `combobox-${autoId}`;
  const listId = `${inputId}-listbox`;

  const [open, setOpen] = React.useState(false);
  const [activeIndex, setActiveIndex] = React.useState(-1);
  const rootRef = React.useRef<HTMLDivElement>(null);

  const filtered = React.useMemo(() => {
    const q = value.trim().toLowerCase();
    if (!q) return options;
    return options.filter((o) => o.toLowerCase().includes(q));
  }, [options, value]);

  const exactMatch = React.useMemo(
    () => options.some((o) => o.toLowerCase() === value.trim().toLowerCase()),
    [options, value],
  );
  const showCustomRow = allowCustom && value.trim().length > 0 && !exactMatch;
  const rowCount = filtered.length + (showCustomRow ? 1 : 0);

  const closeList = () => {
    setOpen(false);
    setActiveIndex(-1);
  };

  const selectOption = (option: string) => {
    onChange(option);
    closeList();
  };

  const selectActive = () => {
    if (activeIndex >= 0 && activeIndex < filtered.length) {
      selectOption(filtered[activeIndex]);
    } else {
      // Enter with nothing highlighted (or on the custom row) just accepts the typed text, which
      // is already the live value — simply close the panel.
      closeList();
    }
  };

  React.useEffect(() => {
    function onDocMouseDown(e: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        closeList();
      }
    }
    document.addEventListener("mousedown", onDocMouseDown);
    return () => document.removeEventListener("mousedown", onDocMouseDown);
  }, []);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      if (!open) {
        setOpen(true);
        return;
      }
      setActiveIndex((i) => (i + 1 >= rowCount ? 0 : i + 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      if (!open) {
        setOpen(true);
        return;
      }
      setActiveIndex((i) => (i - 1 < 0 ? rowCount - 1 : i - 1));
    } else if (e.key === "Enter") {
      if (open) {
        e.preventDefault();
        selectActive();
      }
    } else if (e.key === "Escape") {
      if (open) {
        e.preventDefault();
        closeList();
      }
    }
  };

  const activeId =
    activeIndex >= 0 && activeIndex < filtered.length
      ? `${listId}-opt-${activeIndex}`
      : activeIndex === filtered.length && showCustomRow
        ? `${listId}-custom`
        : undefined;

  return (
    <div ref={rootRef} className={cn("field", error && "invalid", className)}>
      {label && (
        <label htmlFor={inputId}>
          {label}
          {required ? <span className="req"> *</span> : null}
        </label>
      )}
      {error ? (
        <p
          className="err"
          role="alert"
          style={{ display: "flex", alignItems: "center", gap: 6, marginTop: 0, marginBottom: 7 }}
        >
          <AlertCircle size={14} style={{ flexShrink: 0 }} aria-hidden="true" />
          <span>{error}</span>
        </p>
      ) : null}
      <div style={{ position: "relative" }}>
        {leftIcon && (
          <span style={{ position: "absolute", left: 12, top: "50%", transform: "translateY(-50%)", color: "var(--muted)" }}>
            {leftIcon}
          </span>
        )}
        <input
          id={inputId}
          name={name}
          type="text"
          role="combobox"
          aria-expanded={open}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={activeId}
          aria-invalid={!!error}
          autoComplete="off"
          value={value}
          placeholder={placeholder}
          onChange={(e) => {
            onChange(e.target.value);
            setOpen(true);
            setActiveIndex(-1);
          }}
          onFocus={() => setOpen(true)}
          onKeyDown={handleKeyDown}
          style={{
            ...(leftIcon ? { paddingLeft: "2.5rem" } : null),
            paddingRight: "2.5rem",
          }}
        />
        <span style={{ position: "absolute", right: 12, top: "50%", transform: "translateY(-50%)", color: "var(--muted)", pointerEvents: "none" }}>
          <ChevronDown size={16} />
        </span>

        {open && (filtered.length > 0 || showCustomRow) && (
          <ul
            id={listId}
            role="listbox"
            className="absolute z-20 mt-1 max-h-60 w-full overflow-y-auto rounded border border-line bg-white py-1 shadow-lg"
          >
            {filtered.map((option, i) => (
              <li
                key={option}
                id={`${listId}-opt-${i}`}
                role="option"
                aria-selected={i === activeIndex}
                // mousedown (not click), with preventDefault, so the input never blurs — a click
                // elsewhere (outside the panel) is what closes it, handled separately above.
                onMouseDown={(e) => {
                  e.preventDefault();
                  selectOption(option);
                }}
                onMouseEnter={() => setActiveIndex(i)}
                className={cn(
                  "cursor-pointer px-3 py-2 text-sm text-ink",
                  i === activeIndex ? "bg-navy-tint text-navy" : "hover:bg-navy-tint",
                )}
              >
                {option}
              </li>
            ))}
            {showCustomRow && (
              <li
                id={`${listId}-custom`}
                role="option"
                aria-selected={activeIndex === filtered.length}
                onMouseDown={(e) => {
                  e.preventDefault();
                  closeList();
                }}
                onMouseEnter={() => setActiveIndex(filtered.length)}
                className={cn(
                  "cursor-pointer border-t border-line px-3 py-2 text-sm font-medium text-navy",
                  activeIndex === filtered.length ? "bg-navy-tint" : "hover:bg-navy-tint",
                )}
              >
                Use &quot;{value.trim()}&quot;
              </li>
            )}
          </ul>
        )}
      </div>
      {!error && helperText ? <p className="hint">{helperText}</p> : null}
    </div>
  );
}
