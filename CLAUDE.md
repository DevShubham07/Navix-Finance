# CLAUDE.md

> **⚠️ Rebrand (2026-07-22): the product is now "DhanBoost" (domain `dhanboost.com`).**
> The user-visible brand across the frontend + backend was renamed NAVIX → **DhanBoost**. The
> **internal namespace is deliberately kept as `navix`** — Java packages (`com.navix.*`), Maven
> modules (`navix-*`), env vars (`NAVIX_*`), SSM paths (`/navix/…`), session cookies
> (`navix_borrower`/`navix_staff`), the DB name, and the seeded staff logins (`*.navix.example`,
> `navixfinance@gmail.com`). The **legal entity** stays `NAVIX Finance Private Limited` (CIN
> `U64990HR2026PTC144926`). The **live SES-verified email domain is still `navixfinance.com`** and
> the DLT/telecom template registrations are still filed under NAVIX — so this doc, `aws.md`, and the
> integration/regulatory guides under `docs/` intentionally keep those `navix` identifiers. The
> **real DhanBoost logo shipped 2026-07-31** — the green rupee/growth-arrow art, cut from
> `docs/brand/dhanboost-logo-sheet.jpeg`: the app-icon tile lives at `frontend/public/navix-mark.png`
> (+ `-64`, `src/app/icon.png`, `apple-icon.png`) and the transparent standalone mark at
> `frontend/public/dhanboost-mark.png` (schema.org logo + the PDF-header base64 in
> `lib/export/brand-mark.ts`). The tile supplies its own background, so `.brand-mark--img` /
> `.logo--img` / `.offer-logo` deliberately carry **no** navy plate. One follow-up remains outside
> code: registering + SES-verifying `dhanboost.com` before email `From: @dhanboost.com` will
> actually deliver.
> Below, "NAVIX" in infra/namespace contexts = the retained internal name; the product is DhanBoost.

Guidance for Claude Code (and any human) working in this repo. This file is the **single
onboarding doc** — read it first on a fresh machine and you have the full picture: what NAVIX
is, the end-to-end workflow, how the borrower flow works, how the staff/admin login flow works,
how to run it, and what is real vs. deferred.

> This file is the onboarding and lifecycle source of truth. Historical database migrations remain
> immutable even where their comments describe an older workflow.

## Code-review graph (required for code work)

Use the repository's `code-review-graph` before broad text searches or manual dependency tracing.
For implementation, debugging, refactoring, and review tasks, query the graph first to locate the
relevant symbols, callers, dependents, execution flows, and tests; use ordinary file search only to
fill gaps or inspect exact text. Before reviewing or handing off code changes, run
`code-review-graph detect-changes --brief` (or the equivalent MCP change/review tools) to inspect the
blast radius and test coverage.

The graph is local state under `.code-review-graph/` and is intentionally gitignored. Native Windows
agent hooks call `scripts/code-review-graph-hook.ps1` after edits, a CRG watch daemon refreshes changes
made outside agents, and the git pre-commit hook provides a final incremental update. If the graph is
missing or stale, run `code-review-graph build` once, then `code-review-graph update` as needed. Do not
hand-edit the graph database or commit generated graph artifacts.

---

## Working Agreements

### Asking vs. Assuming
Before implementing any multi-file or multi-step feature, ask clarifying questions about scope,
target branch, and acceptance criteria. Do NOT assume requirements. If a request is ambiguous
(product names, repo names, account names), confirm before researching or coding.

### Shell Environment
This is a Windows machine using Git Bash and PowerShell. Never emit Windows `^` line continuations
in bash commands (use `\`). For curl with file payloads use `MSYS_NO_PATHCONV=1` only on path args,
and prefer `--data-binary @file` over inline bodies to avoid arg-length limits. Avoid PowerShell
here-strings in git commit messages — use `git commit -F <file>` instead.

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

## 2. Current state

DhanBoost runs the **full loan lifecycle end-to-end** — a single `loan_application` aggregate (§5)
wired to a Next.js frontend through a BFF (§8), on real JWT + Spring Security (§7), with real
verification providers (Fintrix bureau · Signzy · Digitap), S3-backed documents, and a two-phase
borrower journey (intake → credit sanction → offer journey, §6). It is deployed (Vercel → ALB → ECS
Fargate → RDS/S3/SSM; see `aws.md`), and CI deploys on every push to `main`.

> **What is live, feature by feature, and what remains before go-live: [`docs/STATE.md`](docs/STATE.md).**
> The blow-by-blow history is in git; the roadmap is [`FUTURE.md`](FUTURE.md) and
> [`PRODUCTION_READINESS.md`](PRODUCTION_READINESS.md).

Two live landmines worth knowing before you deploy anything:
- **The backend image carries the OTP SMS template.** Only the old NAVIX-worded `NAVIX_OTP_LOGIN_V2`
  is DLT-approved, so ECS task-def **revision 4 pins `NAVIX_SMS_OTP_TEMPLATE`** to that wording.
  Redeploying from `application.yml` defaults swaps in unapproved text and every send fails
  `006 Invalid template text`. All 15 `DHANBOOST_*_V1` templates are still awaiting operator approval.
- **The credit-score auto-reject is suspended** (`bureau-auto-reject` flag, V64). Every bureau result
  goes to a human until the floor is recalibrated against CRIF's distribution — see §12 and
  [`docs/STATE.md`](docs/STATE.md).

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
│   ├── navix-collections/        # DPD buckets, collection cases, settlements
│   ├── navix-storage/            # S3 abstraction (presign)
│   ├── navix-notification/       # ★ notification engine: events→dispatcher→in-app/SMS/email
│   ├── navix-app/                # ★ the only bootable module; JwtAuthFilter, SecurityConfig, Flyway
│   │   └── src/main/resources/db/migration/   # V1..V65 (the REAL schema lives here — see §10)
│   └── pom.xml                   # parent BOM
├── frontend/
│   └── src/
│       ├── app/
│       │   ├── (marketing)/      # public landing page
│       │   ├── (borrower)/       # borrower routes: login, signup wizard, kyc, loan, dashboard…
│       │   ├── staff/            # staff routes: login, dashboard, applications (the one stage
│       │   │                     #   console), customers, loans, verifications, performance,
│       │   │                     #   collections, telecalling, leads, dsa, admin/*…
│       │   └── api/              # ★ the BFF: auth/{staff,borrower}, staff/{applications,collections,
│       │                         #   users,invites}, admin/blocklist, borrower/*…
│       ├── lib/
│       │   ├── api/              # typed client (applications.ts), live-journey.ts (borrower seam),
│       │   │                     #   BFF session/proxy helpers
│       │   ├── auth/rbac.ts      # StaffRole + permissions (mirrors backend)
│       │   └── calc/             # frontend loan-math (mirrors LoanMath; live borrower pages)
│       └── middleware.ts         # gates /staff/* on cookie presence
└── docker-compose.yml            # Postgres 16 + Adminer
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
  `navix_borrower` httpOnly cookie. Every "Apply now" CTA → `/signup/otp` starts the Phase-1 intake
  (§6).
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
For a fully offline demo/recording stack, `scripts/run-demo.ps1` runs the backend on **:8090** and
Postgres on **:5433** (not the :8080/:5432 defaults in §4.2/§4.3 above — a native PostgreSQL on some
machines already holds :5432, and :8080 can be reserved by Windows http.sys). With that stack up,
**`.\scripts\seed-demo-data.ps1`** seeds one application at every stage of the lifecycle (KYC →
credit → disbursement → ACTIVE/OVERDUE/CLOSED, plus reborrow PRE_APPROVED / REVIEW_PENDING) and
every back-office surface (collections, settlements, expenses, blocklist, invites, referral payouts)
— all through the live API, with a small SQL companion for the handful of states the API can't
produce (overdue backdating, DEFAULTED/WRITTEN_OFF, trend spreading, ADMIN notifications). See
[`populateDummyData.md`](populateDummyData.md) for the full seeding guide and
[`DEMO_WALKTHROUGH.md`](DEMO_WALKTHROUGH.md) for the chaptered recording script it supports.

---

## Verification Rules

Never report tests or builds as passing based on grepped output — always check the actual exit
code (`echo $?` or `set -o pipefail`). Run typecheck + lint before every commit. Never claim a
deploy succeeded until you have verified the running image/commit SHA matches what was just
pushed.

---

## 5. The end-to-end product workflow (the spine)

Everything is **one aggregate** — a single `loan_application` row with one `status` field that
walks this state machine. **No stage-skipping**; every transition is server-validated against a
transition map (`ApplicationStatus.canTransitionTo`) and logged to `application_event`.

```
DRAFT → KYC_PENDING → CREDIT_EXEC_PENDING → SANCTIONED → DISBURSEMENT_PENDING
  │         │                 │                  │                 │
  └→ CANCELLED/REJECTED       ├→ REJECTED        │                 └→ DISBURSED → ACTIVE → CLOSED
                             └→ pending in place └→ borrower completes offer journey
```

`CREDIT_EXEC_PENDING` is the single live credit-review stage. The Credit Head assigns to self or an
active Credit Executive and may reassign without clearing review data or pending notes. The Head can
decide any file; an Executive can see and decide only files assigned to them; ADMIN keeps oversight.
Accepting sanctions directly into `SANCTIONED`; there is no second Credit Head approval stage.

**Disbursement (no accountant hop — V47/V48):** the Disbursement Head makes the transfer and records
its id: `DISBURSEMENT_PENDING → DISBURSED → ACTIVE`, writing `loan.disbursal_txn_ref`. Accepting
**without** a `txnRef` is an error (`TXN_REF_REQUIRED`), not a hand-off — the transfer either happened
and has a reference, or there is nothing to accept. `accountantValidate` and its endpoint are gone;
`ACCOUNTANT_PENDING` stays in the enum + CHECK for historical `application_event` rows only, and V48
moved every file parked there back onto the Head's desk. A failed transfer is retried by the same
Head (`retry-disbursement`). ADMIN may force `SANCTIONED → DISBURSEMENT_PENDING`
(`force-disbursement-pending`).

**Offer journey (`SANCTIONED`):** a sanction is an amount + repayment date, and the borrower then
walks `JourneyService.OfferStep` (amount → repayment-date → DigiLocker → references → summary →
selfie → address → sanction letter → eSign → 🎉 → disbursal account) under `/api/applications/{id}/
offer/*`. Confirming the disbursal account is what routes the file to `DISBURSEMENT_PENDING`. A
sanction never expires.

**Repay → close:** repayments are recorded by the borrower (PENDING_VERIFICATION) and confirmed by
the Accountant (`…/repayments/{pid}/verify`); when Σ verified payments ≥ total the loan closes and
`ApplicationFlowService.closeForLoan` transitions the application `ACTIVE/OVERDUE → CLOSED`.

**Reborrow (returning borrower):** `ApplicationFlowService.reborrow` mints a **new** application for an
existing borrower, reusing their saved profile (no re-collection — **salary day carried over from the
prior loan and never re-asked**, prior penny-drop carried over; eligible limit recomputed from the
stored salary). Standing is computed from loan history (`isDisqualifiedByHistory` — repaid more than
`LATE_REPAYMENT_TOLERANCE_DAYS` late, or a prior advance never fully repaid) and is the **only** gate
(credit score does **not** gate reborrow): disqualified → an outright **auto-reject** into the
rejection register (V45 retired the `REVIEW_PENDING` manual queue — there is no review desk behind
it); clean → `DRAFT → PRE_APPROVED`, and if a prior sanction exists `carryOverForReapply` copies it
forward → `SANCTIONED`, so the borrower re-walks only the short offer journey (amount → locked date →
summary → sanction letter → eSign → 🎉 → account). Carried over: KYC profile, sanctioned ceiling,
salary day, DigiLocker/selfie/address evidence, references, disbursal account. **Not** carried: the
eSign — every advance is signed afresh against its own Key Fact Statement. The repayment date is
recomputed from the carried salary day. Reborrow is blocked while a live application/loan exists.

**Invariants:**
- Every credit assignment/reassignment and decision is appended to `application_event`; reassignment
  records the previous and new assignee and keeps the application in `CREDIT_EXEC_PENDING`.
- Interest accrues through the contractual salary day and for one additional grace day. The grace
  day has no late penalty; 2% daily penalty starts the following day and is capped at 30 days.
- The DPD bucket is computed-on-read, never stored.

---

## 6. Borrower (user) flow

How a real applicant moves through the product — this is now the **designed, backend-wired** path
(the single seam is `lib/api/live-journey.ts`, which the polished pages call):

1. **Login** — `/login`, mobile + OTP (or password). Sets the `navix_borrower` cookie (separate from
   staff); identity = a numeric `customerId` derived from the mobile. (The signup wizard's mobile-OTP
   step establishes the same session early.) With `NAVIX_SMS_MOCK=true` the code is `123456`.
2. **Phase-1 intake (`/signup/*`, application `DRAFT`)** — `JourneyService.Step` in order: `otp` →
   `set-password` (optional) → `employment` → `employer` → `email` → `bank` → `payslips` → `consent` →
   `submitted`. **Where the borrower is is answered server-side** (`GET …/journey`, the max of what
   their saved data proves and the `journey_step` pointer), so a second device resumes on the right
   screen. The `email` screen is a real gate: both the personal and the official address must be
   OTP-confirmed (`emailsSettled`, V52/V65) — distinct from the provider deliverability/employer-match
   check that runs against the same work address. Self-employed applicants are turned away at intake
   (`/self-employed`, 90-day block).
3. **Verification & credit** — `submit-kyc` is gated on completeness (`KYC_INCOMPLETE`) →
   `KYC_PENDING`. The Credit Head assigns the file; the assigned **Credit Executive's decision is
   final** — `sanction` (amount + repayment date → `SANCTIONED`), `reject-lead` (30-day cooling-off),
   or `mark-pending` (park it and ask for a document). There is no separate KYC-approver desk.
4. **Phase-3 offer journey (`/loan/*`, application `SANCTIONED`)** — `OfferStep` in order: `amount`
   (within the sanctioned ceiling) → `repayment-date` → `digilocker` → `references` → `summary` →
   `selfie` → `address` → `sanction-letter` (Key Fact Statement PDF) → `esign` (Aadhaar eSign, §14) →
   🎉 → `disbursal-account`. Confirming the account routes the file to `DISBURSEMENT_PENDING`.
5. **Track live** — `/loan/status` polls `GET …/{id}` and renders the live state-machine status +
   audit trail.
6. **Active loan** — after the Disbursement Head releases the money (`ACTIVE`), `/dashboard` shows the
   real loan: **net disbursed**, **due date** (salary-linked), **total repayable**.
7. **Repay / prepay** — `/repay` reads the real loan and records a manual payment with a **screenshot
   proof** (→ PENDING_VERIFICATION); the Accountant verifies it, which reduces the outstanding and
   closes the loan + application at zero. The page shows the prepayment-aware "pay today" amount
   (interest only to the day paid).
8. **Reborrow** — "Borrow again" on `/reloan` calls `borrowerApi.reborrow()`. A clean history is
   **pre-approved** and lands on the shortened offer journey with the prior sanction carried over
   (salary day never re-asked); a disqualifying history is **auto-rejected** outright. See §5.

The borrower can only call **borrower** actions (`requireRole("BORROWER")`); `apply` is rejected
unless the application is `KYC_APPROVED` or `PRE_APPROVED`, the amount is ≥ ₹1,000, and (if an
eligible limit is set) within it.

> Manual fallbacks exist for the two checks that can hard-fail: an **Aadhaar card upload** when
> DigiLocker won't connect, and a **cancelled cheque / passbook** when the penny drop can't pass —
> both land in one staff review path (see the §13 raw-Aadhaar note).

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
| `CREDIT_HEAD` | assign/reassign a credit file to self or an active Credit Executive; sanction, reject, or park any file in `CREDIT_EXEC_PENDING`; approve/reject KYC |
| `CREDIT_EXECUTIVE` | **the final credit decision** — sanction (amount + repayment date), reject, or park files assigned to them in `CREDIT_EXEC_PENDING`; absorbed the deleted `KYC_APPROVER` (V45), so it also holds `kyc:approve` |
| `DISBURSEMENT_HEAD` | make the transfer and release it with a **txn id** (→ `DISBURSED`→`ACTIVE`; an accept without one is `TXN_REF_REQUIRED`); retry on failure; **settle referral payouts** (`referral:payout`) |
| `ACCOUNTANT` | **verify or reject borrower repayments**; **view the transactions ledger**. No longer part of disbursement (V47/V48 retired the accountant hop) |
| `COLLECTION_HEAD` | collections management + settlements (**approve / reject**); the **Loans register** (`loan:register`) |
| `COLLECTION_EXECUTIVE` | borrower collections interactions |
| `TELECALLER` | calls the lead list and logs the outcome (V42). **No lifecycle authority** — views customers, writes leads + call logs + remarks, self-assigns chase-up work; never in maker-checker or SoD |
| `DSA` | **external commission agent** (V55). Enters leads and earns **3.5% of net disbursed** on their lead's *first* loan, payable only once that loan is fully repaid. Holds **no** lifecycle authority and is **firewalled from all customer data** — see the note below |
| `ADMIN` | oversight — **bypasses role checks**; also exempt from the credit SoD + active-executive `assign`, so may walk a loan KYC→ACTIVE **solo, per-step** (credit queue shows an **"Assign to me"** button); OTP-gated mobile/sanctioned-amount corrections; edits salary/profile data; force `SANCTIONED → DISBURSEMENT_PENDING`; bureau backfill/rescore; manages company expenses, blocklist and the DSA program |

> **A credit reject (`REJECT_LEAD`) carries a 30-day cooling-off.** `MANUAL_REJECT_BLOCK_DAYS = 30`
> is written to `application_rejection.blocked_until`, and `assertNotBlocked` (mobile-keyed) then
> turns away both a fresh signup and a reborrow with `NOT_ELIGIBLE`. Rejecting is the door closing;
> when the problem is fixable (a stale salary slip, an unopenable statement) the reviewer is meant
> to **park** the lead and ask for the document instead. Before this, a reject was undone by a
> reborrow minutes later that came back `PRE_APPROVED`, skipping KYC *and* credit.

> Role names are `COLLECTION_HEAD` / `COLLECTION_EXECUTIVE` (not the old
> COLLECTIONS_HEAD / COLLECTION_OFFICER). Reconciled in Flyway **V8**.
> `TELECALLER` was added in **V42**, `DSA` in **V55**. `KYC_APPROVER` was **deleted in V45** (holders
> became `CREDIT_EXECUTIVE`s, which absorbed the duty); `DEVELOPER` (added in V8) was **dropped in
> V61** — its holders were deactivated, not deleted. The live roster is exactly: `CREDIT_EXECUTIVE`,
> `CREDIT_HEAD`, `DISBURSEMENT_HEAD`, `ACCOUNTANT`, `COLLECTION_HEAD`, `COLLECTION_EXECUTIVE`,
> `TELECALLER`, `DSA`, `ADMIN`.

> **⚠ `DSA` is the one staff role that is an authz *exclusion*, not just an absence of permissions.**
> Every other staff role holds the broad `customer:view`, and several controllers gate with a
> **deny-list** (`requireStaff()` rejects only BORROWER/ANONYMOUS) rather than an allowlist — so a new
> role is open-by-default there. `DSA` is therefore explicitly rejected in
> `CustomerController.requireStaff`, `ApplicationController.{requireStaff, requireBorrowerOwnsOrStaff}`,
> `DecisionHistoryController.requireStaff` and `CustomerService.{list, detail}`. **If you add another
> staff-open surface, add the DSA rejection too** — `hasRole("STAFF")` in `SecurityConfig` is
> audience-level and a DSA token satisfies it.

**Permission tokens** (`frontend/src/lib/auth/rbac.ts`, mirrored by service-level guards): `kyc:approve`
(the credit roles + ADMIN — the sanction *is* the credit decision, so `loan:approve` now gates
**assignment**, not a second sign-off), `loan:review`, `loan:approve`, `loan:disburse`,
`loan:activate`, `loan:pipeline`, `loan:register` (Loans register — COLLECTION_HEAD + ADMIN),
`collections:manage`, `collections:interact`, `staff:manage`, `customer:view` (every staff role
except **DSA** — see the note above), `customer:view:all`, `customer:assign` (Heads + TELECALLER +
ADMIN), `customer:manage` (ADMIN — correct KYC, cancel, blocklist), `document:upload`,
`verification:retry`, `referral:payout` (DISBURSEMENT_HEAD + ADMIN), `leads:manage` (TELECALLER +
ADMIN), `dsa:portal` (**DSA only** — the *only* token a DSA holds, and deliberately **not** granted to
ADMIN, whose oversight goes through `dsa:manage`), `dsa:manage` (ADMIN).

All staff pages are now **live and role-aware**. The shared machinery lives in
`components/staff/live-pipeline.tsx` (status-backed queues + the per-stage maker-checker action
clusters + the on-demand applicant review). **`/staff/applications` is the one console** that composes
every stage queue (credit, disbursement, accounting) — there is no separate `credit/` or
`kyc-approvals/` page any more. `/staff/dashboard` shows live counts/queues per role. Alongside it:
`customers` (+ `[customerId]`), `loans`, `verifications`, `performance`, `my-decisions`,
`telecalling`, `leads`, `accounting/transactions`, `disbursement/referrals`, `collections`
(`settlements`, `[loanId]`), `dsa/{leads,earnings}`, and `admin/*` (`staff`, `invites`, `blocklist`,
`expenses`, `all-applications`, `rejections`, `leads`, `dsa`, `api-dashboard`, `payment-settings`).
Settlement approval enforces **SoD** (proposer ≠ approver) server-side. Staff screens carry small **ⓘ info-tooltips**
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
- **CSS scope:** never apply global scaling like `html { font-size: X% }` or global rem overrides.
  Font-size and spacing changes must be scoped to specific utilities/components so the staff
  sidebar and dense tables are not broken.
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
  `components/staff/staff-shell.tsx`); the borrower picks their repayment date on
  `/loan/repayment-date` and self-edits on the `/profile` + `/settings` pages.

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

> **Decision (final):** due date is **salary-linked ≤ 40 days**, replacing the older fixed
> +30 days. `LoanService.disburse` uses `dueDateFromSalary`; the application carries
> `salary_credit_day` (Flyway V7, optional, default 1; collected on the apply form).

---

## 10. Data model & migrations

Flyway migrations live in **`backend/navix-app/src/main/resources/db/migration/`** (not
navix-common). Applied on every boot:

Flyway migrations live in **`backend/navix-app/src/main/resources/db/migration/`** (not
navix-common) and are applied on every boot — **V1..V65** today. Each file carries a header comment
explaining *why* it exists; that is the source of truth. The index is
[`docs/MIGRATIONS.md`](docs/MIGRATIONS.md).

The handful of schema decisions worth knowing without opening anything:
- **Historical migrations are immutable**, even where their comments describe a retired workflow.
  Statuses and roles they retired (`ACCOUNTANT_PENDING`, `CREDIT_HEAD_*`, `REVIEW_PENDING`,
  `KYC_APPROVER`, `DEVELOPER`) stay in the enum/CHECK so historical `application_event` rows parse.
- **All money is `BIGINT` paise** (V3/V4).
- The per-person key is **`customer_id`** (renamed from `applicant_id` in V33); code uses
  `customerId` / `CustomerProfile`. Only `co_applicant`/`CoApplicant` (the guarantor) keeps the old
  name, and external bureau-API JSON keys (`Current_Applicant_Details`, …) are not ours to rename.
- Identity uniqueness on `customer_profile` is **customer-scoped**, not global (V12 → relaxed by V23),
  so a returning borrower can re-onboard.

**The aggregate** `loan_application`: `id`, `customer_id` (was `applicant_id`, renamed in V33), `amount_requested` (paise, nullable),
`eligible_limit`, `purpose`, `assigned_executive_id`, `loan_id`, `salary_credit_day`, `status`.
**Audit** `application_event`: `id`, `application_id`, `from_status`, `to_status`, `actor_id`,
`actor_role`, `action`, `notes`, `at` — append-only, and the source of truth for SoD checks.

> Known DB debt (deferred): no FK constraints (indexes only). (The legacy `disbursement_request` UUID
> maker-checker chain — `navix-disbursement` module + its `disbursement_request`/`approval_step` tables —
> was **removed** once superseded by the single aggregate: module deleted, tables dropped in V39.)
> (`collection_case` is
> on the real **bigint** loan id — V11; `loan` carries `disbursal_txn_ref` — V13; `customer_profile`
> (renamed from `applicant_profile` in V33) identity uniqueness is **customer-scoped** — V12 added it
> globally, **V23 relaxed it** to per-customer so returning borrowers can re-onboard.)
> **Naming:** the durable per-person key is `customer_id` (renamed from `applicant_id` in V33); code uses
> `customerId` / `CustomerProfile` throughout. Only `co_applicant`/`CoApplicant` (the guarantor) keeps the
> old name. External bureau-API JSON keys (`Current_Applicant_Details`, …) are **not** ours and stay as-is.

---

## 11. Backend API surface

**The controllers are the source of truth.** The endpoint map — `/api/applications` and its offer
journey, customers, loan/repayments/transactions, collections, IAM/admin, DSA, notifications,
referral, verification dashboard, preferences and registers — lives in
[`docs/API_SURFACE.md`](docs/API_SURFACE.md).

What holds across all of it, and does not belong in that file:
- Every action resolves the actor from the **JWT bearer** (`JwtAuthFilter` → `ActorContext`) and
  enforces `requireRole`. A missing/invalid bearer on a protected route → plain **401**.
- Maker-checker violations return `FORBIDDEN_ROLE`, `SOD_VIOLATION` or `ILLEGAL_TRANSITION` (422).
- Responses are wrapped in the `ApiResponse<T>` envelope; the typed client unwraps it and throws
  `ApplicationApiError` carrying `error.code`.
- Anything a borrower can read is ownership-checked, and staff-only fields (credit score, ★ rating)
  are **stripped** when a borrower reads their own profile.

---

## 12. Conventions & key decisions

- **Money = integer paise (`long`), HALF_UP.** Never floats/whole-rupee for money.
- **One aggregate.** The lifecycle is one `loan_application.status`; don't reintroduce fragmented
  per-stage entities. The old `DisbursementRequest` UUID chain has been removed (module + tables, V39).
- **SoD is mandatory** and enforced server-side (flow service via the event trail), not in
  middleware. Never collapse two maker-checker steps onto one actor.
- **JWT identity (migration P6).** `JwtAuthFilter` validates the bearer → `CurrentActor`; services
  read `CurrentActor` + `requireRole`. The swap stayed localized to the filter/`SecurityConfig` — keep
  it that way (don't reintroduce header-trust or move authz out of the services).
- **Separate staff/borrower sessions.** Never share a cookie or BFF namespace; the JWT audience
  (`staff`/`borrower`) keeps them apart.
- **Salary-linked due date ≤ 40 days.** At disbursal, use the latest clamped occurrence of the
  selected salary-credit day inside the following 40-day window.
- **Notifications are event-driven & non-blocking.** Domain code never calls the engine directly — it
  **publishes a Spring event**; the `@TransactionalEventListener(AFTER_COMMIT) @Async` listener in
  `navix-notification` does the rest. Adding a new notification = add a `NotificationType` + template +
  audience and publish (or map) an event; **never** make business logic depend on a delivery succeeding.
- **Maker-checker steps always have a reject path.** Wherever an actor can approve (credit, disbursement,
  settlement, repayment verification), the counter-action **reject** exists too, SoD-checked
  (proposer ≠ rejecter) and audited; don't add an approve-only flow.
- **Feature flags are dev-only & read-only.** The `feature_flag` table is changed **only by SQL** — there
  is no write API and no admin UI (not even ADMIN). Code reads `FeatureFlagService.isEnabled(key)`
  (navix-common, no cache → instant, no redeploy); gate a feature by adding a row + the check. Live
  flags: `referral` (kill switch), `fintrix-bureau` (bureau primary; off falls back to Digitap),
  `bureau-auto-reject` (**suspended in V64**, read with `defaultWhenMissing = FALSE` so deleting the
  row leaves it off — it takes money-affecting, 90-day-blocking action without a human),
  `digitap-crif` (the middle bureau leg, Digitap's CRIF product; also `defaultWhenMissing = FALSE` —
  the endpoint still 401s, so the implemented leg stays inert until Digitap enables it and a row is
  inserted; see [`docs/INTEGRATIONS.md`](docs/INTEGRATIONS.md)).
- **Secrets** never committed — env / **SSM SecureString** at runtime (`/navix/<env>/…`). Key vars:
  `BACKEND_BASE_URL`, `NEXT_PUBLIC_API_BASE_URL`, `DB_*`, `AUTH_SECRET`, `BORROWER_AUTH_TTL_SECONDS`
  (7-day borrower session), `NAVIX_APP_BASE_URL` (reset-link base), `NAVIX_REMINDERS_CRON`,
  `AWS_PROFILE`, `NAVIX_ENV`,
  `SIGNZY_*` + `DIGITAP_*` + `FINTRIX_*` (`FINTRIX_BASE_URL`, `FINTRIX_CLIENT_ID`, `FINTRIX_CLIENT_SECRET` —
  the bureau-primary Fintrix `crif_combine` client) + `NAVIX_VERIFICATION_CHAIN` (default
  `signzy,fintrix,digitap` — **global order, not per-capability**: Signzy leads so it stays the PAN
  primary now that Fintrix also serves PAN via `pan_comprehensive`, while bureau stays Fintrix-primary
  because Signzy's bureau leg is retired and skips itself; verification providers, §14; loaded from `.env`),
  `NAVIX_S3_*`, `NAVIX_SMS_*` (incl. `NAVIX_SMS_MOCK`),
  `NAVIX_EMAIL_*` (`PROVIDER` log|smtp|ses|resend · `ENABLED` · `FROM` · `CONFIGURATION_SET` for SES · `RESEND_API_KEY`),
  `NAVIX_SES_EVENTS_*` (`ENABLED` · `QUEUE` — the SES bounce/complaint SQS listener), `NAVIX_NOTIF_*` (async pool sizing),
  `NAVIX_BUREAU_FIXTURE` (demo-only, default off — a bundled credit report for local briefs),
  `NAVIX_CAPTCHA_SECRET` + the frontend's `NEXT_PUBLIC_TURNSTILE_SITE_KEY` (Cloudflare Turnstile on the
  password logins + both forgot-password forms — **unset = the check is skipped**, which is how dev, CI,
  the Playwright suite and the demo seed run; set them together or not at all). The backend secret is
  an **SSM param** (`/navix/dev/navix/captcha/secret`), not a task-def env var — no new ECS revision.
  **Order matters:** site key first, secret second; the reverse locks everyone out until the frontend
  rebuild lands, because `NEXT_PUBLIC_*` is inlined at build time.

---

## Git & Deploy Defaults

Commit and push directly to `main` unless told otherwise — do not create side branches. Confirm
which GitHub account is active (`gh auth status`) before pushing; this repo is pushed under
multiple accounts (kartikjindal, meetzy-india). If push fails on OAuth scope, run
`gh auth refresh -s workflow`.

---

## 13. Deferred, and 14. External integrations

- **Go-live backlog** → [`docs/STATE.md`](docs/STATE.md) (＋ [`FUTURE.md`](FUTURE.md),
  [`PRODUCTION_READINESS.md`](PRODUCTION_READINESS.md)).
- **Providers — who does what, auth, hosts, live-test status, per-API gotchas** →
  [`docs/INTEGRATIONS.md`](docs/INTEGRATIONS.md), plus the API catalogs in `docs/signzy/`,
  `docs/digitap/`, `docs/fintrix/`, `docs/sms-dlt/` and `NAVIX_Fintrix_Integration_Flow.md`.

The rules that survive outside that file:
- **Go through the seam, never a provider client.** Identity/bureau/penny-drop/DigiLocker run behind
  `VerificationPort` via `RoutingVerificationPort` (`@Primary`), which routes **per capability**:
  bureau = Fintrix → Digitap; everything else = Signzy → Digitap. The one exception is
  `answerBureauChallenge` (the CRIF KBA answer), which bypasses the chain entirely and goes straight
  to Fintrix — an `order_id` belongs to one vendor. Throw
  `CapabilityNotSupportedException` for "skip to the next provider", `VerificationException` for
  "tried and failed, fall through". Aadhaar eSign is its own seam (`EsignPort`) — one provider, and a
  legal act rather than a check.
- ⚠️ **Every eSign initiate is a real, billable, legally binding contract; there is no sandbox.** The
  drawn-signature fallback deliberately never calls `EsignPort`.
- **Our DB row is the source of truth** for every redirect/poll flow (DigiLocker, liveness, eSign);
  the provider callback only accelerates the closed-tab case.
- **No verification hard-blocks a borrower.** A failed check is `REVIEW` for a human, not a decline.
- Set **`NAVIX_BUREAU_FIXTURE`** (any non-blank value) for an offline, non-billable credit brief.
- SMS text must match the registered DLT template char-for-char, use `Rs.` not `₹` (₹ forces UCS-2),
  and any URL must be portal-whitelisted.

---

## 15. Reference

**Split out of this file (read on demand, not every session):**
- **[`docs/STATE.md`](docs/STATE.md)** — what is live feature by feature, and the go-live backlog.
- **[`docs/API_SURFACE.md`](docs/API_SURFACE.md)** — the full endpoint map (controllers still win).
- **[`docs/INTEGRATIONS.md`](docs/INTEGRATIONS.md)** — Signzy / Digitap / Fintrix / SES / UltronSMS:
  capability routing, auth, hosts, live-test status, per-API gotchas.
- **[`docs/MIGRATIONS.md`](docs/MIGRATIONS.md)** — the V1..V65 Flyway catalog.

**Everything else:**
- **`aws.md`** — the live cloud deployment (Vercel → ALB → ECS → RDS/S3/SSM): every resource id, the
  redeploy recipe, and the smoke tests.
- **`PRODUCTION_READINESS.md`** — go/no-go checklist for real production exposure.
- **`FUTURE.md`** — go-live roadmap for the remaining deferred set.
- **`QA_CHECKLIST.md`** — test inventory; **`populateDummyData.md`** — seed demo data at every lifecycle
  stage (`scripts/seed-demo-data.ps1`); **`DEMO_WALKTHROUGH.md`** — chaptered recording script for the
  admin/staff console walkthrough.
- Memory (`~/.claude/.../memory/`) — `navix-application-state-machine.md` (lifecycle),
  `navix-execution-plan.md` (plan), `navix-unified-design-system.md` (the 2026 "calendar" re-skin), and
  `navix-feature-flags.md` (dev-only DB flags) capture the same decisions for cross-session continuity.
