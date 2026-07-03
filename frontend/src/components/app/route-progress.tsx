"use client";

import * as React from "react";
import { usePathname, useSearchParams } from "next/navigation";

/**
 * RouteProgress — a slim top loading bar shown during client-side route
 * transitions (the "GitHub / YouTube" top loader). Dependency-free.
 *
 * It is **fully imperative** — the bar is animated by mutating the DOM through
 * refs, and the component holds *no* React state. That is deliberate: Next.js
 * performs the history update for a navigation inside a `useInsertionEffect`
 * (React's commit phase), and scheduling a React state update from there throws
 * "useInsertionEffect must not schedule updates". Touching the DOM directly is
 * safe in that phase.
 *
 * - **Start** — on an internal `<a>` click (fires immediately, before the route
 *   commits — so the bar is visible while the next page loads), and on
 *   programmatic / back-forward navigations via `history.pushState`/
 *   `replaceState` / `popstate`.
 * - **Finish** — when the committed pathname/search actually changes. A safety
 *   timer resolves the rare case where a navigation never commits.
 */
function RouteProgressBar() {
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const routeKey = `${pathname}?${searchParams.toString()}`;

  const wrapRef = React.useRef<HTMLDivElement | null>(null);
  const barRef = React.useRef<HTMLDivElement | null>(null);
  const value = React.useRef(0);
  const running = React.useRef(false);
  const creep = React.useRef<ReturnType<typeof setInterval> | null>(null);
  const tail = React.useRef<ReturnType<typeof setTimeout> | null>(null);
  const safety = React.useRef<ReturnType<typeof setTimeout> | null>(null);
  const firstRender = React.useRef(true);

  const paint = React.useCallback(() => {
    if (barRef.current) barRef.current.style.width = `${value.current}%`;
  }, []);

  const clearTimers = React.useCallback(() => {
    if (creep.current) { clearInterval(creep.current); creep.current = null; }
    if (tail.current) { clearTimeout(tail.current); tail.current = null; }
    if (safety.current) { clearTimeout(safety.current); safety.current = null; }
  }, []);

  const done = React.useCallback(() => {
    if (!running.current) return;
    running.current = false;
    clearTimers();
    const bar = barRef.current;
    const wrap = wrapRef.current;
    value.current = 100;
    if (bar) bar.style.transition = "width 200ms ease";
    paint();
    // Snap to 100%, fade out, then silently reset to 0 for the next run.
    tail.current = setTimeout(() => {
      if (wrap) wrap.style.opacity = "0";
      tail.current = setTimeout(() => {
        value.current = 0;
        if (bar) { bar.style.transition = "none"; bar.style.width = "0%"; }
      }, 240);
    }, 180);
  }, [clearTimers, paint]);

  const start = React.useCallback(() => {
    if (running.current) return; // already showing — let it keep creeping
    clearTimers();
    running.current = true;
    const bar = barRef.current;
    const wrap = wrapRef.current;
    value.current = 12;
    if (wrap) wrap.style.opacity = "1";
    if (bar) bar.style.transition = "width 200ms ease";
    paint();
    // Creep toward ~90% so the bar always feels alive but never completes early.
    creep.current = setInterval(() => {
      value.current = value.current >= 90 ? value.current : value.current + Math.max(0.8, (90 - value.current) * 0.1);
      paint();
    }, 200);
    // Never leave the bar hanging if the navigation is cancelled / never commits.
    safety.current = setTimeout(done, 10000);
  }, [clearTimers, done, paint]);

  React.useEffect(() => {
    const samePage = (href: string) => {
      try {
        const url = new URL(href, window.location.href);
        const here = window.location.pathname + window.location.search;
        return url.origin === window.location.origin && url.pathname + url.search === here;
      } catch {
        return true; // unparseable → treat as "no navigation" and skip
      }
    };

    // Early start: internal left-clicks on a real link, before Next commits.
    const onClick = (e: MouseEvent) => {
      if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
      const a = (e.target as Element | null)?.closest?.("a");
      if (!a) return;
      const href = a.getAttribute("href");
      const target = a.getAttribute("target");
      if (!href || href.startsWith("#") || a.hasAttribute("download") || (target && target !== "_self")) return;
      let url: URL;
      try { url = new URL(href, window.location.href); } catch { return; }
      if (url.origin !== window.location.origin || samePage(url.href)) return;
      start();
    };

    // Programmatic navigation: Next updates the URL via the History API.
    const patch = (key: "pushState" | "replaceState") => {
      const original = window.history[key];
      const wrapped = function (this: History, ...args: Parameters<typeof original>) {
        try {
          const url = args[2];
          if (url != null && !samePage(String(url))) start();
        } catch {
          /* ignore malformed URLs */
        }
        return original.apply(this, args);
      } as typeof original;
      window.history[key] = wrapped;
      return () => { window.history[key] = original; };
    };

    document.addEventListener("click", onClick, true);
    const undoPush = patch("pushState");
    const undoReplace = patch("replaceState");
    const onPop = () => start();
    window.addEventListener("popstate", onPop);
    return () => {
      document.removeEventListener("click", onClick, true);
      undoPush();
      undoReplace();
      window.removeEventListener("popstate", onPop);
      clearTimers();
    };
  }, [start, clearTimers]);

  // Finish once the committed route changes (skip the initial mount).
  React.useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false;
      return;
    }
    done();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [routeKey]);

  return (
    <div
      ref={wrapRef}
      aria-hidden="true"
      style={{
        position: "fixed",
        top: 0,
        left: 0,
        width: "100%",
        height: 3,
        zIndex: 200,
        pointerEvents: "none",
        opacity: 0,
        transition: "opacity 240ms ease 80ms",
      }}
    >
      <div
        ref={barRef}
        style={{
          height: "100%",
          width: "0%",
          background: "linear-gradient(90deg, var(--gold-600, #0E8557), var(--gold-400, #3FBF89))",
          boxShadow: "0 0 12px rgba(63,191,137,.55)",
          borderRadius: "0 3px 3px 0",
        }}
      />
    </div>
  );
}

/**
 * `useSearchParams` must sit under a Suspense boundary (Next.js requirement);
 * keeping the boundary here means the root layout can mount `<RouteProgress />`
 * directly without opting the whole tree into client rendering.
 */
export function RouteProgress() {
  return (
    <React.Suspense fallback={null}>
      <RouteProgressBar />
    </React.Suspense>
  );
}
