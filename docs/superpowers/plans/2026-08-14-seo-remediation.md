# DhanBoost SEO Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove inactive-lender claims, normalize public loan-cost facts, generate a maintainable sitemap, consolidate the canonical hostname, and fix audited mobile/accessibility defects with regression coverage.

**Architecture:** Keep Next.js metadata routes as the canonical robots/sitemap implementation, derive article sitemap entries from the existing blog content model, and reuse the pure loan-math module for public cost calculations. Treat public-copy correctness as testable data: a focused SEO invariant suite scans public sources, while Playwright verifies rendered responsive and semantic behavior.

**Tech Stack:** Next.js 15 App Router, React 19, TypeScript 5.7, Vitest 3, Playwright 1.61, CSS, Vercel-compatible Next redirects.

**Spec:** `docs/superpowers/specs/2026-08-13-seo-remediation-design.md`

## Global Constraints

- DhanBoost reviews, approves, and disburses loans; do not call DhanBoost an NBFC or add an RBI registration claim.
- Remove Arthveda Capital Private Limited, Sentinel Finserv Limited, and all generic active-partner/NBFC-partner claims from public frontend content.
- Public economics are fixed: 1% simple interest per day; 10% processing fee deducted from principal; GST is 18% of that fee; repayment is principal plus accrued interest.
- Do not present `365%` as an all-inclusive representative APR; say the all-inclusive APR is disclosed in the KFS.
- Canonical origin is `https://www.dhanboost.com`; sitemap URL is `https://www.dhanboost.com/sitemap.xml`.
- Do not mutate backend loan lifecycle behavior, external webmaster tools, DNS, analytics, or Vercel project settings.
- Use `apply_patch` for edits, preserve unrelated working-tree files, and follow red-green-refactor for production behavior.

## File structure

- `frontend/src/lib/calc/loan-math.ts`: existing canonical pure cost calculations; no duplicate formulas.
- `frontend/src/lib/seo/public-routes.ts`: new typed static public route registry used by sitemap and tests.
- `frontend/src/app/sitemap.ts`: maps public routes and blog metadata to `MetadataRoute.Sitemap`.
- `frontend/src/lib/seo/seo-invariants.test.ts`: source-of-truth, sitemap, redirect, and stale-copy regression tests.
- `frontend/src/app/(marketing)/_content/*.ts`, `frontend/src/components/site/*.tsx`, `frontend/public/llms.txt`, `frontend/public/legal/terms-and-conditions.txt`: normalized public language and calculator markup.
- `frontend/src/components/site/marketing-scripts.tsx`: calculator DOM updates using canonical fee/GST/net/interest/repayment behavior.
- `frontend/next.config.mjs`: apex-host and obsolete `/partners` redirects.
- `frontend/src/app/(marketing)/blog/[slug]/page.tsx`, `frontend/src/app/(marketing)/_content/blog-posts.ts`: related-resource model, visible links, and breadcrumb JSON-LD.
- `frontend/src/app/(marketing)/marketing-theme.css`: responsive containment, contrast, target-size, and animation fallback fixes.
- `frontend/e2e/marketing-seo.spec.ts`: rendered sitemap/redirect/responsive/accessibility checks.

---

### Task 1: Remove inactive partners and normalize entity language

**Files:**
- Modify: `frontend/src/lib/brand.ts`
- Delete: `frontend/src/app/(marketing)/partners/page.tsx`
- Delete: `frontend/src/app/(marketing)/_content/partners.ts`
- Modify: `frontend/src/app/(marketing)/_content/{terms,faq,privacy,products,how-it-works,fair-practices,grievance,blog-posts}.ts`
- Modify: `frontend/src/components/site/{faq-schema,structured-data,marketing-footer}.tsx`
- Modify: `frontend/public/llms.txt`
- Modify: `frontend/public/legal/terms-and-conditions.txt`
- Test: `frontend/src/lib/seo/seo-invariants.test.ts`

**Interfaces:**
- Consumes: `BRAND.phone`, `BRAND.email`, `BRAND.grievanceOfficer` from `frontend/src/lib/brand.ts`.
- Produces: public sources with no inactive-partner claims and one DhanBoost approval/disbursal model.

- [ ] **Step 1: Write the failing source-invariant test**

Create `frontend/src/lib/seo/seo-invariants.test.ts` with a recursive public-source reader and these assertions:

```ts
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, resolve } from "node:path";
import { describe, expect, it } from "vitest";

const FRONTEND = resolve(process.cwd());
const PUBLIC_SOURCES = [join(FRONTEND, "src", "app", "(marketing)"), join(FRONTEND, "src", "components", "site"), join(FRONTEND, "public")];

function filesBelow(path: string): string[] {
  if (statSync(path).isFile()) return [path];
  return readdirSync(path).flatMap((entry) => filesBelow(join(path, entry)));
}

function publicText(): string {
  return PUBLIC_SOURCES.flatMap(filesBelow)
    .filter((file) => /\.(?:ts|tsx|txt)$/.test(file))
    .map((file) => readFileSync(file, "utf8"))
    .join("\n");
}

describe("public SEO facts", () => {
  it("contains no inactive lender or generic NBFC-partner claims", () => {
    const text = publicText();
    expect(text).not.toMatch(/Arthveda Capital|Sentinel Finserv|Pragati Credit|Meridian Finance/i);
    expect(text).not.toMatch(/partner NBFC|NBFC partner|RBI-registered NBFC/i);
  });

  it("describes DhanBoost approval and disbursal without unsupported registration claims", () => {
    const text = publicText();
    expect(text).toMatch(/DhanBoost (?:reviews|assesses)[\s\S]{0,120}approv/i);
    expect(text).toMatch(/DhanBoost[\s\S]{0,120}disburs/i);
    expect(text).not.toMatch(/DhanBoost (?:is|as) (?:an? )?(?:RBI[- ]registered )?NBFC/i);
  });
});
```

- [ ] **Step 2: Run the test and verify RED**

Run: `npm test -- src/lib/seo/seo-invariants.test.ts` from `frontend/`  
Expected: FAIL listing inactive partner names and partner-NBFC wording.

- [ ] **Step 3: Implement minimal copy/data removal**

Remove `LENDING_PARTNERS` from `brand.ts`; delete the two obsolete partner route/content files; replace public wording with these approved meanings:

```text
DhanBoost reviews each completed application under its eligibility and credit policy.
Once an approved borrower accepts and e-signs the offer, DhanBoost disburses the funds to the verified bank account.
Final charges and the all-inclusive APR are disclosed in the Key Fact Statement before acceptance.
```

Update FAQ and FAQ schema from “Is DhanBoost a lender?” to “Who reviews and disburses my loan?” with matching visible/schema answers. Remove partner escalation from grievance copy while preserving DhanBoost grievance-officer escalation. Change privacy-sharing copy to service providers, verification providers, credit bureaus, banks/payment providers, and authorities as applicable—without claiming an NBFC recipient.

Make `llms.txt` primary contact match `BRAND`: `support@dhanboost.com`, `+91 85100 28510`; label `grievance@dhanboost.com`, `+91 97167 60246` only as grievance escalation.

- [ ] **Step 4: Run the invariant test and verify GREEN**

Run: `npm test -- src/lib/seo/seo-invariants.test.ts`  
Expected: PASS for both public-fact tests.

- [ ] **Step 5: Commit**

```powershell
git add -- frontend/src/lib/brand.ts frontend/src/app/(marketing) frontend/src/components/site frontend/public/llms.txt frontend/public/legal/terms-and-conditions.txt frontend/src/lib/seo/seo-invariants.test.ts
git commit -m "fix(seo): remove inactive lending partner claims"
```

### Task 2: Consolidate canonical routing and rebuild the generated sitemap

**Files:**
- Create: `frontend/src/lib/seo/public-routes.ts`
- Modify: `frontend/src/app/sitemap.ts`
- Modify: `frontend/next.config.mjs`
- Modify: `frontend/src/lib/seo/seo-invariants.test.ts`

**Interfaces:**
- Produces: `PUBLIC_ROUTES: readonly PublicRoute[]`, where `PublicRoute = { path: string; changeFrequency: "monthly" | "yearly"; priority: number; lastModified?: string }`.
- Consumes: `POSTS` and `POST_SLUGS` from blog content.

- [ ] **Step 1: Add failing sitemap and redirect tests**

Append:

```ts
import sitemap from "../../app/sitemap";
import { POSTS, POST_SLUGS } from "../../app/(marketing)/_content/blog-posts";

it("generates one canonical sitemap entry for every public route and article", () => {
  const entries = sitemap();
  const urls = entries.map((entry) => entry.url);
  expect(new Set(urls).size).toBe(urls.length);
  expect(urls.every((url) => url.startsWith("https://www.dhanboost.com/"))).toBe(true);
  for (const slug of POST_SLUGS) {
    const entry = entries.find((item) => item.url.endsWith(`/blog/${slug}`));
    expect(entry?.lastModified).toBe(POSTS[slug].datePublished);
  }
  expect(urls.join("\n")).not.toMatch(/\/partners|\/reviews|\/login|\/staff|\/api/);
});

it("configures apex and obsolete partner redirects", () => {
  const config = readFileSync(join(FRONTEND, "next.config.mjs"), "utf8");
  expect(config).toMatch(/type:\s*["']host["'],\s*value:\s*["']dhanboost\\\.com["']/);
  expect(config).toMatch(/destination:\s*["']https:\/\/www\.dhanboost\.com\/:path\*["']/);
  expect(config).toMatch(/source:\s*["']\/partners["'][\s\S]{0,160}destination:\s*["']\/about["']/);
});
```

- [ ] **Step 2: Run and verify RED**

Run: `npm test -- src/lib/seo/seo-invariants.test.ts`  
Expected: FAIL because sitemap dates are blanket values and redirects do not exist.

- [ ] **Step 3: Create the public route registry and refactor sitemap**

Create `public-routes.ts`:

```ts
export type PublicRoute = {
  path: `/${string}`;
  changeFrequency: "monthly" | "yearly";
  priority: number;
  lastModified?: string;
};

export const PUBLIC_ROUTES: readonly PublicRoute[] = [
  { path: "/", changeFrequency: "monthly", priority: 1 },
  { path: "/how-it-works", changeFrequency: "monthly", priority: 0.9 },
  { path: "/products", changeFrequency: "monthly", priority: 0.9 },
  { path: "/calculator", changeFrequency: "monthly", priority: 0.9 },
  { path: "/about", changeFrequency: "yearly", priority: 0.7 },
  { path: "/faq", changeFrequency: "monthly", priority: 0.7 },
  { path: "/contact", changeFrequency: "yearly", priority: 0.6 },
  { path: "/blog", changeFrequency: "monthly", priority: 0.6 },
  { path: "/fair-practices", changeFrequency: "yearly", priority: 0.5 },
  { path: "/grievance", changeFrequency: "yearly", priority: 0.5 },
  { path: "/help", changeFrequency: "monthly", priority: 0.5 },
  { path: "/careers", changeFrequency: "monthly", priority: 0.4 },
  { path: "/privacy", changeFrequency: "yearly", priority: 0.3 },
  { path: "/terms", changeFrequency: "yearly", priority: 0.3 },
];
```

Refactor `sitemap.ts` to map `PUBLIC_ROUTES` and then `POST_SLUGS`, setting article `lastModified` to `POSTS[slug].datePublished` and omitting static `lastModified` unless explicitly present.

Add Next redirects:

```js
async redirects() {
  return [
    {
      source: "/:path*",
      has: [{ type: "host", value: "dhanboost\\.com" }],
      destination: "https://www.dhanboost.com/:path*",
      permanent: true,
    },
    { source: "/partners", destination: "/about", permanent: true },
  ];
},
```

- [ ] **Step 4: Run and verify GREEN**

Run: `npm test -- src/lib/seo/seo-invariants.test.ts`  
Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add -- frontend/src/lib/seo/public-routes.ts frontend/src/app/sitemap.ts frontend/next.config.mjs frontend/src/lib/seo/seo-invariants.test.ts
git commit -m "feat(seo): generate canonical sitemap and host redirects"
```

### Task 3: Normalize the public cost calculator

**Files:**
- Modify: `frontend/src/app/(marketing)/_content/{calculator,home,products}.ts`
- Modify: `frontend/src/components/site/marketing-scripts.tsx`
- Modify: `frontend/src/lib/seo/seo-invariants.test.ts`
- Test: existing `frontend/src/lib/borrower-flow.test.ts` or create `frontend/src/lib/calc/marketing-loan-math.test.ts`

**Interfaces:**
- Consumes: `buildCostBreakdown(amount: number, tenureDays: number): LoanCostBreakdown`, `DAILY_INTEREST_RATE`, `PROCESSING_FEE_RATE`, and `GST_RATE` from `@/lib/calc/loan-math`.
- Produces: DOM IDs `oFee`, `oGst`, `oNet`, `calOFee`, `calOGst`, `calONet` and no rate-slider/APR-number IDs.

- [ ] **Step 1: Write failing cost and copy tests**

Create `frontend/src/lib/calc/marketing-loan-math.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { buildCostBreakdown } from "./loan-math";

describe("public representative loan costs", () => {
  it("shows all deductions and repayment for ₹10,000 over 30 days", () => {
    expect(buildCostBreakdown(10_000, 30)).toEqual({
      principal: 10_000,
      processingFee: 1_000,
      gstOnFee: 180,
      netDisbursed: 8_820,
      interest: 3_000,
      tenureDays: 30,
      totalRepayable: 13_000,
    });
  });
});
```

Append to SEO invariants:

```ts
it("publishes one fixed rate and no incomplete representative APR number", () => {
  const text = publicText();
  expect(text).not.toMatch(/0\.5%\s*\/\s*day|1\.5%\s*\/\s*day|from 1%/i);
  expect(text).not.toMatch(/Representative APR[\s\S]{0,80}365(?:\.0)?%/i);
  expect(text).toMatch(/processing fee[\s\S]{0,100}10%/i);
  expect(text).toMatch(/GST[\s\S]{0,100}18%/i);
});
```

- [ ] **Step 2: Run and verify RED**

Run: `npm test -- src/lib/calc/marketing-loan-math.test.ts src/lib/seo/seo-invariants.test.ts`  
Expected: math fixture PASS (existing engine), source invariant FAIL (current marketing copy/UI).

- [ ] **Step 3: Implement calculator markup and script changes**

Remove the rate slider and all numeric “Representative APR” rows. Replace them with fixed-rate text plus itemized rows for fee, GST, net disbursed, interest, and total repayment. Use this KFS wording:

```html
<div class="cr-line"><span>Processing fee (10%)</span><b id="oFee">₹1,000</b></div>
<div class="cr-line"><span>GST on fee (18%)</span><b id="oGst">₹180</b></div>
<div class="cr-line"><span>Net amount disbursed</span><b id="oNet">₹8,820</b></div>
<div class="cr-line apr"><span>All-inclusive APR</span><b>Disclosed in your KFS</b></div>
```

Import `buildCostBreakdown` into `marketing-scripts.tsx`. In both amount/tenure and repayment-date `compute()` paths, replace locally duplicated interest/APR math with the shared breakdown and update the six DOM fields. Keep donut proportions based on principal and interest only because deductions affect cash received, not repayment composition.

Change static tables to columns `Tenure`, `Interest @ 1%/day`, `Net disbursed`, `Total payable`; remove the APR column. Change homepage/product copy from “From 1%” to “Fixed 1% / day” and include fee/GST/KFS caveats.

- [ ] **Step 4: Run and verify GREEN**

Run: `npm test -- src/lib/calc/marketing-loan-math.test.ts src/lib/seo/seo-invariants.test.ts`  
Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add -- frontend/src/app/(marketing)/_content frontend/src/components/site/marketing-scripts.tsx frontend/src/lib/calc/marketing-loan-math.test.ts frontend/src/lib/seo/seo-invariants.test.ts
git commit -m "fix(seo): publish complete loan cost breakdown"
```

### Task 4: Add contextual article discovery and breadcrumb data

**Files:**
- Modify: `frontend/src/app/(marketing)/_content/blog-posts.ts`
- Modify: `frontend/src/app/(marketing)/blog/[slug]/page.tsx`
- Modify: `frontend/src/app/(marketing)/marketing-theme.css`
- Modify: `frontend/src/lib/seo/seo-invariants.test.ts`

**Interfaces:**
- Extends `BlogPost` with `relatedSlugs: readonly string[]` and optional `dateModified?: string`.
- Produces visible related links and `BreadcrumbList` JSON-LD for every article.

- [ ] **Step 1: Write failing relationship tests**

```ts
it("gives every article valid contextual related links", () => {
  for (const post of Object.values(POSTS)) {
    expect(post.relatedSlugs.length).toBeGreaterThanOrEqual(2);
    expect(post.relatedSlugs).not.toContain(post.slug);
    for (const related of post.relatedSlugs) expect(POSTS[related]).toBeDefined();
  }
});
```

- [ ] **Step 2: Run and verify RED**

Run: `npm test -- src/lib/seo/seo-invariants.test.ts`  
Expected: FAIL because `relatedSlugs` does not exist.

- [ ] **Step 3: Add curated relationships and rendered/schema output**

Add 2–3 topic-relevant slugs per post, such as KFS ↔ APR ↔ short-term-loan suitability, scams ↔ credit score, and repayment ↔ KFS. In `[slug]/page.tsx`, render a `<section aria-labelledby="related-resources">` with `Link` components after `MarketingHtml` and add `BreadcrumbList` alongside `Article` JSON-LD:

```ts
const breadcrumbLd = {
  "@context": "https://schema.org",
  "@type": "BreadcrumbList",
  itemListElement: [
    { "@type": "ListItem", position: 1, name: "Home", item: BASE },
    { "@type": "ListItem", position: 2, name: "Resources", item: `${BASE}/blog` },
    { "@type": "ListItem", position: 3, name: post.title, item: `${BASE}/blog/${post.slug}` },
  ],
};
```

Style the related section using existing card/tokens; do not add keyword-stuffed anchors.

- [ ] **Step 4: Run and verify GREEN**

Run: `npm test -- src/lib/seo/seo-invariants.test.ts`  
Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add -- frontend/src/app/(marketing)/_content/blog-posts.ts frontend/src/app/(marketing)/blog/[slug]/page.tsx frontend/src/app/(marketing)/marketing-theme.css frontend/src/lib/seo/seo-invariants.test.ts
git commit -m "feat(seo): connect articles with contextual resources"
```

### Task 5: Fix mobile rendering and accessibility

**Files:**
- Create: `frontend/e2e/marketing-seo.spec.ts`
- Modify: `frontend/src/app/(marketing)/_content/{home,calculator,faq}.ts`
- Modify: `frontend/src/app/(marketing)/marketing-theme.css`
- Modify: `frontend/src/components/site/marketing-footer.tsx`

**Interfaces:**
- Browser contract: no horizontal overflow at 320/360/390/430 px; all range inputs have accessible names; essential controls are at least 44 px high/wide; footer normal text meets contrast target through token changes.

- [ ] **Step 1: Write failing Playwright checks**

Create:

```ts
import { expect, test } from "@playwright/test";

for (const width of [320, 360, 390, 430]) {
  test(`homepage fits ${width}px without clipping`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await page.goto("/");
    await expect(page.locator(".hero-h1")).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const card = page.locator(".offer-card");
    const box = await card.boundingBox();
    expect(box && box.x >= 0 && box.x + box.width <= width).toBe(true);
  });
}

test("calculator controls have accessible names and usable targets", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/calculator");
  for (const range of await page.locator('input[type="range"]').all()) {
    await expect(range).toHaveAccessibleName(/amount|tenure/i);
  }
  for (const button of await page.locator(".cal-iconbtn, .cal-preset, .preset").all()) {
    const box = await button.boundingBox();
    expect(box && box.width >= 44 && box.height >= 44).toBe(true);
  }
});
```

- [ ] **Step 2: Start the frontend and verify RED**

Run frontend in a separate process: `npm run dev`  
Run: `npm run test:e2e -- e2e/marketing-seo.spec.ts`  
Expected: FAIL on current responsive clipping, unnamed ranges, and undersized controls.

- [ ] **Step 3: Implement semantic markup and CSS fixes**

Add `aria-label="Loan amount"` / `aria-label="Tenure in days"` to every marketing range. Wrap homepage preview FAQ buttons in heading elements matching the full FAQ pattern. Adjust mobile hero/card containment (`min-width: 0`, `max-width: 100%`, safe padding, overflow-wrap), move the decorative underline to a non-overlapping background position, set essential control min sizes to 44 px, and replace footer `#7c8ca3` normal text with an AA-compliant lighter token (at least the existing `#9fb3cc` where contrast passes). Ensure `.reveal` is visible under reduced motion and in a no-IntersectionObserver fallback.

- [ ] **Step 4: Run and verify GREEN**

Run: `npm run test:e2e -- e2e/marketing-seo.spec.ts`  
Expected: PASS at all four widths and semantic checks.

- [ ] **Step 5: Commit**

```powershell
git add -- frontend/e2e/marketing-seo.spec.ts frontend/src/app/(marketing)/_content frontend/src/app/(marketing)/marketing-theme.css frontend/src/components/site/marketing-footer.tsx
git commit -m "fix(marketing): improve mobile accessibility and containment"
```

### Task 6: Full verification and blast-radius review

**Files:**
- Modify only if verification exposes a defect in files already listed above.

**Interfaces:**
- Consumes all prior tasks.
- Produces fresh verification evidence and reviewed change impact.

- [ ] **Step 1: Run focused and full unit tests**

```powershell
cd frontend
npm test -- src/lib/seo/seo-invariants.test.ts src/lib/calc/marketing-loan-math.test.ts
npm test
```

Expected: all Vitest tests PASS with zero failures.

- [ ] **Step 2: Run static verification**

```powershell
npx tsc --noEmit
npx next lint
```

Expected: exit 0, no TypeScript or lint errors. If `next lint` is unsupported by the installed Next version, run the repository’s resolved ESLint command against `src` and `e2e` and record the substitution.

- [ ] **Step 3: Run rendered browser verification**

With the frontend dev server active:

```powershell
npm run test:e2e -- e2e/marketing-seo.spec.ts
```

Also request `/sitemap.xml`, `/robots.txt`, `/partners`, `/calculator`, and `/blog/how-to-read-a-kfs`; confirm expected status/redirect and visible content.

- [ ] **Step 4: Inspect remaining forbidden/stale claims**

```powershell
rg -n --glob '!node_modules/**' --glob '!.next/**' "Arthveda|Sentinel|Pragati Credit|Meridian Finance|partner NBFC|NBFC partner|RBI-registered NBFC|Representative APR[^\n]*365|0\.5% / day|1\.5% / day|from 1%" frontend/src frontend/public
```

Expected: no matches in public production sources. Test descriptions may describe forbidden phrases only if excluded from the production-source scan.

- [ ] **Step 5: Refresh and inspect the code-review graph**

```powershell
cd ..
code-review-graph update --brief
code-review-graph detect-changes --brief
```

Expected: review output understood; inspect every high-risk dependent and test gap before handoff.

- [ ] **Step 6: Review the final diff and commit any verification-only correction**

```powershell
git diff --check
git status --short
git diff --stat HEAD~5..HEAD
```

Expected: no whitespace errors; only scoped SEO/spec/plan files plus pre-existing unrelated user files.
