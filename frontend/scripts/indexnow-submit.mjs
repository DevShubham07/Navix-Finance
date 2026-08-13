#!/usr/bin/env node
/**
 * IndexNow bulk submitter for DhanBoost.
 *
 * Pings IndexNow (Bing / Yandex, which also feed ChatGPT / Copilot) with the list of public
 * marketing URLs so new/changed pages get discovered fast. Run manually after a production
 * deploy:  `npm run indexnow`  (or `node scripts/indexnow-submit.mjs --dry-run` to preview).
 *
 * The URL list is kept in sync with app/sitemap.ts by hand — it intentionally EXCLUDES the
 * noindexed /partners and /reviews pages. Update both when routes change.
 */

const HOST = "www.dhanboost.com";
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
  "/blog/repaying-on-dhanboost",
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

/**
 * Remediation hints keyed by IndexNow's `errorCode`. These are deliberately specific: the
 * generic "check the key file" advice sent us chasing a key file that was already live and
 * correct (200, text/plain, allowed by robots.txt) — the real cause was account-side.
 */
function remediation(errorCode) {
  switch (errorCode) {
    case "UserForbiddedToAccessSite":
      return [
        `${HOST} is not verified in Bing Webmaster Tools, so IndexNow rejects the key.`,
        "Fix: add + verify the site at https://www.bing.com/webmasters using the XML file",
        "method (BingSiteAuth.xml in frontend/public/). Verifying by importing from Google",
        "Search Console is known NOT to satisfy IndexNow.",
      ].join("\n           ");
    case "KeyNotFound":
    case "InvalidKey":
      return `the key file must be live at ${KEY_LOCATION} and contain exactly ${KEY}.`;
    default:
      return `check the key file at ${KEY_LOCATION} and that ${HOST} is verified in Bing Webmaster Tools.`;
  }
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
    // The body carries the real reason ({errorCode, message}); without it the log is a dead end.
    const body = await res.text().catch(() => "");
    let errorCode = "";
    try {
      errorCode = JSON.parse(body)?.errorCode ?? "";
    } catch {
      // non-JSON body — fall through to the generic hint
    }
    if (body) console.error("[indexnow] response:", body.trim());
    console.error(`[indexnow] submission failed — ${remediation(errorCode)}`);
    if (!postbuild) process.exit(1); // manual run surfaces the failure; postbuild stays soft
  }
} catch (err) {
  console.error("[indexnow] submission error:", err?.message ?? err);
  if (!postbuild) process.exit(1); // never break a Vercel build on a network hiccup
}
