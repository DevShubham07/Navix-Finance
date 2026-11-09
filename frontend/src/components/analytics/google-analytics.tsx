"use client";

import { useEffect } from "react";
import Script from "next/script";

/**
 * Google Analytics 4 (gtag.js).
 *
 * Renders nothing unless `NEXT_PUBLIC_GA_ID` is set, so analytics stays OFF in
 * any environment (local dev / preview) that doesn't explicitly configure it —
 * the same env-gated pattern as `GOOGLE_SITE_VERIFICATION`. The measurement id
 * is NOT a secret; it's env-driven only to match the codebase convention and to
 * keep it out of environments that shouldn't report traffic.
 *
 * Loaded via `next/script` (afterInteractive) rather than a raw <script>, so it
 * fires after hydration and doesn't block first paint. It works under the app's
 * CSP because `script-src` allows `'unsafe-inline'` (the gtag bootstrap) and the
 * googletagmanager.com origin (the gtag/js loader) — see `next.config.mjs`.
 *
 * ⚠ PII: this is mounted only in `app/(marketing)/layout.tsx`, never the root layout, because
 * invite and reset links carry tokens in the query string and authenticated borrower/staff URLs
 * can carry PII (customer/loan ids); gtag must not see those pages.
 */
export function GoogleAnalytics() {
  const gaId = process.env.NEXT_PUBLIC_GA_ID;

  // next/script stays loaded across client-side navigation, so switch collection off when the
  // visitor leaves the marketing layout for login, signup or the app.
  useEffect(() => {
    if (!gaId) return;
    const w = window as unknown as Record<string, boolean>;
    w[`ga-disable-${gaId}`] = false;
    return () => {
      w[`ga-disable-${gaId}`] = true;
    };
  }, [gaId]);

  if (!gaId) return null;

  return (
    <>
      <Script
        src={`https://www.googletagmanager.com/gtag/js?id=${gaId}`}
        strategy="afterInteractive"
      />
      <Script id="gtag-init" strategy="afterInteractive">
        {`
          window.dataLayer = window.dataLayer || [];
          function gtag(){dataLayer.push(arguments);}
          gtag('js', new Date());
          gtag('config', '${gaId}');
        `}
      </Script>
    </>
  );
}
