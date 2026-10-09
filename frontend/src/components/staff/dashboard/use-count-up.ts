"use client";

import * as React from "react";

export function prefersReducedMotion(): boolean {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") return false;
  try {
    return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  } catch {
    return false;
  }
}

/**
 * Animates a number from its previous value (0 on first mount) to `target` over `ms`. Skipped
 * entirely under `prefers-reduced-motion`. The caller must still expose the FINAL value to
 * assistive tech (aria-label) — the animated text is decoration.
 */
export function useCountUp(target: number, ms = 600): number {
  const [value, setValue] = React.useState(() => (prefersReducedMotion() ? target : 0));
  const fromRef = React.useRef(0);
  const shownRef = React.useRef(value);
  shownRef.current = value;

  React.useEffect(() => {
    if (prefersReducedMotion() || ms <= 0) {
      setValue(target);
      return;
    }
    const from = shownRef.current;
    fromRef.current = from;
    if (from === target) return;
    let raf = 0;
    const t0 = performance.now();
    const tick = (now: number) => {
      const p = Math.min(1, (now - t0) / ms);
      const eased = 1 - Math.pow(1 - p, 3);
      setValue(from + (target - from) * eased);
      if (p < 1) raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [target, ms]);

  return value;
}
