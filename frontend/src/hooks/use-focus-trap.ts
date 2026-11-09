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

    // Satellites find their owner by id, so the container needs one. Assigned here rather than
    // required of every caller — a trapped surface should not have to know it might host a popover.
    if (!node.id) {
      node.id = `focus-trap-${Math.random().toString(36).slice(2, 10)}`;
    }

    /**
     * Focusables inside the container, plus any the container has portalled elsewhere in the DOM
     * (marked `data-trap-owner="<the container's id>"`).
     *
     * Without the second half, a portalled popover opened from inside a trapped surface — the
     * column-filter panel is the live case — is invisible to the trap: Tab from the last control
     * wraps back to the first instead of entering it, and `node.contains(activeEl)` is false the
     * moment the panel focuses itself, so the very next keystroke yanks focus back out. Keyboard
     * users could never reach the panel's OK button.
     */
    const focusable = () => {
      const roots: HTMLElement[] = [node];
      if (node.id) {
        roots.push(
          ...Array.from(document.querySelectorAll<HTMLElement>(`[data-trap-owner="${CSS.escape(node.id)}"]`)),
        );
      }
      return roots.flatMap((root) =>
        Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)).filter(
          (el) => el.offsetParent !== null || el === document.activeElement,
        ),
      );
    };

    /** Is `el` inside the container or one of its portalled satellites? */
    const owns = (el: Element | null) =>
      !!el && (node.contains(el) || (!!node.id && !!el.closest(`[data-trap-owner="${CSS.escape(node.id)}"]`)));

    // Move focus into the panel on open.
    (focusable()[0] ?? node).focus();

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== "Tab") return;
      // Listening on `document` rather than the container, so a Tab pressed inside a portalled
      // satellite is handled too — it never bubbles to the container, being no descendant of it.
      if (!owns(e.target as Element | null)) return;
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
        if (activeEl === first || !owns(activeEl)) {
          e.preventDefault();
          last.focus();
        }
      } else if (activeEl === last || !owns(activeEl)) {
        e.preventDefault();
        first.focus();
      }
    };

    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      // Restore focus to the trigger (best-effort).
      previouslyFocused?.focus?.();
    };
  }, [active]);

  return ref;
}
