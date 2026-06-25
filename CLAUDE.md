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

## 2. Current state (verified 2026-06-25)

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
- ✅ **Borrower repay / prepay (live)** — `/repay` records a real manual payment
  (`POST /api/loan/{id}/repayments`, → PENDING_VERIFICATION); the **Accountant** verifies it
  (`…/verify`), which reduces the outstanding and, at zero, closes the **loan** and the **application**
  (`ApplicationFlowService.closeForLoan`, ACTIVE/OVERDUE → CLOSED). The repay page shows the
  **prepayment-aware** "pay today" amount (interest only to the day paid) via `GET …/outstanding`.
  (Reborrow `/reloan` is still mock.)
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
- ✅ **DB** — Postgres 16 via Docker; Flyway **V1–V13** apply cleanly (seeded staff/invites/blocklist/
  collection-case demo rows).

**Demo-first (not yet real):**
- 🟡 **No real auth/JWT yet.** Identity is injected via demo headers (§7). This is intentional for
  the current phase and is the main go-live item.
- 🟡 **The mock Zustand layer now backs only the leftovers** (§8): the **cosmetic, unmodeled steps**
  (DigiLocker, selfie, e-sign, penny-drop, co-applicant) and **reborrow** (`/reloan` — no backend
  endpoint yet). Repay is now live. `NEXT_PUBLIC_DEMO_MODE` defaults **on**.
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

### 4.4 Demo logins (no real passwords)
- **Borrower:** `/login` → any 10-digit mobile, **OTP `123456`** → `/dashboard` (the designed,
  backend-wired journey). Every "Apply now" CTA → `/signup/pan` starts the same live flow.
- **Staff/Admin:** `/staff/login` → **pick a role** (no password) → `/staff/dashboard` /
  `/staff/applications`. The role you pick decides which live queues/pages have data.

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

**Invariants:**
- **SoD (D3):** the actor who drove the application into `CREDIT_EXEC_APPROVED` (the recommender)
  must not be the Credit Head who approves. Enforced by replaying `application_event`
  (`ApplicationFlowService.headDecision` → `SOD_VIOLATION`). **ADMIN bypasses role checks** (oversight).
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
4. **Choose amount** — on `/loan/apply` the borrower picks an amount within the eligible limit
   (25% of salary) and submits `apply` (amount + purpose + salary-credit day). The app stays
   `KYC_APPROVED`, now flagged "applied" (`amountRequested != null`), and enters the Credit Head's queue.
5. **Track live** — `/loan/status` polls `GET …/{id}` and renders the live state-machine status +
   audit trail; no more client-side "simulate decision".
6. **Active loan** — after the staff chain completes (`ACTIVE`), `/dashboard` shows the real loan from
   `borrowerApi.loan`: **net disbursed**, **due date** (salary-linked), **total repayable**.
7. **Repay / prepay** — **live**: `/repay` reads the real loan and records a manual payment
   (`borrowerApi.recordRepayment` → PENDING_VERIFICATION); the Accountant verifies it, which reduces the
   outstanding and closes the loan + application at zero. The page shows the prepayment-aware "pay today"
   amount (interest only to the day paid). **Reborrow** (`/reloan`) is still mock (§13).

The borrower can only call **borrower** actions (`requireRole("BORROWER")`); `apply` is rejected
unless the application is `KYC_APPROVED`, the amount is ≥ ₹1,000, and (if an eligible limit is set)
within it.

> The unmodeled designed steps (DigiLocker + selfie KYC, e-sign, penny-drop, co-applicant) remain
> **cosmetic** on the Zustand mock layer — they animate but don't drive real state. The standalone
> `/apply-live` page is **retired** (redirects to `/dashboard`).

---

## 7. Staff & Admin login flow  ★ separate from borrower

**Staff/admin auth is a completely separate namespace from borrower auth — different login
endpoints, different httpOnly cookies, never shared.** This was an explicit requirement.

| | Borrower | Staff / Admin |
|---|---|---|
| Login route | `POST /api/auth/borrower/login` (mobile + OTP `123456`) | `POST /api/auth/staff/login` (pick a role, no password) |
| Cookie | `navix_borrower` `{id, applicantId, name, mobile}` | `navix_staff` `{id, name, role}` |
| Logout / me | `/api/auth/borrower/{logout,me}` | `/api/auth/staff/{logout,me}` |
| BFF proxy | `/api/borrower/applications/*`, `/api/borrower/loan/*` | `/api/staff/{applications,loan,collections,users,invites}/*`, `/api/admin/blocklist/*` |
| UI entry | `/login` → `/dashboard` (designed journey) | `/staff/login` → `/staff/dashboard` / `/staff/applications` |

**How identity reaches the backend (demo mode):** each BFF proxy reads *its own* cookie and
injects demo headers on the forwarded request — **`X-Demo-Actor-Id` / `-Name` / `-Role`**. The
backend's `DemoActorFilter` reads them into `ActorContext`/`CurrentActor`; services call
`requireRole(...)`. The staff proxy honours **only** `navix_staff`; no staff cookie → **401
`UNAUTHENTICATED`** (a borrower session cannot reach staff routes, and vice-versa). At go-live
these headers are replaced by the JWT principal — nothing else changes.

**Roles** (`StaffRole`, mirrored in `frontend/src/lib/auth/rbac.ts`) and who does which step:

| Role | Does (state transition) |
|---|---|
| `KYC_APPROVER` | approve/reject KYC → `KYC_APPROVED` / `KYC_REJECTED` |
| `CREDIT_HEAD` | assign to an executive (→ `CREDIT_EXEC_PENDING`); **final approve** (→ `CREDIT_HEAD_APPROVED`, SoD-checked) |
| `CREDIT_EXECUTIVE` | recommend/reject (→ `CREDIT_EXEC_APPROVED` / `REJECTED`) |
| `DISBURSEMENT_HEAD` | accept for disbursal (→ `ACCOUNTANT_PENDING`); **or finalize directly with a txn id** (→ `DISBURSED`→`ACTIVE`); retry on failure |
| `ACCOUNTANT` | validate bank transfer → `DISBURSED`→`ACTIVE` (mints loan) / `DISBURSEMENT_FAILED`; **verify borrower repayments**; **view the transactions ledger** |
| `COLLECTION_HEAD` | collections management + settlements |
| `COLLECTION_EXECUTIVE` | borrower collections interactions |
| `ADMIN` | oversight — **bypasses role checks** (may act in any step) |
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

All actions resolve the actor from demo headers and enforce `requireRole`. Maker-checker actions
return `FORBIDDEN_ROLE`, `SOD_VIOLATION`, or `ILLEGAL_TRANSITION` (422) on violation.

| Method + path | Role | Purpose |
|---|---|---|
| `POST /` | borrower | create DRAFT |
| `GET /?status=` | staff | list by status (stage queues) |
| `GET /credit-queue` | CREDIT_HEAD | KYC-approved **applied** applications |
| `GET /{id}` · `GET /{id}/events` | any | read application / audit trail |
| `POST /{id}/submit-kyc` | BORROWER | DRAFT → KYC_PENDING |
| `POST /{id}/kyc-decision` | KYC_APPROVER | approve/reject |
| `POST /{id}/apply` | BORROWER | set amount/purpose/salaryDay (stays KYC_APPROVED) |
| `POST /{id}/assign` | CREDIT_HEAD | assign executive → CREDIT_EXEC_PENDING |
| `POST /{id}/exec-decision` | CREDIT_EXECUTIVE | recommend/reject |
| `POST /{id}/head-decision` | CREDIT_HEAD | final approve (SoD) / reject |
| `POST /{id}/disbursement-decision` | DISBURSEMENT_HEAD | accept → ACCOUNTANT_PENDING, **or with `txnRef` → DISBURSED→ACTIVE** / reject |
| `POST /{id}/accountant-validate` | ACCOUNTANT | success → DISBURSED→ACTIVE (records `txnRef`) / fail |
| `POST /{id}/retry-disbursement` | DISBURSEMENT_HEAD | failed → ACCOUNTANT_PENDING |
| `POST /{id}/cancel` | borrower/staff | → CANCELLED (pre-disbursement) |
| `PUT /{id}/profile` · `GET /{id}/profile` | borrower writes · any reads | applicant KYC details (PAN masked on read) |
| `POST /{id}/documents` · `GET /{id}/documents` · `GET /{id}/documents/{docId}` | borrower uploads · any reads | documents (base64; metadata list + content for view/download) |

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

---

## 12. Conventions & key decisions

- **Money = integer paise (`long`), HALF_UP.** Never floats/whole-rupee for money.
- **One aggregate.** The lifecycle is one `loan_application.status`; don't reintroduce fragmented
  per-stage entities. The old `DisbursementRequest` UUID chain is dormant/superseded.
- **SoD is mandatory** and enforced server-side (flow service via the event trail), not in
  middleware. Never collapse two maker-checker steps onto one actor.
- **Demo-first identity.** Headers now, JWT later — keep services reading `CurrentActor` so the
  swap is localized to `DemoActorFilter` → a real auth filter.
- **Separate staff/borrower sessions.** Never share a cookie or BFF namespace between them.
- **Salary-linked due date ≤ 40 days** (final, over dfd D10).
- **Secrets** never committed — env vars / `application-local.yml` (gitignored). Key vars:
  `BACKEND_BASE_URL`, `NEXT_PUBLIC_API_BASE_URL`, `NEXT_PUBLIC_DEMO_MODE`, `DB_*`, `AUTH_SECRET`,
  `FINTRIX_*`, `DIGILOCKER_*`.

---

## 13. Deferred (go-live backlog)

> **The full roadmap with steps, seams, and acceptance criteria is in [`FUTURE.md`](FUTURE.md).**
> The bullets below are just the summary.

- Real **auth/JWT + Spring Security** lockdown (replace `DemoActorFilter`); real staff passwords/
  invites end-to-end (the invite/activate UI exists but is demo-grade — no password, no email).
- Real **Fintrix** (salary verify), **DigiLocker** (KYC), **S3** (sanction letters/docs), bank
  **penny-drop + transfer**.
- **Borrower reborrow** — `/repay` is now **live** (record → accountant verifies → CLOSED); `/reloan`
  (reborrow → a new draft reusing the profile) is still mock and needs a backend endpoint.
- DB cleanup: **FK constraints**; drop/repurpose the legacy UUID `disbursement_request` table;
  unify applicant identity (`applicant_profile` ↔ onboarding `Borrower`).
- Polish the reworked live pages to the design system (functional, lightly styled).

---

## 14. External integrations (when un-mocked)

- **Fintrix** (salary verification) — base `https://admin.fintrix.tech/__api/api/v1/`, HTTP Basic
  `base64(CLIENT_ID:CLIENT_SECRET)`. See `NAVIX_Fintrix_Integration_Flow.md`.
- **DigiLocker** (KYC) — headers `X-Client-ID` / `X-Client-Secret`. See `Digilocker_API_Guide.md`.

---

## 15. Reference

- **`handoff.md`** — running execution plan, phase tracker (P0–P9), detailed change log.
- **`FUTURE.md`** — go-live roadmap for the deferred set (real auth, AWS/Fintrix, hardening).
- **`dfd.md`** — authoritative state machine, roles, and workflows W2–W7.
- Memory (`~/.claude/.../memory/`) — `navix-application-state-machine.md` (lifecycle) and
  `navix-execution-plan.md` (plan) capture the same decisions for cross-session continuity.
