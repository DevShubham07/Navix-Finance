# CLAUDE.md

Guidance for Claude Code (and any human) working in this repo. This file is the **single
onboarding doc** — read it first on a fresh machine and you have the full picture: what NAVIX
is, the end-to-end workflow, how the borrower flow works, how the staff/admin login flow works,
how to run it, and what is real vs. deferred.

> Companion docs: **`handoff.md`** (the running execution plan + detailed change log) and
> **`dfd.md`** (the authoritative state-machine + roles spec). When this file and `dfd.md`
> disagree on the lifecycle, `dfd.md` wins — except the two product decisions explicitly
> recorded below (salary-linked due date, role names), which are final.

---

## 1. What NAVIX is

**NAVIX Finance** is a salary-linked, single-repayment lending platform. A salaried borrower
draws a short advance, pays an upfront fee, and repays **once** on/after their salary day.

The economics in one line (all money is **integer paise**, rounded HALF_UP):

| Rule | Value |
|---|---|
| Eligible limit | **25% of monthly salary**, floored to the nearest ₹100 |
| Minimum loan | ₹1,000 |
| Processing fee | **10%** of principal (upfront, deducted from disbursal) |
| GST | **18% on the fee** (upfront, deducted from disbursal) |
| Interest | **1%/day** on principal, over the actual tenure |
| Due date | **salary-linked** — the borrower's next salary credit, within **≤ 40 days** of disbursal |
| Late penalty | **2%/day** on principal, **capped at 30 days** |
| Repayment | a **single** installment (pay on salary day, day after, or explicit prepayment) |

So the borrower **receives** `principal − fee − GST` and **repays** `principal + interest`
(plus late penalty if overdue). Risk categories A/B/C/D affect limit/required checks, not price.
**Maker-checker separation of duties (SoD)** is a hard requirement throughout.

This is a monorepo:
- **Backend** — Spring Boot 3.4.1 / Java 21, Maven multi-module under `com.navix`.
- **Frontend** — Next.js 15 (App Router, `src/`), React 19, Tailwind, TypeScript.

---

## 2. Current state (verified 2026-06-27)

> **⚠️ Superseded in part by the P0–P8 production migration — see `handoff.md` §15.** Since that
> migration: identity is **real JWT + Spring Security** (not demo headers), the Fintrix/DigiLocker
> clients are **un-mocked** (real API calls), documents are **S3-backed**, onboarding is a **9-step
> verified wizard**, and the **mock layer is removed**. The "demo-first / mock" descriptions below
> describe the pre-migration state; treat `handoff.md` §15 as authoritative for what is live now.

The project moved well past scaffolding. **The full loan lifecycle is implemented in the backend
as one aggregate and wired to the frontend end-to-end through a BFF.**

**Working & verified:**
- ✅ **Backend lifecycle engine** — `loan_application` is a single aggregate walking the canonical
  19-state machine (§5). `ApplicationFlowService` enforces transitions, role-per-step, SoD
  (recommending Credit Executive ≠ approving Credit Head), and writes an append-only
  `application_event` audit trail. At activation it mints the loan with salary-linked due date.
- ✅ **Loan math** — `LoanMath` is the canonical integer-paise engine (fee/GST/interest/penalty/
  limit/salary-linked due date).
- ✅ **REST API** — `/api/applications/*` drives the whole flow; **collections**
  (`/api/collections/*`) and **staff/IAM** (`/api/staff`, `/api/staff/invites`,
  `/api/admin/blocklist`) endpoints are also exposed and wired (§11).
- ✅ **The committed designed UI is now the live path, end-to-end.** A borrower applies through the
  **polished journey** (signup wizard → KYC → choose amount → dashboard), and a staff team walks it
  to `ACTIVE` through **role-aware pages**: the `/staff/applications` console *and* the dedicated
  `kyc-approvals` / `credit/queue` / `disbursement` / `accounting` pages, a live `/staff/dashboard`,
  plus live **collections** and **admin** (staff / invites / blocklist). All through the BFF, with
  **separate** staff and borrower sessions (§7). The old standalone `/apply-live` page is **retired**
  (it now redirects to `/dashboard`).
- ✅ **Applicant review (KYC + documents)** — the borrower captures KYC details and uploads
  documents in the signup flow; every staff reviewer can view the masked-PAN profile and
  view/download the documents (Flyway **V9**; demo-grade storage — document bytes stored inline as
  `bytea`, base64 in/out, not S3 yet).
- ✅ **Collections on the real loans** — `collection_case` / `settlement` now key off the **bigint**
  loan id (Flyway **V11**); opening a case validates the loan and flips it `ACTIVE/OVERDUE →
  IN_COLLECTIONS`; officers + settlement proposer/approver are **real staff** (names, not UUIDs); the
  buckets page lists collectible loans with live DPD. Seam: `LoanDirectory` port in navix-common +
  adapter in navix-loan (mirrors `StaffDirectory`) — no new Maven edge.
- ✅ **Approved settlement → borrower payable + close (live)** — an **approved** partial settlement now
  caps what the borrower owes: `RepaymentService.outstandingAsOf` reads the agreed full-and-final via a
  new `SettlementDirectory` port (port in navix-common, adapter in navix-collections, consumed by
  navix-loan — the **reverse** of `LoanDirectory`, no new Maven edge) and returns
  `min(formulaOwed, settlement − verified)` (a settlement only ever **reduces** the payable). Because
  every read path (repay page, dashboard, partial-flag, close-at-zero) flows through that one method, the
  borrower's `/repay` shows **"Settlement — full & final"** (`OutstandingView.settledAmountPaise`), and
  paying it closes the loan + application and drops the case off the worklist — all automatically. No
  schema change.
- ✅ **Borrower repay / prepay (live)** — `/repay` records a real manual payment
  (`POST /api/loan/{id}/repayments`, → PENDING_VERIFICATION); the **Accountant** verifies it
  (`…/verify`), which reduces the outstanding and, at zero, closes the **loan** and the **application**
  (`ApplicationFlowService.closeForLoan`, ACTIVE/OVERDUE → CLOSED). The repay page shows the
  **prepayment-aware** "pay today" amount (interest only to the day paid) via `GET …/outstanding`.
- ✅ **Returning-borrower reborrow + review gate (live)** — `/reloan` calls
  `POST /api/applications/reborrow`, which reuses the saved KYC profile (no re-entry, **salary day
  carried over from the first loan — never re-asked**) and routes purely on **past delinquency** (credit
  score does **not** gate it): a borrower with **no past overdue** → `PRE_APPROVED` (skips KYC **and**
  credit, **no payslip/penny-drop re-steps** — "Borrow again" lands straight on choose-amount and
  submitting goes **straight to the Disbursement Head**, shown in a fast-track section), while a borrower
  who **ever had an overdue** → `REVIEW_PENDING`, a **separate** KYC-approver queue (`/staff/kyc-review`)
  that must clear them on **every** reborrow. New states `PRE_APPROVED`/`REVIEW_PENDING` (Flyway **V14**);
  standing is **computed from loan history**, no new table. (A deliberate credit-SoD relaxation for
  repeat borrowers — see §5/§7.)
- ✅ **Disbursement fast-path** — the **Disbursement Head** may finalize a release **directly** when
  they enter a transaction id (`DISBURSEMENT_PENDING → DISBURSED → ACTIVE`, recording
  `loan.disbursal_txn_ref`, Flyway **V13**), skipping the accountant; without a txn id it still routes
  to the accountant. (A deliberate SoD relaxation — see §5/§7.)
- ✅ **Accountant transactions ledger** — `GET /api/loan/transactions?q=&direction=` synthesizes a
  company-wide ledger (OUTGOING disbursals + INCOMING repayments, borrower name, masked PAN) from
  existing data — no new table; surfaced at `/staff/accounting/transactions` (searchable).
- ✅ **Unique identity at signup** — PAN / Aadhaar / mobile are unique across applicants (partial
  unique indexes, Flyway **V12**; enforced in `ApplicantReviewService`). Aadhaar is stored in full at
  the owner's request but **masked on every read** (consistent with PAN).
- ✅ **Correctness & RBAC hardening pass** (QA findings in [`PRODUCT_GAPS.md`](PRODUCT_GAPS.md)) — fixed,
  no schema change:
  - **Money:** the displayed **outstanding is penalty/prepayment-aware on every read** (loan view +
    collections `LoanSummary`, not just `GET …/outstanding`), so the dashboard, repay page and
    collections agree; **closure is penalty-aware** — an overdue loan no longer closes when only the
    no-penalty stored total is paid (the accrued late penalty stays owed). A short payment is flagged
    `partial` against the penalty-aware balance.
  - **Lifecycle:** a past-due `ACTIVE` loan **reads as `OVERDUE`** (compute-on-read in `Loan.effectiveStatus`;
    stored column unchanged until a case flips it to `IN_COLLECTIONS`); a **settled loan's collection
    case drops off** the worklist (`CollectionsService.listCaseViews` filters CLOSED/REPAID/WRITTEN_OFF).
  - **Authz:** `DemoActorFilter` now defaults to a non-privileged **`ANONYMOUS`** actor — a header-less
    or direct call fails closed at `requireRole` (no more silent ADMIN); `ApplicationFlowService.cancel`
    and collections **settlement propose/approve** carry role guards (propose = officer/head, approve =
    head, before the proposer≠approver SoD).
  - **Frontend:** the staff **role switcher** now calls the real `POST /api/auth/staff/login` (no more
    stale-session "Not your step"); the signup **address-proof upload is a real file** (→ base64 →
    `POST …/documents`, no longer cosmetic); **UI RBAC gating** added (admin pages, collections
    salary/employer behind `collections:manage`, settlement-approve, applicant-PII review by reviewer
    roles only) and **risk category / credit score are no longer shown to the borrower**; the middleware
    gate keys on the real `navix_staff` cookie.
  - _Deferred from this pass (still §13):_ reborrow endpoint, full JWT/Spring Security, emailed invites.
- ✅ **Tests** — the backend unit suite green (incl. repayment, transactions-ledger, disbursement
  fast-path, collections-bridge, identity-uniqueness); a **Testcontainers integration test** drives
  DRAFT→ACTIVE + SoD-violation + illegal-transition (3/3 green); frontend `tsc --noEmit` + ESLint clean.
- ✅ **DB** — Postgres 16 via Docker; Flyway **V1–V20** apply cleanly (seeded staff/invites/blocklist/
  collection-case demo rows).
- ✅ **Borrower account menu + my-applications (live)** — the borrower app-shell header carries an
  account dropdown (`components/app/account-menu.tsx`): Profile, **Past loans** (`/loans`), **Past
  transactions** (`/transactions`), Repay, Borrow again, Support / Help & FAQ (`/support`), Account
  settings (`/settings`), and a real **Sign out** (`logoutBorrower()` clears the `navix_borrower`
  cookie + stored app id). `/loans` and `/transactions` read **`GET /api/applications/mine`**
  (`ApplicationFlowService.myApplications`) — the caller's own applications, newest-first — plus the
  per-loan `GET /api/loan/{id}` / `…/repayments`. No schema change.
- ✅ **ADMIN full per-step control (live)** — signed in as `ADMIN`, one operator can walk an
  application KYC→ACTIVE **solo, per-step**: ADMIN is now **exempt from the credit-head SoD check**
  (`headDecision`) and from the active-Credit-Executive **assign** requirement (`assignExecutive`),
  and the credit queue renders an **"Assign to me"** button (`components/staff/live-pipeline.tsx`).
  Non-admin SoD/assignee rules are unchanged. (A deliberate oversight relaxation — see §5/§7.)
- ✅ **Per-stage demo data** — `scripts/populate-demo-data.ps1` (+ [`populateDummyData.md`](populateDummyData.md))
  drives the live API to seed one application at **every** lifecycle stage (and a primary borrower with
  history + current + in-review), so every staff queue and the borrower menu show realistic rows (§4.6).
- ✅ **Staff Customers pane (live)** — a borrower-centric roll-up at `/staff/customers` (list + search by
  name/applicant id) and `/staff/customers/{applicantId}` (current loan + past loans + payments +
  applications + KYC profile). Backed by a new `CustomerService`/`CustomerController` (`/api/customers`,
  aggregating by `applicant_id`; latest profile for name/PAN; penalty-aware outstanding reused). **Every
  staff role can view it incl. PII** (product decision — relaxes the per-role PII gating elsewhere; new
  `customer:view` perm on all roles). **ADMIN** can correct KYC data (`PUT /api/customers/{id}/profile`,
  non-identity fields only — PAN/Aadhaar/mobile stay locked) and take lifecycle actions (cancel a
  pre-disbursement application, add to the fraud blocklist) from the detail page (`customer:manage`).
  Staff application rows also gained a lazy **loan-history** block (`live-pipeline.tsx`) so any reviewer
  sees the borrower's current/past loans (amount + due date) in context. No schema change.
- ✅ **Branded CSV + PDF export (live)** — `components/staff/export-menu.tsx` + `lib/export/exporters.ts`
  (jsPDF + jspdf-autotable) add a one-click **Export ▾** (CSV / NAVIX-branded PDF) to the DPD buckets,
  settlements, admin staff/invites/blocklist, transactions ledger, and Customers dashboards. The PDF
  carries the NAVIX wordmark, the table, a **"Downloaded by {name} · {role} · {timestamp}"** provenance
  line, and a per-page confidential footer.
- ✅ **Transactions on Admin (live)** — the company-wide ledger is now a dedicated **Administration →
  Transactions** nav item, and the staff dashboard shows an **ADMIN-only** transactions summary
  (incoming/outgoing totals + latest movements) linking to the full ledger.
- ✅ **Bureau credit brief + 1–5★ rating + one-page PDF (live, 2026-06-28)** — the **existing** bureau
  pull (`ApplicationVerificationService.pullBureau`) previously kept only the score; it now **harvests
  the whole Experian report**. `ExperianClient` parses the spec's Category A/B/C fields into a neutral
  `BureauReportFacts` (in **navix-common**, carried across `VerificationPort` — no Fintrix DTO crosses
  the seam). From those facts `CreditRatingCalculator` computes a **1–5★ "should we recommend" rating**
  (base band by score → bonus if >770 & 0 defaults → −0.5 for >3 recent enquiries → cap 2.5 on any
  default → clamp; `samplepan.json` → **4.0★ RECOMMEND**) + a dynamic underwriter summary, and
  `CreditBriefPdfRenderer` (**OpenPDF**) renders a **NAVIX-branded one-page PDF** (vector stars; amounts
  as "Rs" — the base-14 font has no ₹ glyph; PAN/mobile masked). `CreditBriefService` runs at the bureau
  step — **best-effort, never breaks the pull**: it persists the rating first (so the UI works even if
  S3 is down), then renders the PDF, **stores it to S3**, and **upserts a `CREDIT_BRIEF`
  `application_document`** so the brief rides the customer's documents list. The **score + stars show on
  every staff detail surface** (live-pipeline queue rows + applicant-review card → kyc-approvals /
  credit / disbursement / accounting / applications; `credit/{id}`; Customers list **+** detail
  Profile card "next to the ID"; collections case) via new `StarRating` / `CreditBadge` /
  `CreditProfileCard`, with a **Download brief (PDF)** button. New staff-only
  `GET /api/applications/{id}/credit-brief` (`CreditBriefView`); Flyway **V20** adds
  `applicant_profile.{credit_star_rating, credit_recommendation, credit_brief_summary,
  credit_brief_generated_at, credit_brief_facts jsonb}` (credit **score reuses `bureau_score`**).
  **Staff-only**: the score/stars are stripped from the borrower's own `GET …/{id}/profile`, the
  enriched queue endpoints are staff-guarded, and **nothing** is added to the borrower `/profile`
  (honours the "never show score to the borrower" rule). Local demo: the Fintrix sandbox is thin-file,
  so set **`NAVIX_BUREAU_FIXTURE=classpath:samplepan.json`** to drive a rich brief end-to-end (§14).
- ✅ **Credit-brief identity = the real KYC profile, consistent everywhere (live, 2026-06-29)** — the
  credit-brief card / PDF previously showed the **bureau report's** copy of the applicant identity
  (Category A: name/PAN/mobile/DOB/city/PIN). That's the wrong source: the bureau copy can differ, and
  under `NAVIX_BUREAU_FIXTURE` **every** pull returns the same `samplepan.json`, so the identity belonged
  to the **fixture person** (same for everyone) — it disagreed with the (correct) profile / applicant-
  review card right beside it. Fixed in `CreditBriefService.displayFacts`: the brief's **identity now
  comes from the borrower's `ApplicantProfile`** (name/PAN/mobile/DOB; profile-wins-else-bureau) for both
  the on-screen `CreditProfileCard` (`view`) **and** the rendered PDF (`generate`); city/PIN (no KYC
  equivalent) are dropped from the identity block. The bureau's **credit-health (B) + exposure (C) +
  score** pass through unchanged (still the bureau's data). Also unified the credit **headline** (score
  + ★) on application rows: `ApplicantReviewService.effectiveProfilesByApplications` (own snapshot →
  applicant's latest profile fallback, mirroring `getProfile`) backs `ApplicationController.enrich` +
  `CustomerService.detail`, so a reborrow / snapshot-less application shows the same headline as the
  Profile card instead of a blank. No schema/endpoint change. (Already-generated PDFs keep the old
  identity until regenerated; the on-screen brief is always recomputed correct.)
- ✅ **Notification engine — in-app + SMS + email (live, 2026-06-28)** — a new **`navix-notification`**
  module (depends only on navix-common; `navix-app → navix-notification` is the only new Maven edge) turns
  every lifecycle moment into a notification, **fully decoupled** from business logic. Domain services
  publish Spring events (`ApplicationFlowService.logEvent`, `RepaymentService`, `CollectionsService`,
  `SettlementService`, `StaffService`, `InviteService`); a `@TransactionalEventListener(AFTER_COMMIT)`
  **`@Async`** listener fans them out so notifications **never block, fail, or roll back** business work.
  A self-describing **catalog** (`NotificationType` × `RecipientPolicy`) drives an `AudienceResolver`
  (fan-out across ACTIVE role holders + dedupe), a `{placeholder}` `TemplateRenderer` (unknown key → `—`),
  and the `NotificationDispatcher`, which persists **one `notification` row per recipient** (the row *is*
  the in-app inbox) and **one `notification_delivery` per channel** with per-channel **error isolation**.
  Channels are **address-gated**: IN_APP always; SMS only with a mobile (so staff never get SMS, over the
  existing `SmsGateway`/UltronSMS, mock honoured); EMAIL only with an email (`LogEmailClient` by default,
  `SmtpEmailClient` when `navix.email.provider=smtp` — mirrors the `NAVIX_SMS_MOCK` philosophy). Surfaced
  to both audiences by a shared **`NotificationBell`** (borrower app-header + staff console) polling
  `GET /api/notifications` / `…/unread-count` every 20s with mark-read / mark-all-read, scoped to the
  caller (cross-owner read/write → 404). Flyway **V21** (notification core + partial unread index) +
  **V22** (`applicant_profile.email`, captured at the signup email step for the EMAIL channel). 37 new
  unit tests (dispatcher fan-out + error-isolation, audience, renderer, listener mapping, service
  scoping, email toggle). See §11 (`/api/notifications`) and §12 (event-driven convention).
- ✅ **Unified design system + salary-calendar (live, 2026-06-28)** — the **functional app (borrower +
  staff) is re-skinned to the same "2026 calendar" design the marketing site uses** (navy `#0C2540` ·
  gold `#E9B53A` · cream `#FDFBF6`; **Bricolage Grotesque** display / **Hanken Grotesk** body / **IBM Plex
  Mono** figures), retiring the old "Classic Corporate" theme (navy #1B3A6B / Source Serif). Done as a
  **token-remap cascade, not a per-page rewrite**: the app consumes a shared vocabulary (Tailwind tokens
  + `globals.css` classes), so changing token **values** while keeping every **name** restyled all 54
  functional screens at once. Touch points: `tailwind.config.ts` (palette/fonts/radii/shadows — names
  unchanged), `globals.css` (`:root` retokenized → now **matches** the marketing `.navix-mkt` scope; `.btn*`
  /`.field`/headings restyled; `.cal-*` calendar block added), `app/layout.tsx` (functional app now loads
  Bricolage/Hanken/Plex Mono — Inter/Source Serif dropped). Only a handful of hand-fixes were needed
  (`route-progress.tsx` hardcoded teal→gold, `staff-shell.tsx` sidebar `<Brand light>` so the navy-on-navy
  wordmark shows white, `lib/export/exporters.ts` PDF colours). **Salary calendar wired into the real
  flow:** `components/borrower/salary-calendar.tsx` (`<SalaryCalendar>`) on **`/loan/apply`** lets the
  borrower pick the day their salary lands, driving the real `salaryCreditDay`; its selectable window is
  **15–40 days** (= `SALARY_DUE_MIN_CYCLE_DAYS`) so the picked date always equals what `AmountChooser` **and**
  the backend `dueDateFromSalary` resolve to (no API change). The marketing **`/calculator`** gained its own
  illustrative 7–40-day calendar (markup in `_content/calculator.ts`, scoped CSS in `marketing-theme.css`,
  JS in `marketing-scripts.tsx`). Verified `tsc` + `next lint` clean and `npm run build` green for all 50+
  routes. See §8 (frontend architecture) and the `navix-unified-design-system` memory.

**Demo-first (not yet real):**
- 🟡 **No real auth/JWT yet.** Identity is injected via demo headers (§7). This is intentional for
  the current phase and is the main go-live item.
- 🟡 **The mock Zustand layer now backs only the leftovers** (§8): the **cosmetic, unmodeled steps**
  (DigiLocker, selfie, e-sign, penny-drop, co-applicant). Repay **and reborrow** are now live.
  `NEXT_PUBLIC_DEMO_MODE` defaults **on**.
- 🟡 External integrations (Fintrix salary verify, DigiLocker KYC, S3, bank penny-drop/transfer)
  are **mocked**.

**Deferred (see §13):** real auth + Spring Security lockdown, AWS/Fintrix/DigiLocker infra,
borrower **reborrow** endpoint, FK constraints, applicant-identity unification.

---

## 3. Monorepo layout

```
navix_final/
├── backend/                      # Spring Boot, Maven multi-module (com.navix)
│   ├── navix-common/             # shared DTOs, errors, money math, ActorContext/CurrentActor
│   ├── navix-iam/                # staff users, roles (StaffRole), invites, SoD primitives
│   ├── navix-onboarding/         # applicant intake
│   ├── navix-kyc/                # DigiLocker KYC (mocked)
│   ├── navix-verification/       # Fintrix salary verification (mocked)
│   ├── navix-income-risk/        # risk A/B/C/D + eligible-limit computation
│   ├── navix-loan/               # ★ the aggregate: LoanApplication, ApplicationStatus,
│   │                             #   ApplicationFlowService, LoanService, LoanMath, controllers
│   ├── navix-disbursement/       # (legacy UUID maker-checker chain — superseded, dormant)
│   ├── navix-collections/        # DPD buckets, collection cases, settlements
│   ├── navix-storage/            # S3 abstraction (presign)
│   ├── navix-notification/       # ★ notification engine: events→dispatcher→in-app/SMS/email
│   ├── navix-app/                # ★ the only bootable module; DemoActorFilter, Flyway migrations
│   │   └── src/main/resources/db/migration/   # V1..V13 (the REAL schema lives here)
│   └── pom.xml                   # parent BOM
├── frontend/
│   └── src/
│       ├── app/
│       │   ├── (marketing)/      # public landing page
│       │   ├── (borrower)/       # borrower routes: login, signup wizard, kyc, loan, dashboard…
│       │   ├── staff/            # staff routes: login, dashboard, applications, kyc-approvals,
│       │   │                     #   credit, disbursement, accounting, collections, admin…
│       │   └── api/              # ★ the BFF: auth/{staff,borrower}, staff/{applications,collections,
│       │                         #   users,invites}, admin/blocklist, borrower/*…
│       ├── lib/
│       │   ├── api/              # typed client (applications.ts), live-journey.ts (borrower seam),
│       │   │                     #   BFF session/proxy helpers
│       │   ├── auth/rbac.ts      # StaffRole + permissions (mirrors backend)
│       │   ├── calc/             # frontend loan-math (mock layer)
│       │   └── mock/             # Zustand demo data + personas
│       └── middleware.ts         # gates /staff/* on cookie presence
├── docker-compose.yml            # Postgres 16 + Adminer
├── handoff.md                    # running execution plan + change log
└── dfd.md                        # authoritative lifecycle + roles spec
```

---

## 4. Run it locally

### Prerequisites
- **Java 21** (`sdk install java 21.0.11-tem` if needed — 17/8 won't compile the build).
- **Docker** (Colima works: `colima start --cpu 2 --memory 4 && docker context use colima`).
- **Node 20+** for the frontend.

### 4.1 Database
```bash
docker compose up -d           # Postgres 16 on localhost:5432 (db/user/pass: navix), Adminer :8081
```

### 4.2 Backend  (http://localhost:8080)
```bash
cd backend
./mvnw install -DskipTests     # FIRST build sibling jars (navix-common etc.) into ~/.m2
./mvnw -pl navix-app spring-boot:run
```
Flyway auto-applies **V1–V13** on boot. Swagger UI at `http://localhost:8080/swagger-ui.html`.

### 4.3 Frontend  (http://localhost:3000)
```bash
cd frontend
npm install
npm run dev
```
The BFF route handlers reach the backend via **`BACKEND_BASE_URL`** (server-only, default
`http://localhost:8080`). `NEXT_PUBLIC_DEMO_MODE` defaults **on** (mock UI); the live wired pages
below call the backend regardless.

### 4.4 Demo logins (post-migration — real JWT, see `handoff.md` §15)
- **Borrower:** `/login` → any 10-digit mobile → **Send code** → enter the OTP. Real OTP is delivered
  by the **UltronSMS** gateway, but is **blocked on DLT-template registration**, so for demo/testing
  run the backend with **`NAVIX_SMS_MOCK=true`** → the fixed code **`123456`** always works (also shown
  as "Dev code"). Issues a real **borrower JWT** in the `navix_borrower` httpOnly cookie. Every "Apply
  now" CTA → `/signup/mobile-otp` starts the 9-step verified onboarding.
- **Staff/Admin:** `/staff/login` → **pick a role** → the BFF authenticates for real against
  `POST /api/auth/staff/login` (role → seeded `*.navix.example` email + default password
  **`Admin@12345`**, BCrypt) and stores a **staff JWT**. The role decides which live queues have data.
  (Rotate the default password + set a strong `AUTH_SECRET` before any real exposure.)

### 4.5 Tests
```bash
cd backend
./mvnw test                    # full unit suite (no Docker needed; integration tests excluded)

# Integration test (Testcontainers Postgres) — needs Docker. On Colima, export:
export DOCKER_HOST=unix:///Users/<you>/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
export TESTCONTAINERS_RYUK_DISABLED=true
export TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1     # localhost→::1 gives "connection refused"
./mvnw -pl navix-app -Pit test                    # ApplicationFlowIntegrationTest, 3/3

cd ../frontend && npm run build                    # typecheck + build
```

> **Build caveats (verified 2026-06-25):** the backend build/tests require **Java 21** — a Java 17
> `JAVA_HOME` fails the Maven build with *"release version 21 not supported"* (set `JAVA_HOME` to a
> JDK 21 first). On the frontend, `npm run build`'s **static-prerender** step currently fails at
> `/staff/admin/staff` with a Next 15.1.3 *"React Client Manifest"* error — this **reproduces on a
> clean checkout** (an environmental/Next bug, not app code). `npm run dev`, `npx tsc --noEmit` and
> ESLint are clean; use those to verify the frontend.

### 4.6 Seed demo data (every lifecycle stage)
With the stack up, **`./scripts/populate-demo-data.ps1`** seeds one application at every stage of the
lifecycle (KYC → credit → disbursement → ACTIVE/OVERDUE/CLOSED, plus reborrow PRE_APPROVED /
REVIEW_PENDING) and a primary borrower (login mobile **9819000001**, OTP **123456**) with a closed +
active + in-review history — all through the live API, with one SQL backdate for the overdue personas.
See [`populateDummyData.md`](populateDummyData.md).

---

## 5. The end-to-end product workflow (the spine)

Everything is **one aggregate** — a single `loan_application` row with one `status` field that
walks this state machine. **No stage-skipping**; every transition is server-validated against a
transition map (`ApplicationStatus.canTransitionTo`) and logged to `application_event`.

```
DRAFT ─(borrower)→ KYC_PENDING ─(KYC_APPROVER)→ KYC_APPROVED ─(borrower applies: amount+purpose+salaryDay)
  │                     │                            └─ stays KYC_APPROVED, now "applied" ──┐
  └→ CANCELLED          └→ KYC_REJECTED                                                      ▼
   (pre-disbursement)                                              CREDIT_EXEC_PENDING ─(Credit Exec)→ CREDIT_EXEC_APPROVED
                                                                        │ reject                         │ auto-route
                                                                        └→ REJECTED                      ▼
                                                                                          CREDIT_HEAD_PENDING ─(Credit Head, SoD)→ CREDIT_HEAD_APPROVED
                                                                                                │ reject                                │ auto-route
                                                                                                └→ REJECTED                            ▼
                                                                                                                       DISBURSEMENT_PENDING ─(Disb. Head)→ ACCOUNTANT_PENDING
                                                                                                                              │ reject                          │
                                                                                                                              └→ REJECTED      (Accountant validates transfer)
                                                                                                                                          success ┌─────────────┴──────────┐ failure
                                                                                                                                                  ▼                        ▼
                                                                                                                                            DISBURSED            DISBURSEMENT_FAILED
                                                                                                                                          (mint loan) │ auto          │ retry
                                                                                                                                                       ▼              └→ ACCOUNTANT_PENDING
                                                                                                                                                    ACTIVE ──→ CLOSED   (Σ payments ≥ total)
                                                                                                                                                       └→ OVERDUE → DEFAULTED → WRITTEN_OFF
```

**Auto-routed (system) transitions** the flow service performs without a separate actor call:
`CREDIT_EXEC_APPROVED → CREDIT_HEAD_PENDING`, `CREDIT_HEAD_APPROVED → DISBURSEMENT_PENDING`,
`DISBURSED → ACTIVE` (which mints the loan via `LoanService.disburse`).

**Disbursement fast-path:** the Disbursement Head's accept normally goes
`DISBURSEMENT_PENDING → ACCOUNTANT_PENDING`, but when they supply a **transaction id** the flow
service finalizes the release directly (`DISBURSEMENT_PENDING → DISBURSED → ACTIVE`, recording
`loan.disbursal_txn_ref`), skipping the accountant — a deliberate **relaxation** of the
Disbursement-Head ≠ Accountant SoD (product decision); the no-txn-id path keeps the accountant gate.

**Repay → close:** repayments are recorded by the borrower (PENDING_VERIFICATION) and confirmed by
the Accountant (`…/repayments/{pid}/verify`); when Σ verified payments ≥ total the loan closes and
`ApplicationFlowService.closeForLoan` transitions the application `ACTIVE/OVERDUE → CLOSED`.

**Reborrow (returning borrower):** `ApplicationFlowService.reborrow` mints a **new** application for an
existing borrower, reusing their saved profile (no re-collection — **salary day carried over from the
prior loan and never re-asked**, prior penny-drop carried over; eligible limit recomputed from the
stored salary). Standing is computed from loan history (`hasPastDelinquency` — any loan ever
OVERDUE/IN_COLLECTIONS, or a verified repayment made after its due date) and is the **only** gate
(credit score does **not** gate reborrow): clean → `DRAFT → PRE_APPROVED`, flagged → `DRAFT → REVIEW_PENDING`. A `KYC_APPROVER` clears a review (`REVIEW_PENDING → PRE_APPROVED`)
or rejects it. From `PRE_APPROVED`, the borrower's `apply` routes **straight to `DISBURSEMENT_PENDING`**
(skips the credit maker-checker — a deliberate relaxation for pre-approved repeat borrowers, surfaced
to the Disbursement Head as a separate fast-track section via `ApplicationView.fastTrack`). Reborrow is
blocked while a live application/loan exists.

**Invariants:**
- **SoD (D3):** the actor who drove the application into `CREDIT_EXEC_APPROVED` (the recommender)
  must not be the Credit Head who approves. Enforced by replaying `application_event`
  (`ApplicationFlowService.headDecision` → `SOD_VIOLATION`). **ADMIN bypasses role checks** (oversight)
  **and is additionally exempt from this SoD check and from the active-Credit-Executive `assign`
  requirement**, so an admin can walk a loan KYC→ACTIVE solo, one step at a time (the console's
  "Assign to me" + each stage's action). Non-admin SoD/assignee rules are unchanged.
- Interest accrues only while `ACTIVE`; late penalty only while `OVERDUE` (≤30 days).
- The DPD bucket is computed-on-read, never stored.

---

## 6. Borrower (user) flow

How a real applicant moves through the product — this is now the **designed, backend-wired** path
(the single seam is `lib/api/live-journey.ts`, which the polished pages call):

1. **Login** — `/login`, mobile + demo OTP `123456`. Sets the `navix_borrower` cookie (separate from
   staff); identity = a numeric `applicantId` derived from the mobile. (The signup wizard's
   mobile-OTP step establishes the same session early.)
2. **Apply (signup wizard)** — the borrower fills the polished wizard (PAN, employment, salary, bank,
   address…). On **review → submit**, the frontend runs the real chain: `create` DRAFT → save KYC
   `profile` → upload `documents` → `submit-kyc` → `KYC_PENDING`.
3. **Wait for KYC** — the cosmetic DigiLocker/selfie screens animate; the real gate is a staff
   `KYC_APPROVER` (→ `KYC_APPROVED`).
4. **Choose amount** — on `/loan/apply` the borrower first **picks their salary day on the
   `<SalaryCalendar>`** (a month grid; selectable window 15–40 days, which sets the real
   `salaryCreditDay`), then picks an amount within the eligible limit (25% of salary) on the
   `AmountChooser` — the due date and full cost update live. Submitting `apply` (amount + salary-credit
   day) keeps the app `KYC_APPROVED`, now flagged "applied" (`amountRequested != null`), and enters the
   Credit Head's queue.
5. **Track live** — `/loan/status` polls `GET …/{id}` and renders the live state-machine status +
   audit trail; no more client-side "simulate decision".
6. **Active loan** — after the staff chain completes (`ACTIVE`), `/dashboard` shows the real loan from
   `borrowerApi.loan`: **net disbursed**, **due date** (salary-linked), **total repayable**.
7. **Repay / prepay** — **live**: `/repay` reads the real loan and records a manual payment
   (`borrowerApi.recordRepayment` → PENDING_VERIFICATION); the Accountant verifies it, which reduces the
   outstanding and closes the loan + application at zero. The page shows the prepayment-aware "pay today"
   amount (interest only to the day paid).
8. **Reborrow** — **live**: a returning borrower taps "Borrow again" on `/reloan`, which calls
   `borrowerApi.reborrow()`. With a clean history they're **pre-approved** (reuse profile, skip KYC +
   credit, **salary day carried over — no re-pick**) and land **straight on `/loan/apply`** → choosing an
   amount routes **straight to the Disbursement Head**; if they **ever had an overdue** they're sent to a
   KYC-approver **review** (`/staff/kyc-review`) first. See §5 (`PRE_APPROVED`/`REVIEW_PENDING`) and §11
   (`/reborrow`, `/review-decision`).

The borrower can only call **borrower** actions (`requireRole("BORROWER")`); `apply` is rejected
unless the application is `KYC_APPROVED`, the amount is ≥ ₹1,000, and (if an eligible limit is set)
within it.

> The unmodeled designed steps (DigiLocker + selfie KYC, e-sign, penny-drop, co-applicant) remain
> **cosmetic** on the Zustand mock layer — they animate but don't drive real state. The standalone
> `/apply-live` page is **retired** (redirects to `/dashboard`).

> **Account menu (live):** the app-shell header's avatar dropdown gives the signed-in borrower **Past
> loans** (`/loans`) and **Past transactions** (`/transactions`) — built from `GET /api/applications/mine`
> + per-loan loan/repayment reads — plus Support / Help & FAQ (`/support`), Account settings
> (`/settings`), and a real **Sign out** that clears the `navix_borrower` session and routes to `/login`.

---

## 7. Staff & Admin login flow  ★ separate from borrower

**Staff/admin auth is a completely separate namespace from borrower auth — different login
endpoints, different httpOnly cookies, never shared.** This was an explicit requirement.

| | Borrower | Staff / Admin |
|---|---|---|
| Login route | `POST /api/auth/borrower/otp/request` then `/borrower/login` (mobile + OTP) | `POST /api/auth/staff/login` (role → seeded email + `Admin@12345`, or email+password) |
| Token | **borrower JWT** (HS256, audience `borrower`, subject = applicantId) | **staff JWT** (audience `staff`, subject = staffId, role claim) |
| Cookie | `navix_borrower` `{token, id, applicantId, name, mobile}` | `navix_staff` `{token, id, name, role}` |
| Logout / me | `/api/auth/borrower/{logout,me}` (me strips the token) | `/api/auth/staff/{logout,me}` |
| BFF proxy | `/api/borrower/applications/*`, `/api/borrower/loan/*` | `/api/staff/{applications,loan,collections,users,invites}/*`, `/api/admin/blocklist/*`, `/api/payment-settings`, `/api/storage` |
| UI entry | `/login` → `/dashboard` | `/staff/login` → `/staff/dashboard` |

**How identity reaches the backend (REAL JWT — migration P6):** login is now in the **backend**
(`AuthController`): staff = BCrypt vs `staff_user.password_hash` (V17); borrower = OTP-verified
(`BorrowerOtpService`). The BFF stores the issued **JWT** in its httpOnly cookie and forwards
`Authorization: Bearer <jwt>` (it **no longer injects `X-Demo-Actor-*`**). The backend's
**`JwtAuthFilter`** (which replaced `DemoActorFilter`) validates the bearer and populates the **same**
`ActorContext`/`CurrentActor`; services still call `requireRole(...)` + the SoD event-trail replay.
`SecurityConfig` requires auth on `/api/**` (401 otherwise) except `/api/auth`, `/api/storage`
(decision 6), actuator, docs. Staff/borrower token audiences keep the namespaces apart.

**Roles** (`StaffRole`, mirrored in `frontend/src/lib/auth/rbac.ts`) and who does which step:

| Role | Does (state transition) |
|---|---|
| `KYC_APPROVER` | approve/reject KYC → `KYC_APPROVED` / `KYC_REJECTED`; **reborrow reviews** for returning borrowers with a past overdue (`REVIEW_PENDING` → `PRE_APPROVED` / `REJECTED`) on the separate `/staff/kyc-review` queue |
| `CREDIT_HEAD` | assign to an executive (→ `CREDIT_EXEC_PENDING`); **final approve** (→ `CREDIT_HEAD_APPROVED`, SoD-checked) |
| `CREDIT_EXECUTIVE` | recommend/reject (→ `CREDIT_EXEC_APPROVED` / `REJECTED`) |
| `DISBURSEMENT_HEAD` | accept for disbursal (→ `ACCOUNTANT_PENDING`); **or finalize directly with a txn id** (→ `DISBURSED`→`ACTIVE`); retry on failure |
| `ACCOUNTANT` | validate bank transfer → `DISBURSED`→`ACTIVE` (mints loan) / `DISBURSEMENT_FAILED`; **verify borrower repayments**; **view the transactions ledger** |
| `COLLECTION_HEAD` | collections management + settlements |
| `COLLECTION_EXECUTIVE` | borrower collections interactions |
| `ADMIN` | oversight — **bypasses role checks**; also exempt from the credit SoD + active-executive `assign`, so may walk a loan KYC→ACTIVE **solo, per-step** (credit queue shows an **"Assign to me"** button) |
| `DEVELOPER` | internal read-only (health/logs/DB); no business permissions |

> Role names are the **dfd.md** names: `COLLECTION_HEAD` / `COLLECTION_EXECUTIVE` (not the old
> COLLECTIONS_HEAD / COLLECTION_OFFICER), plus `DEVELOPER`. Reconciled in Flyway **V8**.

All staff pages are now **live and role-aware**. The shared machinery lives in
`components/staff/live-pipeline.tsx` (status-backed queues + the per-stage maker-checker action
clusters + the on-demand applicant review); the `/staff/applications` console composes it, and the
dedicated pages reuse it: `kyc-approvals`, `credit/queue` (+ `credit/{id}` detail), `disbursement`,
`accounting`. `/staff/dashboard` shows live counts/queues per role. Collections (`buckets`,
`settlements`, case detail) and Admin (`staff`, `invites`, `blocklist`) + `activate` are wired via
`collectionsApi` / `adminApi`. Settlement approval enforces **SoD** (proposer ≠ approver) server-side.
The `accounting` page also carries the **repayment-verify queue** and links the **transactions
ledger** (`accounting/transactions`). Staff screens carry small **ⓘ info-tooltips**
(`components/ui/tooltip.tsx`) on dashboard cards / queue / DPD-bucket headers so a newly-added staffer
knows what each section does.

---

## 8. Frontend architecture

- **Route groups:** `(marketing)` landing, `(borrower)` applicant flows, `staff/` back-office.
  `src/middleware.ts` gates `/staff/*` on cookie *presence* (real RBAC is enforced server-side in
  the flow service, not the middleware).
- **Design system (unified 2026 "calendar"):** one visual language across marketing **and** the
  functional app — navy `#0C2540` · gold `#E9B53A` · cream `#FDFBF6`; **Bricolage Grotesque** (display) /
  **Hanken Grotesk** (body) / **IBM Plex Mono** (figures). Tokens live in **`tailwind.config.ts`** (colour/
  font/radius/shadow scales) + **`globals.css`** `:root`, and the functional app styles via those Tailwind
  tokens (`bg-ivory`/`text-navy`/`font-serif`…) **and** globals.css component classes (`.btn*`/`.card`/
  `.field`/`.cal-*`). The marketing site re-declares the **same** tokens scoped under **`.navix-mkt`**
  (`marketing-theme.css`) so it can't bleed into the app. **Re-skin by remapping token *values*, never by
  renaming** — names are load-bearing across ~54 screens (`font-serif` is the Bricolage *display* face,
  not a literal serif). The salary-day `<SalaryCalendar>` (borrower `/loan/apply`) and the marketing
  `/calculator` calendar share the `.cal-*` styles (unscoped in globals.css; `.navix-mkt`-scoped copy in
  marketing-theme.css). Don't reintroduce the retired "Classic Corporate" theme (navy #1B3A6B / Source
  Serif). ⚠️ Running `npm run build` while `npm run dev` is up corrupts the dev server's `.next`
  (`Cannot find module './638.js'`) — kill dev, `rm -rf .next`, restart.
- **BFF (Backend-for-Frontend):** all backend calls go through Next.js route handlers under
  `src/app/api/*`, never browser→Spring directly. Handlers are **optional catch-alls
  `[[...path]]`** (required `[...path]` does **not** match the bare base path — that was a bug,
  fixed) that read the session cookie, inject demo-actor headers, and forward to
  `BACKEND_BASE_URL`. Shared logic: `lib/api/bff-session.ts` (cookies), `lib/api/bff-proxy.ts`
  (`proxyToBackend` / `joinPath` / `unauthorized` / `forbidden`). The borrower/staff **loan** proxies
  also accept **POST** (borrower records a repayment; accountant verifies one — path-restricted).
- **Typed client:** `lib/api/applications.ts` exposes `borrowerApi` (incl. `recordRepayment` /
  `repayments` / `outstanding`), `staffApi` (incl. `pendingRepayments` / `verifyRepayment` /
  `transactions`), `adminApi` (staff users / invites / blocklist), and `collectionsApi` (cases /
  interactions / settlements / DPD / collectible loans / officers). It unwraps the `ApiResponse<T>`
  envelope and throws `ApplicationApiError` carrying `error.code`. Money helpers `rupeesToPaise` /
  `paiseToINR`.
- **Live adapters:** `lib/api/live-journey.ts` is the borrower seam — session + app-id persistence,
  polling (`useLiveApplication`), the `submitOnboarding` / `applyForAmount` mutations, and the
  backend-status → designed-stage mapping that lets the polished pages reuse the existing
  components. `components/staff/live-pipeline.tsx` is the staff seam (shared queues + actions).
- **What's still mock:** the designed journey + every staff page are now backend-wired (incl. repay).
  The Zustand mock layer (`lib/mock/*`, `lib/calc/*`, `NEXT_PUBLIC_DEMO_MODE`) now only backs the
  **cosmetic unmodeled steps** (DigiLocker, selfie, e-sign, penny-drop, co-applicant) and **reborrow**.

---

## 9. Loan economics & math (`navix-loan/.../LoanMath.java`)

Canonical **integer-paise** engine (`long` paise, `BigDecimal` rates, `HALF_UP`). Constants:
`PROCESSING_FEE_RATE=0.10`, `GST_RATE=0.18`, `DAILY_INTEREST_RATE=0.01`, `LATE_PENALTY_RATE=0.02`,
`LATE_PENALTY_CAP_DAYS=30`, `LIMIT_PCT_OF_SALARY=0.25`, `LIMIT_ROUNDING_PAISE=10_000` (₹100),
`MIN_LOAN_PAISE=100_000` (₹1,000), `MAX_TERM_DAYS=40`, `SALARY_GRACE_DAYS=1`.

- `processingFeePaise` = round(principal × 0.10); `gstPaise` = round(fee × 0.18).
- `netDisbursedPaise` = principal − fee − GST  *(what the borrower receives)*.
- `interestPaise(principal, days)` = round(principal × 0.01 × days).
- `totalRepayablePaise(principal, days)` = principal + interest  *(fee/GST are **not** re-added —
  they were taken upfront)*.
- `latePenaltyPaise(principal, daysLate)` = round(principal × 0.02 × min(daysLate, 30)).
- `eligibleLimitPaise(salary)` = floor(salary × 0.25 to a multiple of ₹100).
- `dueDateFromSalary(disbursedOn, salaryCreditDay)` = the **latest** salary-credit date strictly
  after disbursal **and ≤ disbursal + 40 days** (salary day clamped to month length).
- **Outstanding is compute-on-read** (`RepaymentService.outstandingAsOf`): `principal +
  interest(daysHeld, capped at tenure) + latePenalty(daysLate past the 1-day grace) − Σ verified
  payments`. This is the single "amount owed" surfaced **everywhere** — the `LoanView` from
  `GET /api/loan/{id}`, the collections `LoanSummary`, and `GET …/outstanding` all use it (so they
  agree). The stored `loan.outstanding` column is just a recompute cache; a loan **closes only when
  this penalty-aware balance reaches 0** (paying the no-penalty total leaves the penalty owed).

**Worked example** (₹10,000 = 1,000,000 paise): fee 100,000 · GST 18,000 · **net 882,000**.
Disbursed 2026-06-03, salary day 30 → due **2026-06-30** (27 days) → total **1,270,000**.
Disbursed 2026-06-24, salary day 30 → due **2026-07-30** (36 days) → total **1,360,000**.

> **Decision (final):** due date is **salary-linked ≤ 40 days**, chosen over dfd.md D10's fixed
> +30 days. `LoanService.disburse` uses `dueDateFromSalary`; the application carries
> `salary_credit_day` (Flyway V7, optional, default 1; collected on the apply form).

---

## 10. Data model & migrations

Flyway migrations live in **`backend/navix-app/src/main/resources/db/migration/`** (not
navix-common). Applied on every boot:

| Migration | What |
|---|---|
| `V1__init.sql` | no-op placeholder |
| `V2__core_schema.sql` | 21 tables + indexes + enum CHECK constraints (the real base schema) |
| `V3__loan_money_to_paise.sql` | loan money columns → `BIGINT` paise |
| `V4__money_to_paise_rest.sql` | remaining money columns → `BIGINT` paise |
| `V5__application_state_machine.sql` | `loan_application.status` → `ApplicationStatus`; add `purpose`, `assigned_executive_id`, `loan_id`; create `application_event` audit table |
| `V6__loan_application_amount_nullable.sql` | `amount_requested` nullable (DRAFT has no amount yet) |
| `V7__loan_application_salary_credit_day.sql` | add `salary_credit_day` |
| `V8__staff_roles_rename.sql` | role check-constraint + data: COLLECTION_HEAD / COLLECTION_EXECUTIVE / +DEVELOPER |
| `V9__applicant_profile_and_documents.sql` | `applicant_profile` (1:1 KYC snapshot) + `application_document` (uploaded docs, `bytea`) for staff review |
| `V10__seed_demo_staff.sql` | seed demo staff users (one ACTIVE per role) so role-pick login resolves to a **real** staff id |
| `V11__collection_case_real_loan_and_staff_ids.sql` | retype `collection_case.loan_id`/`assigned_officer_id` + `settlement.proposed_by`/`approved_by` to **bigint** (real loan + staff ids) |
| `V12__applicant_profile_unique_identity.sql` | add `aadhaar` + `mobile` to `applicant_profile`; partial **unique** indexes on pan/aadhaar/mobile |
| `V13__loan_disbursal_txn_ref.sql` | add `loan.disbursal_txn_ref` (the outgoing disbursal's transaction id) |
| `V14__application_reborrow_states.sql` | extend the `status` CHECK with `PRE_APPROVED` / `REVIEW_PENDING` (returning-borrower reborrow); no new column/table |
| `V15`–`V19` | the **P0–P8 production-migration** set (S3 `s3_object_key`, `application_verification` + applicant-profile verification fields, staff `password_hash`, payment settings, admin/staff seed) — **detailed in `handoff.md` §15** |
| `V20__applicant_profile_credit_brief.sql` | add `applicant_profile.{credit_star_rating, credit_recommendation, credit_brief_summary, credit_brief_generated_at, credit_brief_facts jsonb}` (the bureau credit brief; credit **score reuses `bureau_score`**) |
| `V21__notification_core.sql` | `notification` (per-recipient in-app inbox) + `notification_delivery` (per-channel send audit); partial unread index `where in_app and read_at is null` |
| `V22__applicant_profile_email.sql` | add `applicant_profile.email` (the borrower's contact email — gates the EMAIL channel) |

**The aggregate** `loan_application`: `id`, `applicant_id`, `amount_requested` (paise, nullable),
`eligible_limit`, `purpose`, `assigned_executive_id`, `loan_id`, `salary_credit_day`, `status`.
**Audit** `application_event`: `id`, `application_id`, `from_status`, `to_status`, `actor_id`,
`actor_role`, `action`, `notes`, `at` — append-only, and the source of truth for SoD checks.

> Known DB debt (deferred): no FK constraints (indexes only). The legacy `disbursement_request` UUID
> maker-checker chain is **superseded by the single aggregate** and left dormant. (`collection_case` is
> now on the real **bigint** loan id — V11; `loan` carries `disbursal_txn_ref` — V13; `applicant_profile`
> carries unique `pan`/`aadhaar`/`mobile` — V12.)

---

## 11. Backend API surface (`/api/applications`)

All actions resolve the actor from the **JWT bearer** (`JwtAuthFilter` → `ActorContext`) and enforce
`requireRole`. Maker-checker actions return `FORBIDDEN_ROLE`, `SOD_VIOLATION`, or `ILLEGAL_TRANSITION`
(422) on violation; a missing/invalid bearer on a protected route → plain **401**.

> **Migration-added endpoints (full list in `QA_CHECKLIST.md` §B):**
> - **Auth:** `POST /api/auth/staff/login`, `POST /api/auth/borrower/otp/request`, `POST /api/auth/borrower/login`.
> - **Onboarding verification** (BORROWER, ownership-checked): `POST /api/applications/{id}/verify/{pan,
>   email,address,digilocker/init,bureau,salary,penny-drop,selfie,agreement,presign-upload}`,
>   `POST …/verify/digilocker/complete`, `GET …/verify/{digilocker/status,summary}`; `submit-kyc` is gated
>   (`KYC_INCOMPLETE`). Staff-readable `GET /api/applications/{id}/verifications`, `GET …/documents/{docId}/url`.
> - **Payment block:** `GET /api/payment-settings` (any authed; presigned QR/PDF URLs), `PUT` (ADMIN).

| Method + path | Role | Purpose |
|---|---|---|
| `POST /` | borrower | create DRAFT |
| `POST /reborrow` | borrower | returning borrower: new advance reusing saved profile → `PRE_APPROVED` (clean) / `REVIEW_PENDING` (past overdue) |
| `GET /?status=` | staff | list by status (stage queues); `ApplicationView.fastTrack` flags a pre-approved reborrow at disbursement |
| `GET /credit-queue` | CREDIT_HEAD | KYC-approved **applied** applications |
| `GET /{id}` · `GET /{id}/events` | any | read application / audit trail |
| `GET /mine` | BORROWER | the caller's own applications, newest-first (backs the account-menu `/loans` + `/transactions`) |
| `POST /{id}/submit-kyc` | BORROWER | DRAFT → KYC_PENDING |
| `POST /{id}/kyc-decision` | KYC_APPROVER | approve/reject |
| `POST /{id}/review-decision` | KYC_APPROVER | reborrow review: `REVIEW_PENDING` → `PRE_APPROVED` / `REJECTED` |
| `POST /{id}/apply` | BORROWER | set amount/purpose/salaryDay (KYC_APPROVED stays; **PRE_APPROVED → DISBURSEMENT_PENDING**, skipping credit) |
| `POST /{id}/assign` | CREDIT_HEAD | assign executive → CREDIT_EXEC_PENDING |
| `POST /{id}/exec-decision` | CREDIT_EXECUTIVE | recommend/reject |
| `POST /{id}/head-decision` | CREDIT_HEAD | final approve (SoD) / reject |
| `POST /{id}/disbursement-decision` | DISBURSEMENT_HEAD | accept → ACCOUNTANT_PENDING, **or with `txnRef` → DISBURSED→ACTIVE** / reject |
| `POST /{id}/accountant-validate` | ACCOUNTANT | success → DISBURSED→ACTIVE (records `txnRef`) / fail |
| `POST /{id}/retry-disbursement` | DISBURSEMENT_HEAD | failed → ACCOUNTANT_PENDING |
| `POST /{id}/cancel` | borrower/staff | → CANCELLED (pre-disbursement) |
| `PUT /{id}/profile` · `GET /{id}/profile` | borrower writes · any reads | applicant KYC details (PAN masked on read; the staff-only credit score/★ rating are **stripped** for a borrower reading their own profile) |
| `POST /{id}/documents` · `GET /{id}/documents` · `GET /{id}/documents/{docId}` | borrower uploads · any reads | documents (base64; metadata list + content for view/download) — the auto-generated `CREDIT_BRIEF` PDF rides this list |
| `GET /{id}/credit-brief` | staff only | bureau credit brief: 1–5★ rating + categorized facts (A/B/C) + summary + the `CREDIT_BRIEF` PDF doc id (`CreditBriefView`); borrower/anonymous → `FORBIDDEN_ROLE` |

### Customers (`/api/customers`) — borrower-centric roll-up

| Method + path | Role | Purpose |
|---|---|---|
| `GET /?q=` | staff (all roles) | list/search distinct applicants (name / applicant id); each row rolls up counts + total outstanding |
| `GET /{applicantId}` | staff (all roles) | one customer's full history: latest profile + all applications + loans + payments |
| `PUT /{applicantId}/profile` | ADMIN | correct KYC data (non-identity fields; PAN/Aadhaar/mobile locked) |

### Loan ledger, repayments & transactions (`/api/loan`)

| Method + path | Role | Purpose |
|---|---|---|
| `GET /{id}` · `GET /{id}/outstanding?asOf=` | any | disbursed-loan view · **prepayment-aware** balance (interest only to `asOf`) |
| `POST /{id}/repayments` · `GET /{id}/repayments` | borrower writes · any reads | record a manual repayment (→ PENDING_VERIFICATION) · list a loan's repayments |
| `POST /{id}/repayments/{pid}/verify` | ACCOUNTANT | confirm proof → reduce outstanding, close loan + application at zero |
| `GET /pending-repayments` | ACCOUNTANT | repayments awaiting verification (company-wide queue) |
| `GET /transactions?q=&direction=` | ACCOUNTANT | company-wide ledger (OUTGOING disbursals + INCOMING repayments), searchable by borrower |

### Collections (`/api/collections`) and IAM/Admin (`/api/staff`, `/api/admin`)

Controller RBAC is deferred (handoff §0.1) — only collections settlement approval enforces **SoD**
via `ActorContext` (proposer ≠ approver). The BFF still injects the staff actor headers.

| Method + path | Purpose |
|---|---|
| `GET/POST /api/collections/cases` · `GET /cases/{id}` | list/open/read a case (real **bigint** loan id; open flips the loan → IN_COLLECTIONS) |
| `GET /api/collections/loans` · `GET /api/collections/officers` | collectible loans (ACTIVE/OVERDUE, due ≤ today) · ACTIVE collection officers (assignee picker) |
| `POST /cases/{id}/assign` · `GET/POST /cases/{id}/interactions` | assign officer (real staff id) · log/list interactions |
| `POST /cases/{id}/settlements` · `GET /settlements` · `POST /settlements/{id}/approve` | propose · list · approve (SoD) |
| `GET /api/collections/dpd?dueDate=&asOf=` | days-past-due + bucket helper |
| `GET/PUT/DELETE /api/staff` (+`/{id}`) | staff users: list · update role/status · disable |
| `GET/POST /api/staff/invites` · `POST /accept` | list/create invites (one-time token) · activate |
| `GET/POST/DELETE /api/admin/blocklist` (+`/{id}`) | fraud blocklist: list · add · remove |

### Notifications (`/api/notifications`) — the caller's in-app inbox

All four endpoints are **scoped to the authenticated caller** (`NotificationService` resolves the
recipient from the JWT — `BORROWER` → applicant inbox, else staff inbox); a cross-recipient id → 404.
The borrower/staff BFF namespaces both proxy to the same backend path.

| Method + path | Role | Purpose |
|---|---|---|
| `GET /?page=&size=` | any authed | the caller's notifications, newest-first |
| `GET /unread-count` | any authed | unread in-app count for the bell badge |
| `POST /{id}/read` | any authed | mark one read (idempotent) → fresh unread count |
| `POST /read-all` | any authed | mark all read → fresh unread count (0) |

---

## 12. Conventions & key decisions

- **Money = integer paise (`long`), HALF_UP.** Never floats/whole-rupee for money.
- **One aggregate.** The lifecycle is one `loan_application.status`; don't reintroduce fragmented
  per-stage entities. The old `DisbursementRequest` UUID chain is dormant/superseded.
- **SoD is mandatory** and enforced server-side (flow service via the event trail), not in
  middleware. Never collapse two maker-checker steps onto one actor.
- **JWT identity (migration P6).** `JwtAuthFilter` validates the bearer → `CurrentActor`; services
  read `CurrentActor` + `requireRole`. The swap stayed localized to the filter/`SecurityConfig` — keep
  it that way (don't reintroduce header-trust or move authz out of the services).
- **Separate staff/borrower sessions.** Never share a cookie or BFF namespace; the JWT audience
  (`staff`/`borrower`) keeps them apart.
- **Salary-linked due date ≤ 40 days** (final, over dfd D10).
- **Notifications are event-driven & non-blocking.** Domain code never calls the engine directly — it
  **publishes a Spring event**; the `@TransactionalEventListener(AFTER_COMMIT) @Async` listener in
  `navix-notification` does the rest. Adding a new notification = add a `NotificationType` + template +
  audience and publish (or map) an event; **never** make business logic depend on a delivery succeeding.
- **Secrets** never committed — env / **SSM SecureString** at runtime (`/navix/<env>/…`). Key vars:
  `BACKEND_BASE_URL`, `NEXT_PUBLIC_API_BASE_URL`, `DB_*`, `AUTH_SECRET`, `AWS_PROFILE`, `NAVIX_ENV`,
  `FINTRIX_*`, `DIGILOCKER_*`, `NAVIX_S3_*`, `NAVIX_SMS_*` (incl. `NAVIX_SMS_MOCK`),
  `NAVIX_EMAIL_*` (`PROVIDER` log|smtp · `ENABLED` · `FROM`), `NAVIX_NOTIF_*` (async pool sizing),
  `NAVIX_BUREAU_FIXTURE` (demo-only, default off — a bundled credit report for local briefs).

---

## 13. Deferred (go-live backlog)

> **The full roadmap with steps, seams, and acceptance criteria is in [`FUTURE.md`](FUTURE.md)
> (see its 2026-06-27 banner).** Most of the original deferred set **shipped** in the P0–P8 migration
> (`handoff.md` §15); the bullets below are what genuinely **remains**.

- ✅ **Done in the migration:** real auth (JWT + Spring Security, `JwtAuthFilter` replaced
  `DemoActorFilter`, staff BCrypt login); real **Fintrix** + **DigiLocker** clients; **S3** documents
  (presign + `s3_object_key`); bank **penny-drop**; SSM secrets; the 9-step verified onboarding; the
  admin payment block; mock-layer removal; and a **test suite** (`QA_CHECKLIST.md`, ~136 backend tests,
  Playwright `frontend/e2e/*`, `.github/workflows/ci.yml`).
- 🟡 **Borrower OTP** — real **UltronSMS** client done, but SMS delivery is **blocked on a DLT-registered
  template**; a **mock mode** (`NAVIX_SMS_MOCK=true` → `123456`) is wired for demo/testing.
- 🟡 Staff **emailed invites** + ADMIN-gated invite create; middleware **JWT-signature verify** (still a
  presence check). Rotate the seeded `Admin@12345` + set a strong `AUTH_SECRET` for prod.
- 🔴 Real bank **payout** (NEFT/IMPS) at the accountant step; sanction-letter/agreement generation → S3.
- 🔴 DB cleanup: **FK constraints**; drop the legacy `bytea` doc column + the UUID `disbursement_request`
  table; unify applicant identity (`applicant_profile` ↔ onboarding `Borrower`); PII-at-rest encryption.
- 🔴 Persisted `borrower_standing` table (standing is recomputed from loan history today); design-system
  polish; full-Aadhaar masking; compliance/regulatory alignment (NBFC/DLG, reporting, product copy).

---

## 14. External integrations (when un-mocked)

- **Fintrix** (salary verification + **Experian/CRIF bureau**) — base
  `https://admin.fintrix.tech/__api/api/v1/`, HTTP Basic `base64(CLIENT_ID:CLIENT_SECRET)`. See
  `NAVIX_Fintrix_Integration_Flow.md`.
- **DigiLocker** (KYC) — headers `X-Client-ID` / `X-Client-Secret`. See `Digilocker_API_Guide.md`.

**Bureau report → credit brief (verified 2026-06-28):** `ExperianClient.pull` now parses the **full**
`individual_experian` response (`data.credit_report.*` — CAIS summary, outstanding balances, CAPS
enquiries; shape in `samplepan.json`) into `BureauReportFacts`, not just the score. The **sandbox is
thin-file** (no CAIS detail → `facts == null` → score-only, no brief); a **prod** response is rich.
For local end-to-end demos set **`NAVIX_BUREAU_FIXTURE=classpath:samplepan.json`** (bundled in
`navix-app`/`navix-verification` resources) — every pull then returns that report, yielding a real
4.0★ brief + PDF without calling Fintrix. The rating math + field map live in `CreditRatingCalculator`
(see §2); the PDF needs **OpenPDF** (`com.github.librepdf:openpdf`, in the parent BOM + `navix-loan`).
The bureau facts drive the **rating + credit-health + exposure** numbers, but the brief's **displayed
identity** (name/PAN/mobile/DOB) is overridden from the borrower's `ApplicantProfile`
(`CreditBriefService.displayFacts`) — never the report's copy — so it can't show the fixture person
(see the 2026-06-29 entry in §2). Because of the fixture, the report's identity is the same for everyone.

**DigiLocker live-flow gotchas (verified 2026-06-28, `signup/digilocker/page.tsx`):**
- **`digilocker_initialize` caches the consent session keyed by `redirect_url`.** The provider
  (Fintrix → Surepass/`notbot.in`) re-serves the **same** (eventually-expired) token for a repeat
  `redirect_url`, so a fixed callback gets stuck on a stale token → the SDK shows *"Access Denied —
  please try again with a valid token"* (its `kycapi.notbot.in/api/v1/digilocker/options` call → 401
  `token_expired`). This is **not** a domain/origin/CORS issue (the token encodes only
  `{client_id,gateway,type}`, no redirect binding). **Fix:** make `redirect_url` **unique per attempt**
  (we append `?app=<id>&sid=<nonce>`); the callback page resolves the app from `localStorage`, so the
  extra params are ignored. A never-before-used `redirect_url` always mints a fresh, valid token.
- **Completion is redirect-driven, not status-poll-driven (reworked 2026-06-30).** The
  `digilocker_status` poll is unreliable — it routinely **stalls at `client_initiated` and never
  reports `completed:true`**, so trusting that flag left the Aadhaar unfetched (the app silently fell
  to the "manual review" branch at `submit-kyc` while *looking* successful). The flow now treats the
  **redirect back to `/kyc/digilocker/callback` as the post-consent signal** and the **data endpoints
  as the source of truth**:
  - **Callback tab is the single finaliser:** on mount it calls `digilockerComplete` **directly**
    (which probes `digilocker_aadhar_xml`), with bounded retry (8× / ~30s) on a retryable
    `DIGILOCKER_NOT_READY`. The backend `digilockerComplete` now **gates on readiness** — blank
    name *and* masked-Aadhaar → throw `DIGILOCKER_NOT_READY` instead of persisting a bogus `PASS`.
  - **`digilockerStatus` is now finalized-aware:** once the `AADHAAR` row exists it **short-circuits
    to `PASS`** (skipping the flaky provider call), so the signup tab resolves off **our own DB**, not
    the provider flag. Derived carries a `finalized` boolean.
  - **Signup tab no longer advances optimistically on `client_initiated`:** it keeps polling until
    status is `PASS` (set by the callback tab), with a generous `POLL_LIMIT` (~3 min) fallback so the
    wizard never hard-blocks — on timeout it advances and the unfinished Aadhaar legitimately drops to
    staff manual review. A failed status call surfaces a retry.
  - _Touch points:_ `ApplicationVerificationService.{digilockerStatus,digilockerComplete}`,
    `signup/digilocker/page.tsx`, `kyc/digilocker/callback/page.tsx`. The alternative API on
    `solution.fintrix.tech` (a possibly-newer DigiLocker–Aadhaar flow) is **unevaluated** — it's a
    JS-rendered SPA, not fetchable headlessly.

---

## 15. Reference

- **`handoff.md`** — running execution plan, phase tracker (P0–P9), detailed change log.
- **`FUTURE.md`** — go-live roadmap for the deferred set (real auth, AWS/Fintrix, hardening).
- **`populateDummyData.md`** — seed demo data at every lifecycle stage (`scripts/populate-demo-data.ps1`).
- **`dfd.md`** — authoritative state machine, roles, and workflows W2–W7.
- Memory (`~/.claude/.../memory/`) — `navix-application-state-machine.md` (lifecycle),
  `navix-execution-plan.md` (plan), and `navix-unified-design-system.md` (the 2026 "calendar" re-skin +
  salary-calendar wiring) capture the same decisions for cross-session continuity.
