/** @type {import('next').NextConfig} */

// Content-Security-Policy. External resource origins are: (1) Google Analytics 4 (gtag.js) — loader
// from googletagmanager.com, beacons to *.google-analytics.com (region-redirected `/g/collect`) +
// *.analytics.google.com; (2) Amazon S3 (ap-south-1) — the browser uploads (PUT) and views (GET) KYC
// documents / selfies / receipts / payment assets DIRECTLY via short-lived presigned URLs, never
// through the BFF, so the S3 host must be in connect-src (fetch PUT) + img-src (rendered assets).
// Everything else is same-origin (next/font self-hosts; the BFF is same-origin /api). `'unsafe-inline'`
// is required for both script (Next's inline hydration bootstrap + the gtag config snippet) and style
// (200+ inline style= attrs in the design-export marketing HTML). `data:`/`blob:` cover the KYC selfie
// capture (canvas → data URL, getUserMedia stream) and client-side jsPDF exports. `'unsafe-eval'` is
// added in DEV only (React Refresh/HMR). A nonce-based script-src is the future hardening.
const isDev = process.env.NODE_ENV !== "production";
// Google Analytics origins, split by directive.
const gaScript = "https://www.googletagmanager.com";
const gaConnect =
  "https://www.google-analytics.com https://*.google-analytics.com https://*.analytics.google.com https://www.googletagmanager.com";
const gaImg =
  "https://www.googletagmanager.com https://www.google-analytics.com https://*.google-analytics.com";
// S3 (ap-south-1) — presigned direct browser<->S3 upload/view. Region-scoped: virtual-hosted
// (bucket.s3.…) covers any bucket/env; path-style (s3.…) is a fallback for that URL form.
const s3 = "https://*.s3.ap-south-1.amazonaws.com https://s3.ap-south-1.amazonaws.com";
const csp = [
  "default-src 'self'",
  `script-src 'self' 'unsafe-inline' ${gaScript}${isDev ? " 'unsafe-eval'" : ""}`,
  "style-src 'self' 'unsafe-inline'",
  `img-src 'self' data: blob: ${gaImg} ${s3}`,
  "media-src 'self' blob:",
  "font-src 'self'",
  `connect-src 'self' ${gaConnect} ${s3}`,
  "worker-src 'self' blob:",
  "frame-src 'self'",
  "frame-ancestors 'self'",
  "form-action 'self'",
  "base-uri 'self'",
  "object-src 'none'",
].join("; ");

const nextConfig = {
  reactStrictMode: true,
  // Baseline security headers (applied to all routes). HSTS ships with includeSubDomains but
  // WITHOUT `preload`: preload (once submitted to hstspreload.org) forces every subdomain to
  // HTTPS near-permanently — add it only after auditing subdomains.
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "Content-Security-Policy", value: csp },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "X-Frame-Options", value: "SAMEORIGIN" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          // camera=(self): the KYC selfie step uses getUserMedia; keep it same-origin-only.
          { key: "Permissions-Policy", value: "camera=(self), microphone=(), geolocation=()" },
          { key: "Strict-Transport-Security", value: "max-age=63072000; includeSubDomains" },
        ],
      },
    ];
  },
  // typedRoutes intentionally disabled: the app uses many dynamic, hash, and
  // data-driven hrefs (marketing anchors, staff case IDs, mock flows) where
  // compile-time route literals add friction without runtime benefit.
  // TODO: add rewrites() to proxy /api/* to BACKEND_BASE_URL (http://localhost:8080)
  // when the backend integration is wired up. Example:
  // async rewrites() {
  //   return [{ source: "/api/:path*", destination: `${process.env.BACKEND_BASE_URL}/:path*` }];
  // }
};

export default nextConfig;
