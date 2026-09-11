# DhanBoost — current state & go-live backlog

> Extracted from `CLAUDE.md` (2026-08-24) to keep the onboarding doc small. Reference material:
> read it when you need it, not on every session. Rules and invariants stay in `CLAUDE.md`.

## 2. Current state (verified 2026-08-24)

NAVIX runs the **full loan lifecycle end-to-end** — a single `loan_application` aggregate (§5) wired to
a polished frontend through a BFF (§8), on **real JWT + Spring Security** (§7), with real
**Signzy (identity primary) + Digitap (fallback, and bureau primary) + Fintrix (bureau fallback)** verification clients (§14), **S3-backed**
documents, and a two-phase verified borrower journey (intake → credit sanction → offer journey). It is
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
- **Two-phase borrower journey** (V44/V46) — a **Phase-1 intake** (`JourneyService.Step`: otp ·
  set-password · employment · employer · email · bank · payslips · consent · submitted), then, after a
  Credit Executive sanctions, a **Phase-3 offer journey** (`OfferStep`: amount · repayment-date ·
  DigiLocker · references · summary · selfie · address · sanction-letter · eSign · 🎉 · disbursal
  account). Resume is answered **server-side** (`GET …/journey`), not from localStorage. Each
  verification is real (§11, §14); documents are S3-backed (presigned).
- **KYC verification dashboard** — staff progress tracker + manual PASS/FAIL override + a per-check
  **retry** + a cross-app overview + borrower reminders, at `/staff/verifications`.
- **Bureau credit brief** — the bureau pull (**Digitap Experian primary**, Fintrix CRIF Highmark
  fallback, walking past a no-hit — §14) yields a **1–5★ "recommend" rating** + a DhanBoost-branded PDF (OpenPDF, stored to
  S3), shown on every staff detail surface and **never to the borrower**; the brief's identity comes
  from the KYC profile, not the bureau copy, and the score is always labelled with the bureau it came
  from. The sub-floor **auto-reject is suspended** (V64) — every bureau result goes to a human.
- **Disbursement** — the Disbursement Head releases the money themselves and records the transaction
  id; **there is no accountant hop** (retired in V47/V48). An accept without a `txnRef` is
  `TXN_REF_REQUIRED`, not a hand-off (§5).

**Collections, repay & reborrow**
- **Collections** — `collection_case` / `settlement` on the real bigint loan id; DPD buckets, officer
  assignment, and settlements with a **propose → approve / reject** maker-checker (proposer ≠ approver).
- **Repay** — the borrower records a payment (→ PENDING_VERIFICATION); the Accountant **verifies or
  rejects** it; at zero the loan + application close. An approved settlement caps the payable.
- **Reborrow** — returning borrowers reuse their saved KYC (salary day carried over, never re-asked);
  routed on **repayment history only** — clean → `PRE_APPROVED` → straight to `SANCTIONED` on the
  prior sanction, disqualified → an outright auto-reject (the manual `REVIEW_PENDING` queue went with
  V45). One live loan at a time.

**Back-office & platform**
- **Staff console** — role-aware queues (`components/staff/live-pipeline.tsx`) across
  credit / disbursement / accounting, a live dashboard, a **Customers** roll-up (with stage dates and
  ADMIN corrections), a **Loans register** (ADMIN + Collection Head), the company-wide **transactions
  ledger**, a **staff-performance dashboard** + **decision history** (`/api/staff/decisions`), the
  **telecalling** lead queue, the ADMIN **Provider API dashboard** (every real Signzy/Digitap/Fintrix
  call, V49/V57), the ADMIN **bureau backfill/rescore** tool (V62), plus the ADMIN-only
  **company-expense ledger**, **DSA administration** and the full **all-applications register**;
  branded CSV / PDF export throughout.
- **Bot challenge** — Cloudflare **Turnstile** in front of both password logins and both
  forgot-password forms; unset keys = the check is skipped (dev/CI/E2E/demo) — see §12.
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
  borrower picks their repayment date on the offer journey's `/loan/repayment-date` step (the
  `<SalaryCalendar>` month grid; also on marketing `/calculator`).

**Verification:** Postgres 16 (Docker) for local; Flyway applies all migrations on boot (§10). The
backend unit suite + a Testcontainers integration test are green; frontend `tsc` + ESLint clean. Demo
logins and seed data are in §4. Remaining go-live work is in §13 / `PRODUCTION_READINESS.md`.

---

## 13. Deferred (go-live backlog)

> **The full roadmap is in [`FUTURE.md`](FUTURE.md); the go/no-go production checklist is
> [`PRODUCTION_READINESS.md`](PRODUCTION_READINESS.md).** Most of the original deferred set **shipped**
> (real auth, S3, verification clients, notifications, reborrow, referral, expenses);
> the bullets below are what genuinely **remains**.

- ✅ **Done in the migration:** real auth (JWT + Spring Security, `JwtAuthFilter` replaced
  `DemoActorFilter`, staff BCrypt login); real verification clients (**Signzy primary + Digitap fallback** —
  §14; superseded the earlier Fintrix/DigiLocker layer, now removed); **S3** documents
  (presign + `s3_object_key`); bank **penny-drop**; SSM secrets; the verified borrower journey; the
  admin payment block; mock-layer removal; and a **test suite** (`QA_CHECKLIST.md`, ~136 backend tests,
  Playwright `frontend/e2e/*`, `.github/workflows/ci.yml`).
- 🟡 **SMS/DLT — the whole batch was re-filed under the DhanBoost brand on 2026-07-31.** The rebrand
  changed the brand string, the sender (`NAVIXF` → **`DHANBT`**, Active) and the URL in every body,
  which invalidated all 15 previously-registered `NAVIX_*_V2` ids (now **blacklisted** on the portal —
  do not send against them). All 15 `DHANBOOST_*_V1` templates are **submitted and awaiting operator
  approval** (1 Active, 14 Work In Progress); **no new DLT Template IDs have been issued yet**, so the
  `NAVIX_SMS_DLT_*` env vars stay unset and the notification engine keeps no-op'ing the SMS channel.
  > **The full state + the next-steps runbook is `docs/sms-dlt/DLT_SUBMISSION_TRACKER.md` → "▶ NEXT
  > SESSION"** — how to collect the ids, wire them in, and handle rejections. Do **not** re-run
  > `docs/sms-dlt/CHROME_AGENT_PROMPT.md`; it would duplicate the registrations.
- 🔴 **Borrower OTP is currently NOT sending.** The only DLT-approved template is the old
  NAVIX-worded `NAVIX_OTP_LOGIN_V2` (`1707178366195230667`), so ECS task-def **revision 4 pins
  `NAVIX_SMS_OTP_TEMPLATE` to the NAVIX Finance wording** while `application.yml`'s default is the
  DhanBoost wording. **Any backend redeploy must be built from rev 4** — building from defaults swaps
  the live OTP text to unapproved wording and every send fails `006 Invalid template text`. The
  gateway also still rejects the SSM demo credentials for sender `NAVIXF`. `NAVIX_SMS_MOCK=true` →
  `123456` remains the local/demo path (it is **off** in prod as of 2026-07-30).
- 🟡 Staff **emailed invites** + ADMIN-gated invite create; middleware **JWT-signature verify** (still a
  presence check). Rotate the seeded `Admin@12345` + set a strong `AUTH_SECRET` for prod.
- 🔴 **Recalibrate the bureau score floor against CRIF's own distribution** and re-enable the
  `bureau-auto-reject` flag (V64 suspended it; `MIN_BUREAU_SCORE = 550` is an Experian number, and on
  the live CRIF distribution the median sits at 510). Until then every bureau result goes to a human.
- 🔴 Real bank **payout** (NEFT/IMPS) at the disbursement step. (Sanction-letter/agreement generation → S3
  **shipped** — `SanctionLetterPdfRenderer` renders the Key Fact Statement via OpenPDF and `OfferService`
  stores it as the `SANCTION_LETTER` document; **Aadhaar eSign of it shipped 2026-08-11**, see §14.)
- 🔴 DB cleanup: **FK constraints**; drop the legacy `bytea` doc column (still on the live borrower
  document upload/read path — migrate that write path to S3 first); unify applicant identity
  (`applicant_profile` ↔ onboarding `Borrower`); PII-at-rest encryption.
- 🔴 Persisted `borrower_standing` table (standing is recomputed from loan history today); design-system
  polish; full-Aadhaar masking; compliance/regulatory alignment (NBFC/DLG, reporting, product copy).
- ⚠️ **Known exception to "never store the raw Aadhaar number".** The manual-proof fallbacks store
  borrower-uploaded images of identity and bank instruments: `AADHAAR_FRONT` / `AADHAAR_BACK` (the
  DigiLocker alternative) and `BANK_PROOF` (a cancelled cheque or passbook, the penny-drop
  alternative). An Aadhaar card image necessarily carries the **full** Aadhaar number, so these
  documents are a deliberate, product-approved exception to the masking rule stated in the security
  guidance — not an oversight. They are handled exactly like every other KYC document (S3, SSE-KMS,
  short-lived presigned GETs, never logged, never exported), and are readable by any staff role that
  can already open a document. Two consequences to weigh before an audit or a masking pass: the
  images are **not** redacted at rest, and the presigned-URL route is **not** narrowed to the roles
  that actually review them. Revisit both alongside full-Aadhaar masking above.
