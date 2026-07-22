import type { MetadataRoute } from "next";

const BASE = "https://www.dhanboost.com";

/**
 * XML sitemap (Next.js App Router convention) — the single source of truth for the public
 * marketing URLs we want indexed.
 *
 * `/partners` and `/reviews` are intentionally OMITTED: `/partners` carries placeholder RBI
 * CoR numbers and `/reviews` carries unverifiable aggregate stats. Do NOT add them back until
 * those content issues are resolved (Track B: B2 / B4 in seoPlan.md). They are also
 * `noindex`ed at the page level as belt-and-suspenders.
 */
const ROUTES: { path: string; priority: number }[] = [
  { path: "/", priority: 1.0 },
  { path: "/how-it-works", priority: 0.9 },
  { path: "/products", priority: 0.9 },
  { path: "/calculator", priority: 0.9 },
  { path: "/about", priority: 0.7 },
  { path: "/faq", priority: 0.7 },
  { path: "/contact", priority: 0.6 },
  { path: "/blog", priority: 0.6 },
  { path: "/fair-practices", priority: 0.5 },
  { path: "/grievance", priority: 0.5 },
  { path: "/help", priority: 0.5 },
  { path: "/careers", priority: 0.4 },
  { path: "/privacy", priority: 0.3 },
  { path: "/terms", priority: 0.3 },
  // Blog posts (keep in sync with _content/blog-posts.ts POST_SLUGS and scripts/indexnow-submit.mjs).
  { path: "/blog/how-to-read-a-kfs", priority: 0.5 },
  { path: "/blog/signs-of-a-loan-scam", priority: 0.5 },
  { path: "/blog/what-affects-your-credit-score", priority: 0.5 },
  { path: "/blog/apr-vs-flat-rate", priority: 0.5 },
  { path: "/blog/when-a-short-term-loan-makes-sense", priority: 0.5 },
  { path: "/blog/repaying-on-dhanboost", priority: 0.5 },
];

export default function sitemap(): MetadataRoute.Sitemap {
  // Fixed ISO date — kept deterministic (no `new Date()`); bump on meaningful content changes.
  const lastModified = "2026-07-01";
  return ROUTES.map(({ path, priority }) => ({
    url: `${BASE}${path}`,
    lastModified,
    changeFrequency: "monthly",
    priority,
  }));
}
