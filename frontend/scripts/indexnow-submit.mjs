#!/usr/bin/env node
/**
 * IndexNow bulk submitter for NAVIX.
 *
 * Pings IndexNow (Bing / Yandex, which also feed ChatGPT / Copilot) with the list of public
 * marketing URLs so new/changed pages get discovered fast. Run manually after a production
 * deploy:  `npm run indexnow`  (or `node scripts/indexnow-submit.mjs --dry-run` to preview).
 *
 * The URL list is kept in sync with app/sitemap.ts by hand — it intentionally EXCLUDES the
 * noindexed /partners and /reviews pages. Update both when routes change.
 */

const HOST = "www.navixfinance.com";
const BASE = `https://${HOST}`;
const KEY = "58ec64fe7399039dafbd0668231718e2";
const KEY_LOCATION = `${BASE}/${KEY}.txt`;
const ENDPOINT = "https://api.indexnow.org/IndexNow";

// Mirror of app/sitemap.ts (public, indexable marketing routes) + real blog posts.
// Excludes /partners and /reviews (noindexed pending real data).
const PATHS = [
  "/",
  "/how-it-works",
  "/products",
  "/calculator",
  "/about",
  "/faq",
  "/contact",
  "/blog",
  "/fair-practices",
  "/grievance",
  "/help",
  "/careers",
  "/privacy",
  "/terms",
  "/blog/how-to-read-a-kfs",
  "/blog/signs-of-a-loan-scam",
  "/blog/what-affects-your-credit-score",
  "/blog/apr-vs-flat-rate",
  "/blog/when-a-short-term-loan-makes-sense",
  "/blog/repaying-on-navix",
];

const urlList = PATHS.map((p) => `${BASE}${p}`);
const payload = { host: HOST, key: KEY, keyLocation: KEY_LOCATION, urlList };

const dryRun = process.argv.includes("--dry-run");
// --postbuild: run automatically after `next build`. Submits ONLY on Vercel production
// deploys (VERCEL_ENV === "production") so local builds and preview deploys don't ping
// IndexNow, and NEVER exits non-zero — a submission hiccup must not fail the deploy.
const postbuild = process.argv.includes("--postbuild");

if (dryRun) {
  console.log("[indexnow] DRY RUN — payload that would be POSTed to", ENDPOINT);
  console.log(JSON.stringify(payload, null, 2));
  console.log(`[indexnow] ${urlList.length} URLs (no /partners, /reviews)`);
  process.exit(0);
}

if (postbuild && process.env.VERCEL_ENV !== "production") {
  console.log(
    `[indexnow] postbuild skip — VERCEL_ENV=${process.env.VERCEL_ENV ?? "unset"} (submits on production only)`,
  );
  process.exit(0);
}

try {
  const res = await fetch(ENDPOINT, {
    method: "POST",
    headers: { "Content-Type": "application/json; charset=utf-8" },
    body: JSON.stringify(payload),
  });
  // IndexNow returns 200 or 202 on success; 4xx indicates a key/host problem.
  console.log(`[indexnow] ${res.status} ${res.statusText} for ${urlList.length} URLs`);
  if (!res.ok) {
    console.error("[indexnow] submission failed — check the key file is live at", KEY_LOCATION);
    if (!postbuild) process.exit(1); // manual run surfaces the failure; postbuild stays soft
  }
} catch (err) {
  console.error("[indexnow] submission error:", err?.message ?? err);
  if (!postbuild) process.exit(1); // never break a Vercel build on a network hiccup
}
