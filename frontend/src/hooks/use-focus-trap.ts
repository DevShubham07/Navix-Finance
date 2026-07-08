"use client";

import * as React from "react";

/** Elements that can receive keyboard focus inside a trapped container. */
const FOCUSABLE_SELECTOR = [
  "a[href]",
  "button:not([disabled])",
  "textarea:not([disabled])",
  "input:not([disabled])",
  "select:not([disabled])",
  '[tabindex]:not([tabindex="-1"])',
].join(",");

/**
 * Trap keyboard focus inside a container while `active`, and restore focus to
 * the previously-focused element when the trap deactivates.
 *
 * Attach the returned ref to the container (which should carry `tabIndex={-1}`
 * so it can hold focus when it has no focusable children). While active:
 * - focus is moved to the first focusable descendant (or the container),
 * - Tab / Shift+Tab wrap within the container,
 * - on cleanup, focus returns to whatever was focused before activation.
 *
 * Built for the {@link Drawer} primitive (which, unlike Dialog, needs a trap);
 * reusable by any modal surface.
 */
export function useFocusTrap<T extends HTMLElement = HTMLElement>(
  active: boolean,
): React.RefObject<T | null> {
  const ref = React.useRef<T>(null);

  React.useEffect(() => {
    if (!active) return;
    const node = ref.current;
    if (!node) return;

    const previouslyFocused = document.activeElement as HTMLElement | null;

    const focusable = () =>
      Array.from(node.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)).filter(
        (el) => el.offsetParent !== null || el === document.activeElement,
      );

    // Move focus into the panel on open.
    (focusable()[0] ?? node).focus();

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== "Tab") return;
      const els = focusable();
      if (els.length === 0) {
        // Nothing focusable — keep focus on the container itself.
        e.preventDefault();
        node.focus();
        return;
      }
      const first = els[0];
      const last = els[els.length - 1];
      const activeEl = document.activeElement;
      if (e.shiftKey) {
        if (activeEl === first || !node.contains(activeEl)) {
          e.preventDefault();
          last.focus();
        }
      } else if (activeEl === last || !node.contains(activeEl)) {
        e.preventDefault();
        first.focus();
      }
    };

    node.addEventListener("keydown", onKeyDown);
    return () => {
      node.removeEventListener("keydown", onKeyDown);
      // Restore focus to the trigger (best-effort).
      previouslyFocused?.focus?.();
    };
  }, [active]);

  return ref;
}
