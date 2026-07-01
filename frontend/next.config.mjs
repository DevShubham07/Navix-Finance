/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // Baseline security headers (applied to all routes). HSTS ships with includeSubDomains but
  // WITHOUT `preload`: preload (once submitted to hstspreload.org) forces every subdomain to
  // HTTPS near-permanently — add it only after auditing subdomains. A full CSP is deferred
  // until the allowed script/style/font origins are inventoried.
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "X-Frame-Options", value: "SAMEORIGIN" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
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
