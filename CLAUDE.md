# CLAUDE.md

> **⚠️ Rebrand (2026-07-22): the product is now "DhanBoost" (domain `dhanboost.com`).**
> The user-visible brand across the frontend + backend was renamed NAVIX → **DhanBoost**. The
> **internal namespace is deliberately kept as `navix`** — Java packages (`com.navix.*`), Maven
> modules (`navix-*`), env vars (`NAVIX_*`), SSM paths (`/navix/…`), session cookies
> (`navix_borrower`/`navix_staff`), the DB name, and the seeded staff logins (`*.navix.example`,
> `navixfinance@gmail.com`). The **legal entity** stays `NAVIX Finance Private Limited` (CIN
> `U64990HR2026PTC144926`) — the technology platform; the **lender of record is the NBFC partner
> MLB Securities** (named on the KFS, sanction letter and borrower footer). The **live SES-verified
> email domain is still `navixfinance.com`**, so `aws.md` and the integration guides under `docs/`
> intentionally keep those `navix` identifiers. Logo: the green rupee/growth-arrow art
> (`docs/brand/dhanboost-logo-sheet.jpeg`) — app-icon tile `frontend/public/navix-mark.png` (+ `-64`,
> `src/app/icon.png`, `apple-icon.png`), transparent mark `frontend/public/dhanboost-mark.png`
> (schema.org logo + the PDF-header base64 in `lib/export/brand-mark.ts`). The tile supplies its own
> background, so `.brand-mark--img` / `.logo--img` / `.offer-logo` carry **no** navy plate. Outside
> code: `dhanboost.com` must be SES-verified before `From: @dhanboost.com` mail will deliver.
> Below, "NAVIX" in infra/namespace contexts = the retained internal name; the product is DhanBoost.

Guidance for Claude Code (and any human) working in this repo — the **single onboarding doc**: what
DhanBoost is, the lifecycle, the borrower flow, the staff flow, how to run it, and what is real vs.
deferred. A from-scratch rebuild spec lives in [`docs/PRD_REBUILD.md`](docs/PRD_REBUILD.md).

> Historical database migrations are immutable even where their comments describe an older workflow.
> Where this file and code disagree, **code wins** — fix this file.

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

## 1. What DhanBoost is

A salary-linked, single-repayment lending platform (India). A salaried borrower draws a short
advance, pays an upfront fee, and repays **once** on their salary day. All money is **integer
paise**, rounded HALF_UP.

| Rule | Value |
|---|---|
| Eligible limit | **25% of monthly salary**, floored to ₹100 (ADMIN per-customer override wins, V69) |
| Min / max loan | ₹1,000 / ₹10,00,000 |
| Processing fee | **10%** of principal, upfront (minus any repay-now discount, V95) |
| GST | **18% on the fee**, upfront |
| Interest | **1%/day** on principal, for the days actually held (prepay = pay less) |
| Due date | the borrower's salary credit date, the **latest** one ≤ **40 days** after disbursal |
| Grace | per loan (`loan.grace_days`); **0** for everything disbursed/not-yet-due since V91 |
| Late penalty | **2%/day** on principal, **capped at 30 days** |
| Repayment | one installment — manual UPI/bank + proof, PayU checkout (flagged), or NACH debit (flagged) |

Borrower **receives** `principal − fee − GST` and **repays** `principal + interest` (+ penalty if
late). Risk categories A/B/C/D are staff-only and never change the price. **Maker-checker
separation of duties (SoD)** is a hard requirement throughout.

Monorepo: **backend** Spring Boot 3.4.1 / Java 21, Maven multi-module `com.navix`; **frontend**
Next.js 15.1.12 (App Router, `src/`), React 19, Tailwind 3.4, TypeScript 5.7.

---

## 2. Current state

The full loan lifecycle runs end-to-end in production: one `loan_application` aggregate (§5), a
Next.js frontend behind a BFF (§8), real JWT + Spring Security (§7), real verification providers
(Signzy · Fintrix · Digitap), S3 documents, Aadhaar eSign, and a two-phase borrower journey (intake →
credit sanction → offer journey, §6). Deployed Vercel → ALB → ECS Fargate → RDS/S3/SSM (`aws.md`);
**CI deploys both tiers on every push to `main`**.

Shipped and behind flags (see §12 for each flag's state): PayU repayment checkout, Digio eMandate +
NACH debit, admin customer messaging + templates + repay-now discount + reapply allowance, reference
OTP, admin eSign documents, onboarding waitlist, applications-closed switch. Per-feature history:
[`docs/STATE.md`](docs/STATE.md) (partly stale — trust code first).

**Live landmines — read before you deploy anything:**
- **Every push to `main` is a production deploy** (frontend + backend). There are no path filters;
  use `[skip ci]` in the message for a docs-only commit.
- **eSign refuses to boot without its callback parameters.** `navix.esign.callback-url` +
  `callback-secret` (SSM `/navix/<env>/navix/esign/…`) are mandatory when the provider is `signzy`
  (Signzy rejected 156/156 contracts sent without one, silently, for four weeks).
- **`ProductionConfigGuard` fails closed**: the backend refuses to start with the built-in
  `AUTH_SECRET` (or one < 32 chars), mock OTP, SMS dev-echo, mock eSign, `log` email, Digio mock with
  `digio-mandate` on, or PayU on without credentials / on `test.payu.in` — unless
  `NAVIX_ALLOW_DEV_DEFAULTS=true` (local stacks only).
- **The credit-score auto-reject is suspended** (`bureau-auto-reject`, V64): every bureau result goes
  to a human until the floor (`MIN_BUREAU_SCORE = 550`) is recalibrated against CRIF.
- **A fresh database starts in waitlist mode**: V76 seeds `onboarding-paused` **ON**.
- **The simulation stack holds a copy of real customer data** (`scripts/sim/`, RDS
  `navix-finance-sim`, SSM `/navix/sim/*`). The BFF routes the staff login whose email equals
  `SIM_LOGIN_EMAIL` to `SIM_BACKEND_BASE_URL`; exports are labelled `SIMULATION`. Treat it as PII.

---

## 3. Monorepo layout

```
backend/                       Spring Boot, Maven multi-module (com.navix)
  navix-common/                ApiResponse envelope, BusinessException(422), FeatureFlagService, JwtService,
                               ActorContext/CurrentActor/ActingRole, ports (VerificationPort, EsignPort,
                               MandatePort, LoanDirectory, …), notification events, Masking/Aadhaar utils
  navix-iam/                   staff users, invites, blocklist, expenses, payment settings, feature-flag API
  navix-loan/                  ★ the aggregate: ApplicationStatus, ApplicationFlowService, JourneyService,
                               OfferService, LoanService, LoanMath, RepaymentService, customers, DSA, leads,
                               referral, mandates/debits, PayU, messaging/templates, eSign documents
  navix-collections/           cases, DPD, settlements, collection payments, manual NACH debit, auto-assign
  navix-verification/          Signzy / Fintrix / Digitap / Digio clients behind RoutingVerificationPort
  navix-notification/          events → dispatcher → IN_APP / SMS / EMAIL / WHATSAPP; SES feedback listener
  navix-storage/               S3 presign (/api/storage)
  navix-income-risk/           live: LimitCalculator + RiskAdapter only (rest is legacy, unrouted)
  navix-onboarding/, navix-kyc/ legacy; their routes are denyAll in SecurityConfig — do not build on them
  navix-app/                   ★ the only bootable module: auth, SecurityConfig, JwtAuthFilter, schedulers,
                               dashboard, reports, search, skip trace, BSA, provider workbench, Flyway
    src/main/resources/db/migration/   V1..V102 (the real schema)
  (navix-disbursement/ is a dead directory — only a stale target/, not in the pom)
frontend/src/
  app/(marketing)/             public site — raw-HTML pages from _content/*.ts + marketing-scripts.tsx
  app/(borrower)/              login, /signup/* intake, /loan/* offer journey, dashboard, repay, reloan…
  app/staff/                   the back-office console (§7)
  app/api/                     ★ the BFF (auth/*, borrower/*, staff/*, admin/*, payu/*, storage, contact…)
  lib/api/                     applications.ts (all typed clients), live-journey.ts (borrower seam), bff-*.ts
  lib/auth/rbac.ts             StaffRole, permission tokens, working-role hierarchy (mirrors backend)
  lib/calc/                    frontend loan math (mirrors LoanMath)
  middleware.ts                network guard + /staff/* session gate
docker-compose.yml             Postgres 16 + Adminer
```

---

## 4. Run it locally (Windows)

This machine runs **Postgres on :5433** (a native PG holds :5432 — the untracked
`docker-compose.override.yml` remaps with `ports: !override`) and the **backend on :8090** (Windows
http.sys can reserve :8080). **Java 21** is required (`C:\Program Files\Java\jdk-21`).

```powershell
docker compose up -d                                   # Postgres 16 (db/user/pass: navix) + Adminer :8081
cd backend; .\mvnw clean install -DskipTests           # always clean-install the sibling jars first
# then run the jar with DB_URL=jdbc:postgresql://localhost:5433/navix, DB_USERNAME/DB_PASSWORD=navix,
# -Dserver.port=8090 -Duser.timezone=Asia/Kolkata, NAVIX_SMS_MOCK=true, NAVIX_ALLOW_DEV_DEFAULTS=true
cd frontend; npm install; npm run dev                  # http://localhost:3000, BACKEND_BASE_URL=http://localhost:8090
```

- **`scripts/run-demo.ps1`** is the fully offline stack (no AWS/SSM). `run-local.ps1` and the
  compose override are local-only — never commit them.
- **`frontend/.env.local` points at production.** Override `BACKEND_BASE_URL` in-process for a local
  run and prove the target before logging in.
- Flyway applies every migration on boot; Swagger at `/swagger-ui.html`.
- **Demo logins:** borrower `/login` → any 10-digit mobile; with `NAVIX_SMS_MOCK=true` the OTP is
  **`123456`**. Staff `/staff/login` (email + password). V92 blanked the published seed passwords;
  on the offline stack restore them with `scripts/restore-demo-logins.sql`.
- **Seed data:** `scripts/seed-demo-data.ps1` (+ `seed-demo-sql.sql`) is **stale** (fails at
  submit-kyc and calls retired endpoints) — insert rows directly for targeted tests.

**Tests**
```powershell
cd backend; .\mvnw test                     # unit suite, no Docker
.\mvnw -pl navix-app -Pit test              # Testcontainers IT — needs Docker; CI runs it. It is the only
                                            # guard for Spring wiring breaks that pass unit tests.
cd frontend; npx tsc --noEmit; npx eslint .; npm test   # typecheck, lint, vitest
npm run test:e2e                            # Playwright against an already-running stack (E2E_BASE_URL)
```
CI runs the backend unit + IT suites and frontend `tsc` + ESLint; it does **not** run vitest,
Playwright or `next build` (Vercel builds). ⚠️ Running `npm run build` while `npm run dev` is up
corrupts `.next` (`Cannot find module './638.js'`) — kill dev, delete `.next`, restart.

---

## Verification Rules

Never report tests or builds as passing based on grepped output — always check the actual exit
code (`echo $?` or `set -o pipefail`). Run typecheck + lint before every commit. Never claim a
deploy succeeded until you have verified the running image/commit SHA matches what was just
pushed.

---

## 5. The lifecycle (the spine)

One aggregate — a `loan_application` row with one `status`, validated by
`ApplicationStatus.canTransitionTo` and logged to the append-only `application_event` (which also
records `acting_role`, V78). **No stage-skipping.**

| From | To |
|---|---|
| DRAFT | KYC_PENDING, PRE_APPROVED, REJECTED, CANCELLED |
| KYC_PENDING | CREDIT_EXEC_PENDING, KYC_APPROVED, KYC_REJECTED, REJECTED, CANCELLED |
| KYC_APPROVED | CREDIT_EXEC_PENDING, SANCTIONED, REJECTED, CANCELLED |
| PRE_APPROVED | SANCTIONED, DISBURSEMENT_PENDING, REJECTED, CANCELLED |
| CREDIT_EXEC_PENDING | SANCTIONED, REJECTED, CANCELLED |
| SANCTIONED | DISBURSEMENT_PENDING, REJECTED, CANCELLED |
| DISBURSEMENT_PENDING | DISBURSED, REJECTED |
| DISBURSED → ACTIVE → CLOSED / OVERDUE; OVERDUE → CLOSED / DEFAULTED; DEFAULTED → OVERDUE / WRITTEN_OFF | |

Retired values stay in the enum/CHECK for historical rows only: `REVIEW_PENDING`,
`CREDIT_EXEC_APPROVED`, `CREDIT_HEAD_PENDING`, `CREDIT_HEAD_APPROVED`, `ACCOUNTANT_PENDING`.
`DISBURSEMENT_FAILED` has an outbound edge (`retry-disbursement`) but **nothing transitions into it**
— a dead state; do not build on it.

**Credit.** `CREDIT_EXEC_PENDING` is the single credit stage. The Credit Head assigns to self or an
**ACTIVE** Credit Executive and may reassign (keeps the stage and any review data). The Head can
decide any file; an Executive only files assigned to them; ADMIN keeps oversight. Decisions:
`sanction` (amount + repayment date → `SANCTIONED`; refused while an `AADHAAR_DUPLICATE` review is
unresolved), `reject-lead` (30-day cooling-off), `mark-pending` (park and ask for a document).

**Disbursement (no accountant hop since V47/V48).** The Disbursement Head makes the transfer and
records its id: `DISBURSEMENT_PENDING → DISBURSED → ACTIVE`, writing `loan.disbursal_txn_ref`; an
accept without `txnRef` is `TXN_REF_REQUIRED`. ADMIN may force `SANCTIONED → DISBURSEMENT_PENDING`.
Every newly disbursed loan is auto-assigned to a collections officer (`CollectionAutoAssigner`).

**Offer journey (`SANCTIONED`).** The borrower walks `OfferStep` (§6); confirming the disbursal
account routes to `DISBURSEMENT_PENDING` — unless `digio-mandate` is on and the customer holds no
usable UMRN, in which case the registered mandate hands the file off. A sanction never expires.

**Repay → close.** Manual payments go `PENDING_VERIFICATION` → Accountant **verify / reject**. A
**PayU** payment and a **NACH** debit are recorded `VERIFIED` in one step (gateway- or
bank-confirmed). When the penalty-aware outstanding reaches 0, the loan closes and
`closeForLoan` moves the application `ACTIVE/OVERDUE → CLOSED`. An approved settlement caps the
payable and closes the loan when met.

**NACH debit (`digio-debit`, off).** `MandateDebitScheduler` presents at **DPD 1, 3, 5, 9** (10:00
IST); collections staff can present manually (`POST /api/collections/loans/{loanId}/debit`,
`mandate:debit`, executives only on their own cases). Double-debit guards: `uq_mandate_debit_in_flight`
(V84) + an advisory lock; caps 4 attempts per loan, 3 presentations per row. Outcomes come from a
**polling** reconcile pass (50 rows/run) — **there is no Digio webhook receiver**. Re-presentation
uses a terminal **denylist** (`DebitService.TERMINAL_MARKERS`, matched on code and reason): an
unseen failure code is retried. `mandate_debit.updated_at` on an open row means "last attempted".
Staff (Heads/ADMIN) can **cancel an ACTIVE mandate** (V88; refused while a debit is in flight) and
**change the mandate account** (penny-drop through `VerificationPort`, then withdraw every old
mandate, then re-invite). Not built: UPI AutoPay, mandate amendment, settlement reconciliation.

**Reborrow.** `ApplicationFlowService.reborrow` mints a **new** application for a borrower with a
**CLOSED** loan (else `NO_PRIOR_LOAN`), reusing the saved profile — salary day, KYC, sanction
ceiling, DigiLocker/selfie/address evidence, references, disbursal account. Never carried: the
eSign. Standing (`isDisqualifiedByHistory`: repaid > 5 days late or never fully repaid) is the
**only** gate — disqualified → auto-reject (`PAST_DELINQUENCY`, no time box, bypassed only by an
ADMIN one-use **reapply allowance**); clean → `PRE_APPROVED` → `SANCTIONED` via
`carryOverForReapply`. The best unlocked **repay-now discount** offer is pinned on the new file.
Blocked while a live application/loan exists.

**Invariants**
- Every assignment, reassignment and decision is appended to `application_event`.
- **Grace is per loan** (`loan.grace_days`, V90), stamped at disbursal from
  `LoanMath.SALARY_GRACE_DAYS` (**0** since V91). Loans already due or closed on 2026-10-24 keep 1.
  Changing the constant never touches an existing loan.
- The DPD bucket is computed on read, never stored.
- Signed loan terms are locked after eSign; editing a verified profile field re-triggers that check.

---

## 6. Borrower flow

Seam: `frontend/src/lib/api/live-journey.ts`. The borrower can only call borrower actions
(`requireRole("BORROWER")`), and everything they read is ownership-checked.

1. **Login** — `/login`, mobile + OTP (SMS + WhatsApp `dhanboost_otp`) or mobile + password
   (Turnstile). Cookie `navix_borrower`; identity = a numeric `customerId` derived from the mobile.
2. **Gates** — `onboarding-paused` on and no loan → one-form waitlist at `/apply` ("under review");
   `applications-closed` → `/applications-closed`; self-employed → `/signup/self-employed` (90-day
   block); any auto-reject → `/application-rejected` (never shows the reason).
3. **Phase-1 intake (`/signup/*`, `DRAFT`)** — `start` → `otp` → `set-password` (optional) →
   `employment` → `employer` → `email` (personal + official, both OTP-confirmed) → `bank` →
   `payslips` → `aadhaar` → `pan-card` → `consent` → `submitted`. Resume is answered server-side
   (`GET …/journey`). External verification APIs fire at `consent` (PAN, email, bureau, salary,
   employment); a CRIF KBA challenge sends the borrower to `/credit-question`.
   - `aadhaar`: typed 12-digit number (Verhoeff, stored in full, `INVALID_AADHAAR`) + both card sides
     (`AADHAAR_CARD_FRONT/BACK`); `pan-card`: `PAN_CARD_FRONT/BACK`. Photos or PDFs, optional file
     password. `submit-kyc` refuses (`KYC_INCOMPLETE`) until PAN/EMAIL/BUREAU/SALARY were attempted and
     the number + four images are on file. The number is **locked** (`AADHAAR_LOCKED`) once a provider
     has read it or the file leaves `DRAFT`.
   - **Fraud rules:** typed Aadhaar vs the masked Aadhaar from PAN/DigiLocker (`Aadhaar.matchesMasked`)
     and entered DOB vs DigiLocker DOB (1 Jan → year only) — a mismatch auto-rejects as
     `FRAUD_REJECTED` (365 days). A number another customer holds is accepted but flagged
     (`AADHAAR_DUPLICATE` REVIEW, staff-only, blocks `sanction`); at `submit-kyc` a number already on
     another customer's **submitted** file auto-rejects `AADHAAR_DUPLICATE` (permanent). Staff see the
     full number; the borrower sees only "rejected".
4. **Credit** — `KYC_PENDING` → Credit Head assigns → the Executive's decision is final (§5).
5. **Phase-3 offer journey (`/loan/*`, `SANCTIONED`)** — `amount` (≤ sanctioned ceiling) →
   `repayment-date` (preselected, locked) → `digilocker` (manual Aadhaar upload fallback; 5 consent
   sessions/24 h) → `references` (two; OTP-confirmed when `reference-otp` is on) → `summary` →
   `selfie` (Digitap Face Liveness v4, Signzy liveness fallback; never blocks) → `address` (geolocation
   reverse-geocode + typed fallback) → `sanction-letter` (KFS PDF + Aadhaar eSign redirect, drawn
   signature fallback) → `sanctioned` 🎉 → `disbursal-account` (confirm salary account or penny-drop a
   new one; 3 fails = 12 h lock; cancelled-cheque/passbook fallback) → `mandate` (Digio, only when
   `digio-mandate` is on and no usable UMRN).
6. **Track** — `/loan/status` polls the status + audit trail.
7. **Active loan** — `/dashboard` shows net disbursed, the salary-linked **due date**, total repayable.
8. **Repay** — `/repay` quotes today / due-day balances (prepayment-aware) and offers PayU "Pay now"
   when `payu-repayment` is on (card fee 2.36% passed through), or manual UPI/bank + screenshot proof.
9. **Reborrow** — `/reloan` → `borrowerApi.reborrow()` (§5).
10. **Account menu** — Past loans (`/loans`), Past transactions (`/transactions`), Support,
    Settings (notification preferences), Profile (self-edit non-identity fields), Sign out.

---

## 7. Staff & admin

**Staff and borrower auth are separate namespaces** — different endpoints, cookies, JWT audiences.

| | Borrower | Staff / Admin |
|---|---|---|
| Login | OTP (`/api/auth/borrower/otp/request` → `/login`) or `/password-login`; forgot/reset link | `POST /api/auth/staff/login` (email + password); forgot/reset link; invite accept |
| Token | HS256, audience `borrower`, sub = customerId, **7 days** | audience `staff`, sub = staffId, role + `sid` claim, **1 day** |
| Cookie | `navix_borrower` `{token,id,customerId,name,mobile}` | `navix_staff` `{token,id,name,role,sim?}` |
| UI | `/login` → `/dashboard` | `/staff/login` → `/staff/dashboard` (DSA → `/staff/dsa/leads`) |

- The BFF stores the JWT in its httpOnly cookie and forwards `Authorization: Bearer`; `JwtAuthFilter`
  populates `ActorContext`; services call `requireRole(...)` and replay the event trail for SoD.
- **One live staff session per staffer** (`StaffSessionRegistry`): a second login without `force` →
  `SESSION_CONFLICT`; disabling a user or changing their role revokes the session.
- Sign-in throttle: 3 wrong passwords per 15 min → 30 s cool-off (`TOO_MANY_ATTEMPTS`; successes
  don't count). Passwords ≥ 8 chars. Turnstile on both password logins and both forgot-password forms.

**Roles** (`StaffRole`, mirrored in `frontend/src/lib/auth/rbac.ts`):

| Role | Does |
|---|---|
| `CREDIT_HEAD` | assign/reassign credit files; reject a lead; works as Credit Executive for files assigned to them |
| `CREDIT_EXECUTIVE` | **the final credit decision** on assigned files (sanction / reject / park); holds `kyc:approve` |
| `DISBURSEMENT_HEAD` | transfer + release with a txn id; mandate overrides; settle referral payouts |
| `ACCOUNTANT` | verify / reject repayments; resolve PayU review rows; transactions ledger |
| `COLLECTION_HEAD` | assign cases, approve/reject settlements and field payments, Loans register, skip trace, mandates console |
| `COLLECTION_EXECUTIVE` | only their own cases (`CASE_NOT_ASSIGNED` otherwise): interactions, payments, settlement proposals, manual debit |
| `TELECALLER` | lead list + call logs + remarks; no lifecycle authority |
| `DSA` | external agent: own leads only; 3.5% of net disbursed on the lead's first loan, payable once repaid |
| `ADMIN` | oversight; bypasses role checks and the credit SoD, so may walk a loan solo by switching working role; corrections, flags in Settings, reports, messaging, eSign documents |

> **⚠ `DSA` is an authz *exclusion*, not just an absence of permissions.** Several controllers gate
> with a deny-list (`requireStaff()` rejects only BORROWER/ANONYMOUS), so a new role is
> open-by-default there. `DSA` is explicitly rejected in `CustomerController.requireStaff`,
> `ApplicationController.{requireStaff, requireBorrowerOwnsOrStaff}`,
> `DecisionHistoryController.requireStaff`, `CustomerService.{list, detail}`,
> `ApplicationFlowService.search`, `GlobalSearchService.requireSearchingStaff`, the loan reads, and
> skip-trace/BSA reads. **If you add another staff-open surface, add the DSA rejection too** —
> `hasRole("STAFF")` in `SecurityConfig` is audience-level and a DSA token satisfies it.

**Permission tokens** (`rbac.ts`; backend guards mirror them): `kyc:approve`, `loan:review`,
`loan:approve` (gates **assignment**), `loan:disburse`, `loan:activate`, `loan:pipeline`,
`loan:register`, `collections:manage`, `collections:interact`, `collections:overview`,
`staff:manage`, `customer:view` (all but DSA), `customer:view:all`, `customer:assign`,
`customer:manage`, `document:upload`, `leads:manage`, `leads:import` (every role), `referral:payout`,
`mandate:override` (this application's own newest `BANK_UNSUPPORTED` attempt only), `mandate:debit`,
`verification:retry`, `dsa:portal` (DSA only — not ADMIN), `dsa:manage`, `waitlist:view`,
`message:send`. The Admin working role uses the narrower `ADMIN_ROLE_PERMISSIONS` set.

**Working role (switcher).** A header pill picks the working role: ADMIN → Admin (default), Credit
Head, Credit Executive, Disbursement Head, Accountant, Collection Head, Collection Executive,
Telecaller (never DSA); CREDIT_HEAD → Head/Executive; COLLECTION_HEAD → Head/Executive; others fixed.
Each working role shows only its own tabs and buttons. Stored in localStorage
`navix-staff-working-role:<staffId>`, sent as `X-Acting-Role`, validated by `ActingRole.normalize`
(illegal values ignored). **Authorization stays on the real JWT role**; `effectiveRole()` only
scopes lists. The timeline reads "by Meera Krishnan · ADMIN (as Credit Executive)". Spec:
[`docs/design/claude-design-role-modes.md`](docs/design/claude-design-role-modes.md).

**Console pages** (`components/staff/staff-nav.ts` is the single nav + gate list, shared by the
sidebar and Cmd/Ctrl+K search):
- *Overview:* dashboard (Admin tabs: snapshot, team, calendar, India map, company-wise, staff
  reports, collection allocation; per-role views; monthly targets), staff performance, my decisions.
- *Customers & credit:* `/staff/applications` (the one stage console, rendered per working role),
  customers register (`?seg=` lifecycle segments) + customer 360 (pop-up and
  `/staff/customers/[id]`, 21 tabs: Customer, Loan, Disbursal, Repayment, Collections, Third-party
  logs, Banking, Credit report, BRE, AI Analyzer, Journey, References, Documents, Addresses, Dedupe,
  Communication, Activity, Sanction, Follow-ups, Mandate, Verifications), all applications,
  rejections, onboarding waitlist, blocklist, customer messages, templates.
- *Leads & sales:* leads, telecalling, leads dashboard, lead import (CSV/XLSX via S3, background
  job), referral payouts.
- *Collections:* Loans register, DPD buckets worklist (Upcoming, 1–7, 8–30, 31–60, 61–90, 90+),
  Mandates & debits, settlements (+ settlements dashboard), refunds owed (NACH overpayments).
- *Finance & reports:* transactions ledger, company expenses, **Reports** (43 reports in 10 groups,
  CSV + branded PDF from one server table; groups live in both `ReportService` and
  `lib/reports/categories.ts` — change together) + customer data export (field picker;
  `includeIdNumbers` returns full Aadhaar/PAN/UAN/account, logged).
- *DSA:* my leads, my earnings.
- *Team & settings:* staff, DSA admin, invites, Provider API dashboard, eSign documents, Settings
  (onboarding-paused, applications-closed, PayU gateway, company payment block).

Settlement and repayment approvals enforce **SoD** (proposer ≠ approver) server-side. Staff screens
carry ⓘ info-tooltips (`components/ui/tooltip.tsx`).

---

## 8. Frontend architecture

- **Route groups:** `(marketing)` public site (pages are generated raw-HTML strings in
  `_content/*.ts`, rendered via `MarketingHtml`, behaviour in `marketing-scripts.tsx`), `(borrower)`,
  `staff/`.
- **Middleware** (`src/middleware.ts`): (1) **network guard** (`lib/network-guard.ts`) — non-India
  (`x-vercel-ip-country`, Vercel-only) and VPN/proxy/Tor (proxycheck.io, `PROXYCHECK_API_KEY`) →
  `/network-blocked` (403) or a `NETWORK_BLOCKED` envelope on `/api/*`; fails open on vendor errors;
  clean verdict cached 30 min in the IP-bound HMAC cookie `dbx_net_ok`; verified Google/Bing/Apple
  crawlers pass (`lib/verified-crawlers.ts`, fails closed); `NETWORK_GUARD_DISABLED=true` kill
  switch; (2) `/staff/*` gate — decodes the cookie JWT `exp` (no signature check), redirects to
  `/staff/login?redirect=` and deletes a bad cookie. Real RBAC is server-side. `/api/payu/*` and
  static files are excluded.
- **BFF:** every backend call goes through `src/app/api/*` route handlers (optional catch-alls
  `[[...path]]`; `borrower/loan` and `staff/loan` use `[...path]` with a POST allow-list regex).
  Shared helpers `lib/api/bff-session.ts` (cookies) and `lib/api/bff-proxy.ts` (`proxyToBackend`
  forwards Bearer, `X-Request-Id`, `X-Acting-Role`; 502 `BACKEND_UNREACHABLE`; `rejectCrossSite` login
  CSRF guard). `api/payu/return` (303 back to `/repay?order=`) and `api/payu/webhook` are anonymous.
  `lib/api/sim-routing.ts` routes the simulation login (§2).
- **Typed client:** `lib/api/applications.ts` (`borrowerApi`, `journeyApi`, `verificationApi`,
  `offerApi`, `staffApi`, `customersApi`, `collectionsApi`, `dashboardApi`, `reportsApi`, `adminApi`,
  `mandatesApi`, `customerMessagesApi`, `esignDocsApi`, …) unwraps `ApiResponse<T>` and throws
  `ApplicationApiError` with `error.code`. Money helpers `rupeesToPaise` / `paiseToINR`. React Query
  for server state; Zustand only for the signup draft.
- **Security headers** (`next.config.mjs`): CSP (Turnstile, GA, S3, `*.signzy.app` frames,
  `form-action` PayU), Permissions-Policy (camera for Signzy liveness, geolocation self), HSTS 2y,
  `X-Frame-Options SAMEORIGIN`; host `staff.dhanboost.com` redirects non-staff paths to `/staff`.
- **Design system:** **Inter** for every face (`font-serif` = display weight, `font-mono` = tabular
  figures), navy `#0C2540`, accent **emerald `#14A06B`** (the token is still *named* `gold`), cream
  `#FDFBF6`; type scale at 80% (body 12.8px) via tokens. Tokens in `tailwind.config.ts` +
  `globals.css :root`; component classes `.btn*`/`.card`/`.field`/`.cal-*`/`.staff-data-table`. The
  marketing site re-declares the same tokens under `.navix-mkt` (`marketing-theme.css`, generated —
  don't hand-edit). **Re-skin by remapping token values, never by renaming** — names are
  load-bearing.
- **CSS scope:** never apply global scaling like `html { font-size: X% }` or global rem overrides.
  Font-size and spacing changes must be scoped to specific utilities/components so the staff
  sidebar and dense tables are not broken.
- **Async states:** `components/ui` `skeleton` / `EmptyState` / `ErrorState(onRetry)`; toasts only in
  the staff shell.
- **Global search (Cmd/Ctrl+K):** pages resolve locally from `staff-nav.ts` through `navVisible`;
  records come from `GET /api/staff/search` (role-scoped groups, delegated to each list page's own
  service). Hidden for DSA and when `global-search` is off.
- **Exports:** `lib/export/*` — CSV (RFC-4180, formula-neutralised, BOM) and branded jsPDF.
- **SEO:** `metadataBase` is `https://www.dhanboost.com`; `sitemap.ts`, `robots.ts`, JSON-LD
  (Organization/WebSite/FAQ/Article); IndexNow runs on Vercel production `postbuild`.

---

## 9. Loan math (`navix-loan/.../LoanMath.java`, mirrored in `frontend/src/lib/calc/loan-math.ts`)

Integer paise (`long`), `BigDecimal` rates, `HALF_UP`. Constants: `PROCESSING_FEE_RATE=0.10`,
`GST_RATE=0.18`, `DAILY_INTEREST_RATE=0.01`, `LATE_PENALTY_RATE=0.02`, `LATE_PENALTY_CAP_DAYS=30`,
`LIMIT_PCT_OF_SALARY=0.25`, `LIMIT_ROUNDING_PAISE=10_000`, `MIN_LOAN_PAISE=100_000`,
`MAX_INSTANT_LOAN_PAISE=100_000_000`, `MAX_TERM_DAYS=40`, `SALARY_GRACE_DAYS=0`.

- fee = round(P × 0.10) − fee discount; GST = round(fee × 0.18); **net = P − fee − GST**.
- `dueDateFromSalary(disbursedOn, salaryDay)` = the **latest** salary date strictly after disbursal
  and ≤ disbursal + 40 days (salary day clamped to month length).
- **Outstanding is compute-on-read** (`RepaymentService.breakdown`): `days = DAYS.between(disbursedOn,
  asOf)`; interest on `min(days, tenure + grace)`; penalty on `min(max(0, dpd − grace), 30)` days;
  minus Σ VERIFIED payments; capped by an approved settlement. This one figure is used everywhere
  (`/api/loan/{id}`, `/outstanding`, collections, reports). `loan.outstanding` is only a cache; a
  loan closes only when this balance hits 0.

**Worked example** (₹10,000): fee ₹1,000 · GST ₹180 · **net ₹8,820**. Disbursed 2026-06-03, salary
day 30 → due **2026-06-30** (27 days) → total ₹12,700. Disbursed 2026-06-24, salary day 30 → due
**2026-07-30** (36 days) → total ₹13,600. First loan, grace 0, still unpaid on 2026-07-05: interest
capped at 27 days + 5 penalty days (₹1,000) → ₹13,700. Prepaid on 2026-06-10 (7 days) → ₹10,700.

---

## 10. Data model & migrations

Flyway migrations live in **`backend/navix-app/src/main/resources/db/migration/`** — **V1..V102**,
contiguous, applied on boot. Each file's header comment explains *why*; the index is
[`docs/MIGRATIONS.md`](docs/MIGRATIONS.md).

- **Historical migrations are immutable**; retired statuses/roles stay in enums/CHECKs.
- **All money is `BIGINT` paise.** No FK constraints (indexes only) — a deliberate schema convention.
- The per-person key is **`customer_id`** (renamed from `applicant_id`, V33); code uses `customerId` /
  `CustomerProfile`. Only `co_applicant` keeps the old name; external bureau JSON keys are not ours.
- Identity uniqueness on `customer_profile` is customer-scoped (V23), so a returning borrower can
  re-onboard; Aadhaar duplicates across customers are flagged, not refused.
- Core tables: `loan_application` (status, eligible_limit, sanctioned_amount_paise,
  approved_repayment_date, assigned_executive_id, journey_step, reapplied_from, salary_credit_day,
  disbursal account, fee_discount_*), `application_event` (append-only, SoD source of truth),
  `loan` (principal, fee, gst, net_disbursed, due_date, grace_days, disbursal_txn_ref, status),
  `payment`, `application_verification`, `application_document` (S3 key + optional file password),
  `customer_profile`, `application_rejection` (reason_code, blocked_until).

---

## 11. Backend API surface

**Controllers are the source of truth**; the map is [`docs/API_SURFACE.md`](docs/API_SURFACE.md).

- Every action resolves the actor from the **JWT bearer** and enforces `requireRole`. A missing or
  invalid bearer → **401**. `SecurityConfig`: `/api/auth/**`, `/api/contact`, `/api/webhooks/**` (each
  checks its own secret), actuator and docs are public; `/api/staff`, `/api/admin`, `/api/dsa`,
  `/api/collections`, `/api/dashboard`, `/api/mandates`, `/api/customer-messages` need the staff
  audience; legacy `/api/kyc/**` and `GET /api/borrower/*` are denyAll.
- Business errors are `BusinessException` → **422** with a code (`FORBIDDEN_ROLE`, `SOD_VIOLATION`,
  `ILLEGAL_TRANSITION`, `TXN_REF_REQUIRED`, …), wrapped in the `ApiResponse<T>` envelope.
- Staff-only fields (credit score, ★ rating, BRE, AADHAAR_DUPLICATE rows) are **stripped** for a
  borrower reading their own data.

---

## 12. Conventions & key decisions

- **Money = integer paise (`long`), HALF_UP.** Never floats.
- **One aggregate.** Don't reintroduce fragmented per-stage entities.
- **SoD is mandatory and server-side** (flow service via the event trail). Never collapse two
  maker-checker steps onto one actor. **Every approve has a reject**, SoD-checked and audited.
- **JWT identity** stays localized to `JwtAuthFilter`/`SecurityConfig`; authz lives in the services.
- **Separate staff/borrower sessions** — never share a cookie or BFF namespace.
- **Notifications are event-driven and non-blocking.** Domain code publishes a Spring event; the
  `@TransactionalEventListener(AFTER_COMMIT) @Async` listener dispatches. Adding one = a
  `NotificationType` + template + audience. Never make business logic depend on a delivery.
- **Ports, not providers.** Identity/bureau/penny-drop/DigiLocker go through `VerificationPort`;
  eSign through `EsignPort`; mandates through `MandatePort`.
- **Schedulers are single-instance** (no distributed lock): payment reminders 09:00, employment retry
  hourly :30, provider health hourly :15, NACH debit sweep 10:00 IST, PayU reconcile every 5 min,
  provider-history purge 02:15.
- **Feature flags** live in `feature_flag`, are changed **only by SQL**, and are read uncached via
  `FeatureFlagService.isEnabled(key, defaultWhenMissing)`. The only ADMIN-flippable ones (Staff →
  Settings) are `onboarding-paused`, `applications-closed`, `payu-repayment`.

| Flag | Seed | Missing row ⇒ | Gates |
|---|---|---|---|
| `referral` | on | env default (on) | refer-a-friend |
| `fintrix-bureau` | on | on | Fintrix CRIF as bureau primary |
| `bureau-auto-reject` | **off** (V64) | off | auto-reject below score 550 |
| `digitap-pan`, `digitap-email` | off | off | Digitap fallback legs (not provisioned, 412) |
| `digitap-crif` | no row | off | Digitap CRIF leg (endpoint 401s) |
| `employment-auto-retry` | on | on | hourly EPFO re-run |
| `global-search` | on | **on** | Cmd/Ctrl+K palette |
| `onboarding-paused` | **on** (V76) | off | waitlist mode for new borrowers |
| `applications-closed` | no row | off | refuse every new application/reborrow |
| `digio-mandate` | off | off | eMandate offer step (real NPCI filing, no sandbox) |
| `digio-debit` | off | off | NACH debit presentation (moves real money) |
| `payu-repayment` | off | off | PayU checkout (refused without credentials) |
| `admin-messaging` | off | off | customer messaging, templates writes, discount offers |
| `reference-otp` | off | off | OTP-confirm each reference |
| `esign-documents` | off | off | admin eSign documents (billable, binding) |

  The DB seed is not the production state — e.g. `digio-mandate` is on in production per the Digio
  runbook. Check the live table before reasoning about a flag.
- **Secrets** never committed — env / **SSM SecureString** (`/navix/<env>/…`; the live task-def only
  sets `NAVIX_ENV=dev` + `AWS_REGION`, everything else is SSM). Key groups: `AUTH_SECRET`,
  `BORROWER_AUTH_TTL_SECONDS`, `NAVIX_APP_BASE_URL`, `DB_*`, `SIGNZY_*` (+ `_PROD_*`, `_BSA_AUTH_TOKEN`),
  `DIGITAP_*`, `FINTRIX_*`, `NAVIX_VERIFICATION_CHAIN` (default **`signzy,fintrix,digitap`**),
  `NAVIX_VERIFICATION_BUREAU_NO_HIT_FALL_THROUGH` (true), `NAVIX_ESIGN_*`, `NAVIX_DIGIO_*` (incl.
  `CORPORATE_ACCOUNT_NUMBER` — unset ⇒ every debit refuses `DEBIT_NOT_CONFIGURED`), `NAVIX_PAYU_*`,
  `NAVIX_S3_*`, `NAVIX_SMS_*`, `NAVIX_WHATSAPP_*`, `NAVIX_EMAIL_*`, `NAVIX_SES_*`, `NAVIX_CAPTCHA_SECRET`
  + `NEXT_PUBLIC_TURNSTILE_SITE_KEY` (unset = skipped; deploy the site key **before** the secret),
  `NAVIX_STATIC_EGRESS_IP` (Digitap allow-list), `PROXYCHECK_API_KEY`, `NETWORK_GUARD_*`, `SIM_*`,
  `NAVIX_BUREAU_FIXTURE` (offline credit brief), `NAVIX_ALLOW_DEV_DEFAULTS`.

---

## Git & Deploy Defaults

Commit and push directly to `main` unless told otherwise — do not create side branches. Confirm
which GitHub account is active (`gh auth status`) before pushing; this repo is pushed under
multiple accounts (kartikjindal, meetzy-india). If push fails on OAuth scope, run
`gh auth refresh -s workflow`.

> A push to `main` **is** the production deploy (`.github/workflows/ci.yml`: backend image tagged
> with the full SHA, task-def cloned from the live revision with only the image swapped; frontend via
> `vercel deploy --prod` to project `dhanboost`). Don't hand-build images. Rollback =
> `workflow_dispatch` with `deploy_tag`. Verify the running SHA before claiming a deploy.

---

## 13. External integrations

Who does what, auth, hosts, live status and per-API gotchas →
[`docs/INTEGRATIONS.md`](docs/INTEGRATIONS.md) (bureau order there predates 2026-10-10), plus
`docs/signzy/`, `docs/digitap/`, `docs/fintrix/`, `docs/digio/` (runbook 14 is the latest truth),
`docs/payu/README.md`, `docs/sms-dlt/`, `docs/whatsapp/WHATSAPP_GUIDE.md`,
`docs/BANK_STATEMENT_ANALYSIS.md`.

- **Verification chain** (`RoutingVerificationPort`, one global order, default
  `signzy,fintrix,digitap`): bureau = Fintrix CRIF (`crif_combine`, carries the vendor PDF) →
  Digitap Experian (Signzy's bureau leg skips itself); PAN = Signzy → Fintrix → Digitap; email =
  Signzy → Digitap; address = Signzy reverse-geocode → Digitap; employment = Digitap UAN only;
  penny-drop + DigiLocker = Signzy only; selfie = Digitap Face Liveness v4, Signzy liveness iframe as
  secondary. Bureau alone walks **past a no-hit**. `answerBureauChallenge` (CRIF KBA) bypasses the
  chain to Fintrix — an `order_id` belongs to one vendor. Throw `CapabilityNotSupportedException` to
  skip, `VerificationException` to fall through, `TerminalVerificationException` to stop.
- Every provider call lands in `provider_api_execution` (ADMIN Provider API dashboard), credentials
  stripped, joinable to logs by `requestId`. ADMINs are alerted on `PROVIDER_BALANCE_EXHAUSTED` /
  `PROVIDER_CAPABILITY_DOWN`.
- ⚠️ **Every eSign initiate (borrower KFS or admin document) and every Digio mandate is real,
  billable and legally binding; there is no sandbox.** The drawn-signature fallback never calls
  `EsignPort`.
- **Our DB row is the source of truth** for every redirect/poll flow (DigiLocker, liveness, eSign,
  mandate, BSA, PayU); provider callbacks only accelerate.
- **No verification hard-blocks a borrower.** A failed check is `REVIEW` for a human, not a decline
  (the fraud rules in §6 are the deliberate exception).
- On-demand, billable staff tools never run in the lifecycle: Digitap **Skip Tracer** (Collection
  Head/ADMIN, `skip_trace`) and Signzy **Bank Statement Analysis** (credit roles/ADMIN — read-back is
  403 on our account, so don't press Run).
- **PayU:** credit `transaction_amount` (not `amt`), every signal re-checked with `verify_payment`,
  reconcile every 5 min, anomalies land in the Accountant's review queue (resolution is record-only;
  refunds happen in the PayU dashboard).
- **SMS (UltronSMS, sender `DHANBT`):** text must match the DLT template char-for-char, use `Rs.` not
  `₹`, and any URL must be exactly the whitelisted `https://dhanboost.com/login`. The `DHANBOOST_*_V1`
  batch is approved and defaulted in `application.yml`.
- **WhatsApp (SmartChat)** mirrors borrower SMS; wording lives with Meta, so never reorder the keys in
  `NotificationTemplates.whatsapp(type, templateName, orderedKeys…)` or rename a template without an
  approved one of that name. Check the `_164` name suffix the portal adds.
- **Email:** `EmailClient` = `log` | `smtp` | `ses` | `resend`; SES bounces/complaints arrive over
  SNS→SQS into `email_suppression`.

---

## 14. Known gaps (go-live backlog)

- Real bank **payout** (NEFT/IMPS) at disbursement — today the Disbursement Head transfers by hand.
- Bureau score floor recalibration (then re-enable `bureau-auto-reject`).
- DB: FK constraints; drop the legacy `bytea` doc column; PII-at-rest encryption. The raw Aadhaar
  number (V75) and unredacted card images are a deliberate, product-approved exception to masking.
- Distributed locks before running more than one backend task.
- Middleware JWT signature verification (today it reads `exp` only).
- Digio webhook receiver; UPI AutoPay; mandate amendment.

---

## 15. Reference

- **[`docs/PRD_REBUILD.md`](docs/PRD_REBUILD.md)** — the full rebuild spec / prompt for recreating the platform.
- [`docs/API_SURFACE.md`](docs/API_SURFACE.md) · [`docs/INTEGRATIONS.md`](docs/INTEGRATIONS.md) ·
  [`docs/MIGRATIONS.md`](docs/MIGRATIONS.md) · [`docs/STATE.md`](docs/STATE.md)
- **`aws.md`** — the cloud deployment (resource ids, SSM layout, smoke tests). Its revision numbers
  and manual-deploy recipe predate CI.
- `docs/digio/`, `docs/payu/`, `docs/whatsapp/`, `docs/sms-dlt/` — per-provider knowledge bases.
- Historical (don't treat as current): `FUTURE.md`, `PRODUCTION_READINESS.md`, `QA_CHECKLIST.md`,
  `plan.md`, `revamp.md`, `ADMIN_CONSOLE_REVAMP_PLAN.md`, `OPS_UI_UX_OPTIMIZATION_PLAN.md`,
  `PRODUCT_GAPS.md`, `NAVIX_Finance_Product_Flow.md`, `Digilocker_API_Guide.md` (obsolete).
- `AGENTS.md` is the Codex copy of this file and lags it.
