import type { MetadataRoute } from "next";

/**
 * robots.txt (Next.js App Router convention).
 *
 * Allows the public marketing site to all crawlers, including named AI crawlers we
 * explicitly want to permit (AI Overviews / ChatGPT / Perplexity / Claude / Bing). Only
 * the private surface is disallowed: the BFF (`/api/`) and the staff console (`/staff/`).
 *
 * NOTE: the borrower auth-entry pages (`/login`, `/signup/*`, …) are intentionally NOT
 * disallowed here — they are de-indexed via `robots: noindex` metadata on the (borrower)
 * layout instead. A robots `Disallow` would stop crawling and therefore prevent the
 * `noindex` from ever being seen (the noindex-vs-disallow conflict).
 */
export default function robots(): MetadataRoute.Robots {
  return {
    rules: [
      {
        userAgent: [
          "*",
          "GPTBot",
          "OAI-SearchBot",
          "ChatGPT-User",
          "PerplexityBot",
          "ClaudeBot",
          "Google-Extended",
          "Bingbot",
          "CCBot",
        ],
        allow: "/",
        disallow: ["/api/", "/staff/"],
      },
    ],
    sitemap: "https://www.dhanboost.com/sitemap.xml",
    // The `Host:` directive is a bare hostname (no scheme), not a URL.
    host: "www.dhanboost.com",
  };
}
