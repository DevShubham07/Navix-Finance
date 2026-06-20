/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  experimental: {
    typedRoutes: true,
  },
  // TODO: add rewrites() to proxy /api/* to BACKEND_BASE_URL (http://localhost:8080)
  // when the backend integration is wired up. Example:
  // async rewrites() {
  //   return [{ source: "/api/:path*", destination: `${process.env.BACKEND_BASE_URL}/:path*` }];
  // }
};

export default nextConfig;
