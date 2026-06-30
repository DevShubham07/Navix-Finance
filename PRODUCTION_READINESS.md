# NAVIX — Production Readiness Checklist

Outcome of the 9-phase production-readiness program (2026-06-30). All phases below are **implemented,
verified, and deployed** to AWS ECS + Vercel prod (`www.navixfinance.com`) unless noted. Each phase
shipped as its own commit on `main`.

---

## Done — shipped & live

### Phase 2.3 — RBAC / security hardening (commit `215666f`)
- Closed live PII/authorization leaks by adding **server-side role guards** (the UI was the only gate;
  a direct API call bypassed it):
  - `GET /api/loan/transactions` → **ACCOUNTANT/ADMIN only** (was readable by any token incl. a borrower
    JWT). Staff keep the full PAN — **masking is a customer-side concern only** (product rule).
  - `/api/staff` list/get/update/disable, `/api/staff/invites` list/create, `/api/admin/blocklist`
    add/remove/list → **ADMIN-only** (service-level `requireAdmin`).
- BFF session cookie now `secure` in production; staff middleware rejects malformed/expired sessions
  (backend `JwtAuthFilter` remains the authoritative boundary).
- Negative RBAC tests for each guarded service.

### Phase 2.1 — Salary management (commit `1fd33ea`, Flyway V26)
- `annual_salary_paise`, `salary_percentage`, `increment_percentage` on `applicant_profile`.
- `profile_change_log` table — append-only **previous→new** audit (modified-by/at). Admin salary edit
  records every changed field and **recomputes the eligible limit** (RiskPort 25% cap) on
  not-yet-disbursed applications. `GET /api/customers/{id}/changes` + a Change-history card.

### Phase 3 — KYC Verification System (commits `fac0a36`, `65aeac9`)
- **Verification dashboard**: per-step status + **Retry / manual approve-reject** (KYC_APPROVER/ADMIN),
  audited via the existing `MANUAL` provider upsert.
- **Progress tracker**: `GET …/{id}/verification-progress` (completed/failed/pending/percent) + a bar on
  the applicant review.
- **Pending-API dashboard** (`/staff/verifications`): cross-applicant tallies (passed/review/failed/
  pending/never-run) + filters, backed by `GET …/verifications/overview`.
- **Reminders**: `KYC_REMINDER` notification (TO_BORROWER, IN_APP/SMS/EMAIL) + `POST …/{id}/send-reminder`
  (computes the pending-step list); delivery reuses the notification/delivery audit.

### Phase 2.2 + Phase 4 — Profile management + re-verification (commit `4f1745f`, Flyway V27)
- **Edit → invalidate → re-verify seam** (`VerificationInvalidationService`): editing a verification-
  linked field (address→ADDRESS, bank→PENNY_DROP, salary→SALARY, employer/employment/email→EMAIL) resets
  the check to PENDING + clears the profile flag; a salary change recomputes eligibility.
- **Borrower self-edit**: `/profile` is an editable form — identity (name/PAN/Aadhaar/mobile/DOB)
  **locked**; contact/employment/salary/bank + **emergency contacts** editable
  (`PUT …/{id}/profile/self`).
- **Settings persisted server-side**: `borrower_preferences` (keyed by applicant id) +
  `GET|PUT /api/preferences`; the notification dispatcher **suppresses an opted-out SMS/EMAIL** per
  borrower (IN_APP kept; a `SKIPPED=OPTED_OUT` delivery recorded). "Demo settings" banner removed.
- **Staff self-profile**: `staff_user.department/designation` + `GET|PUT /api/staff/me` + `/staff/profile`
  (role/status stay admin-only).

### Phase 5 — Transaction ledger / PDF export (commit `9286cb7`)
- Fixed the **period off-by-one** (`new Date("yyyy-mm-dd")` UTC-midnight vs local) — period filtering is
  now ISO-string based and **server-side** (`GET …/transactions?from=&to=`, inclusive).
- PDF export states the **statement period (label + from–to), timezone, generated timestamp, transaction
  count**.

### Phase 6 — Demo removal & hardening (this batch)
- Deleted dead/unused code: the all-TODO SDK shims `lib/api/fintrix.ts` + `lib/api/digilocker.ts`, and the
  orphan cosmetic `signup/co-applicant` step (absent from the canonical wizard; nothing routed to it).
- **`PennyDropGate` fails closed by default** (`navix.disbursement.penny-drop-stub-pass=false`) — the
  legacy disbursement chain's bank-verification stub can no longer silently auto-approve a release in
  prod (the live flow's real Fintrix penny-drop is unaffected).

### Phase 7 — Workflow refinement (this batch)
- Reviewed the borrower + staff journeys; **all account-menu and staff-nav links verified to resolve**
  (no dead internal links). The major workflow gaps (editable profile, real settings, verification
  actions) were closed in Phases 2–5.

### Phase 8 — Testing (this batch)
- Added unit tests for the new surfaces: `VerificationInvalidationService` (field→check reset),
  `BorrowerPreferencesService` (borrower-scoping + upsert), dispatcher **opt-out suppression**, and the
  transaction **date-range** filter. Full backend unit suite green; frontend `tsc` + ESLint clean.

---

## Remaining — go-live backlog (vendor / legal / ops; not code-blocked)

These require external credentials, legal content, or an ops decision — out of scope for in-app code:

| Item | Action |
|---|---|
| **Seeded staff password** `Admin@12345` | Force a first-login reset / per-user passwords; rotate before any real exposure. |
| **"Act as role" persona switcher** (`staff-role-bar`) | Demo convenience — gate behind a prod flag or remove for production. |
| **Real SMS delivery** | UltronSMS is wired but blocked on **DLT template registration**; currently `NAVIX_SMS_MOCK=true` (OTP 123456). Register templates, set mock off. |
| **Real email delivery** | Default is the log client; set `navix.email.provider=smtp` (+ SMTP creds). |
| **Bureau** | Turn `NAVIX_BUREAU_FIXTURE` **off** in prod so pulls hit real Fintrix/Experian. |
| **Payslip OCR + employment-verification API** | The SALARY step records declared salary (always PASS); employment is corroborated via EPFO/EMAIL. A real payslip parser + employment API are vendor integrations. |
| **NBFC partner legal disclosures** (`lib/brand.ts`) | Placeholder copy — replace with the real RBI-registered NBFC partner disclosures (business/legal). |
| **Real bank payout** | NEFT/IMPS at the accountant step; sanction-letter / agreement generation → S3. |
| **DB cleanup** | Add FK constraints; drop the legacy `bytea` document column + the dormant `disbursement_request` UUID chain; PII-at-rest encryption; full-Aadhaar masking on customer reads. |
| **Edge auth** | Middleware does presence + expiry decode; the backend `JwtAuthFilter` is authoritative. Add edge JWT-signature verification if defence-in-depth is desired. |

---

## How to verify (per the program)
- **Backend**: `cd backend && ./mvnw -o test` (full unit suite). Integration: `-Pit` (Testcontainers).
- **RBAC regression**: `curl` each guarded endpoint with a borrower JWT and a non-admin staff JWT →
  expect `403 / FORBIDDEN_ROLE`; admin → 200.
- **Frontend**: `npx tsc --noEmit` + `next lint`.
- **Live smoke**: ALB `/actuator/health` → `{"status":"UP"}`; Vercel prod alias `www.navixfinance.com`.
