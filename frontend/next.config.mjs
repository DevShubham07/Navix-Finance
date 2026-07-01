/** @type {import('next').NextConfig} */

// Content-Security-Policy. The app has NO external resource origins (next/font self-hosts; the BFF
// is same-origin /api; no third-party scripts/analytics/frames). `'unsafe-inline'` is required for
// both script (Next's inline hydration bootstrap) and style (200+ inline style= attrs in the
// design-export marketing HTML). `data:`/`blob:` cover the KYC selfie capture (canvas → data URL,
// getUserMedia stream) and client-side jsPDF exports. `'unsafe-eval'` is added in DEV only (React
// Refresh/HMR). A nonce-based script-src is the future hardening (needs middleware).
const isDev = process.env.NODE_ENV !== "production";
const csp = [
  "default-src 'self'",
  `script-src 'self' 'unsafe-inline'${isDev ? " 'unsafe-eval'" : ""}`,
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob:",
  "media-src 'self' blob:",
  "font-src 'self'",
  "connect-src 'self'",
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
