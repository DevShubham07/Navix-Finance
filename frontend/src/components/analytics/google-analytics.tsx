"use client";

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
 * ⚠ PII: this is mounted in the ROOT layout, so gtag also fires on authenticated
 * borrower/staff surfaces whose URLs/titles can carry PII (customer/loan ids). If
 * that becomes a compliance concern, move `<GoogleAnalytics />` out of the root
 * layout and into `app/(marketing)/layout.tsx` to scope it to public pages only.
 */
export function GoogleAnalytics() {
  const gaId = process.env.NEXT_PUBLIC_GA_ID;
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
