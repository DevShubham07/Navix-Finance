"use client";

import { useEffect, useState } from "react";
import { usePathname } from "next/navigation";
import { featureFlagsApi } from "@/lib/api/applications";

/**
 * Demo host: on app.dhanboost.com the staff console hides how big the business is — dashboard and
 * analytics figures, DPD bucket totals, record counts — while every customer's own details stay
 * readable. Cosmetic only: the figures are still in the DOM and the API responses.
 *
 * What is hidden:
 *  - everything with a digit inside <main> on an ANALYTICS_ROUTES page (portals such as the records
 *    drawer render outside <main>, so drilling into a customer list stays readable);
 *  - anywhere else, only inside an element marked `data-redact` (bucket cards, pagination totals,
 *    sidebar badges).
 * Figures are flagged with an attribute React never manages, so re-renders cannot strip it; charts
 * are frosted whole, with their tooltips suppressed.
 *
 * Switch: the `redact-figures` row of the shared `feature_flag` table (no redeploy; SQL only, like
 * every flag). The backend and database are shared with the main site, but only this host ever
 * blurs. Fail-closed: a missing row or a failed read keeps the blur ON, and it is on from the first
 * paint, lifted only once the flag reads false — so real figures never flash on screen.
 *   update feature_flag set enabled = false where flag_key = 'redact-figures';   -- show figures
 *   insert into feature_flag (flag_key, enabled, description, created_at)
 *     values ('redact-figures', false, 'app.dhanboost.com figure blur', now())
 *     on conflict (flag_key) do update set enabled = excluded.enabled;            -- if no row yet
 */
export const REDACT_FLAG = "redact-figures";
export const BLUR_HOSTS = new Set(["app.dhanboost.com"]);
export const BLUR_ATTR = "data-num-blur";
export const CHART_ATTR = "data-redact-chart";

const ANALYTICS_ROUTES = ["/staff/dashboard", "/staff/performance", "/staff/admin/api-dashboard", "/staff/admin/expenses"];
const DIGIT = /\d/;
const SKIP = new Set(["SCRIPT", "STYLE", "NOSCRIPT", "TEXTAREA", "OPTION"]);

export const isAnalyticsRoute = (path: string) => ANALYTICS_ROUTES.some((r) => path === r || path.startsWith(`${r}/`));

function ownText(el: Element): string {
  let s = "";
  el.childNodes.forEach((c) => {
    if (c.nodeType === Node.TEXT_NODE) s += c.nodeValue;
  });
  return s;
}

function inScope(el: Element, wholeMain: boolean) {
  return !!el.closest("[data-redact]") || (wholeMain && !!el.closest("main"));
}

/** Re-decides one element. In an SVG the whole <text> is flagged, since a <tspan> can't take a filter. */
export function evaluate(el: Element | null, wholeMain: boolean) {
  if (!el || SKIP.has(el.tagName)) return;
  const chart = el.closest(".recharts-wrapper");
  if (chart && inScope(chart, wholeMain)) return void chart.setAttribute(CHART_ATTR, "");
  const svgText = el.closest("text");
  const target = svgText ?? el;
  const text = svgText ? svgText.textContent ?? "" : ownText(el);
  target.toggleAttribute(BLUR_ATTR, DIGIT.test(text) && inScope(target, wholeMain));
}

/** Flags every in-scope figure under `root`. */
export function scan(root: Node, wholeMain: boolean) {
  if (root.nodeType === Node.TEXT_NODE) return evaluate(root.parentElement, wholeMain);
  if (root instanceof Element && root.matches(".recharts-wrapper")) evaluate(root, wholeMain);
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  for (let n = walker.nextNode(); n; n = walker.nextNode()) evaluate(n.parentElement, wholeMain);
}

export function clear() {
  document.querySelectorAll(`[${BLUR_ATTR}],[${CHART_ATTR}]`).forEach((el) => {
    el.removeAttribute(BLUR_ATTR);
    el.removeAttribute(CHART_ATTR);
  });
}

export function NumberBlur() {
  const pathname = usePathname() ?? "";
  const wholeMain = isAnalyticsRoute(pathname);
  // Blur until the flag says otherwise; only an explicit `false` lifts it.
  const [flagOn, setFlagOn] = useState(true);

  useEffect(() => {
    if (!BLUR_HOSTS.has(window.location.hostname)) return;
    let live = true;
    featureFlagsApi
      .get()
      .then((flags) => {
        if (live) setFlagOn(flags?.[REDACT_FLAG] !== false);
      })
      .catch(() => {
        /* unreadable: stay blurred */
      });
    return () => {
      live = false;
    };
  }, []);

  useEffect(() => {
    if (!BLUR_HOSTS.has(window.location.hostname) || !flagOn) return;
    scan(document.body, wholeMain);
    const observer = new MutationObserver((mutations) => {
      for (const m of mutations) {
        if (m.type === "characterData") evaluate(m.target.parentElement, wholeMain);
        else {
          if (m.target instanceof Element) evaluate(m.target, wholeMain);
          m.addedNodes.forEach((n) => scan(n, wholeMain));
        }
      }
    });
    observer.observe(document.body, { childList: true, characterData: true, subtree: true });
    return () => {
      observer.disconnect();
      clear();
    };
  }, [wholeMain, flagOn]);

  // SVG text outside a chart wrapper takes a url() filter — CSS blur() on SVG elements is unreliable.
  return (
    <svg width="0" height="0" aria-hidden="true" style={{ position: "absolute" }}>
      <filter id="num-blur" x="-50%" y="-50%" width="200%" height="200%">
        <feGaussianBlur stdDeviation="6" />
      </filter>
    </svg>
  );
}
