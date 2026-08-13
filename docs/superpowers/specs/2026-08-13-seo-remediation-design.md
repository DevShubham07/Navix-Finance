# DhanBoost SEO remediation design

Date: 2026-08-13  
Status: approved in chat; awaiting written-spec review  
Scope: public marketing SEO, structured data, sitemap, canonical routing, regulated-product copy consistency, accessibility, and regression coverage

## Context and product decisions

The live SEO audit scored DhanBoost 67/100. The technical foundation is broadly healthy, but the public site contains high-impact trust inconsistencies: dormant lending partners, contradictory pricing language, an incomplete APR presentation, duplicate apex/www hosts, weak YMYL authorship, and mobile/accessibility defects.

The product owner confirmed:

- Arthveda Capital Private Limited and Sentinel Finserv Limited are not active partners and must be removed.
- No replacement NBFC partner should be published.
- DhanBoost reviews, approves, and disburses loans.
- Legal confirmation exists for that operating model.

The implementation will therefore use neutral factual language about DhanBoost reviewing, approving, and disbursing loans. It will not call DhanBoost an NBFC, claim RBI registration, invent a Certificate of Registration, or otherwise add an unsupported regulatory credential.

The repository onboarding document remains authoritative for product economics:

- daily simple interest: 1% of principal;
- processing fee: 10% of principal, deducted from disbursal;
- GST: 18% of the processing fee, deducted from disbursal;
- repayment: principal plus accrued interest, with the existing salary-linked due-date and penalty rules.

## Goals

1. Remove all inactive-partner claims and data without changing authenticated loan lifecycle behavior.
2. Present one internally consistent public cost model.
3. Stop presenting 365% as an all-inclusive representative APR when the displayed calculation excludes fee and GST.
4. Make the canonical www hostname enforceable rather than advisory.
5. Make the generated sitemap maintainable, complete, deterministic, and test-covered.
6. Fix the confirmed mobile and accessibility defects.
7. Improve internal discovery, structured data, and machine-readable entity consistency.
8. Add regression tests that prevent the audited defects from returning.

## Non-goals

- No changes to backend underwriting, sanction, disbursement, repayment, or accounting behavior.
- No invented RBI/NBFC registration details, social profiles, authors, reviewer credentials, testimonials, or external citations.
- No Google Search Console, Bing Webmaster Tools, analytics, DNS, or Vercel dashboard mutation.
- No content farm or large new topic cluster in this implementation.
- No claim that a locally calculated annualized number is the legally defined APR. The KFS remains the authoritative source for all-inclusive APR unless a compliance-approved formula is supplied later.

## Architecture and components

### 1. Public product facts

Create or extend a small typed public-loan facts module under `frontend/src/lib/`. It will expose the canonical public constants needed by marketing copy and tests: minimum/maximum amount, daily interest rate, processing-fee rate, GST rate, and approved/disbursed-by-DhanBoost wording.

The existing borrower calculation engine remains the numerical source for runtime borrower calculations. Marketing helpers may reuse its exported pure functions where dependency direction is clean; otherwise they will use a narrowly scoped shared pure calculation module rather than duplicating formulas in HTML strings and browser scripts.

`LENDING_PARTNERS` and its dormant entries will be removed. Importers will be migrated before deletion.

### 2. Partner-page disposition

The obsolete `/partners` content will not remain as a page listing nonexistent entities. Existing external/bookmarked `/partners` requests will permanently redirect to `/about` or the most relevant corporate-disclosure page. `/partners` remains absent from the sitemap.

No new “lending partners” navigation or schema relationship will be emitted.

### 3. Copy normalization

All public marketing sources, FAQ schema, blog content, legal/support copy, footer disclaimers, and `llms.txt` will be searched and normalized.

Approved language will distinguish:

- application review and approval by DhanBoost;
- disbursal by DhanBoost to the verified bank account;
- no advance payment required from the borrower;
- fee and GST deducted from the sanctioned principal;
- final terms and all-inclusive APR disclosed in the KFS before acceptance.

The implementation will remove “partner NBFC,” “lender partner,” and equivalent dormant-partner wording. It will not introduce “RBI-approved,” “RBI-registered,” or “NBFC” claims for DhanBoost.

### 4. Cost calculator and APR labels

The marketing calculator will use the canonical fixed 1% daily simple-interest model. The selectable 0.5%–1.5% rate control and “from 1%” contradictions will be removed.

The calculator and representative examples will display:

- sanctioned principal;
- processing fee at 10%;
- GST at 18% of that fee;
- net amount disbursed;
- interest for the selected tenure;
- total repayment.

The current 365% “Representative APR” number will be removed or relabelled as a nominal annualized interest-rate illustration only if that distinction remains helpful. The preferred design is to show “All-inclusive APR: see your KFS” and explain that the KFS is authoritative. This avoids publishing a fabricated or incomplete APR formula.

Marketing browser scripts and pre-rendered example tables must use the same pure calculation behavior. Tests will cover representative and boundary cases.

### 5. Canonical host routing

Add a production-only permanent redirect in Next/Vercel-compatible configuration:

`https://dhanboost.com/:path*` → `https://www.dhanboost.com/:path*`

The redirect must preserve path and query string and must not interfere with localhost or preview deployments. Existing www canonicals, OG URLs, robots sitemap URL, and sitemap entries remain unchanged.

### 6. Sitemap

Keep the Next App Router metadata route at `frontend/src/app/sitemap.ts`; do not add a static competing XML file.

Refactor it so:

- public core routes are defined in one typed collection;
- article routes are derived from the blog content source rather than copied manually;
- each article uses its `datePublished` or a future explicit `dateModified` value;
- static pages use explicit, truthful modification dates only when known;
- unreliable blanket modification dates are omitted rather than refreshed on every build;
- only canonical, indexable, 200-intent public routes are included;
- auth, borrower, staff, API, obsolete partner, and noindex pages are excluded.

The sitemap URL remains `https://www.dhanboost.com/sitemap.xml`.

### 7. Structured data and metadata

Keep sitewide `Organization` and `WebSite` schema, but remove comments/logic implying a future partner-provider node. Entity contact facts will come only from `BRAND`.

`llms.txt`, visible footer/contact pages, and schema will use the same primary support email/phone. The grievance officer contact will remain explicitly labelled as a separate escalation contact.

Blog `Article` data will gain accurate `dateModified` only where available. No fake `Person` author/reviewer entity will be added. Breadcrumb markup may be added for visible nested article breadcrumbs. Article-specific social imagery is deferred unless suitable assets can be generated from existing approved brand assets without pretending to be editorial evidence.

### 8. Internal links

Every article will gain a small, hand-curated related-reading set based on its actual topic. Commercial/support pages will link to relevant educational resources. Links will be descriptive and contextual, not a sitewide keyword block.

The article content model may gain related-slug metadata so both visible links and tests are data-driven.

### 9. Mobile and accessibility fixes

Fix the confirmed 390 px defects:

- prevent hero paragraph and offer-card horizontal truncation;
- ensure the decorative green line does not cross the H1;
- provide at least 44×44 CSS-pixel hit areas for essential navigation, calculator, and calendar controls;
- add accessible labels to range controls, including `#calAmt`;
- correct heading-order failures without changing visual hierarchy;
- raise footer text/link contrast to WCAG AA for normal text;
- make essential content visible with reduced motion and robust when scroll-trigger animations do not run.

The implementation will favor CSS containment and semantic markup over viewport-specific JavaScript patches.

## Data flow

1. Typed product/brand/content constants define public facts.
2. Server-rendered marketing content and schema consume those constants where possible.
3. Pure calculation functions produce fee, GST, net disbursal, interest, and repayment values.
4. Client-side calculator controls call the same calculation behavior or a generated equivalent with regression fixtures.
5. Sitemap generation imports the public route list and blog metadata to emit canonical URLs.
6. Tests assert copy invariants, calculation fixtures, sitemap membership, metadata/canonical behavior, accessibility semantics, and removal of inactive partner names.

## Error handling and safety

- Unknown blog-related slugs are rejected at build/test time rather than emitted as broken links.
- Sitemap generation never uses the current time, avoiding meaningless modification churn.
- Host redirects apply only when the incoming host is the production apex.
- No partner or regulatory data is substituted with placeholders.
- If stale partner wording remains anywhere under public frontend sources, the regression test fails.
- If a future marketing page lacks its own canonical or is accidentally added without an indexability decision, the SEO test fails.

## Testing strategy

Implementation follows red-green-refactor.

### Unit tests

- Product calculation fixtures for minimum, representative, and maximum amounts/tenures.
- Sitemap includes all intended static/blog routes once, uses www, and excludes private/obsolete routes.
- Sitemap article dates match content metadata.
- Public-source scan contains no Arthveda, Sentinel, or partner-NBFC language.
- Public cost copy contains one fixed daily rate and no incomplete 365% representative-APR claim.
- Contact/schema/llms facts remain consistent.

### Component/semantic tests

- Marketing range inputs have accessible names.
- Article breadcrumbs and heading order are semantically valid.
- Essential control class rules provide usable target sizes.

### Browser verification

- Desktop and mobile screenshots at 1440 px and 390 px.
- No horizontal overflow or hero clipping at 320, 360, 390, and 430 px.
- Reduced-motion rendering exposes all essential content.
- Generated `/sitemap.xml`, `/robots.txt`, representative pages, obsolete `/partners`, and apex redirect behavior are manually/provisionally checked against a production-mode local server where host simulation is possible.

### Repository verification

- targeted Vitest suite;
- full frontend Vitest suite;
- `npx tsc --noEmit`;
- ESLint using the repository-supported command;
- production build if the documented environmental Next.js issue does not prevent it;
- `code-review-graph update` and `code-review-graph detect-changes --brief` before handoff.

## Rollout

1. Land tests and implementation together.
2. Deploy to a preview and inspect the generated sitemap, metadata, calculator, obsolete partner redirect, and four mobile widths.
3. Deploy production.
4. Confirm apex redirects to www and the live sitemap contains only canonical public URLs.
5. Submit the sitemap in Google Search Console/Bing Webmaster Tools only through an authorized account; that external action is not part of this code change.

## Acceptance criteria

- No inactive partner name or generic partner-NBFC claim remains in public frontend content.
- DhanBoost approval/disbursal wording is consistent and contains no unsupported RBI/NBFC claim.
- Public cost examples match 1% daily interest, 10% fee, and 18% GST-on-fee.
- No page presents 365% as an all-inclusive representative APR.
- Apex production requests permanently consolidate to www.
- `/sitemap.xml` is generated from maintained sources, includes every intended canonical public page and excludes private/obsolete routes.
- Confirmed mobile/accessibility defects are fixed and covered proportionately by tests.
- Type checking, tests, lint, relevant browser checks, and code-review graph impact review complete with documented results.
