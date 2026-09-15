"use client";

import * as React from "react";
import { createPortal } from "react-dom";
import { Info } from "lucide-react";
import { cn } from "@/lib/utils";

export interface InfoTooltipProps {
  /** Short explanation shown in the popover. */
  content: React.ReactNode;
  /** Extra classes for the trigger wrapper. */
  className?: string;
  /** Accessible label for the trigger button. */
  label?: string;
  /** Icon size in px. */
  size?: number;
}

const POPOVER_MAX_WIDTH = 256; // 16rem, matches max-w-[16rem] below
const VIEWPORT_MARGIN = 8;

/**
 * A small ⓘ info trigger that reveals a short explanation on hover or click.
 *
 * The popover is portalled to `document.body` and positioned from a measured snapshot of the
 * trigger's rect, rather than living in normal flow next to the icon. It used to be a plain
 * `absolute right-0` child of the trigger — which grows LEFTWARD from the icon, so the first card
 * in a horizontally-scrollable strip (`PipelineBar`'s "Started" stage) or the first filterable
 * column in `.staff-table-scroll` clipped the popover's left portion against the scroll
 * container's edge: the text rendered, just with its opening words cut off. A portal escapes
 * every such ancestor; the position is then clamped to the viewport on both axes so it can no
 * longer run off-screen there either.
 */
export function InfoTooltip({ content, className, label = "More information", size = 14 }: InfoTooltipProps) {
  const [open, setOpen] = React.useState(false);
  const triggerRef = React.useRef<HTMLSpanElement | null>(null);
  const popoverRef = React.useRef<HTMLSpanElement | null>(null);
  const [style, setStyle] = React.useState<React.CSSProperties | null>(null);
  const closeTimer = React.useRef<ReturnType<typeof setTimeout> | null>(null);

  const cancelClose = () => {
    if (closeTimer.current) {
      clearTimeout(closeTimer.current);
      closeTimer.current = null;
    }
  };
  // A short grace period, not an instant close: the portal breaks DOM containment between the
  // trigger and the popover, so moving the mouse from one to the other would otherwise register as
  // leaving both and close the tooltip before the cursor arrives.
  const scheduleClose = () => {
    cancelClose();
    closeTimer.current = setTimeout(() => setOpen(false), 120);
  };

  React.useEffect(() => () => cancelClose(), []);

  // Measure once on open (visibility stays hidden until then, so nothing flashes at the wrong
  // position or width), the same two-phase measure-then-place dance the column filter panel uses.
  React.useLayoutEffect(() => {
    if (!open) {
      setStyle(null);
      return;
    }
    const anchor = triggerRef.current?.getBoundingClientRect();
    const pop = popoverRef.current;
    if (!anchor || !pop) return;

    const width = Math.min(pop.offsetWidth, POPOVER_MAX_WIDTH);
    const vw = window.innerWidth;
    const vh = window.innerHeight;

    // Default: right edge flush with the trigger's right edge (the original `right-0` look).
    // Flip to left-flush when that would run past the viewport's left edge.
    let left = anchor.right - width;
    if (left < VIEWPORT_MARGIN) left = anchor.left;
    left = Math.max(VIEWPORT_MARGIN, Math.min(left, vw - width - VIEWPORT_MARGIN));

    let top = anchor.bottom + 6;
    if (top + pop.offsetHeight > vh - VIEWPORT_MARGIN) {
      const above = anchor.top - 6 - pop.offsetHeight;
      top = above >= VIEWPORT_MARGIN ? above : Math.max(VIEWPORT_MARGIN, vh - VIEWPORT_MARGIN - pop.offsetHeight);
    }

    setStyle({ position: "fixed", top, left, zIndex: 60, visibility: "visible" });
  }, [open, content]);

  React.useEffect(() => {
    if (!open) return;
    const inside = (target: EventTarget | null) =>
      (target instanceof Node)
      && ((triggerRef.current?.contains(target) ?? false) || (popoverRef.current?.contains(target) ?? false));
    const onDoc = (e: MouseEvent) => {
      if (!inside(e.target)) setOpen(false);
    };
    const onEsc = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    const onDismiss = () => setOpen(false);
    document.addEventListener("mousedown", onDoc);
    document.addEventListener("keydown", onEsc);
    // The popover is a fixed-position snapshot of the trigger's rect, not a live-tracked one — a
    // scroll or resize invalidates it, so close rather than leave it pointing at the wrong spot.
    window.addEventListener("scroll", onDismiss, true);
    window.addEventListener("resize", onDismiss);
    return () => {
      document.removeEventListener("mousedown", onDoc);
      document.removeEventListener("keydown", onEsc);
      window.removeEventListener("scroll", onDismiss, true);
      window.removeEventListener("resize", onDismiss);
    };
  }, [open]);

  return (
    <span
      ref={triggerRef}
      className={cn("relative inline-flex", className)}
      onMouseEnter={() => { cancelClose(); setOpen(true); }}
      onMouseLeave={scheduleClose}
    >
      <button
        type="button"
        aria-label={label}
        aria-expanded={open}
        onClick={(e) => {
          e.preventDefault();
          e.stopPropagation();
          setOpen((o) => !o);
        }}
        className="inline-flex items-center justify-center rounded-full text-muted transition-colors hover:text-navy focus:outline-none focus-visible:text-navy"
      >
        <Info size={size} strokeWidth={2.25} />
      </button>
      {open
        && createPortal(
          <span
            ref={popoverRef}
            role="tooltip"
            onMouseEnter={cancelClose}
            onMouseLeave={scheduleClose}
            style={style ?? { position: "fixed", top: -9999, left: -9999, visibility: "hidden" }}
            className="w-max max-w-[16rem] rounded border border-line bg-white p-2.5 text-left text-xs font-normal leading-snug text-ink shadow-md"
          >
            {content}
          </span>,
          document.body,
        )}
    </span>
  );
}
