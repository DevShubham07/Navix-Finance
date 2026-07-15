# NAVIX SEO Implementation Plan — Execution Handoff

> **Purpose.** Self-contained handoff for a fresh Claude Code session. Carries everything
> needed to execute the NAVIX SEO pass without prior conversation context: project facts, the
> audit results, and the concrete changes (with code skeletons). Produced from a full
> **claude-seo** four-agent specialist audit of the live site + code (2026-07-01), then
> hardened by a review pass (see §8 for what the review changed and why).
>
> **How to use it.** Track A is safe, mechanical code work — implement end-to-end and verify
> locally. **Shipping Track A code is harmless; TRIGGERING indexation is not** — see the
> ship-vs-index gate in §2.0 and §6. Track B is content/compliance needing the product owner's
> real data — do NOT invent values; flag and wait. Track C is deferred/larger effort.

---

## 0. Project facts a fresh session needs

- **Repo:** monorepo at `/Users/kartik.jindal/navix_final`. Frontend is **Next.js 15 (App
  Router, `src/`), React 19, TypeScript, Tailwind**, under `frontend/`.
- **What NAVIX is:** a salary-linked personal-loan **platform** (India). It is *not* the
  lender of record — RBI-registered **NBFC partners** are. **Never describe NAVIX as an NBFC or
  lender**, in prose or in machine-readable schema.
- **Live site:** `https://www.navixfinance.com` (apex `navixfinance.com` 308-redirects to
  `www` — the **canonical host**). Vercel; the frontend code IS the deployment.
- **`trailingSlash`:** **not set** in `frontend/next.config.mjs` → Next default `false` → served
  URLs have **no trailing slash** (`/about/` 308-redirects to `/about`, confirmed live). All
  canonicals in this plan are therefore the **no-slash** form, which matches the served form.
- **Route groups** (`frontend/src/app/`):
  - `(marketing)/` — public, crawlable. Group name is not in the URL → root-level paths.
    **16 public URLs:** `/`, `/about`, `/blog`, `/calculator`, `/careers`, `/contact`,
    `/fair-practices`, `/faq`, `/grievance`, `/help`, `/how-it-works`, `/partners`, `/privacy`,
    `/products`, `/reviews`, `/terms`. Each `page.tsx` exports its own `metadata` (title +
    description only). Content is prebuilt HTML from `(marketing)/_content/*.ts`, rendered
    server-side via `components/site/marketing-html.tsx` (SSR — good for SEO).
  - `(borrower)/` — borrower app + public auth entry (`/login`, `/signup/*`, `/forgot-password`,
    `/reset-password`, `/dashboard`, `/loan`, …). Auth-gated by `middleware.ts`.
  - `staff/` — back-office console (`/staff/*`). Auth-gated.
- **Brand facts** live in **`frontend/src/lib/brand.ts`** (`BRAND` + `LENDING_PARTNERS`) — the
  single source of truth; **reuse, never re-type**:
  ```
  legalName: "NAVIX Finance Private Limited"   shortName: "NAVIX"
  phone: "+91 97167 60246"   email: "info@navixfinance.com"
  cin: "U64990HR2026PTC144926"
  address: { line1: "Dev Nagar", city: "Gurugram", pin: "122102" }  // country IN
  logo asset: /navix-mark.png  (in frontend/public/)
  ```
- **Existing SEO-positive facts** (don't "fix"): `<html lang="en-IN">`; favicon/apple-icon via
  file convention (`app/icon.png`, `app/apple-icon.png`); fonts `display: "swap"`; unique
  title+description on all 16 marketing pages; clean single-hop redirects.
- **Build caveat:** `npm run build` static-prerender fails at `/staff/admin/staff` on a clean
  checkout (Next 15.1.3 environmental bug, not app code). **Verify with `npx tsc --noEmit` +
  `npm run dev`, NOT `npm run build`.** Running `build` while `dev` is up corrupts `.next`.

---

## 1. Audit results — SEO Health Score: 36 / 100

| Category | Weight | Score | Headline finding |
|---|---|---|---|
| Technical SEO | 22% | **28** | `robots.txt` + `sitemap.xml` both 404; no canonical; no security headers; **not indexed** |
| Content / E-E-A-T | 23% | **24** | YMYL **trust defects** (Track B) — the biggest drag |
| On-Page SEO | 20% | ~55* | titles/desc/H1 unique & clean; missing canonical/OG |
| Schema / Structured Data | 10% | **8** | zero JSON-LD anywhere |
| Performance (CWV) | 10% | ~60* | *not measured (no PSI/CrUX key); ~153 KB blocking CSS + ~370 KB JS flagged |
| AI Search (GEO) | 10% | **37** | no robots/llms.txt; blog is title-only stubs; FAQ uses `<button>`, not headings |
| Images | 5% | ~50* | existing imgs have alt/dims; no social/OG image |

\* derived/unmeasured — flagged honestly, not fabricated as precise.

**Root causes (all four agents agree):** (1) no crawl primitives → not indexed; (2) zero
structured data; (3) critical YMYL trust defects. (1)+(2) = code (Track A); (3) = content
(Track B).

---

## 2. TRACK A — Code SEO

### 2.0 Ship-vs-index gate (read first)

**Two separate acts, deliberately decoupled:**
- **Shipping Track A code** (deploy the changes below) — **safe to do anytime.** It is made
  harmless by A10 (temporarily noindex + drop `/partners` and `/reviews` from the sitemap), so
  the AI-crawler allow-list in A2 can't surface the fake-CoR / fabricated-review pages.
- **Triggering indexation** (§6 step 4b — GSC/Bing sitemap submit + Request-indexing) — **BLOCKED
  until B1, B2, B3 clear.** Submitting the sitemap is what actively pushes pages into Google and
  the AI crawlers; doing that while the prototype disclaimers, fake RBI CoR numbers, and
  entity-name conflict are live is *worse than staying unindexed*.
- `/partners` and `/reviews` stay out of the sitemap **and** noindexed until **B2 and B4** clear
  (they carry the fake CoR numbers and the unverifiable review stats respectively).

Nine changes below (A1–A10). All low-risk, additive, no business logic. Canonical host is
always `https://www.navixfinance.com`.

### A1. Root layout metadata — edit `frontend/src/app/layout.tsx`
Extend the existing `metadata` object (only `title`+`description` today, ~lines 39-43):
```ts
export const metadata: Metadata = {
  metadataBase: new URL("https://www.navixfinance.com"),
  title: "NAVIX — Instant Personal Loans, Fully Digital",
  description:
    "NAVIX is a digital lending platform offering instant, fully-digital, salary-linked personal loans. Paperless process, direct bank disbursal, single repayment, zero advance fees.",
  alternates: { canonical: "/" },
  openGraph: {
    type: "website", siteName: "NAVIX", locale: "en_IN", url: "/",
    title: "NAVIX — Instant Personal Loans, Fully Digital",
    description: "Instant, fully-digital, salary-linked personal loans — single repayment, no advance fees.",
  },
  twitter: {
    card: "summary_large_image",
    title: "NAVIX — Instant Personal Loans, Fully Digital",
    description: "Instant, fully-digital, salary-linked personal loans — single repayment, no advance fees.",
  },
};
```
- **Do NOT** add a `title.template: "%s — NAVIX"` — the 16 marketing titles already end "— NAVIX".
- **Canonical footgun (important):** `alternates.canonical: "/"` here is the site-wide *default*.
  Next merges `alternates` per-route, so any marketing page that forgets its own A9 canonical
  **silently inherits `"/"`** and gets canonicalized-away (deindex risk). A9 covers all 16 today
  — §6 adds a per-page assertion so a future missing canonical fails in CI, not in GSC weeks later.

### A2. `robots` route — new `frontend/src/app/robots.ts`
```ts
import type { MetadataRoute } from "next";

export default function robots(): MetadataRoute.Robots {
  return {
    rules: [
      {
        userAgent: [
          "*", "GPTBot", "OAI-SearchBot", "ChatGPT-User", "PerplexityBot",
          "ClaudeBot", "Google-Extended", "Bingbot", "CCBot",
        ],
        allow: "/",
        disallow: ["/api/", "/staff/"],
      },
    ],
    sitemap: "https://www.navixfinance.com/sitemap.xml",
    host: "www.navixfinance.com", // Host: directive is scheme-less (bare hostname), NOT a URL
  };
}
```
- **Only `/api/` and `/staff/` disallowed** (private/console). **Deliberately do NOT disallow**
  `/login`, `/signup/`, `/dashboard`, etc. — those are de-indexed via `noindex` meta (A6).
  Reason (**noindex-vs-disallow conflict**): a robots `Disallow` stops Googlebot from *crawling*
  the page, so it never *sees* the `noindex`. Correct de-index recipe = **allow-crawl + noindex**.

### A3. `sitemap` route — new `frontend/src/app/sitemap.ts`
```ts
import type { MetadataRoute } from "next";

const BASE = "https://www.navixfinance.com";

// Single source of truth for public marketing URLs.
// NOTE: /partners and /reviews are intentionally OMITTED until B2 (fake RBI CoR#) and
// B4 (unverifiable review stats) clear — do not add them back before then (§2.0, §8).
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
];

export default function sitemap(): MetadataRoute.Sitemap {
  const lastModified = "2026-07-01"; // fixed ISO string — keep deterministic, no new Date()
  return ROUTES.map(({ path, priority }) => ({
    url: `${BASE}${path}`, lastModified, changeFrequency: "monthly", priority,
  }));
}
```

### A4. Branded OG image — new `frontend/src/app/opengraph-image.tsx`
```tsx
import { ImageResponse } from "next/og";

export const runtime = "edge";
export const alt = "NAVIX — Instant personal loans. Fully digital. Fairly priced.";
export const size = { width: 1200, height: 630 };
export const contentType = "image/png";

export default function Image() {
  return new ImageResponse(
    (
      <div style={{ height: "100%", width: "100%", display: "flex", flexDirection: "column",
        justifyContent: "center", padding: "80px", background: "#0C2540",
        color: "#FDFBF6", fontFamily: "sans-serif" }}>
        <div style={{ fontSize: 96, fontWeight: 800, letterSpacing: "-0.03em", color: "#E9B53A" }}>NAVIX</div>
        <div style={{ fontSize: 52, fontWeight: 700, marginTop: 24, lineHeight: 1.1 }}>Instant personal loans.</div>
        <div style={{ fontSize: 52, fontWeight: 700, lineHeight: 1.1 }}>Fully digital. Fairly priced.</div>
        <div style={{ fontSize: 30, marginTop: 32, opacity: 0.85 }}>Salary-linked · single repayment · no advance fees</div>
      </div>
    ),
    { ...size },
  );
}
```
- The `opengraph-image` convention emits **`og:image`**. X/Twitter previews still work (X falls
  back to `og:image` at render), but there is **no explicit `twitter:image` tag** from this file
  alone. To emit one explicitly, add **`frontend/src/app/twitter-image.tsx`** re-exporting this
  generator (DRY — don't duplicate the layout):
  ```ts
  export { default, size, contentType, alt, runtime } from "./opengraph-image";
  ```

### A5. Structured data (JSON-LD) — new `frontend/src/components/site/structured-data.tsx`
Render once in **`frontend/src/app/(marketing)/layout.tsx`** (inside `.navix-mkt`, alongside
header/footer). **NOT** root layout (root also wraps borrower/staff — wrong context there).
```tsx
import { BRAND } from "@/lib/brand";

const BASE = "https://www.navixfinance.com";

export function StructuredData() {
  const graph = {
    "@context": "https://schema.org",
    "@graph": [
      {
        "@type": "Organization", // NOT "FinancialService" — NAVIX is a platform, not the lender.
                                  // FinancialService would machine-assert NAVIX provides the loan,
                                  // violating the "never call NAVIX a lender" rule (§0). Lending is
                                  // attributed to the partner NBFC later via LoanOrCredit (Track C).
        "@id": `${BASE}/#organization`,
        name: BRAND.shortName,
        legalName: BRAND.legalName,
        url: BASE,
        logo: `${BASE}/navix-mark.png`,
        image: `${BASE}/navix-mark.png`,
        telephone: BRAND.phone, // reuse BRAND — spaces are valid in schema telephone
        email: BRAND.email,
        address: {
          "@type": "PostalAddress",
          streetAddress: BRAND.address.line1,
          addressLocality: BRAND.address.city,
          postalCode: BRAND.address.pin,
          addressCountry: "IN",
        },
        identifier: { "@type": "PropertyValue", propertyID: "CIN", value: BRAND.cin },
        areaServed: { "@type": "Country", name: "IN" },
        sameAs: [], // populate with the real LinkedIn company page etc. when available —
                    // cheapest entity/knowledge-graph + E-E-A-T signal; ships empty fine
                    https://www.linkedin.com/company/navix-finance
      },
      {
        "@type": "WebSite",
        "@id": `${BASE}/#website`,
        name: BRAND.shortName, url: BASE, inLanguage: "en-IN",
        publisher: { "@id": `${BASE}/#organization` },
      },
    ],
  };
  return <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(graph) }} />;
}
```
Constraints: **no `SearchAction`** (no site search); **no `Review`/`AggregateRating`** until the
`/reviews` numbers are verified (B4 — marking up unverifiable ratings is a policy violation).

### A6. Noindex the app surface — edit two layouts
Add to **`frontend/src/app/(borrower)/layout.tsx`** and **`frontend/src/app/staff/layout.tsx`**:
```ts
import type { Metadata } from "next";
export const metadata: Metadata = { robots: { index: false, follow: false } };
```
Covers public auth-entry pages and private app routes; neutralizes `/login` reusing the homepage
title. (If either layout is `"use client"`, don't force it — extract a small server wrapper.
Verify before editing.)

### A7. Security headers — edit `frontend/next.config.mjs`
Add a `headers()` (none today), applied to all routes:
```js
async headers() {
  return [{
    source: "/:path*",
    headers: [
      { key: "X-Content-Type-Options", value: "nosniff" },
      { key: "X-Frame-Options", value: "SAMEORIGIN" },
      { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
      { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
      // HSTS: includeSubDomains now; preload is DEFERRED. `preload` (once submitted to
      // hstspreload.org) forces EVERY subdomain to HTTPS ~permanently — audit mail/staging/
      // other subdomains and submit intentionally before adding it. (Track C.)
      { key: "Strict-Transport-Security", value: "max-age=63072000; includeSubDomains" },
    ],
  }];
}
```
- A full **CSP is deferred** (Track C) — needs an inventory of allowed script/style/font origins first.

### A8. Per-page canonicals — edit the 16 `(marketing)/**/page.tsx`
Add one line to each page's existing `metadata` (with A1's `metadataBase`, these become absolute):
```ts
alternates: { canonical: "/how-it-works" }, // the page's OWN path; "/" for the home page
```
Must be the **no-slash** served form (see §0 `trailingSlash`). All 16 must have their own —
see the A1 footgun + the §6 assertion.

### A9. Temporary noindex on `/partners` + `/reviews` — edit two page.tsx
Until **B2** (fake RBI CoR#) and **B4** (unverifiable stats) clear, add to
`(marketing)/partners/page.tsx` and `(marketing)/reviews/page.tsx` metadata:
```ts
robots: { index: false, follow: true }, // TEMP — remove when B2/B4 resolve; also re-add to A3 sitemap then
```
This is the belt to the sitemap-omission's suspenders: it blocks the allowed AI crawlers (A2)
from indexing those pages via nav links, not just via the sitemap.

### A10. `llms.txt` — **moved to Track C (C5).** Blocked on B5, and Google doesn't consume it.

---

## 3. TRACK B — Content & compliance (needs product-owner data; DO NOT invent)

Highest-priority items in the whole audit (live on an indexable **lending** site), but each
needs real data or a legal decision. Files: `frontend/src/app/(marketing)/_content/*.ts` and
`frontend/src/lib/brand.ts`. **B1, B2, B3 gate indexation (§6 step 4b); B2, B4 gate re-adding
`/partners`+`/reviews`; B5 gates C5.**

- **B1 (Critical).** Live **"Prototype content for demonstration"** on `/grievance` and
  **"Prototype terms… not legal advice"** on `/terms`. Remove, or `noindex`+gate, until
  counsel-reviewed copy ships. (`_content/grievance.ts`, `_content/terms.ts`.)
- **B2 (Critical).** Four **placeholder RBI CoR numbers** (`N-14.03XXXX`, `N-13.02XXXX`,
  `N-09.01XXXX`, `N-05.07XXXX`) on `/partners`. Real verifiable registrations or remove the
  cards. (`LENDING_PARTNERS` in `brand.ts` / `_content/partners.ts`.)
- **B3 (Critical).** **Entity-name conflict**: `/grievance` says "NAVIX Technologies Pvt. Ltd."
  vs footer/CIN "NAVIX Finance Private Limited." Reconcile.
- **B4 (High).** `/reviews` — "12,400+ / 4.8★ / 96%" + named testimonials have no verifiable
  source. Substantiate (linkable source) or remove specifics. Gates A5 review schema + A9 re-add.
- **B5 (High).** **₹1,00,000 (home) vs ₹10,00,000 (FAQ)** loan-cap contradiction. Resolve.
  Unblocks C5 (llms.txt).
- **B6 (High).** Name a real **Grievance/Nodal Officer** (title + contact) per RBI Fair Practices
  Code; publish the actual **late-payment penalty rate** publicly.

---

## 4. TRACK C — Deferred (bigger effort, not blocking indexation)

- **C1. Blog is title-only stubs** — the 6 `/blog` cards link nowhere (no `blog/[slug]`).
  Highest-leverage **AI-citation** lever (informational queries a new brand can win). Build real
  `blog/[slug]` posts, 600–1200 words, question-form H2s. Content project.
- **C2. FAQ heading structure** — questions are `<button class="q">`, not `<h2>/<h3>`
  (`_content/faq.ts` + accordion). Convert to semantic headings (keep accordion UX). Optionally
  add `FAQPage` JSON-LD — **Info-level, AI-citation value only; NO Google SERP feature** (FAQ
  rich results retired May 2026). Not for ranking.
- **C3. Performance + full CSP** — trim/split ~153 KB blocking CSS + ~370 KB JS; confirm real
  LCP/INP/CLS via Lighthouse/CrUX (no PSI key today). Add a full CSP once origins are inventoried.
- **C4. IndexNow** *(higher AI-citation value than C5)* — key file in `public/` + a deploy hook
  pinging Bing/Yandex. This feeds **Bing → ChatGPT/Copilot**, which is the actual AI-citation
  path; prioritize it over llms.txt. Do after A2/A3 land.
- **C5. `llms.txt`** — new `frontend/public/llms.txt`. A markdown fact hand-off. Lower priority
  than C4: **Google confirmed it does not use llms.txt** (only Anthropic/OpenAI-style agents
  do). **Blocked on B5** (must not encode the wrong loan cap). Also add a future `LoanOrCredit`
  node (`provider` = partner NBFC) on `/products` once B2 clears — the accurate way to represent
  lending in schema.

---

## 5. Files touched (Track A)

**New:** `frontend/src/app/robots.ts`, `frontend/src/app/sitemap.ts`,
`frontend/src/app/opengraph-image.tsx`, `frontend/src/app/twitter-image.tsx` (A4 re-export),
`frontend/src/components/site/structured-data.tsx`.
**Modified:** `frontend/src/app/layout.tsx` (A1), `frontend/next.config.mjs` (A7),
`frontend/src/app/(marketing)/layout.tsx` (render `<StructuredData/>`, A5),
`frontend/src/app/(borrower)/layout.tsx` + `frontend/src/app/staff/layout.tsx` (A6),
`(marketing)/partners/page.tsx` + `(marketing)/reviews/page.tsx` (A9 temp noindex),
the 16 `(marketing)/**/page.tsx` (A8 canonicals).
**Deferred file:** `frontend/public/llms.txt` (C5, after B5).
**Reuse (don't duplicate):** `BRAND` / `LENDING_PARTNERS` (`frontend/src/lib/brand.ts`);
`app/icon.png` / `app/apple-icon.png`; `components/site/marketing-html.tsx` (unchanged).

---

## 6. Verification

**Local** (`cd frontend`; `tsc` + `dev`, **NOT** `build` — §0):
1. `npx tsc --noEmit` → clean.
2. `grep -i trailingSlash next.config.mjs` → **no match** (confirms no-slash served form, so A8
   canonicals match); then `curl -sI localhost:3000/about/ | grep -i location` → redirects to
   `/about` (no slash).
3. `npm run dev`, then:
   - `curl -s localhost:3000/robots.txt` → rules + AI-crawler allows + `Sitemap:` line;
     `Host: www.navixfinance.com` (bare, no scheme).
   - `curl -s localhost:3000/sitemap.xml` → **14** `<url>` entries; `grep -E 'partners|reviews'`
     on it → **empty** (omitted until B2/B4).
   - `curl -s localhost:3000/ | grep -Eo '(og:[a-z]+|application/ld\+json|rel="canonical")'` →
     og:*, JSON-LD, canonical present. Confirm the JSON-LD `@type` is **`Organization`**, not
     `FinancialService`.
   - **Per-page canonical assertion (catches the A1 footgun):**
     ```bash
     for p in "" about blog calculator careers contact fair-practices faq grievance help \
       how-it-works partners privacy products reviews terms; do
       u="localhost:3000/$p"
       echo -n "/$p → "; curl -s "$u" | grep -oE 'rel="canonical" href="[^"]+"' || echo "MISSING"
     done
     ```
     Each must show its **own** path (home = `/`), never inherit `/`.
   - Open `http://localhost:3000/opengraph-image` → branded 1200×630 PNG.
   - `curl -s localhost:3000/login | grep -i noindex`, `.../staff/login`, `.../partners`,
     `.../reviews` → `noindex` present on all four.
   - `curl -sI localhost:3000/ | grep -iE 'x-frame-options|x-content-type-options|strict-transport'`
     → headers set; HSTS has `includeSubDomains` but **no** `preload`.
4. **Split by ship-vs-index (§2.0):**
   - **4a — Ship code (safe now):** deploy Track A. Re-run the live equivalents of step 3 against
     `https://www.navixfinance.com`. Confirm `/partners` + `/reviews` carry `noindex` and are
     absent from the live sitemap.
   - **4b — Trigger indexation (BLOCKED until B1 + B2 + B3 clear):** verify the domain in **Google
     Search Console** + **Bing Webmaster Tools**, submit
     `https://www.navixfinance.com/sitemap.xml`, URL-Inspect → Request indexing on `/` + top
     pages. Only after **B2 + B4** also clear: remove the A9 noindex from `/partners`+`/reviews`
     and add them back to the A3 sitemap. Re-check `site:navixfinance.com` after a few days.
5. Paste the homepage JSON-LD into Google **Rich Results Test** + **schema.org validator** → no errors.

---

## 7. Guardrails / gotchas (read before editing)

- **Canonical host is `https://www.navixfinance.com`** (with `www`). Never emit apex URLs.
  Canonicals use the **no-slash** form (matches served URLs).
- **Ship code ≠ trigger indexation.** Don't submit the sitemap / Request-indexing until B1–B3
  clear (§2.0, §6-4b).
- **NAVIX is a platform, not an NBFC/lender** — in prose AND schema. `@type` is `Organization`,
  never `FinancialService`/`LoanOrCredit` for the NAVIX entity.
- **Money/product facts:** state no loan cap anywhere new until **B5** resolves.
- **No review or FAQ rich-result schema for ranking** — review schema waits on B4; FAQPage earns
  no SERP feature (AI-citation only).
- **HSTS `preload` is near-irreversible** — ship `includeSubDomains` only; add `preload` after a
  subdomain audit + intentional hstspreload.org submission.
- **Don't run `npm run build`** to verify (breaks at `/staff/admin/staff`; corrupts a running
  dev `.next`). Use `tsc` + `dev`.
- **Reuse `BRAND`** — schema/OG/llms.txt values trace to `frontend/src/lib/brand.ts`, not retyped.
- If a layout needing `metadata` is `"use client"`, extract a server wrapper — don't force it.

---

## 8. Review-pass changelog (what hardened this plan, and why)

1. **Ship-vs-index decoupled** — the plan now gates sitemap submission + Request-indexing on
   B1–B3, and pulls `/partners`+`/reviews` from the sitemap + noindexes them (A9) until B2/B4,
   so shipping code can't push fake-CoR / fabricated-review pages to Google or the allowed AI
   crawlers. (§2.0, A3, A9, §6-4.)
2. **A5 `@type`: `FinancialService` → `Organization`** — `FinancialService` machine-asserts NAVIX
   provides the loan, the exact forbidden claim; `Organization` is accurate to platform position.
3. **A2 `host`** → bare `www.navixfinance.com` (the `Host:` directive is scheme-less).
4. **trailingSlash confirmed** unset (no-slash served form) — stated in §0, asserted in §6-2, so
   A8 canonicals provably match the served form.
5. **HSTS `preload` dropped** from the shipped header (near-irreversible commitment); deferred to
   Track C behind a subdomain audit.
6. **A4 `twitter:image`** claim made precise — `opengraph-image` emits `og:image` only; added an
   explicit `twitter-image.tsx` re-export.
7. **A1 canonical footgun** documented + a per-page canonical assertion added to §6.
8. **A5 phone** now `BRAND.phone` (was a retyped hyphenated literal) — honors the reuse rule.
9. **`sameAs`** note strengthened (populate with the real LinkedIn page when available).
10. **llms.txt** recategorized A8 → **C5** (blocked on B5; Google doesn't consume it); **IndexNow
    (C4) prioritized above it** as the real Bing→ChatGPT/Copilot AI-citation path.
