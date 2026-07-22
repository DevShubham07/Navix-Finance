# CLAUDE.md

> **⚠️ Rebrand (2026-07-22): the product is now "DhanBoost" (domain `dhanboost.com`).**
> The user-visible brand across the frontend + backend was renamed NAVIX → **DhanBoost**. The
> **internal namespace is deliberately kept as `navix`** — Java packages (`com.navix.*`), Maven
> modules (`navix-*`), env vars (`NAVIX_*`), SSM paths (`/navix/…`), session cookies
> (`navix_borrower`/`navix_staff`), the DB name, and the seeded staff logins (`*.navix.example`,
> `navixfinance@gmail.com`). The **legal entity** stays `NAVIX Finance Private Limited` (CIN
> `U64990HR2026PTC144926`). The **live SES-verified email domain is still `navixfinance.com`** and
> the DLT/telecom template registrations are still filed under NAVIX — so this doc, `aws.md`, and the
> integration/regulatory guides under `docs/` intentionally keep those `navix` identifiers. Two
> follow-ups remain outside code: (1) a real **DhanBoost logo asset** (the wordmark is renamed but
> `frontend/public/navix-mark.png` still shows the old emblem); (2) registering + SES-verifying
> `dhanboost.com` before email `From: @dhanboost.com` will actually deliver.
> Below, "NAVIX" in infra/namespace contexts = the retained internal name; the product is DhanBoost.

Guidance for Claude Code (and any human) working in this repo. This file is the **single
onboarding doc** — read it first on a fresh machine and you have the full picture: what NAVIX
is, the end-to-end workflow, how the borrower flow works, how the staff/admin login flow works,
how to run it, and what is real vs. deferred.

> Companion doc: **`dfd.md`** (the authoritative state-machine + roles spec). When this file and `dfd.md`
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

## 2. Current state (verified 2026-07-01)

NAVIX runs the **full loan lifecycle end-to-end** — a single `loan_application` aggregate (§5) wired to
a polished frontend through a BFF (§8), on **real JWT + Spring Security** (§7), with real
**Signzy (primary) + Digitap (fallback)** verification clients (§14), **S3-backed** documents, and a
**9-step verified onboarding**. It is
deployed (Vercel frontend → AWS ALB → ECS Fargate → RDS/S3/SSM; see `aws.md`). This section is the
at-a-glance map of what's live (the blow-by-blow history is in git); detail on the lifecycle, roles,
math, schema and endpoints lives once in §5/§7/§9/§10/§11.

**Lifecycle & money**
- **Lifecycle engine** — `ApplicationFlowService` walks the canonical state machine (§5), enforcing
  transitions, role-per-step, and maker-checker SoD, with an append-only `application_event` audit
  trail. At activation it mints the loan with a salary-linked due date.
- **Loan math** — `LoanMath` is the canonical integer-paise engine (§9); the outstanding is
  penalty/prepayment-aware on **every** read (`RepaymentService.outstandingAsOf`), and a loan closes
  only when that balance reaches 0.

**KYC, credit & disbursement**
- **9-step verified onboarding** — PAN · email · address · DigiLocker · bureau · salary · penny-drop ·
  selfie · agreement, each a real verification (§11); `submit-kyc` is gated on completeness
  (`KYC_INCOMPLETE`). Documents are S3-backed (presigned).
- **KYC verification dashboard** — staff progress tracker + manual PASS/FAIL override + a cross-app
  overview + borrower reminders, at `/staff/verifications`.
- **Bureau credit brief** — the Experian pull yields a **1–5★ "recommend" rating** + a NAVIX-branded
  one-page PDF (OpenPDF, stored to S3), shown on every staff detail surface and **never to the
  borrower**; the brief's identity comes from the KYC profile, not the bureau copy.
- **Disbursement** — the Disbursement Head may finalize directly with a txn id (fast-path, skips the
  accountant) or route to the Accountant to validate the transfer (§5).

**Collections, repay & reborrow**
- **Collections** — `collection_case` / `settlement` on the real bigint loan id; DPD buckets, officer
  assignment, and settlements with a **propose → approve / reject** maker-checker (proposer ≠ approver).
- **Repay** — the borrower records a payment (→ PENDING_VERIFICATION); the Accountant **verifies or
  rejects** it; at zero the loan + application close. An approved settlement caps the payable.
- **Reborrow** — returning borrowers reuse their saved KYC (salary day carried over, never re-asked);
  routed on **past delinquency only** (clean → `PRE_APPROVED`, ever-overdue → `REVIEW_PENDING` cleared
  by a KYC approver). One live loan at a time; top-up against headroom while a loan is active.

**Back-office & platform**
- **Staff console** — role-aware queues (`components/staff/live-pipeline.tsx`) across
  kyc-approvals / credit / disbursement / accounting, a live dashboard, a **Customers** roll-up, the
  company-wide **transactions ledger**, plus ADMIN-only **company-expense ledger** and a full
  **all-applications register**; branded CSV / PDF export throughout.
- **Editable profiles & settings** — borrowers self-edit non-identity profile fields (an edit can
  **invalidate** the matching verification and trigger re-verify) and toggle server-persisted
  notification preferences; staff have a self-profile.
- **Salary management** — ADMIN edits a customer's salary data with a `profile_change_log` audit; a
  monthly-salary change recomputes the eligible limit.
- **Notifications** — an event-driven, non-blocking in-app + SMS + email engine (`navix-notification`),
  surfaced to both audiences by a shared `NotificationBell` (§11/§12). Email delivers via a pluggable
  `EmailClient` (`log` default · `smtp` · **AWS `ses`** · `resend`), each message carrying a plain-text
  body + an optional branded **HTML** alternative; SES **bounce/complaint feedback** is ingested
  over SNS→SQS into an `email_suppression` list that the sender skips on future sends (§14).
- **Payment reminders** — a daily `@Scheduled` sweep (`PaymentReminderScheduler`, navix-app; the app's only
  `@EnableScheduling`) nudges every live loan: **due-soon** (`PAYMENT_DUE_SOON`, from 7 days before due through
  the day-after-salary grace — "due in N days", penalty-free) then **overdue** (`PAYMENT_OVERDUE`, the 7 days
  past grace — "₹Y overdue, pay now or credit-score + penalty"), stopping the moment the penalty-aware
  outstanding hits 0. Single-instance only (no distributed lock — TODO before scaling out).
- **Feature flags** — dev-only **DB-backed** flags (`feature_flag`, read-only API), changed via SQL with
  no redeploy; first used as a kill-switch for the referral program (§11/§12).
- **Referral** — refer-a-friend (codes, rewards, staff payout settlement), gated by the feature flag.
- **Design system** — one unified 2026 "calendar" visual language across marketing + app (§8); the
  borrower states their salary date on a compact day-of-month field at `/loan/apply` (the
  `<SalaryCalendar>` month grid lives on the reborrow `/loan/salary` page + marketing `/calculator`).

**Verification:** Postgres 16 (Docker) for local; Flyway applies all migrations on boot (§10). The
backend unit suite + a Testcontainers integration test are green; frontend `tsc` + ESLint clean. Demo
logins and seed data are in §4. Remaining go-live work is in §13 / `PRODUCTION_READINESS.md`.

---

## 3. Monorepo layout

```
navix_final/
├── backend/                      # Spring Boot, Maven multi-module (com.navix)
│   ├── navix-common/             # shared DTOs, errors, money math, ActorContext/CurrentActor
│   ├── navix-iam/                # staff users, roles (StaffRole), invites, SoD primitives
│   ├── navix-onboarding/         # applicant intake
│   ├── navix-kyc/                # DigiLocker KYC client
│   ├── navix-verification/       # Signzy (primary) + Digitap (fallback) verification clients (§14)
│   ├── navix-income-risk/        # risk A/B/C/D + eligible-limit computation
│   ├── navix-loan/               # ★ the aggregate: LoanApplication, ApplicationStatus,
│   │                             #   ApplicationFlowService, LoanService, LoanMath, controllers
│   ├── navix-disbursement/       # (legacy UUID maker-checker chain — superseded, dormant)
│   ├── navix-collections/        # DPD buckets, collection cases, settlements
│   ├── navix-storage/            # S3 abstraction (presign)
│   ├── navix-notification/       # ★ notification engine: events→dispatcher→in-app/SMS/email
│   ├── navix-app/                # ★ the only bootable module; JwtAuthFilter, SecurityConfig, Flyway
│   │   └── src/main/resources/db/migration/   # V1..V31 (the REAL schema lives here — see §10)
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
Flyway applies **all migrations** on boot (the full list is §10). Swagger UI at `http://localhost:8080/swagger-ui.html`.

### 4.3 Frontend  (http://localhost:3000)
```bash
cd frontend
npm install
npm run dev
```
The BFF route handlers reach the backend via **`BACKEND_BASE_URL`** (server-only, default
`http://localhost:8080`); every page calls the real backend through it.

### 4.4 Demo logins (real JWT)
- **Borrower:** `/login` → any 10-digit mobile → **Send code** → enter the OTP. Real OTP is delivered
  by the **UltronSMS** gateway; the **login-OTP DLT template is now approved and live** (verified
  2026-07-10 — `NAVIX_OTP_LOGIN_V2`, id `1707178366195230667`, whose text already matches the
  `navix.sms.otp-template` in `application.yml`), so real OTP works once the gateway env is set (see
  §14). For demo/testing without a handset, run the backend with **`NAVIX_SMS_MOCK=true`** → the fixed
  code **`123456`** always works (also shown as "Dev code"). Issues a real **borrower JWT** in the
  `navix_borrower` httpOnly cookie. Every "Apply now" CTA → `/signup/mobile-otp` starts the 9-step
  verified onboarding.
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

> **Build caveats:** the backend build/tests require **Java 21** — a Java 17
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
3. **Verification & KYC** — the onboarding steps (DigiLocker, selfie, penny-drop, bureau, salary…) are
   **real verifications** (§11), S3-backed; `submit-kyc` is gated on completeness, then a staff
   `KYC_APPROVER` approves (→ `KYC_APPROVED`).
4. **Choose amount** — on `/loan/apply` the borrower first **states their salary date on a compact
   day-of-month select** (1–31, which sets the real `salaryCreditDay`; the helper text previews the
   salary-linked due date computed with the exact backend rule), then picks an amount within the
   eligible limit (25% of salary) on the `AmountChooser` — the due date and full cost update live. Submitting `apply` (amount + salary-credit
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

> Post-migration the onboarding steps are **real** (DigiLocker, selfie, penny-drop, bureau, salary are
> live verifications, S3-backed) — they no longer run on the old cosmetic mock layer.

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
| Login route | mobile + OTP (`/borrower/otp/request` → `/borrower/login`) **or** password (`/borrower/password-login`); forgot/reset via link (§11) | `POST /api/auth/staff/login` (role → seeded email + `Admin@12345`, or email+password); forgot/reset via link (§11) |
| Token | **borrower JWT** (HS256, audience `borrower`, subject = customerId, **7-day TTL**) | **staff JWT** (audience `staff`, subject = staffId, role claim, 1-day TTL) |
| Cookie | `navix_borrower` `{token, id, customerId, name, mobile}` (7-day; a KYC-verified returning borrower skips `/login`) | `navix_staff` `{token, id, name, role}` |
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
| `DISBURSEMENT_HEAD` | accept for disbursal (→ `ACCOUNTANT_PENDING`); **or finalize directly with a txn id** (→ `DISBURSED`→`ACTIVE`); retry on failure; **settle referral payouts** (`referral:payout`) |
| `ACCOUNTANT` | validate bank transfer → `DISBURSED`→`ACTIVE` (mints loan) / `DISBURSEMENT_FAILED`; **verify or reject borrower repayments**; **view the transactions ledger** |
| `COLLECTION_HEAD` | collections management + settlements (**approve / reject**) |
| `COLLECTION_EXECUTIVE` | borrower collections interactions |
| `ADMIN` | oversight — **bypasses role checks**; also exempt from the credit SoD + active-executive `assign`, so may walk a loan KYC→ACTIVE **solo, per-step** (credit queue shows an **"Assign to me"** button); edits salary/profile data; manages company expenses + blocklist |
| `DEVELOPER` | internal read-only (health/logs/DB); `customer:view` only |

> Role names are the **dfd.md** names: `COLLECTION_HEAD` / `COLLECTION_EXECUTIVE` (not the old
> COLLECTIONS_HEAD / COLLECTION_OFFICER), plus `DEVELOPER`. Reconciled in Flyway **V8**.

**Permission tokens** (`frontend/src/lib/auth/rbac.ts`, mirrored by service-level guards): `kyc:approve`,
`loan:review`, `loan:approve`, `loan:disburse`, `loan:activate`, `collections:manage`,
`collections:interact`, `staff:manage`, `customer:view` (granted to **all** roles incl. DEVELOPER —
every staff member can view the Customers pane incl. PII), `customer:manage` (ADMIN — correct KYC,
cancel, blocklist), `referral:payout` (DISBURSEMENT_HEAD + ADMIN).

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
  not a literal serif). The salary-day `<SalaryCalendar>` (reborrow `/loan/salary`) and the marketing
  `/calculator` calendar share the `.cal-*` styles (unscoped in globals.css; `.navix-mkt`-scoped copy in
  marketing-theme.css). Don't reintroduce the retired "Classic Corporate" theme (navy #1B3A6B / Source
  Serif). ⚠️ Running `npm run build` while `npm run dev` is up corrupts the dev server's `.next`
  (`Cannot find module './638.js'`) — kill dev, `rm -rf .next`, restart.
- **BFF (Backend-for-Frontend):** all backend calls go through Next.js route handlers under
  `src/app/api/*`, never browser→Spring directly. Handlers are **optional catch-alls
  `[[...path]]`** (required `[...path]` does **not** match the bare base path — that was a bug,
  fixed) that read the session cookie and forward `Authorization: Bearer <jwt>` to
  `BACKEND_BASE_URL` (no demo headers). Shared logic: `lib/api/bff-session.ts` (cookies), `lib/api/bff-proxy.ts`
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
- **Fully backend-wired:** the designed journey + every staff page call the backend (onboarding,
  repay, reborrow, collections, admin). The demo Zustand mock layer no longer gates any real flow.
- **Cross-cutting UI:** a shared `NotificationBell` (`components/notifications/`) polls the inbox for
  both audiences; the staff shell hides a nav item when its feature flag is off (`navVisible` in
  `components/staff/staff-shell.tsx`); the borrower states their salary date on a day-of-month field
  at `/loan/apply` and self-edits on the `/profile` + `/settings` pages.

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
| `V15`–`V19` | the **P0–P8 production-migration** set: **V15** `application_verification` + `application_document.s3_object_key`, **V16** applicant-profile derived verification fields, **V17** `staff_user.password_hash` (BCrypt login seed), **V18** singleton `payment_settings`, **V19** admin/staff seed |
| `V20__applicant_profile_credit_brief.sql` | add `applicant_profile.{credit_star_rating, credit_recommendation, credit_brief_summary, credit_brief_generated_at, credit_brief_facts jsonb}` (the bureau credit brief; credit **score reuses `bureau_score`**) |
| `V21__notification_core.sql` | `notification` (per-recipient in-app inbox) + `notification_delivery` (per-channel send audit); partial unread index `where in_app and read_at is null` |
| `V22__applicant_profile_email.sql` | add `applicant_profile.email` (the borrower's contact email — gates the EMAIL channel) |
| `V23__applicant_profile_drop_global_identity_unique.sql` | drop V12's **global** unique indexes on pan/aadhaar/mobile → identity uniqueness is now **applicant-scoped** / app-layer, so a returning borrower can re-onboard without `DUPLICATE_MOBILE` |
| `V24__company_expense.sql` | `company_expense` ledger (ADMIN-managed operational expenses) |
| `V25__company_expense_receipt.sql` | add `company_expense.receipt_object_key` (S3 key for an uploaded receipt) |
| `V26__salary_management.sql` | add `applicant_profile.{annual_salary_paise, salary_percentage, increment_percentage}` + append-only `profile_change_log` (audited profile edits) |
| `V27__profile_editing_and_preferences.sql` | add `applicant_profile.emergency_contact_*`; new `borrower_preferences` (notification settings); `staff_user.{department, designation}` |
| `V28__referral.sql` | `referral_code` / `referral` / `referral_payout` (refer-a-friend program) |
| `V29__applicant_profile_aadhaar_verified.sql` | add `applicant_profile.aadhaar_verified` (mirrors pan/address verified; set on DigiLocker completion) |
| `V30__settlement_status.sql` | add `settlement.{status, rejected_by, rejected_at}` (maker-checker **reject** for settlements + repayments) |
| `V31__feature_flag.sql` | `feature_flag` — dev-only DB feature flags (SQL-controlled, read-only API; no write path) |
| `V32__email_suppression.sql` | `email_suppression` (bounced/complained addresses, unique on `lower(email)`) — fed by the SES SNS→SQS listener; the email sender skips suppressed addresses (§14) |
| `V33__rename_applicant_to_customer.sql` | **rename `applicant` → `customer` across the schema**: `applicant_id → customer_id` (9 tables), `applicant_profile → customer_profile`, all embedded-name indexes/constraints. The id **value** is unchanged (still mobile-derived); only names change. The guarantor `co_applicant` is deliberately **untouched**. |
| `V34__auth_passwords_and_reset.sql` | password auth: `borrower_credential` (first durable per-customer row, keyed by `customer_id`), `staff_user.mobile` (+ demo backfill `9000000000` for the email+mobile reset gate), `password_reset_token` (one-time, SHA-256-hashed, single-use, 30-min) |

**The aggregate** `loan_application`: `id`, `customer_id` (was `applicant_id`, renamed in V33), `amount_requested` (paise, nullable),
`eligible_limit`, `purpose`, `assigned_executive_id`, `loan_id`, `salary_credit_day`, `status`.
**Audit** `application_event`: `id`, `application_id`, `from_status`, `to_status`, `actor_id`,
`actor_role`, `action`, `notes`, `at` — append-only, and the source of truth for SoD checks.

> Known DB debt (deferred): no FK constraints (indexes only). The legacy `disbursement_request` UUID
> maker-checker chain is **superseded by the single aggregate** and left dormant. (`collection_case` is
> on the real **bigint** loan id — V11; `loan` carries `disbursal_txn_ref` — V13; `customer_profile`
> (renamed from `applicant_profile` in V33) identity uniqueness is **customer-scoped** — V12 added it
> globally, **V23 relaxed it** to per-customer so returning borrowers can re-onboard.)
> **Naming:** the durable per-person key is `customer_id` (renamed from `applicant_id` in V33); code uses
> `customerId` / `CustomerProfile` throughout. Only `co_applicant`/`CoApplicant` (the guarantor) keeps the
> old name. External bureau-API JSON keys (`Current_Applicant_Details`, …) are **not** ours and stay as-is.

---

## 11. Backend API surface (`/api/applications`)

All actions resolve the actor from the **JWT bearer** (`JwtAuthFilter` → `ActorContext`) and enforce
`requireRole`. Maker-checker actions return `FORBIDDEN_ROLE`, `SOD_VIOLATION`, or `ILLEGAL_TRANSITION`
(422) on violation; a missing/invalid bearer on a protected route → plain **401**.

> **Migration-added endpoints (full list in `QA_CHECKLIST.md` §B):**
> - **Auth:** `POST /api/auth/staff/login`, `POST /api/auth/borrower/otp/request`, `POST /api/auth/borrower/login`.
> - **Password auth (V34):** borrowers sign in by **password OR OTP** — `POST /api/auth/borrower/password-login`
>   (mobile+password), `POST …/borrower/set-password` (authed; optional signup step / profile). **Forgot-password**
>   for both audiences — `POST /api/auth/{borrower,staff}/forgot-password` (email+mobile gate, generic ack, no
>   enumeration) emails a **one-time reset link** (30-min, single-use, hashed at rest; surfaced in the backend
>   log when `NAVIX_EMAIL_PROVIDER=log`), redeemed at `POST /api/auth/{borrower,staff}/reset-password`
>   (token+new password; ≥10-char alnum policy; `subjectType` guards cross-audience reuse). Borrower JWT TTL is
>   **7 days** (`navix.auth.borrower-ttl-seconds`); staff stays 1 day.
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
| `GET /{customerId}` | staff (all roles) | one customer's full history: latest profile + all applications + loans + payments |
| `PUT /{customerId}/profile` | ADMIN | correct KYC + salary data (non-identity fields; PAN/Aadhaar/mobile locked) — a monthly-salary change recomputes the eligible limit |
| `GET /{customerId}/changes` | staff (all roles) | audited profile-change history (`profile_change_log`, previous→new per field) |

### Loan ledger, repayments & transactions (`/api/loan`)

| Method + path | Role | Purpose |
|---|---|---|
| `GET /{id}` · `GET /{id}/outstanding?asOf=` | any | disbursed-loan view · **prepayment-aware** balance (interest only to `asOf`) |
| `POST /{id}/repayments` · `GET /{id}/repayments` | borrower writes · any reads | record a manual repayment (→ PENDING_VERIFICATION) · list a loan's repayments |
| `POST /{id}/repayments/{pid}/verify` · `POST …/{pid}/reject` | ACCOUNTANT | confirm proof → reduce outstanding, close at zero · **reject** a pending payment (no recompute; can't reject a VERIFIED one) |
| `GET /pending-repayments` | ACCOUNTANT | repayments awaiting verification (company-wide queue) |
| `GET /transactions?q=&direction=&from=&to=` | ACCOUNTANT/ADMIN | company-wide ledger (OUTGOING disbursals + INCOMING repayments), searchable + server-side date range |

### Collections (`/api/collections`) and IAM/Admin (`/api/staff`, `/api/admin`)

Authz is enforced **server-side in the services** off the JWT actor (the BFF forwards `Bearer <jwt>`,
not headers): settlement approve/reject enforces **SoD** (proposer ≠ approver) via `ActorContext`, and
**`/api/staff*` + `/api/admin/blocklist` are ADMIN-only** (`requireAdmin`, RBAC Wave 1).

| Method + path | Purpose |
|---|---|
| `GET/POST /api/collections/cases` · `GET /cases/{id}` | list/open/read a case (real **bigint** loan id; open flips the loan → IN_COLLECTIONS) |
| `GET /api/collections/loans` · `GET /api/collections/officers` | collectible loans (ACTIVE/OVERDUE, due ≤ today) · ACTIVE collection officers (assignee picker) |
| `POST /cases/{id}/assign` · `GET/POST /cases/{id}/interactions` | assign officer (real staff id) · log/list interactions |
| `POST /cases/{id}/settlements` · `GET /settlements` · `POST /settlements/{id}/{approve,reject}` | propose · list · **approve / reject** (SoD; COLLECTION_HEAD/ADMIN) |
| `GET /api/collections/dpd?dueDate=&asOf=` | days-past-due + bucket helper |
| `GET/PUT/DELETE /api/staff` (+`/{id}`) | ADMIN — staff users: list · update role/status · disable |
| `GET/POST /api/staff/invites` · `POST /accept` | ADMIN — list/create invites (one-time token) · activate |
| `GET/POST/DELETE /api/admin/blocklist` (+`/{id}`) | ADMIN — fraud blocklist: list · add · remove |

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

### Referral (`/api/referral`) — refer-a-friend

All routes are gated by the **`referral` feature flag** (off → `REFERRAL_DISABLED`).

| Method + path | Role | Purpose |
|---|---|---|
| `GET /me` · `POST /apply` · `GET /validate?code=` | BORROWER | the caller's code + reward + earnings (`enabled` mirrors the flag) · redeem a code at signup · live preview |
| `GET /payouts?status=` · `POST /payouts/{id}/pay` · `GET /expenses` | DISBURSEMENT_HEAD/ADMIN | payout queue · settle one (logs a txn id, credits the beneficiary) · expense totals |

### KYC verification dashboard, profiles, preferences & admin registers

| Method + path | Role | Purpose |
|---|---|---|
| `GET /api/applications/{id}/verification-progress` | staff | per-application completion snapshot |
| `POST /api/applications/{id}/verifications/{checkType}/decision` | KYC_APPROVER/ADMIN | manual PASS/FAIL override (provider MANUAL, audited) |
| `GET /api/applications/verifications/overview` | staff | cross-application rows + status tallies |
| `POST /api/applications/{id}/send-reminder` | KYC_APPROVER/ADMIN | nudge the borrower on outstanding steps (no-op when nothing pending) |
| `PUT /api/applications/{id}/profile/self` | BORROWER | self-edit non-identity profile fields (may invalidate the matching verification → re-verify) |
| `GET\|PUT /api/preferences` | BORROWER | notification settings (opt-out suppresses SMS/EMAIL, never IN_APP) |
| `GET\|PUT /api/staff/me` | staff | staff self-profile (role/status stay ADMIN-only) |
| `GET/POST/DELETE /api/admin/expenses` (+`/{id}`) | ADMIN | company-expense ledger (+ receipt S3 keys) |
| `GET /api/applications/all` | ADMIN | full register of every application (complete + incomplete) |
| `GET /api/feature-flags` | any authed | dev-only flag states `{key: enabled}` for UI gating — **read-only, no write path** (flags change only via SQL, §12) |

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
- **Maker-checker steps always have a reject path.** Wherever an actor can approve (credit, disbursement,
  settlement, repayment verification), the counter-action **reject** exists too, SoD-checked
  (proposer ≠ rejecter) and audited; don't add an approve-only flow.
- **Feature flags are dev-only & read-only.** The `feature_flag` table is changed **only by SQL** — there
  is no write API and no admin UI (not even ADMIN). Code reads `FeatureFlagService.isEnabled(key)`
  (navix-common, no cache → instant, no redeploy); gate a feature by adding a row + the check.
- **Secrets** never committed — env / **SSM SecureString** at runtime (`/navix/<env>/…`). Key vars:
  `BACKEND_BASE_URL`, `NEXT_PUBLIC_API_BASE_URL`, `DB_*`, `AUTH_SECRET`, `BORROWER_AUTH_TTL_SECONDS`
  (7-day borrower session), `NAVIX_APP_BASE_URL` (reset-link base), `NAVIX_REMINDERS_CRON`,
  `AWS_PROFILE`, `NAVIX_ENV`,
  `SIGNZY_*` + `DIGITAP_*` + `NAVIX_VERIFICATION_CHAIN` (verification providers, §14; loaded from `.env`),
  `NAVIX_S3_*`, `NAVIX_SMS_*` (incl. `NAVIX_SMS_MOCK`),
  `NAVIX_EMAIL_*` (`PROVIDER` log|smtp|ses|resend · `ENABLED` · `FROM` · `CONFIGURATION_SET` for SES · `RESEND_API_KEY`),
  `NAVIX_SES_EVENTS_*` (`ENABLED` · `QUEUE` — the SES bounce/complaint SQS listener), `NAVIX_NOTIF_*` (async pool sizing),
  `NAVIX_BUREAU_FIXTURE` (demo-only, default off — a bundled credit report for local briefs).

---

## 13. Deferred (go-live backlog)

> **The full roadmap is in [`FUTURE.md`](FUTURE.md); the go/no-go production checklist is
> [`PRODUCTION_READINESS.md`](PRODUCTION_READINESS.md).** Most of the original deferred set **shipped**
> (real auth, S3, verification clients, notifications, reborrow, referral, expenses);
> the bullets below are what genuinely **remains**.

- ✅ **Done in the migration:** real auth (JWT + Spring Security, `JwtAuthFilter` replaced
  `DemoActorFilter`, staff BCrypt login); real verification clients (**Signzy primary + Digitap fallback** —
  §14; superseded the earlier Fintrix/DigiLocker layer, now removed); **S3** documents
  (presign + `s3_object_key`); bank **penny-drop**; SSM secrets; the 9-step verified onboarding; the
  admin payment block; mock-layer removal; and a **test suite** (`QA_CHECKLIST.md`, ~136 backend tests,
  Playwright `frontend/e2e/*`, `.github/workflows/ci.yml`).
- 🟢 **Borrower OTP — live.** Real **UltronSMS** client done and the **login-OTP DLT template is
  approved** (verified 2026-07-10 via `docs/sms-dlt/test-all-templates.sh` → `NAVIX_OTP_LOGIN_V2`, id
  `1707178366195230667`, gateway `ErrorCode 000`); its text matches `navix.sms.otp-template`, so OTP
  sends for real with the gateway env set (§14). `NAVIX_SMS_MOCK=true` → `123456` stays wired for
  demo/testing without a handset.
- 🟡 **The 15 lifecycle SMS (`_V2` batch)** are registered but **14 remain pending DLT approval** — the
  gateway returns `006 Invalid template text` for them (an approval-status issue, not a content one:
  the sent text is char-for-char identical to `docs/sms-dlt/SMSULTRON.md`). Keep their
  `NAVIX_SMS_DLT_*` env vars unset (the notification engine no-ops the SMS channel) until they clear;
  re-run `docs/sms-dlt/test-all-templates.sh` to see which have flipped to `000`. Full run recorded in
  `docs/sms-dlt/TEMPLATE_TEST_RESULTS.md`.
- 🟡 Staff **emailed invites** + ADMIN-gated invite create; middleware **JWT-signature verify** (still a
  presence check). Rotate the seeded `Admin@12345` + set a strong `AUTH_SECRET` for prod.
- 🔴 Real bank **payout** (NEFT/IMPS) at the accountant step; sanction-letter/agreement generation → S3.
- 🔴 DB cleanup: **FK constraints**; drop the legacy `bytea` doc column + the UUID `disbursement_request`
  table; unify applicant identity (`applicant_profile` ↔ onboarding `Borrower`); PII-at-rest encryption.
- 🔴 Persisted `borrower_standing` table (standing is recomputed from loan history today); design-system
  polish; full-Aadhaar masking; compliance/regulatory alignment (NBFC/DLG, reporting, product copy).

---

## 14. External integrations (when un-mocked)

### Verification providers — Signzy (primary) + Digitap (fallback)

NAVIX's identity/bureau/penny-drop/DigiLocker verification runs behind the provider-neutral
`VerificationPort` seam via `RoutingVerificationPort` (`@Primary`, `navix-verification`), which routes **per
capability: Signzy first, Digitap as fallback; where Signzy lacks a capability, Digitap directly**. The old
Fintrix + Fintrix-DigiLocker integration was **removed** (`git` history has it). Two per-provider adapters
(`SignzyVerificationAdapter`, `DigitapVerificationAdapter`) map provider clients → the neutral records;
a `CapabilityNotSupportedException` tells the router "skip to the next provider" vs a `VerificationException`
"tried and failed, fall through". Full API catalogs + field/sample specs: **`docs/signzy/`** (11 APIs) and
**`docs/digitap/`** (43 APIs).

| Capability (`VerificationPort`) | Provider used | Endpoint |
|---|---|---|
| `verifyPan` | **Signzy** → Digitap | Signzy `/api/v3/pan/compliance-206-individual-search` → Digitap `/validation/kyc/v1/pan_details_plus` |
| `pullBureau` | **Signzy** → Digitap | Signzy `/api/v3/bureau/experian-lite` → `/api/v3/bureau/crif` → Digitap `/credit_analytics/request` |
| `faceLiveness` (selfie) | **Digitap** | Digitap `/fmfl/v2/face-match` — **1:1 face-match** of the uploaded selfie vs the DigiLocker Aadhaar photo (no live camera). Signzy Liveness Secure (interactive iframe) is not wired to this sync port method |
| `pennyDrop` | **Signzy only** | Signzy `/api/v3/bankaccountverification/bankaccountverifications` (Digitap has no penny-drop) |
| `digilocker*` | **Signzy only** | Signzy `/api/v3/digilocker/createUrl` + `/geteaadhaarwithxml` (Digitap has no consent flow) |
| `verifyEmail` | **Digitap only** | Digitap `/cv/email_verification/v1` (Signzy has no email API) |
| `verifyAddress` | **Digitap only** | Digitap `/ent/v1/address-verification` (Signzy has no address API) |

- **Auth & hosts (env-driven, PREPRODUCTION by default).** Signzy = raw opaque token in `Authorization`
  **plus** the account id in the `x-client-unique-id` header (`SIGNZY_TOKEN` + `SIGNZY_CLIENT_UNIQUE_ID`,
  base `SIGNZY_BASE_URL` default `https://api-preproduction.signzy.app`). Digitap = HTTP
  Basic `base64(client_id:client_secret)` (`DIGITAP_CLIENT_ID`/`DIGITAP_CLIENT_SECRET`) over **two** hosts —
  `DIGITAP_SVC_BASE_URL` (default `https://svcdemo.digitap.work`, KYC/Email) + `DIGITAP_API_BASE_URL`
  (default `https://apidemo.digitap.work`, Credit/Address/Face-Match). Routing order via
  `NAVIX_VERIFICATION_CHAIN` (default `signzy,digitap`). Switch to prod by overriding the `*_BASE_URL` vars
  (`api.signzy.app`, `svc.digitap.ai`, `api.digitap.ai`). **Keys load from `backend/.env`** (auto-loaded by
  `spring-dotenv` — see `.env.example`) or SSM; never committed.
- **Bureau consent gotcha:** Signzy's `experian-lite`/`crif` require `consent.consentTimestamp` as a JSON
  **number** (epoch millis) — a string returns `400 "must be a number"`. `SignzyDtos.Consent.consentTimestamp`
  is a `long` for this reason.
- **DigiLocker (Signzy)** — consent flow unchanged in shape (init consent URL → user authorizes →
  **redirect-driven** completion, DB `AADHAAR` row is the source of truth); PASS gates on
  `x509Data.validAadhaarDSC == "yes"`. On completion NAVIX also ingests the Aadhaar **face photo** to S3 as an
  `AADHAAR_PHOTO` document, which the **selfie step face-matches against** (`ApplicationVerificationService`).
  The gotchas below still apply.
- **Selfie = face-match, not liveness (no live camera).** `verifySelfie` presigns the uploaded selfie **and**
  the stored `AADHAAR_PHOTO`, and calls `faceLiveness(selfieUrl, referenceUrl, ref)` → Digitap Face Match
  (`is_same_face` + confidence ≥ 0.60). No Aadhaar photo yet → degrades to a single-image quality check.
- **Bureau fixture** — `NAVIX_BUREAU_FIXTURE=classpath:samplepan.json` still yields a rich local credit brief
  offline (now via `SignzyExperianClient`, which tolerates both the real `jsonExperianReport` and the fixture
  `credit_report` shape).
- **Live-test status (verified 2026-07-14, preproduction/production).** ✅ Signzy PAN, **penny-drop**, CRIF
  (score 799), DigiLocker init; ✅ Digitap **Address** (200, prod host). ⚠️ **Account-side blockers, not code:**
  Digitap **Email** → `412` (product not provisioned), Digitap **Face Match** → `402` (needs account balance),
  Digitap **PAN/Credit fallback** → `403` IP-not-allowed (whitelist the caller IP; not critical since Signzy is
  primary). Signzy **Experian** may `409` on a thin/no-match identity → CRIF fallback covers it.
  **Config caveat:** the current Digitap keys are **production** but the app defaults to Digitap **preprod**
  hosts (which `401` prod keys) — set the `DIGITAP_*_BASE_URL` vars to the prod hosts to use them, or get a
  Digitap UAT key pair. Live-test scripts: `docs/signzy/test-all-signzy.sh`, `docs/digitap/test-all-digitap.sh`;
  ready-to-run curls in `docs/signzy/SIGNZY_LIVE_CURLS.md` + `docs/signzy/SIGNZY_CURLS_DIRECT.md`.
- **UltronSMS** (borrower OTP + lifecycle SMS) — `GET https://ultronsms.com/api/mt/SendSMS`, params
  `user/password/senderid/channel/DCS/flashsms/number/text/route/peid/DLTTemplateId`; success envelope
  `{ErrorCode:"0"|"000", JobId}`. Sent by `UltronSmsClient` (`navix-app`), bound from `navix.sms.*`.
  The **PEID is entity-level and constant** across all templates (verified working value
  `1701178039634361131`, sender `NAVIXF`, route `02`). Live-test **without** the app:
  `docs/sms-dlt/test-send-sms.sh <number> [text] [dltTemplateId]` (one send) and
  `docs/sms-dlt/test-all-templates.sh [number]` (sweeps every `_V2` template → a pass/fail tracker at
  `docs/sms-dlt/TEMPLATE_TEST_RESULTS.md`). The 15 template ids + exact content are in
  `docs/sms-dlt/SMSULTRON.md`; the sent text must match the registered template **char-for-char** (only
  variable slots filled), use `Rs.` not `₹` (₹ forces costly UCS-2), and any URL must be portal-
  whitelisted. **Status (2026-07-10):** `NAVIX_OTP_LOGIN_V2` approved & live; the other 14 pending
  (return `006 Invalid template text`).

**Bureau report → credit brief:** the Signzy Experian pull (`SignzyExperianClient`) unwraps the report to
`data.jsonExperianReport` and hands it to `support/ExperianFactsParser`, which parses the **full** report
(CAIS summary, outstanding balances, CAPS enquiries) into `BureauReportFacts`, not just the score — the
**same shape** the Digitap Credit client (`DigitapCreditClient`, `result.result_json.INProfileResponse`) reuses.
A **thin-file** response (no CAIS detail → `facts == null`) is score-only, no brief; a rich response yields the
brief. For local end-to-end demos set **`NAVIX_BUREAU_FIXTURE=classpath:samplepan.json`** (bundled in
`navix-app`/`navix-verification` resources; the client tolerates the fixture's `data.credit_report` shape) —
every pull then returns that report, yielding a real 4.0★ brief + PDF without a live call. The rating math +
field map live in `CreditRatingCalculator`
(see §2); the PDF needs **OpenPDF** (`com.github.librepdf:openpdf`, in the parent BOM + `navix-loan`).
The bureau facts drive the **rating + credit-health + exposure** numbers, but the brief's **displayed
identity** (name/PAN/mobile/DOB) is overridden from the borrower's `ApplicantProfile`
(`CreditBriefService.displayFacts`) — never the report's copy — so it can't show the fixture person;
the on-screen brief is always recomputed from the profile.

**DigiLocker live-flow gotchas** (touch points
`ApplicationVerificationService.{digilockerStatus,digilockerComplete}`, `signup/digilocker/page.tsx`,
`kyc/digilocker/callback/page.tsx`):
- **`digilocker_initialize` caches the consent session by `redirect_url`** and re-serves a stale,
  expired token on reuse (→ SDK "Access Denied"). **Fix:** make `redirect_url` unique per attempt
  (append `?app=<id>&sid=<nonce>`; the callback resolves the app from `localStorage`).
- **Completion is redirect-driven, not poll-driven.** The `digilocker_status` poll routinely stalls at
  `client_initiated`, so the **redirect to `/kyc/digilocker/callback`** is the completion signal and our
  **own DB is the source of truth**: the callback tab finalises via `digilockerComplete` (bounded retry
  on `DIGILOCKER_NOT_READY`, which the backend now throws instead of persisting a bogus PASS), and
  `digilockerStatus` short-circuits to PASS once the `AADHAAR` row exists. The signup tab polls until
  PASS with a ~3-min fallback to staff manual review.

**AWS SES — email delivery + bounce/complaint suppression (live, 2026-07-01):** the email channel's
`EmailClient` port (`navix-notification`) has four impls selected by `navix.email.provider`:
`log` (default, masked-log no-send), `smtp` (Boot `JavaMailSender`), **`ses`** (`SesEmailClient` over
the SES v2 SDK, reusing the **same region + default credential chain as S3** — no separate SMTP creds),
and `resend` (`ResendEmailClient` over the Resend HTTP API — interim while SES is sandbox-limited). Each
message carries a plain-text body + an optional branded **HTML** alternative (`EmailMessage.html`,
built by `EmailHtmlRenderer`).
When `navix.email.configuration-set` is set, sends are tagged with a SES **configuration set**
(`navix-notifications`) so deliverability events fire.

- **Bounce/complaint loop:** SES config-set → **SNS topic `navix-ses-events`** → **SQS queue
  `navix-ses-events`** (raw delivery, + DLQ) → `SesEventSqsListener` (`@SqsListener`, gated by
  `navix.ses.events.enabled`). A **permanent** bounce or any complaint adds the address to the
  `email_suppression` table (`EmailSuppressionService`, idempotent) and flips the originating
  `notification_delivery` row to `BOUNCED`/`COMPLAINED` (matched by the SES messageId in `provider_ref`).
  `EmailSender` then **skips** suppressed addresses (`SKIPPED("SUPPRESSED")`). Transient bounces are
  logged, not suppressed. SES account-level suppression is also on, so this is belt-and-suspenders +
  app-side visibility.
- **Run it (sandbox):** `AWS_PROFILE=navix-dev NAVIX_EMAIL_PROVIDER=ses
  NAVIX_EMAIL_FROM="NAVIX Finance <noreply@navixfinance.com>" NAVIX_SES_CONFIG_SET=navix-notifications
  NAVIX_SES_EVENTS_ENABLED=true`. The verified identity is the **domain** `navixfinance.com`, so any
  alias on it (e.g. `noreply@`) is a valid `From`. Test bounce/complaint/success without verifying
  recipients via the SES mailbox simulator (`{bounce,complaint,success}@simulator.amazonses.com`).
- **Caveats (both environmental, not code):** (1) with `NAVIX_SES_EVENTS_ENABLED=true` the app needs
  working AWS credentials **at startup** — the SQS listener resolves the queue URL eagerly and the boot
  **fails** without them (in prod that's the task role; locally pass `AWS_PROFILE=navix-dev`).
  (2) With those creds the SSM import succeeds and points the app at **RDS**, not local Docker — know
  which DB you're hitting when you test. The account is still in the **SES sandbox** (production access
  pending); real recipients need that approval.

---

## 15. Reference

- **`aws.md`** — the live cloud deployment (Vercel → ALB → ECS → RDS/S3/SSM): every resource id, the
  redeploy recipe, and the smoke tests.
- **`PRODUCTION_READINESS.md`** — go/no-go checklist for real production exposure.
- **`FUTURE.md`** — go-live roadmap for the remaining deferred set.
- **`dfd.md`** — authoritative state machine, roles, and workflows W2–W7.
- **`QA_CHECKLIST.md`** — test inventory; **`populateDummyData.md`** — seed demo data at every lifecycle
  stage (`scripts/populate-demo-data.ps1`).
- Memory (`~/.claude/.../memory/`) — `navix-application-state-machine.md` (lifecycle),
  `navix-execution-plan.md` (plan), `navix-unified-design-system.md` (the 2026 "calendar" re-skin), and
  `navix-feature-flags.md` (dev-only DB flags) capture the same decisions for cross-session continuity.
