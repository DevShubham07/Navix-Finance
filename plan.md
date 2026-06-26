# Plan: Borrower account menu + full admin per-step control

> Status: **validated against the code and being implemented** (branch
> `plan/borrower-menu-admin-control`). Decisions locked with the user: **per-step admin control**
> (no one-click fast-forward) and **all** borrower menu extras. Seed demo data for every lifecycle
> stage with the populator — see [`populateDummyData.md`](populateDummyData.md).

## Context

Two user-requested capabilities for the NAVIX demo:

1. **Borrower account menu** — a top-right account icon (avatar) on the borrower
   app shell that opens a dropdown with: **Profile details, Past transactions,
   Past loans (with current status), Support**, plus the extras the user approved
   (**Repay/prepay, Borrow again, Help & FAQ, Account settings**) and **Sign out**.
   Today the borrower header (`components/app/app-header.tsx`) is a flat nav with
   only Dashboard / Repay / Profile, there is no loans/transactions/support view,
   and there is **no backend endpoint to list a borrower's applications** (the UI
   only tracks one in-flight app id in `localStorage`).

2. **Full admin control (per-step)** — when signed in as `ADMIN`, the user wants
   to approve/reject a loan at **every** stage himself. The backend already lets
   `ADMIN` bypass `requireRole` (`ApplicationFlowService.requireRole`, line ~404),
   and the staff console (`/staff/applications`) already renders **all** queues for
   ADMIN. Two server-side guards still block a solo admin run:
   - **SoD** in `headDecision` — the same actor who recommended (exec) can't approve
     (head). Admin logs in as one real seeded staff row (constant bigint id, Meera
     Krishnan), so recommending + approving as admin trips `SOD_VIOLATION`.
   - **Assign** requires an *active Credit Executive* assignee, so the admin can't
     drive the credit step as "himself."

   The user chose **per-step only** (no one-click fast-forward). So the work is to
   lift those two guards **for ADMIN only**, leaving every existing per-stage button
   (which ADMIN already sees) fully functional end-to-end.

Demo-first conventions still hold: integer paise, one `loan_application` aggregate,
identity via demo actor headers, separate staff/borrower sessions. No schema change.

---

## Part A — Borrower account menu

### A1. Backend: list the borrower's applications
- `ApplicationFlowService.java` — add `myApplications()` mirroring `reborrow()`'s
  identity resolution: `requireRole("BORROWER")`, `applicantId = Long.valueOf(ActorContext.get().id())`,
  return `applicationRepository.findByApplicantId(applicantId)` sorted by id desc.
  (`findByApplicantId` already exists — used by reborrow.)
- `ApplicationController.java` — add `@GetMapping("/mine")` → returns
  `List<ApplicationView>`. **Must resolve before `/{id}`** (literal segment wins over
  the `{id}` path-variable in Spring, so `/mine` is safe — same precedence the existing
  `/credit-queue` and `/pending-repayments` literal routes already rely on), reachable through the
  existing borrower catch-all proxy at `/api/borrower/applications/mine`.
- No BFF change — `app/api/borrower/applications/[[...path]]/route.ts` is a catch-all
  that injects the borrower actor (id = applicantId).

### A2. Typed client + helpers
- `lib/api/applications.ts` — add `borrowerApi.myApplications()` →
  `bff<ApplicationView[]>("/api/borrower/applications/mine", "GET")`.
- `lib/api/live-journey.ts` — add `logoutBorrower()` helper that fixes the latent
  sign-out bug: it calls `signOutBorrower()` (imported from `@/lib/mock/session`),
  `POST /api/auth/borrower/logout` (clears the real `navix_borrower` cookie — today's `app-header`
  sign-out never does), `writeStoredAppId(null)`, then the caller routes to `/login`.
  (The logout **route already exists** at `app/api/auth/borrower/logout/route.ts`; only this client
  helper is new.)

### A3. Account menu component + header
- New `components/app/account-menu.tsx` — a client dropdown: avatar/`User` button at
  top-right, panel with click-outside + Escape close. Identity name from the **live**
  session (`useBorrowerSession` in `live-journey.ts`) with the mock session as fallback.
  Grouped links (lucide icons, matching existing nav styling):
  - Dashboard (`/dashboard`), Profile details (`/profile`)
  - Past loans (`/loans`), Past transactions (`/transactions`)
  - Repay / prepay (`/repay`), Borrow again (`/reloan`)
  - Support (`/support`), Help & FAQ (`/support#faq`), Account settings (`/settings`)
  - Sign out (calls `logoutBorrower()` then `router.push("/login")`)
- `components/app/app-header.tsx` — keep Brand (left) + a "New loan" gold CTA, replace
  the inline Profile/Sign-out cluster with `<AccountMenu />` on the right. Keep the
  signed-out state (Sign in / Apply Now) unchanged.

### A4. New borrower pages (under `app/(borrower)/`, so they inherit the shell + menu)
- `loans/page.tsx` — "Your loans": `useQuery(borrowerApi.myApplications)`; one card per
  application showing `statusLabel(status)`, requested amount (`amountRequestedPaise`), and — when `loanId` set —
  the loan summary via `borrowerApi.loan(loanId)` (principal, net disbursed, outstanding,
  due date). Reuses `Badge`, `paiseToINR`, `formatDate`, the dashboard's status mapping.
- `transactions/page.tsx` — "Transactions": client-side ledger built from existing
  endpoints (no new backend) — for each application with a `loanId`, one **DISBURSAL**
  row (credit, `loan.netDisbursedPaise` on `disbursedOn`) + a **REPAYMENT** row per
  `borrowerApi.repayments(loanId)` entry (debit, with `status`). Sorted by date desc;
  reuses `paiseToINR`/`formatDate` and the `PaymentStatusName` styling.
- `support/page.tsx` — "Support": grievance link (`/grievance`), contact (email/phone),
  and an `id="faq"` FAQ accordion (product facts from `CLAUDE.md` §1: 25% limit, 10% fee,
  18% GST, 1%/day interest, single salary-day repayment). Serves both the Support and
  Help & FAQ menu items.
- `settings/page.tsx` — "Account settings": demo-grade, clearly labelled — local
  notification/security toggles persisted to `localStorage` only (no backend), plus a
  link to Profile. Mirrors the existing profile page layout/components.

---

## Part B — Admin full per-step control (ADMIN-gated relaxations)

### B1. Backend (`ApplicationFlowService.java`)
- **SoD exemption** in `headDecision`: skip the recommender==approver check when
  `"ADMIN".equals(ActorContext.get().role())`. One guard wraps the existing
  `recommender.equals(ActorContext.get().id())` test (~L230-233; `requireRole` already bypasses
  ADMIN at ~L406). Non-admin SoD is untouched, so the integration test
  `separationOfDutiesBlocksSameActorAsExecutiveAndHead` (uses role `CREDIT_HEAD`, id
  `sameperson`) stays green.
- **Admin self-assign** in `assignExecutive`: when the actor role is `ADMIN`, skip the
  `staffDirectory.isActiveWithRole(executiveId, "CREDIT_EXECUTIVE")` requirement (~L201) so the
  admin can assign the review to himself (his own staff id) and proceed. Non-admin path
  unchanged (still must pick an active Credit Executive).

### B2. Frontend (`components/staff/live-pipeline.tsx`)
- `AssignActions` — for `ADMIN` (read `useStaffMe()`), render an extra **"Assign to me"**
  button beside the executive dropdown that calls `staffApi.assign(app.id, Number(me.id))`.
  Non-admins keep the dropdown-only behaviour.
- No other UI change needed: every per-stage cluster (`KycActions`, `ExecActions`,
  `HeadActions`, `DisbursementActions`, `AccountantActions`) already renders for ADMIN via
  `ActionGate`/`hasPermission`, and the SoD exemption makes `HeadActions` approve succeed.
- Confirm the staff applications proxy `app/api/staff/applications/[[...path]]/route.ts`
  is a catch-all (mirrors the borrower one) — no new staff endpoint is introduced, so this
  is just a verification step.

### Result
Signed in as ADMIN on `/staff/applications`, the admin walks one application solo:
KYC ✔ → **Assign to me** → Exec ✔ → Head ✔ (SoD lifted) → Disburse ✔ (txn id) → ACTIVE,
and may **reject** at any stage (existing reject buttons already work for ADMIN). Reject/
cancel need no change.

---

## Demo data (every lifecycle stage)

Before exercising the menu/admin features, seed the app with an application parked at **every** stage
(plus a primary borrower with history + current + in-review) using the API-driven populator —
[`populateDummyData.md`](populateDummyData.md) / `scripts/populate-demo-data.ps1`. It drives the real
REST API on `:8090` with demo-actor headers (genuine money math, salary-linked due dates, audit
trail), with one small SQL `UPDATE` to backdate the OVERDUE / past-delinquency loans. After it runs,
every staff queue and the borrower account menu show realistic rows. Primary demo login: mobile
**9819000001**, OTP **123456**.

---

## Files

**Backend**
- `navix-loan/.../service/ApplicationFlowService.java` — `myApplications()`, SoD exemption in `headDecision`, admin branch in `assignExecutive`.
- `navix-loan/.../controller/ApplicationController.java` — `GET /mine`.

**Frontend**
- `lib/api/applications.ts` — `borrowerApi.myApplications()`.
- `lib/api/live-journey.ts` — `logoutBorrower()`.
- `components/app/app-header.tsx` — mount `<AccountMenu />`.
- `components/app/account-menu.tsx` — **new** dropdown.
- `app/(borrower)/loans/page.tsx`, `transactions/page.tsx`, `support/page.tsx`, `settings/page.tsx` — **new** pages.
- `components/staff/live-pipeline.tsx` — `AssignActions` admin "Assign to me".

---

## Verification

1. **Backend tests** — `cd backend && ./mvnw test` (114 unit tests). Add/confirm:
   - SoD still blocks a non-admin recommender==approver; an `ADMIN` recommender→approver
     now **succeeds** (extend `ApplicationFlowIntegrationTest` with an all-ADMIN-header run
     DRAFT→ACTIVE: submit-kyc/apply as BORROWER, then kyc/assign(self)/exec/head/disburse
     as ADMIN → asserts `ACTIVE` + minted `loanId`).
   - `GET /api/applications/mine` returns only the calling applicant's apps.
2. **Integration** (Docker) — per `CLAUDE.md` §4.5 env, `./mvnw -pl navix-app -Pit test`.
3. **Frontend** — `cd frontend && npm run build` (tsc + ESLint clean).
4. **Manual / Chrome MCP**:
   - Borrower: `/login` (mobile + `123456`) → top-right avatar menu shows all items →
     open `/loans` (status per application), `/transactions` (disbursal + repayments),
     `/support` (+ `#faq`), `/settings`; **Sign out** lands on `/login` AND clears the
     `navix_borrower` cookie (re-visiting `/dashboard` no longer resolves a session).
   - Admin: `/staff/login` → pick **Administrator** → `/staff/applications`; take one
     applied application KYC→ACTIVE entirely as admin (incl. **Assign to me** and Head
     approve with SoD lifted); verify the audit trail attributes each step to ADMIN.
