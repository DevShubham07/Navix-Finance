// Reuse the Open Graph image as the explicit `twitter:image` (X/Twitter card). DRY — the
// generator lives in ./opengraph-image; this re-exports it under the twitter-image route.
// `runtime` is declared directly here (Next.js can't trace a re-exported route-segment config).
export const runtime = "edge";
export { default, size, contentType, alt } from "./opengraph-image";
