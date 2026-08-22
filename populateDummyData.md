# populateDummyData.md — seed demo data at every loan/collections/admin surface

A one-shot seeder that fills a local, **fully offline** DhanBoost environment with realistic data
at every stage of the loan lifecycle plus every back-office surface (collections, settlements,
expenses, blocklist, invites, referral payouts, audit trails) — so every one of the 19 admin
nav items renders populated for a recorded walkthrough. See
[`DEMO_WALKTHROUGH.md`](DEMO_WALKTHROUGH.md) for the recording script this data supports.

- **Launcher:** [`scripts/run-demo.ps1`](scripts/run-demo.ps1) — starts Postgres (Docker, `:5433`),
  the backend (`:8090`), and the frontend (`:3000`), fully offline (no AWS/S3 calls).
- **Seeder:** `scripts/seed-demo-data.ps1` — drives the **real backend REST API** with real JWTs
  (staff login + borrower OTP), so the flow service computes the money (integer paise), the
  salary-linked due date, and the append-only `application_event` audit trail for you. A small
  companion, `scripts/seed-demo-sql.sql`, backdates a handful of rows directly via `psql` for the
  states the API can't produce (see "What needs SQL" below).
- **Reset:** `scripts/reset-test-data.sql` — truncates business data while preserving
  `staff_user` / `staff_invite` / `payment_settings` / `feature_flag` / `flyway_schema_history`.

> **Note on the old script:** `scripts/populate-demo-data.ps1` predates the JWT migration — it
> authenticated with `X-Demo-Actor-*` headers that `DemoActorFilter` used to read (that filter was
> replaced by `JwtAuthFilter` back in the P6 migration), posted `applicantId` where the API now
> expects `customerId` (renamed in V33), and never created the `application_verification` rows that
> `submit-kyc` now hard-gates on. It does not work against the current backend and should not be
> used; `scripts/seed-demo-data.ps1` replaces it entirely.

---

## Prerequisites

- **Docker Desktop** running (Postgres is published on `:5433`; a native PostgreSQL on this machine
  already owns `:5432`, which is why the demo stack uses the alternate port).
- **JDK 21** for the backend build (`release version 21 not supported` under JDK 17).
- **Node 20+** for the frontend.
- `psql` on `PATH` (the seeder's SQL companion shells out to it directly — no
  `docker compose exec` indirection needed).

### Why offline, and what that costs

The demo runs with **no AWS/S3 at all** — `backend/.env` (which sets `AWS_PROFILE=navix-dev` and
would otherwise pull `spring.config.import: aws-parameterstore` and silently repoint the app at dev
RDS) must be renamed to `backend/.env.aws-backup` for the duration. `run-demo.ps1` instead launches
the backend with:

| Env var | Why |
|---|---|
| `DB_URL=jdbc:postgresql://localhost:5433/navix`, `DB_USERNAME=navix`, `DB_PASSWORD=navix` | local Docker Postgres, not RDS |
| `SPRING_CONFIG_IMPORT=optional:configtree:./no-aws-config/` | kills the SSM parameter-store import — the RDS hazard |
| `SPRING_CLOUD_AWS_CREDENTIALS_ACCESS_KEY=test`, `..._SECRET_KEY=test`, `..._REGION_STATIC=ap-south-1`, `AWS_EC2_METADATA_DISABLED=true` | static dummy creds so the S3 client/presigner beans still construct without reaching the network |
| `NAVIX_SMS_MOCK=true` | borrower OTP is always `123456`, no SMS gateway call |
| `NAVIX_BUREAU_FIXTURE=classpath:samplepan.json` | every bureau pull returns a bundled report → real 1–5★ credit briefs with no vendor call |
| `NAVIX_EMAIL_PROVIDER=log`, `NAVIX_SES_EVENTS_ENABLED=false` | emails log instead of sending; no SQS listener trying to reach AWS at boot |
| `AUTH_SECRET=<32+ chars>` | JWT signing secret (any sufficiently long string for local use) |

**Accepted offline gaps:** credit briefs show score + ★ rating + facts but no PDF download link
(the S3 render step no-ops); expense receipts and the payment-settings QR/PDF stay blank; document
previews/uploads are skipped. None of this blocks the console from looking complete — see
`DEMO_WALKTHROUGH.md`'s per-chapter traps for exactly where each gap surfaces on screen.

---

## Run it

```powershell
# 1. Start the offline stack (Postgres + backend + frontend, each in its own window)
.\scripts\run-demo.ps1
# add -Rebuild after any backend code change; -NoFrontend for backend-only

# 2. Wait for http://localhost:8090/actuator/health to answer UP, then seed
.\scripts\seed-demo-data.ps1

# 3. Confirm every admin-visible surface is populated
.\scripts\seed-demo-data.ps1 -Verify
```

`seed-demo-data.ps1` accepts `-BackendBase http://localhost:8090` (default), `-DbPort 5433`
(default), `-Verify` (re-read every admin-visible endpoint and print a PASS/EMPTY checklist instead
of seeding), and `-SkipReset` (skip the reset-before-seed step, e.g. to layer more data on top of an
existing run). Expect **~5–10 minutes** for the full run — roughly 49 applications, each driven
through 10+ sequential API calls to walk the real state machine.

Demo logins the run leaves you with:

| Role | Email | Password |
|---|---|---|
| ADMIN | `meera.krishnan@navix.example` | `Admin@12345` |
| (alternate ADMIN) | `navixfinance@gmail.com` | `demo` |
| any other role | `<firstname>.<lastname>@navix.example` (see table below) | `Admin@12345` |

Once signed in as any role, the floating **"Act as role"** bar (bottom-right) re-authenticates as
any of the 9 seeded personas in one click — see `DEMO_WALKTHROUGH.md` chapter 6 for why that matters
for demonstrating separation of duties.

---

## The identity scheme

Every seeded customer's mobile is `9819000NNN`, which the backend derives into
`customerId = 9000NNN` (its last 7 digits); PAN is `AAAPD0NNNA`. `NNN` runs `010`–`058`, chosen to
avoid the V12 unique PAN/mobile-index collisions and the "one live advance per customer" guard
(`assertCanStartNewApplication` throws `ACTIVE_LOAN` / `ACTIVE_APPLICATION` on a second live
application for the same customer) — hence one persona per lifecycle state rather than reusing an
identity across states.

## Seeded staff personas (V10, all password `Admin@12345`)

| Name | Role |
|---|---|
| Ananya Rao | `KYC_APPROVER` |
| Rahul Mehta, Kabir Singh, Neha Gupta | `CREDIT_EXECUTIVE` |
| Priya Nair | `CREDIT_HEAD` |
| Vikram Shah | `DISBURSEMENT_HEAD` |
| Deepa Iyer | `ACCOUNTANT` |
| Arjun Patel | `COLLECTION_HEAD` |
| Sana Khan | `COLLECTION_EXECUTIVE` |
| Meera Krishnan | `ADMIN` |

## The customer/application personas (~49 applications)

| Group | Count | customerIds | Ends at / how |
|---|---|---|---|
| KYC queue | 3 | 010–012 | `KYC_PENDING` |
| Credit queue | 3 | 013–015 | `KYC_APPROVED` + applied |
| Exec review | 3 | 016–018 | `CREDIT_EXEC_PENDING` (assigned by Credit Head) |
| Head-owned review | 3 | 019–021 | `CREDIT_EXEC_PENDING` (assigned to the Credit Head) |
| Disbursement | 3 | 022–024 | `DISBURSEMENT_PENDING` |
| Accountant | 2 | 025–026 | `ACCOUNTANT_PENDING` (released with no `txnRef`) |
| Disbursement failed | 1 | 027 | `accountant-validate {decision:false}` → `DISBURSEMENT_FAILED` |
| Active | 3 | 028–030 | fast-path with `txnRef`; `disbursed_on` spread over the last 30 days |
| Overdue | 5 | 031–035 | SQL-backdated into DPD bands 1–7 / 8–30 / 31–60 / 61–90 / 90+ |
| Closed (fully repaid) | 3 | 036–038 | backdated → `GET .../outstanding` read *after* backdating → paid exactly → verified |
| Defaulted | 1 | 039 | **SQL** — `loan_application.status = 'DEFAULTED'` (no code path writes this) |
| Written off | 1 | 040 | **SQL** — `loan_application.status = 'WRITTEN_OFF'` (same) |
| KYC rejected | 1 | 041 | `kyc-decision {decision:false}` |
| Credit rejected | 2 | 042–043 | `head-decision {decision:false}` |
| Cancelled | 1 | 044 | `POST /{id}/cancel` |
| Verification-dashboard mix | 3 | 045–047 | left pre-submit: zero checks ("Not started") · one FAIL · a partial 4/8 |
| Reborrow | 2 | 048–049 | `PRE_APPROVED` (clean history) · `REVIEW_PENDING` (late-repaid prior loan) |
| Referral | 2 | 050–051 | referrer + a brand-new referred borrower → disbursed → 2 payouts |

**Loan OVERDUE is compute-on-read.** Nothing ever writes `LoanStatus.OVERDUE` to the `loan` row, and
the `loan_application.status` for those five personas stays `ACTIVE` — so the overdue personas
populate the DPD buckets and collections surfaces but will **not** show up under
`GET /api/applications?status=OVERDUE`. That's expected, not a seeding bug.

## Back-office data (beyond the application personas)

| Surface | What's seeded |
|---|---|
| Collections cases | opened on the overdue loans (opening a case flips the loan to `IN_COLLECTIONS`; the DPD-buckets grid on `/staff/applications` lists them, and any overdue loan *without* a case still shows in the "Overdue" column with an Assign picker, which opens its case on demand); assigned to an ACTIVE `COLLECTION_EXECUTIVE`; 2–3 interactions per case across CALL/SMS/VISIT × CONNECTED/NO_ANSWER/PROMISE_TO_PAY (a `PAID` outcome always carries a `proofRef`) |
| Settlements | proposed on 2 different cases as `COLLECTION_EXECUTIVE`; one **approved**, one **rejected**, both by `COLLECTION_HEAD` (two distinct staff tokens — `approvedBy == proposedBy` throws `SOD_VIOLATION`) |
| Repayments | 3 left unverified (feeds `/api/loan/pending-repayments` + the accounting queue), 1 **rejected** (rejected *before* it could be verified — verifying first makes `PAYMENT_ALREADY_VERIFIED`), the rest verified with `paidOn` spread across the last 30 days |
| Company expenses | 8 rows via `POST /api/admin/expenses`, dated across the last 30 days, `receiptObjectKey: null` (the list endpoint presigns every key, unreliable offline) |
| Blocklist | 4 entries — one each across `PAN` / `PHONE` / `BANK_ACCOUNT` / `DEVICE` |
| Staff & invites | 2 invites (the response returns the one-time token so the UI's copy chip is demoable); 1 new staff account created; 1 role change via `PUT /api/staff/{id}`; 1 persona disabled (never the persona you're currently signed in as — `INACTIVE_STAFF` locks you out mid-run) |
| Salary management / audit | 2 ADMIN edits via `PUT /api/customers/{id}/profile` (a **full replace**, not a patch — every field must be sent or the omitted ones are nulled *and logged* as changes), populating `profile_change_log` |
| Customer remarks | 3 rows |
| Referral | the `referral` feature flag is already seeded `true`; the referrer mints a code (`GET /api/referral/me`, borrower token only), the referred borrower must have zero prior loans (`NOT_NEW_BORROWER` otherwise), and driving that loan to disbursal creates 2 `PENDING` payouts — one is paid as `DISBURSEMENT_HEAD`, one deliberately left pending so both tabs on `/staff/disbursement/referrals` have rows |

---

## What needs SQL, and why

Everything above is created through the real API — nothing bypasses `ApplicationFlowService`'s
transition map. A handful of states genuinely cannot be produced through the API, so
`scripts/seed-demo-sql.sql` handles them via `psql` (already the pattern
`docs/sms-dlt/test-all-templates.sh`-style scripts in this repo use — no Docker exec needed):

- **Overdue backdating.** The server always stamps `due_date` in the future on disbursal; to get a
  loan into a DPD bucket you must `UPDATE loan SET disbursed_on = …, due_date = …` after the fact.
  The five overdue personas and the three closed personas are backdated this way (closed loans read
  `GET /api/loan/{id}/outstanding` **after** backdating, then pay that exact figure — paying the
  no-penalty total instead leaves the late penalty owed and the loan never reaches zero, since
  `outstandingAsOf` is penalty-aware on every read).
- **`DEFAULTED` / `WRITTEN_OFF`.** No controller action ever writes these `loan_application.status`
  values — they're set directly.
- **Trend spreading.** `DashboardService.trends` (backing the three sparkline cards on
  `/staff/dashboard`) reads `application_event.at` for `action='CREATE'`, `loan.disbursed_on`, and
  `payment.paid_on` (VERIFIED only). It always returns 30 points, so the cards render regardless —
  but they're flat at zero with 0% week-over-week deltas unless those three timestamp series are
  spread across the last 14 days. The SQL companion spreads all three.
- **ADMIN notifications.** `RecipientPolicy.TO_ADMINS` is declared in the notification engine but
  **no `NotificationType` actually uses it** — so the ADMIN inbox is structurally empty no matter
  what you do through the live API. The SQL companion direct-inserts ~8 `notification` rows
  (`recipient_type='STAFF', recipient_id=10`) so the bell has something to show when you're signed
  in as ADMIN. The alternative — seeing notifications arrive "for real" — is switching to
  KYC_APPROVER / ACCOUNTANT / COLLECTION_HEAD via the role bar, where ordinary application events do
  reach the inbox.

---

## Reset and re-seed

```powershell
psql -h localhost -p 5433 -U navix -d navix -f scripts\reset-test-data.sql
.\scripts\seed-demo-data.ps1
.\scripts\seed-demo-data.ps1 -Verify
```

`reset-test-data.sql` truncates business tables (applications, loans, payments, profiles,
collections, settlements, expenses, blocklist, notifications, referral tables, etc.) while
preserving `staff_user`, `staff_invite`, `payment_settings`, `feature_flag`, and
`flyway_schema_history`. It intentionally targets the **current** (V33-renamed) schema —
`customer_profile`, `borrower_mobile`, `borrower_credential`, `password_reset_token`,
`profile_change_log`, `borrower_preferences`, `referral`, `referral_code`, `referral_payout`,
`customer_remark`, `company_expense`, `email_suppression`, and friends — not the pre-rename
`applicant_profile`. Leaving `borrower_mobile` behind on a reset causes `CUSTOMER_ID_COLLISION` on
the next seed run, so don't skip it.

Re-seeding after a reset is safe and expected — do it once end-to-end before you record, specifically
to prove the demo is reproducible if a take goes bad.

---

## Money used (so the numbers on screen are recognisable)

Salary **₹50,000** → eligible limit **₹12,500** (25% of salary, floored to the nearest ₹100) → loan
amount **₹10,000** → processing fee **₹1,000** (10%) → GST **₹180** (18% of the fee) → **net
disbursed ₹8,820**. Interest accrues at **1%/day** on principal over the actual (salary-linked)
tenure; late penalty is **2%/day**, capped at 30 days, on the backdated overdue personas. All
amounts are integer paise end-to-end — see `CLAUDE.md` §9 for the full `LoanMath` reference and two
worked disbursal-date examples.
