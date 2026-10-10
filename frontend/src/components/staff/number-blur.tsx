"use client";

import { useEffect } from "react";

/**
 * Demo host: on app.dhanboost.com the staff console shows names and lists but blurs every figure —
 * amounts, counts, DPD buckets, mobiles, dates, chart labels. Cosmetic only: the numbers are still in
 * the DOM and the API responses, so this hides them from a screen, not from a determined viewer.
 *
 * Works by watching the DOM and flagging any element whose own text holds a digit with an attribute
 * React never manages (so re-renders cannot strip it). Portals (dialogs, the customer pop-up, chart
 * tooltips) render outside the shell, hence observing document.body rather than a container.
 */
export const BLUR_HOSTS = new Set(["app.dhanboost.com"]);
export const BLUR_ATTR = "data-num-blur";

const DIGIT = /\d/;
const SKIP = new Set(["SCRIPT", "STYLE", "NOSCRIPT", "TEXTAREA"]);

function ownText(el: Element): string {
  let s = "";
  el.childNodes.forEach((c) => {
    if (c.nodeType === Node.TEXT_NODE) s += c.nodeValue;
  });
  return s;
}

/** Re-decides one element. In an SVG the whole <text> is flagged, since a <tspan> can't take a filter. */
export function evaluate(el: Element | null) {
  if (!el || SKIP.has(el.tagName)) return;
  const svgText = el.closest("text");
  const target = svgText ?? el;
  target.toggleAttribute(BLUR_ATTR, DIGIT.test(svgText ? svgText.textContent ?? "" : ownText(el)));
}

/** Flags every digit-bearing element under `root`. */
export function scan(root: Node) {
  if (root.nodeType === Node.TEXT_NODE) return evaluate(root.parentElement);
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  for (let n = walker.nextNode(); n; n = walker.nextNode()) evaluate(n.parentElement);
}

export function NumberBlur() {
  useEffect(() => {
    if (!BLUR_HOSTS.has(window.location.hostname)) return;
    scan(document.body);
    const observer = new MutationObserver((mutations) => {
      for (const m of mutations) {
        if (m.type === "characterData") evaluate(m.target.parentElement);
        else {
          if (m.target instanceof Element) evaluate(m.target);
          m.addedNodes.forEach(scan);
        }
      }
    });
    observer.observe(document.body, { childList: true, characterData: true, subtree: true });
    return () => {
      observer.disconnect();
      document.querySelectorAll(`[${BLUR_ATTR}]`).forEach((el) => el.removeAttribute(BLUR_ATTR));
    };
  }, []);

  // SVG text needs a url() filter — CSS blur() on SVG elements is not reliable across browsers.
  return (
    <svg width="0" height="0" aria-hidden="true" style={{ position: "absolute" }}>
      <filter id="num-blur" x="-50%" y="-50%" width="200%" height="200%">
        <feGaussianBlur stdDeviation="4" />
      </filter>
    </svg>
  );
}
