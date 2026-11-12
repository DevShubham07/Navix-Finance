# DhanBoost — Rebuild PRD & master build prompt

> **What this is.** A complete product + engineering spec of the DhanBoost lending platform **as it
> stands on 2026-11-13** (backend V1..V102, frontend Next 15.1.12), written so an autonomous coding
> model (Claude Fable) can recreate the same platform from an empty repository, phase by phase, with
> multi-agent workflows. It describes the **current behaviour**, not the history: retired stages,
> roles and modules are listed only where an enum must keep them.
>
> **What this is not.** It holds no secrets, no account ids, no customer data and no seeded
> passwords. Every credential is named, never valued. Every external provider sits behind a port
> with a **mock adapter as the default**, so the rebuild runs fully offline.
>
> Sections: **Part 0** is the prompt to paste. **Part A** (§1–§16) is the product spec. **Part B**
> (§17–§19) is the phased workflow build plan with acceptance gates. **Appendices** list enums, error
> codes, reports, notifications and env vars.
>
> **The detail lives in the use-case catalog under [`docs/prd/`](prd/)** — screen by screen, every
> field, validation message, button, permission and rule, extracted from the code:
>
> | File | Covers | Build phases |
> |---|---|---|
> | [`prd/01-borrower-onboarding.md`](prd/01-borrower-onboarding.md) | login, gates, waitlist, the 12-step `/signup/*` intake, consent-time checks, KBA question, fraud rules | W1, W3 |
> | [`prd/02-borrower-offer-and-loan.md`](prd/02-borrower-offer-and-loan.md) | `/loan/*` offer journey, eSign, disbursal account, mandate, dashboard, repay (manual + PayU), reborrow, profile, settings | W4, W5, W6, W7 |
> | [`prd/03-staff-credit-disbursement.md`](prd/03-staff-credit-disbursement.md) | the applications console per working role, credit decisions, disbursement, repayment verification, customer 360 (all 21 tabs), customers register, ADMIN corrections | W4, W5, W8 |
> | [`prd/04-staff-collections-and-money.md`](prd/04-staff-collections-and-money.md) | loans register, DPD worklist, case work, settlements, mandates & NACH debits, refunds, ledger, reminders | W5, W6, W7 |
> | [`prd/05-staff-growth-and-messaging.md`](prd/05-staff-growth-and-messaging.md) | leads, telecalling, import, DSA portal + admin + commission, referral, customer messaging, templates, discounts, reapply allowance | W7, W8 |
> | [`prd/06-staff-admin-analytics.md`](prd/06-staff-admin-analytics.md) | dashboard metrics, performance, reports (per-report columns), customer export, global search, admin pages, Provider API dashboard, eSign documents | W9, W10 |
> | [`prd/07-business-rules.md`](prd/07-business-rules.md) | every business rule with its exact condition, where it is enforced, the error code, and worked examples | all phases |
>
> Precedence when they differ: `07-business-rules.md` > the use-case files > this document.

---

# Part 0 — The master prompt (paste this to Claude Fable)

```text
You are building "DhanBoost", a salary-linked, single-repayment consumer-lending platform for India,
from an empty repository. The specification is docs/PRD_REBUILD.md (overview, architecture, build
plan) plus the use-case catalog in docs/prd/01..07 (every screen, field, validation message, button,
permission and business rule). Together they are the contract: when they state a rule, a number, an
enum value, a route, a piece of user-facing copy or an error code, reproduce it exactly. Precedence:
docs/prd/07-business-rules.md > the use-case files > PRD_REBUILD.md. When they are silent, choose
the simplest behaviour consistent with the rules. Before starting a phase, every agent in it reads
the use-case files listed for that phase in the PRD_REBUILD.md index table, and each use case (UC-…)
it implements gets at least one test named after its id.

Use workflows (multi-agent orchestration) to build it. Run EACH phase W0..W12 as its own workflow
of about 2-8 agents (never the whole build as one workflow), in dependency order; inside a phase fan out the independent work items listed under "Parallel lanes" to
separate agents, then run that phase's acceptance gate before starting a phase that depends on it.
Each phase ends with: code + migrations + tests committed, `./mvnw test` green, the Testcontainers
integration test green, `npx tsc --noEmit` and `npx eslint .` clean, and the phase's golden tests
passing. Never mark a phase done on grepped output — check exit codes.

Hard rules (never violate):
1. Money is integer paise (Java long / BIGINT), HALF_UP rounding. Never floats for money.
2. One aggregate: a single loan_application row whose status walks the transition table in §5.
   Every transition is server-validated and appended to application_event. No stage skipping.
3. Separation of duties is enforced server-side: different roles own credit, disbursement and
   repayment verification; settlements and collection payments also check the actor (proposer ≠
   approver). Every approve has a reject.
4. Staff and borrower auth are separate namespaces: different endpoints, cookies, JWT audiences.
5. All browser→backend traffic goes through the Next.js BFF; the browser never calls Spring.
6. Every external provider is behind a port with a mock adapter as the default. Do not call any
   real provider. eSign, eMandate and NACH debit are legally binding and billable in real life.
7. Feature flags live in a DB table, are read uncached, and default as specified in §14.
8. Notifications are event-driven, async, after-commit; business logic never depends on delivery.
9. No secrets in code; config through env vars with safe local defaults; the app refuses to boot in
   production with demo settings (ProductionConfigGuard, §12).
10. Keep the internal namespace "navix" (packages com.navix.*, modules navix-*, env NAVIX_*,
    cookies navix_borrower / navix_staff); the user-visible brand is "DhanBoost".

Start with W0. After each phase, write a short PHASE_LOG.md entry: what shipped, what was cut, and
any spec ambiguity you resolved and how.
```

---

# Part A — Product specification

## 1. Product summary

**DhanBoost** gives salaried Indians a short advance against their next salary. The borrower borrows
up to 25% of monthly salary, pays a processing fee + GST upfront (deducted from the payout), and
repays principal + daily interest **once**, on their salary day. Prepaying early reduces interest
to the days actually held.

- **Brand / legal.** User-visible brand DhanBoost (`dhanboost.com`, canonical `www.`). Technology
  platform: NAVIX Finance Private Limited (CIN `U64990HR2026PTC144926`). **Lender of record: the NBFC
  partner MLB Securities**, named on the Key Fact Statement, the sanction letter and the borrower
  footer. Support `support@dhanboost.com`, grievance `grievance@dhanboost.com`. Brand constants live
  in one file (`frontend/src/lib/brand.ts`).
- **Surfaces.** (1) A public marketing site with a loan calculator, blog and legal pages. (2) A
  borrower web app (mobile-first). (3) A staff back-office console with nine roles, a working-role
  switcher, dashboards, registers, reports and admin tools. (4) A DSA (external agent) portal inside
  the staff console.
- **Market constraints.** India only (network guard blocks non-India + VPN/proxy/Tor), `en-IN`
  locale, ₹ formatting with lakh/crore grouping, IST (`Asia/Kolkata`) for every business date.

## 2. Personas & roles

| Persona | Goal |
|---|---|
| New borrower | apply on a phone in one sitting: intake, documents, consent, then wait for a sanction |
| Returning borrower | borrow again without re-doing KYC (a shortened offer journey) |
| Credit Head / Executive | assign and decide credit files from verified evidence and a bureau brief |
| Disbursement Head | pay out sanctioned loans and record the transfer reference |
| Accountant | verify repayment proofs, review gateway anomalies, read the ledger |
| Collection Head / Executive | work overdue loans, log interactions, take payments, settle |
| Telecaller | call leads and log dispositions |
| DSA (external agent) | add leads, follow them, earn commission — never see customer data |
| Admin | oversight, corrections, settings, reports, messaging, staff management |

`StaffRole` = `CREDIT_EXECUTIVE, CREDIT_HEAD, DISBURSEMENT_HEAD, ACCOUNTANT, COLLECTION_HEAD,
COLLECTION_EXECUTIVE, TELECALLER, DSA, ADMIN`. Staff status = `ACTIVE, INVITED, DISABLED`
(ACTIVE↔DISABLED; INVITED→ACTIVE/DISABLED). The last ADMIN cannot be disabled (`LAST_ADMIN`); a
staffer cannot change their own role/status (`SELF_MODIFY`).

## 3. Economics (canonical — implement exactly)

`LoanMath` (backend) and `lib/calc/loan-math.ts` (frontend) implement the same rules.

| Constant | Value |
|---|---|
| `PROCESSING_FEE_RATE` | 0.10 of principal |
| `GST_RATE` | 0.18 of the fee |
| `DAILY_INTEREST_RATE` | 0.01 of principal per day |
| `LATE_PENALTY_RATE` | 0.02 of principal per day |
| `LATE_PENALTY_CAP_DAYS` | 30 |
| `LIMIT_PCT_OF_SALARY` | 0.25 |
| `LIMIT_ROUNDING_PAISE` | 10_000 (floor to ₹100) |
| `MIN_LOAN_PAISE` | 100_000 (₹1,000) |
| `MAX_INSTANT_LOAN_PAISE` | 100_000_000 (₹10,00,000) |
| `MAX_TERM_DAYS` | 40 |
| `SALARY_GRACE_DAYS` | 0 (stamped onto each loan as `loan.grace_days`) |

Formulas (all `round` = HALF_UP to whole paise):
- `fee = max(0, round(P × 0.10) − feeDiscount)`, where `feeDiscount = round(P × discountPercent / 100)` from a
  pinned repay-now discount offer (§10.6), else 0.
- `gst = round(fee × 0.18)`; `netDisbursed = P − fee − gst` (what the borrower receives).
- `eligibleLimit(salary) = floor(salary × 0.25 / 10_000) × 10_000`, capped at the max loan. An ADMIN
  per-customer limit override (`customer_limit_override`) wins over the formula.
- `dueDateFromSalary(disbursedOn, salaryDay)`: the **latest** salary-credit date strictly after
  `disbursedOn` and ≤ `disbursedOn + 40 days`; the salary day is clamped to the month's length.
- **Outstanding is computed on read** for any `asOf` (IST date):
  - `tenure = days(disbursedOn → dueDate)`; `days = max(0, days(disbursedOn → asOf))`
  - `interestDays = min(days, tenure + graceDays)`; `interest = round(P × 0.01 × interestDays)`
  - `dpd = max(0, days(dueDate → asOf))`; `penaltyDays = min(max(0, dpd − graceDays), 30)`;
    `penalty = round(P × 0.02 × penaltyDays)`
  - `owed = P + interest + penalty − Σ(VERIFIED payments)`; with an APPROVED settlement,
    `owed = min(owed, max(0, settlementAmount − Σ VERIFIED))`.
  - The loan closes when `owed ≤ 0`. Fee and GST are never re-added (they were taken upfront).
- DPD bucket (computed, never stored): `≤0 UPCOMING`, `1–7 T0_T7`, `8–30 T8_T30`, `31–60 T30_T60`,
  `61–90 T60_T90`, `>90 T90_PLUS`.

**Golden test vectors (must pass in both Java and TypeScript):**

| # | Input | Expected |
|---|---|---|
| G1 | P = ₹10,000 | fee 100,000 p · GST 18,000 p · net 882,000 p |
| G2 | disbursed 2026-06-03, salary day 30 | due 2026-06-30 (27 days), total repayable 1,270,000 p |
| G3 | disbursed 2026-06-24, salary day 30 | due 2026-07-30 (36 days), total 1,360,000 p |
| G4 | G2 loan, grace 0, asOf 2026-07-05, nothing paid | interest 27 d = 270,000 · penalty 5 d = 100,000 · owed 1,370,000 p |
| G5 | G2 loan, grace 1, asOf 2026-07-05 | interest 28 d · penalty 4 d · owed 1,360,000 p |
| G6 | G2 loan, asOf 2026-06-10 (prepay) | owed 1,070,000 p |
| G7 | G2 loan, asOf 2026-09-30 | penalty capped at 30 d = 600,000 p |
| G8 | salary ₹45,678 | eligible limit ₹11,400 (1,140,000 p) |
| G9 | disbursed 2026-01-31, salary day 31 | due 2026-02-28 (salary day clamped; 03-31 is beyond +40 days) |
| G10 | P ₹10,000 with a 5% discount offer | fee = max(0, 100,000 − 50,000) = 50,000 · GST 9,000 · net 941,000 p |

## 4. Rejections, blocks and gates

`application_rejection(reason_code, blocked_until, mobile)` is checked on every new application and
reborrow, keyed on mobile (`assertNotBlocked` → `NOT_ELIGIBLE`).

| Reason code | Block | Trigger |
|---|---|---|
| `SELF_EMPLOYED` | 90 days | intake employment = self-employed |
| `LOW_BUREAU_SCORE` | 90 days | score < 550, **only** when flag `bureau-auto-reject` is on (ships off) |
| `FRAUD_REJECTED` | 365 days | typed Aadhaar ≠ provider-masked Aadhaar, or entered DOB ≠ DigiLocker DOB |
| `MANUAL` | 30 days | a credit `reject-lead` |
| `PAST_DELINQUENCY` | none (no `blocked_until`) | reborrow with a history repaid > 5 days late or never fully repaid; bypassed only by an ADMIN one-use reapply allowance |
| `AADHAAR_DUPLICATE` | permanent (9999-12-31) | at submit-kyc, the number is on another customer's submitted file |
| `BLOCKED_BY_STAFF` | until ADMIN unblocks | ADMIN "block customer" (also offered at repayment verify) |

The borrower never sees the reason, only "Your application has been rejected"
(`/application-rejected`). Staff see the full detail in the rejection register.

Global intake switches (ADMIN, in Settings):
- `onboarding-paused` → **waitlist mode**. A borrower with no loan who logs in fills one plain form
  (`onboarding_waitlist`) and sees "under review". No vendor spend. `POST /api/applications` →
  `ONBOARDING_PAUSED`. People with a loan keep the normal app.
- `applications-closed` → every new application and reborrow is refused (`APPLICATIONS_CLOSED`) →
  `/applications-closed`.

## 5. The lifecycle state machine (the spine)

`ApplicationStatus` (full enum, including retired values that must stay for history):
`DRAFT, KYC_PENDING, KYC_APPROVED, KYC_REJECTED, PRE_APPROVED, REVIEW_PENDING†, CREDIT_EXEC_PENDING,
CREDIT_EXEC_APPROVED†, CREDIT_HEAD_PENDING†, CREDIT_HEAD_APPROVED†, SANCTIONED, DISBURSEMENT_PENDING,
ACCOUNTANT_PENDING†, DISBURSEMENT_FAILED, DISBURSED, ACTIVE, OVERDUE, DEFAULTED, CLOSED, WRITTEN_OFF,
REJECTED, CANCELLED` († = deprecated, never entered by new code).

**Live transitions** (implement as an `EnumMap`; a state with no targets is terminal):

| From | Allowed targets | Who / how |
|---|---|---|
| DRAFT | KYC_PENDING · PRE_APPROVED · REJECTED · CANCELLED | borrower `submit-kyc` · reborrow · auto-reject · cancel |
| KYC_PENDING | CREDIT_EXEC_PENDING · KYC_APPROVED · KYC_REJECTED · REJECTED · CANCELLED | Credit Head assign · KYC decision · reject |
| KYC_APPROVED | CREDIT_EXEC_PENDING · SANCTIONED · REJECTED · CANCELLED | |
| PRE_APPROVED | SANCTIONED · DISBURSEMENT_PENDING · REJECTED · CANCELLED | reborrow carry-over |
| CREDIT_EXEC_PENDING | SANCTIONED · REJECTED · CANCELLED | assigned Executive (or Head / ADMIN) |
| SANCTIONED | DISBURSEMENT_PENDING · REJECTED · CANCELLED | borrower confirms disbursal account (or mandate registers); ADMIN force |
| DISBURSEMENT_PENDING | DISBURSED · REJECTED | Disbursement Head release with `txnRef` / reject |
| DISBURSED | ACTIVE | same release, immediately; mints the `loan` |
| ACTIVE | CLOSED · OVERDUE | repayment to zero · past due |
| OVERDUE | CLOSED · DEFAULTED | |
| DEFAULTED | OVERDUE · WRITTEN_OFF | |
| DISBURSEMENT_FAILED | DISBURSEMENT_PENDING · CANCELLED | (kept for parity; nothing enters it — do not add an inbound path) |

**Rules**
- Every transition writes `application_event(application_id, from_status, to_status, actor_id,
  actor_role, acting_role, action, notes, at)`. This table is append-only and is the SoD source of
  truth.
- **Credit.** One stage, `CREDIT_EXEC_PENDING`. The Credit Head assigns to self or an **ACTIVE**
  Credit Executive (`GET /credit-executives` returns only ACTIVE). Reassignment keeps the stage and
  review data. Executives see and decide only files assigned to them (`CREDIT_FILE_NOT_ASSIGNED`).
  Decisions: **sanction** (`sanctionedAmountPaise` ≤ eligible limit, `approvedRepaymentDate`) →
  `SANCTIONED`; **reject-lead** (30-day block); **mark-pending** (stays in stage, `pending_reason`,
  borrower notified to upload a document). `sanction` refuses `AADHAAR_DUPLICATE_UNRESOLVED` while an
  `AADHAAR_DUPLICATE` check is not PASS.
- **Disbursement.** The Disbursement Head transfers by hand and accepts with a `txnRef` (required —
  `TXN_REF_REQUIRED`). Acceptance mints the `loan` (due date from §3, `grace_days` stamped), moves
  DISBURSED → ACTIVE, accrues the DSA commission and referral, consumes any discount offer
  (compare-and-set, `DISCOUNT_OFFER_USED`), and auto-assigns a collections officer.
- **Close.** `closeForLoan` moves ACTIVE/OVERDUE → CLOSED when the outstanding hits zero.
- **Reborrow.** §7.4.

## 6. Data model (consolidated — create as fresh migrations)

Conventions: PostgreSQL 16; `BIGSERIAL` ids (except `collection_case` uuid); money `BIGINT` paise;
`created_at/updated_at` timestamptz on every table; enum-like columns are `VARCHAR` + `CHECK`;
**no foreign keys** (indexes only — a deliberate convention); JSON payloads are `jsonb`. Recreate the
schema as a small number of clean migrations (e.g. V1 core, V2 verification, V3 collections, …).
**Do not** replay the 102 historical migrations.

**Identity & staff**
- `staff_user(id, name, email unique, mobile, password_hash, role, status, department, designation,
  email_opt_in default true, active_session_id, active_session_at)`
- `staff_invite(token_hash, email, role, invited_by, expires_at (7 days), accepted_at)`
- `borrower_credential(customer_id, password_hash)` · `borrower_mobile(mobile → customer_id)`
- `password_reset_token(token_hash, subject_type STAFF|BORROWER, subject_id, expires_at 30 min, used_at)`

**Lifecycle**
- `loan_application(id, customer_id, status, amount_requested, eligible_limit, purpose,
  sanctioned_amount_paise, approved_repayment_date, sanction_tenure_days, assigned_executive_id,
  journey_step, reapplied_from, salary_credit_day, pending_reason, loan_id, disbursal_account_*
  (masked number, IFSC, holder, bank), fee_discount_percent, fee_discount_offer_id, created_at)`
- `application_event` (above) · `application_rejection(application_id, customer_id, mobile,
  reason_code, detail, blocked_until)`
- `customer_profile(customer_id, name, dob, gender, mobile, email, personal_email_verified,
  official_email, official_email_verified, official_email_otp_verified, pan, pan_verified, aadhaar
  varchar(12), aadhaar_verified, uan, employer, employment_type, monthly_salary_paise,
  annual_salary_paise, salary_bank_* , address fields, address_verified, emergency_contact_*,
  bureau_score, bureau_name, credit_star_rating, credit_recommendation, credit_brief_summary,
  credit_brief_facts jsonb, credit_brief_generated_at, risk_category A–D)`
- `application_verification(application_id, check_type, status PASS|FAIL|REVIEW|PENDING, provider,
  raw_response jsonb, derived jsonb, score, name_match, s3_object_key, reopened_at, reopened_by)`
- `application_document(application_id, doc_type, file_name, content_type, s3_object_key,
  file_password, uploaded_by)` · `loan_document(loan_id, type AGREEMENT|SANCTION|KFS, s3_object_key)`
- `application_reference(application_id, slot 1|2, name, mobile, relation, otp_required, otp_hash,
  otp_expires_at, otp_attempts, otp_send_count, otp_last_sent_at, verified_at)`
- `profile_change_log(customer_id, field, previous, next, changed_by, reason)` (Aadhaar masked)
- `customer_limit_override(customer_id, limit_paise, reason, set_by)` ·
  `customer_mandate_ceiling_override(customer_id, ceiling_paise, set_by)`
- `penny_drop_attempt`, `customer_penny_drop_lock(customer_id, locked_until)` (3 fails → 12 h)

**Money**
- `loan(id, customer_id, application_id, principal, processing_fee, gst, fee_discount_paise,
  net_disbursed, total_repayable, outstanding (cache), daily_interest_rate, disbursed_on, due_date,
  grace_days, closed_on, status, disbursal_txn_ref)` — `LoanStatus` values in Appendix A.
- `payment(loan_id, amount, method UPI|BANK_TRANSFER|NACH|PAYU, status
  PENDING_VERIFICATION|VERIFIED|REJECTED, txn_ref (dedupe), paid_on, proof_url, partial, decided_by,
  decided_at, rejection_reason, rejection_note)`
- `payu_order(txnid unique, loan_id, amount_paise, card_fee_paise, status
  INITIATED|PENDING|SUCCESS|FAILED|EXPIRED, mihpayid, mode, payment_id, review_reason,
  review_resolved_at/by, review_note, next_check_at)`
- `mandate(application_id, customer_id, customer_ref_number unique, umrn, status
  PENDING_AUTHORIZATION|ACTIVE|AUTHORIZATION_FAILED|EXPIRED|BANK_UNSUPPORTED, max_amount_paise,
  bank, masked_account, authentication_url, cancelled_at/by, cancel_reason)` — one row per attempt
- `mandate_debit(loan_id, mandate_id, amount_paise, status
  SCHEDULED|IN_PROGRESS|SUCCEEDED|FAILED|FAILED_TECHNICAL|CANCELLED|SKIPPED, trigger
  SCHEDULED|MANUAL, attempt_no, present_count, unique_key, utr, failure_code, failure_reason)` +
  partial unique index **one SCHEDULED/IN_PROGRESS row per loan**
- `repayment_discount_offer(customer_id, loan_id, percent 1..7, repay_by (≤ 30 days out), status,
  used_by_application_id)` · `reapply_allowance(customer_id, reason, document_key, granted_by,
  used_at)`

**Collections**
- `collection_case(id uuid, loan_id bigint, current_bucket, assigned_officer_id)` (no status column)
- `settlement(collection_case_id, settlement_amount, status PROPOSED|APPROVED|REJECTED, proposed_by,
  approved_by/at, rejected_by/at, note)`
- `collection_payment(case_id, kind PART_PAYMENT|FULL_PAYMENT|SETTLEMENT, status
  PENDING_HEAD|PENDING_ACCOUNTANT|VALIDATED|REJECTED, amount_paise, ledger_payment_id, settlement_id,
  raised_by, validated_by)` · `interaction_log(case_id, staff_id, channel, outcome, note, proof,
  promise_date)` · `repayment_plan`

**CRM, leads, partners**
- `customer_owner(customer_id, staff_id)` · `customer_remark` · `customer_call_log(customer_id,
  loan_id?, staff_id, outcome, note)`
- `lead(name, mobile, pan, pincode, source, call_status
  NOT_CALLED|CALLED|CALLBACK|NO_ANSWER|NOT_INTERESTED|WRONG_NUMBER|CONNECTED, lead_outcome
  NEW|OUTREACHED|REJECTED, owner_dsa_id, dsa_note, remarks, quality_rating, assigned_to)` + partial
  unique index on `pan` where `owner_dsa_id is not null`
- `lead_import_job(status, counts, issues_json first 50)` · `lead_outreach(lead_id, channel, status)`
- `dsa_commission(lead_id unique, loan_id, rate_bps snapshot, amount_paise, status
  ACCRUED|PAYABLE|PAID|VOID, txn_ref)` · `dsa_commission_event` · `dsa_lead_rejection(pan, reason
  OTHER_DSA|EXISTING_CUSTOMER)`
- `referral_code(customer_id, code 8 chars)` · `referral(referrer, referred, status
  PENDING|QUALIFIED|REJECTED)` · `referral_payout(beneficiary, role REFERRER|REFERRED, amount_paise,
  status PENDING|PAID, txn_ref)`

**Platform**
- `feature_flag(flag_key pk, enabled, description)` · `notification(recipient_type, recipient_id,
  type, category, title, body, link, in_app, read_at)` + partial index on unread ·
  `notification_delivery(notification_id, channel, status PENDING|SENT|FAILED|SKIPPED|BOUNCED,
  provider_ref, error)` · `email_suppression(lower(email) unique, reason)` ·
  `borrower_preferences(customer_id, sms_opt_in, email_opt_in, …)`
- `customer_message(customer_id, channel, template_key, body, status SENT|FAILED|SKIPPED, sent_by,
  email)` · `message_template(channel SMS|WHATSAPP|EMAIL, name, label, purpose, category
  SERVICE|MARKETING, body, variables jsonb, status DRAFT|SUBMITTED|ACTIVE|REJECTED, provider_id,
  rule ANY|LIVE|OVERDUE, confirmation_text, system_managed)`
- `esign_document(title, s3_object_key, contract_id, status SENT|COMPLETED|FAILED|EXPIRED,
  created_by)` · `esign_document_signer(document_id, name, email, mobile, status
  PENDING|SIGNED|FAILED, email_status, signature_boxes jsonb [{page,x,y,width,height}])`
- `provider_api_execution(source MANUAL|LIVE, provider, capability, endpoint, http_status,
  check_type, request_id, status, request jsonb (redacted), response jsonb, expires_at)`
- `bank_statement_analysis(customer_id, status PENDING|ANALYSED|FAILED, report_id, report_status,
  bank_score, request jsonb (keys only), response_json, report urls, last_polled_at)` ·
  `skip_trace(customer_id, application_id?, loan_id?, status SUCCESS|NO_RECORD|FAILED, run_by_*,
  request jsonb, response_json)`
- `blocklist_entry(type PAN|AADHAAR_REF|PHONE|DEVICE|BANK_ACCOUNT, value normalised, reason, active,
  customer_id)` unique (type, value) ·
  `company_expense(date, category, amount_paise, note, receipt_object_key)` ·
  `payment_settings` (singleton: payee name, UPI id, bank details, QR + PDF S3 keys) ·
  `monthly_target(month, disbursal_target_paise, collection_target_pct)` · `bureau_phone(mobile,
  customer_id, source)` · `onboarding_waitlist(mobile, name, pan, aadhaar, employer, salary, …)` ·
  `bureau_backfill_row(run, application_id, cohort, outcome)`

## 7. Borrower experience

### 7.1 Login
- Mobile + OTP (6 digits, TTL configurable, 5 sends / 5 verifies per 15 min) delivered by SMS and in
  parallel by the WhatsApp auth template; or mobile + password (≥ 8 chars) behind Cloudflare
  Turnstile. Forgot password = email + mobile gate → one-time reset link (30 min, single use, hashed,
  generic acknowledgement — no enumeration).
- Mock mode (`NAVIX_SMS_MOCK=true`): the code is always `123456` and is echoed as "Dev code".
- On success the BFF sets `navix_borrower` (httpOnly, sameSite lax, secure in prod, 7 days). The
  `customerId` is **derived server-side from the mobile** and never accepted from the client.

### 7.2 Phase-1 intake — `/signup/*` (application `DRAFT`)
`JourneyService.Step` and route segment, in order (resume pointer = the max of what saved data proves
and `journey_step`; a second device resumes on the right screen):

| # | Segment | Screen & rules |
|---|---|---|
| 1 | `start` | name, DOB (not future, ≤ 100 years — `INVALID_DOB`), mobile, terms consent (`terms-and-conditions@1`) |
| 2 | `otp` | verify the mobile (creates the session early) |
| 3 | `set-password` | optional, skippable |
| 4 | `employment` | salaried / self-employed (self-employed → `/signup/self-employed`, 90-day block) |
| 5 | `employer` | employer, monthly salary, salary credit day, optional 12-digit UAN (`INVALID_UAN`) |
| 6 | `email` | personal + official email, **both OTP-confirmed** (an undeliverable work address is recorded REVIEW and waved through; an unreachable personal address must be replaced) |
| 7 | `bank` | salary account (bank combobox of Indian banks, IFSC) |
| 8 | `payslips` | salary slips + bank statement uploads, optional file password |
| 9 | `aadhaar` | typed 12-digit Aadhaar (Verhoeff, `INVALID_AADHAAR`) + front and back images (`AADHAAR_CARD_FRONT/BACK`) |
| 10 | `pan-card` | PAN number + front/back images (`PAN_CARD_FRONT/BACK`) |
| 11 | `consent` | re-confirm with OTP + bureau consent (the consent text is sent to the bureau verbatim). **Only now** do external checks run: PAN, email, bureau, salary, employment. A CRIF knowledge-based question → `/credit-question`; an auto-reject → `/application-rejected` |
| 12 | `submitted` | "With our credit team"; optional extra documents |

- Uploads go browser → S3 through presigned URLs; images are compressed client-side; PDFs allowed.
- `submit-kyc` (`DRAFT → KYC_PENDING`) refuses `KYC_INCOMPLETE` until PAN, EMAIL, BUREAU, SALARY were
  **attempted** and the Aadhaar number plus all four card images are on file.
- Aadhaar handling: stored in full (product decision), shown unmasked to staff, **locked**
  (`AADHAAR_LOCKED`) once a provider has read it or the file left DRAFT. Duplicate across customers →
  accepted + staff-only `AADHAAR_DUPLICATE` REVIEW row (never a "who holds this number" oracle); at
  submit-kyc a number already on another customer's *submitted* (≥ KYC_PENDING, not itself
  FRAUD_REJECTED) file auto-rejects `AADHAAR_DUPLICATE`. Masked-Aadhaar mismatch from PAN/DigiLocker
  (positional compare on the revealed digits) and DOB mismatch (1 January = year-only) → `FRAUD_REJECTED`.
- `/credit-question` (outside `/signup/*`): answer the CRIF KBA verbatim; a new question replaces it
  (`S11`), attempts exhausted (`S02`); 60 s cooldown, 3 attempts; skip allowed. Staff can email a
  nudge link `/login?next=/credit-question?appId=…`.

### 7.3 Phase-3 offer journey — `/loan/*` (application `SANCTIONED`)
`OfferStep` and segment, in order (the server returns the step list; reborrows get a shorter one):

| # | Segment | Rules |
|---|---|---|
| 1 | `amount` | choose ≥ ₹1,000 and ≤ the sanctioned ceiling (`BELOW_DRAWN_AMOUNT`, `AMOUNT_NOT_CHOSEN`) |
| 2 | `repayment-date` | the sanctioned date, preselected and locked, shown on a month grid with the caption "due on your salary day" — never "in N days" |
| 3 | `digilocker` | Aadhaar via DigiLocker redirect + poll (`/kyc/digilocker/callback`). Max 5 consent sessions per application per 24 h, then the manual Aadhaar-card upload. Errors: `DIGILOCKER_NOT_READY` (retry), `_UPSTREAM_DOWN` (back off), `_CONSENT_DENIED` (terminal for that session), `_PROVIDER_ERROR` |
| 4 | `references` | two references (name, mobile, relation); not self (`REFERENCE_IS_SELF`), not duplicate. With `reference-otp` on, each confirms a 6-digit code (6 sends, 5 attempts) |
| 5 | `summary` | server figures: amount, fee, GST, net disbursed, repayment date, total repayable, all-in APR (GST excluded) |
| 6 | `selfie` | face liveness (primary vendor) with a secondary iframe liveness; never blocks |
| 7 | `address` | browser geolocation → reverse-geocode, typed fallback |
| 8 | `sanction-letter` | render the Key Fact Statement + sanction letter PDF (stored as `SANCTION_LETTER`), then Aadhaar eSign by redirect + poll (`/kyc/esign/callback`); a drawn-signature fallback that never calls the eSign port. Signed terms are locked |
| 9 | `sanctioned` | celebration, no Back |
| 10 | `disbursal-account` | confirm the salary account, or add a new one verified by penny-drop (3 failures → 12 h lock; cancelled-cheque/passbook upload fallback). Confirming routes to `DISBURSEMENT_PENDING` |
| 11 | `mandate` | Digio NACH eMandate — offered only when `digio-mandate` is on and the customer has no usable UMRN; then the registered mandate (not the account) routes to `DISBURSEMENT_PENDING`. Max 10 attempts per application; resuming reuses the in-flight attempt's authorisation URL |

`/loan/status` polls the application and renders the live status + audit trail.

### 7.4 Active loan, repay, reborrow
- `/dashboard`: the active loan — net disbursed, due date (as a date, "due on your salary day"),
  total repayable, today's payoff, a "pay early & save" hint; plus an applications roll-up.
- `/repay`: three quotes (today, due day, day after) from one schedule endpoint. Two ways to pay:
  - **PayU Hosted Checkout** (flag `payu-repayment`): create order → auto-POST form to PayU →
    return/webhook → server re-verifies with PayU → credited once as `VERIFIED` (no Accountant).
    Credit cards pay a 2.36% surcharge passed as `additional_charges`.
  - **Manual**: UPI/bank transfer to the company payment block (QR + account PDF from
    `payment_settings`), then submit amount, transaction id and a screenshot (backdate ≤ 3 days) →
    `PENDING_VERIFICATION`. While PayU is on, manual appears only after a failed PayU attempt or one
    stale for ≥ 30 min.
- `/reloan` → `reborrow`: allowed only with a **CLOSED** loan (`NO_PRIOR_LOAN` otherwise) and no live
  application/loan. Standing from history only (credit score does not gate reborrow): disqualified →
  `PAST_DELINQUENCY` auto-reject; clean → `PRE_APPROVED` → `SANCTIONED` with the prior sanction,
  salary day, KYC, DigiLocker/selfie/address evidence, references and disbursal account carried.
  **Never carried: the eSign** (each advance signs its own KFS). Repayment date recomputed from the
  carried salary day. The best unlocked repay-now discount offer is pinned on the new application.
- Account menu: Past loans `/loans`, Past transactions `/transactions`, Support/FAQ `/support`,
  Settings `/settings` (notification preferences; opt-out suppresses SMS/EMAIL/WhatsApp, never
  in-app), Profile `/profile` (self-edit non-identity fields; editing a verified field invalidates
  that check), Sign out.
- Notification bell for the borrower (in-app inbox, unread badge, mark read / all).
- Referral (flag `referral`): own code, share, earnings (redeeming a code has no screen — §9.6); ₹200 to each side
  once the referred loan qualifies.

## 8. Staff console

### 8.1 Shell & navigation
- Sidebar (navy, resizable, collapsible groups, live counts on DPD buckets) generated from **one**
  nav definition (`staff-nav.ts`) that both the sidebar and global search read. An item is visible
  when the working role is not in `hideFor`, any of its permission tokens pass
  `can(realRole, workingRole, perm)`, and its feature flag is not explicitly `false`.
- Header: global search (Cmd/Ctrl+K), working-role pill, notification bell.
- Groups and items:
  1. **Overview** — Dashboard · Staff performance · My decisions (all hidden for DSA)
  2. **Customers & credit** — Live applications (`loan:pipeline`; label per working role: "Credit
     desk · all files", "My credit files", "Disbursal queue", "Repayments to verify", "Payment
     approvals", "My repayment book") · Customers (`customer:view`, segments incomplete, pending,
     review, approved, disbursementPending, active, overdue, hold, rejected, blocked, closed, reloan,
     unallocated) · Unallocated customers · All applications · Rejections · Onboarding waitlist ·
     Blocklist · Customer messages · Templates
  3. **Leads & sales** — Leads · Telecalling · Leads dashboard · Import leads · Referral payouts
  4. **Collections** — Loans register (active/overdue/closed) · DPD buckets (Upcoming, 1–7, 8–30,
     31–60, 61–90, 90+) · Mandates & debits · Settlements · Refunds owed
  5. **Finance & reports** — Transactions · Company expenses · Reports (10 category sub-items)
  6. **DSA** — My leads · My earnings
  7. **Team & settings** — Staff · DSA · Invites · Provider API dashboard · eSign documents · Settings

### 8.2 Permissions matrix (`lib/auth/rbac.ts`, mirrored by backend guards)

CE = Credit Exec, CH = Credit Head, DH = Disbursement Head, AC = Accountant, COH = Collection Head,
COE = Collection Exec, TC = Telecaller, ADM = Admin (real role), "Adm-WR" = what the **Admin working
role** shows (the narrower `ADMIN_ROLE_PERMISSIONS`).

| Token | CE | CH | DH | AC | COH | COE | TC | DSA | ADM | Adm-WR |
|---|---|---|---|---|---|---|---|---|---|---|
| kyc:approve | ✓ | | | | | | | | ✓ | |
| loan:review | ✓ | | | | | | | | ✓ | |
| loan:approve (assignment) | | ✓ | | | | | | | ✓ | |
| loan:disburse | | | ✓ | | | | | | ✓ | |
| loan:activate | | | | ✓ | | | | | ✓ | |
| loan:pipeline | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | | | ✓ | |
| loan:register | | | | | ✓ | | | | ✓ | ✓ |
| collections:manage | | | | | ✓ | | | | ✓ | |
| collections:interact | | | | | | ✓ | | | ✓ | |
| collections:overview | | | | | ✓ | | | | ✓ | ✓ |
| staff:manage | | | | | | | | | ✓ | ✓ |
| customer:view | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | | ✓ | ✓ |
| customer:view:all | | ✓ | ✓ | | ✓ | | | | ✓ | ✓ |
| customer:assign | | ✓ | | | ✓ | | ✓ | | ✓ | ✓ |
| customer:manage | | | | | | | | | ✓ | ✓ |
| document:upload | ✓ | ✓ | | | | | | | ✓ | ✓ |
| leads:manage | | | | | | | ✓ | | ✓ | |
| leads:import | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| referral:payout | | | ✓ | | | | | | ✓ | |
| mandate:override | | | ✓ | | | | | | ✓ | ✓ |
| mandate:debit | | | | | ✓ | ✓ | | | ✓ | ✓ |
| verification:retry | | | | | | | | | ✓ | ✓ |
| dsa:portal | | | | | | | | ✓ | | |
| dsa:manage | | | | | | | | | ✓ | ✓ |
| waitlist:view | | | | | | | | | ✓ | ✓ |
| message:send | | | | | | | | | ✓ | ✓ |

### 8.3 Working roles
- Hierarchy (first = default): ADMIN → [ADMIN, CREDIT_HEAD, CREDIT_EXECUTIVE, DISBURSEMENT_HEAD,
  ACCOUNTANT, COLLECTION_HEAD, COLLECTION_EXECUTIVE, TELECALLER] (never DSA); CREDIT_HEAD →
  [CREDIT_HEAD, CREDIT_EXECUTIVE]; COLLECTION_HEAD → [COLLECTION_HEAD, COLLECTION_EXECUTIVE]; others
  fixed (static pill).
- Each working role shows only its own tabs and buttons. Admin-only buttons (edit customer, force to
  disbursement, export, log payment, delete document, edit references, retry verification) appear
  only in the Admin working role.
- Transport: localStorage `navix-staff-working-role:<staffId>` (cleared on sign-out) → header
  `X-Acting-Role` (sent only when ≠ real role) → BFF forwards → `ActingRole.normalize` validates
  against the hierarchy (illegal values ignored, never a 400) → `CurrentActor.actingRole`.
- **Authorization stays on the real JWT role.** `effectiveRole()` is used only to scope lists
  (credit queues, customer book, decision history, collections cases/settlements/payments).
- Audit: `application_event.acting_role`; timeline text "by <name> · ADMIN (as Credit Executive)".
- ADMIN is exempt from the credit SoD and the active-executive rule, so one admin can walk a loan
  from KYC to ACTIVE by switching working role at each step.

### 8.4 The live applications console (`/staff/applications`)
One page that renders per working role:
- **Credit Head**: credit workbench — all `KYC_PENDING`/`CREDIT_EXEC_PENDING` files, assign / reassign
  / "Assign to me", reject; bulk assign.
- **Credit Executive**: "My credit files" — sanction (amount + date dialog), reject, mark pending;
  the applicant review panel (profile, documents, verifications, credit brief).
- **Disbursement Head**: disbursal queue — release with txn ref, reject.
- **Accountant**: repayment verification queue (verify / reject with reason; optional "block
  customer for future loans"), PayU review queue (record-only resolve).
- **Collection Head**: collection-payment approval queue. **Collection Executive**: my repayment book.
- Maker-checker queues poll every 8 s.

### 8.5 Customer 360 (pop-up and `/staff/customers/[customerId]`)
21 tabs in this order (same component in both places; actions inside are permission-gated, tabs are
not): **Customer · Loan · Disbursal · Repayment · Collections** | **Third-party logs · Banking ·
Credit report · BRE · AI Analyzer** | **Journey · References · Documents · Addresses · Dedupe ·
Communication · Activity** | **Sanction · Follow-ups · Mandate · Verifications**.

Notable contents:
- *Customer*: full profile (full Aadhaar/PAN to staff), behaviour pills, "earned from customer",
  trust stars, owner, remarks; ADMIN corrections (salary/profile with audit log; a monthly-salary
  change recomputes the limit; mobile change by OTP; sanctioned-amount and salary-day changes; limit
  override; block/unblock; reapply allowance; delete).
- *Credit report*: the bureau brief — score labelled with its bureau, 1–5★ rating, categorized facts,
  tradeline table, the vendor PDF and our branded `CREDIT_BRIEF` PDF. Never shown to the borrower.
- *BRE*: rule-by-rule pass/fail of the business rules engine (§10.1); the ★ rating follows it.
- *Banking*: on-demand bank statement analysis runs + history (§11).
- *Third-party logs*: every provider call for this customer.
- *Verifications*: per-check status, manual PASS/FAIL override (audited, provider MANUAL), retry,
  "send the customer a link" to reopen a failed step (EMAIL or COPY), reminders.
- *Dedupe*: matches on PAN / Aadhaar / mobile / bank account across customers; mobile found in other
  customers' bureau reports or as a reference.
- *Mandate*: attempts, status (bank approval shown separately from NPCI), invite, refresh, override
  `BANK_UNSUPPORTED`, ceiling override, cancel (pending or ACTIVE with reason), change account,
  manual debit (when `digio-debit` is on), debit history.
- *Collections*: case, bucket, officer, interactions, promises-to-pay, payments, settlement.
- *Skip Tracer*: (in Collections context) on-demand lookups + history.
- Full-profile export (CSV/PDF).

### 8.6 Registers (all server-paged)
Customers (segments, IST date window, "mine"), Loans register, All applications (+ facets),
Rejections, Transactions ledger (OUTGOING disbursals + INCOMING repayments, totals for the whole
filter), Leads, Telecalling queue, Settlements, Mandates & debits, Refunds owed, Waitlist, Blocklist,
Expenses, Staff, Invites, DSA admin. Shared register framework: search, Excel-style column filters
(server-side, URL wire format), named-range dropdown filters, sort, page/size ≤ 100, saved views per
staffer, period presets (today … this/last FY, Indian FY April–March, custom), CSV + PDF export.

### 8.7 Dashboard (`/staff/dashboard`)
- Admin tabs: Business snapshot (disbursed, collected, AUM, book quality, target vs achieved,
  deficit collection), Team & performance (leaderboards), Calendar (dues/disbursals by day), India map
  (by state), Company-wise (employer concentration), Credit-executive report, Sales-ops report,
  Collection allocation. Monthly targets editable by ADMIN.
- Per-role views for Credit, Collection, Telecaller, Disbursement, Accountant.
- Style rule: no plain widgets — gradient colour cards, hover shows exact numbers, click opens a
  records drawer listing the underlying rows.

### 8.8 Other staff pages
Staff performance + decision history (per-employee totals; who the caller may inspect), my decisions,
my profile (email opt-in for operational mail; security mail is never suppressible), staff
activation via invite link (`/staff/activate`), forgot/reset password.

## 9. Back-office modules

### 9.1 Collections
- Collectible loans: ACTIVE/OVERDUE with due ≤ today. Opening a case flips the loan to IN_COLLECTIONS.
  New disbursals are auto-assigned to an **ACTIVE** collection executive.
- Collection Head desk: needs-assignment panel, team tracker, bulk assign, agency-handover DPD export.
- Executives see only their own cases (`CASE_NOT_ASSIGNED`). They log interactions (channel,
  outcome, flexible proof: screenshot / text / transaction id), promises-to-pay, raise field payments
  (PART_PAYMENT / FULL_PAYMENT → Accountant validates; SETTLEMENT → Head approves first), propose
  settlements.
- **Settlements**: propose → approve / reject by Collection Head/ADMIN; **proposer ≠ approver**
  (`SOD_VIOLATION`, checked on the actor). An approved settlement caps the payable and closes the loan when met; NACH debits
  are sized to it. (A settlements dashboard page exists only as untracked work with no backend
  endpoint — not part of the platform.)
- Hardship / revised plan: only the `repayment_plan` table and entity exist — no flow is built.
- Refunds owed: NACH overpayments to return by hand.

### 9.2 eMandate & NACH (Digio)
As in §7.3 step 11 and the Mandate tab, plus the console `/staff/collections/mandates` (summary,
mandates, debits, actors). Debit presentation (flag `digio-debit`):
- Scheduler at 10:00 IST presents on overdue loans at **DPD 1, 3, 5, 9**; staff can present manually.
- Guards: DB unique index (one in-flight debit per loan) + advisory lock; max 4 attempts per loan,
  3 presentations per row; amount ≤ mandate ceiling (`AMOUNT_EXCEEDS_MANDATE`); refuses without the
  lender settlement account configured (`DEBIT_NOT_CONFIGURED`).
- Outcomes by a **polling** reconcile pass (50 rows per run; no webhook receiver). A success is
  credited through `LoanDirectory.creditCollectionPayment` as a `VERIFIED` `NACH` payment.
- Re-present unless the failure matches the terminal denylist (account closed/dead, mandate
  cancelled/dead, payer deceased, duplicate reference) — an unknown code **is** retried.
- Registered mandate amount = max(formula, ADMIN ceiling override); coverage of an application is
  judged on the formula.

### 9.3 PayU (repayment gateway)
- `POST /api/loan/{id}/payu/orders {amountPaise}` (≥ 100 p, ≤ outstanding) → server creates
  `payu_order`, computes the SHA-512 request hash with the server-only salt, returns the form fields;
  the browser auto-posts to PayU. Emails containing `+` or over 50 chars are replaced with
  `pay<loanId>@dhanboost.com`.
- PayU's surl/furl POST arrives cross-site (no cookie) at the BFF `/api/payu/return`, forwarded raw
  to `/api/webhooks/payu/return`; the BFF answers 303 to `/repay?order=<txnid>`. The webhook
  `/api/payu/webhook` → `/api/webhooks/payu/payment` always 200.
- **Every signal is re-checked with PayU `verify_payment`**; credit `transaction_amount` (never
  `amt`); verify the reverse hash (prefixed with `additionalCharges` when a fee was charged);
  `pending` stays pending; `addedon` is IST. A reconcile job re-verifies INITIATED/PENDING orders
  every 5 minutes (not flag-gated). Credit exactly once.
- Anomalies (amount mismatch, unexpected fee, loan already settled) set `review_reason` and land in
  the Accountant/ADMIN "PayU payments needing review" queue; resolving records who/when/why only
  (refunds are done in the PayU dashboard).
- ADMIN toggles the gateway in Settings; turning it on without credentials → `PAYU_NOT_CONFIGURED`.

### 9.4 Repayment verification (manual)
Accountant queue (company-wide): verify → `VERIFIED`, recompute outstanding, close at zero; reject
with a reason (cannot reject a VERIFIED payment). Duplicate `txn_ref` is refused. ADMIN can record a
payment on a borrower's behalf (`admin-record`).

### 9.5 Leads, telecalling, DSA
- Leads: create/import (CSV/XLSX → S3 → background job in chunks, status + first-50 issues, PAN
  dedupe), dispositions (`call_status`), outcomes (`lead_outcome`; CONFIRMED is resolved on read from
  a PAN-attributed application), call logs, remarks, quality rating, self-assign chase-ups.
- Telecaller: the telecalling queue with batched verification completeness; no lifecycle authority.
- **DSA portal** (`/api/dsa/**`, DSA only): add a lead (PAN + name + mobile). A PAN already held by
  another DSA **or** by an existing customer → one generic `LEAD_ALREADY_KNOWN` (indistinguishable;
  logged to `dsa_lead_rejection`). Lead view = what the DSA typed + coarse status
  `NOT_APPLIED/APPLIED/IN_PROGRESS/DISBURSED/REPAID/DECLINED` + net disbursed + commission — never KYC
  data, never a second loan. Foreign lead id → `LEAD_NOT_FOUND`. Outreach by SMS (fixed template) or
  email (one of three fixed templates), rate-limited, audited.
- **Commission**: 350 bps of net disbursed on the lead's **first** loan; `ACCRUED` at disbursal →
  `PAYABLE` when fully repaid → `PAID` by ADMIN (txn id); `VOID` on approved settlement, default or
  write-off. Rate snapshotted per row. ADMIN can pay, void, reassign, create manually — every action
  appended to `dsa_commission_event`.
- **DSA is firewalled from all customer data** (§12).

### 9.6 Referral (flag `referral`)
Codes (8 chars), apply/validate endpoints (no self-referral, no double referral) — **no screen calls
  apply or validate today, so a code cannot be redeemed**; payouts queue
for Disbursement Head/ADMIN (settle with txn id), expense totals. Off → `REFERRAL_DISABLED`.

### 9.7 Admin customer messaging & templates (flag `admin-messaging`)
- Audience picker (rules ANY / LIVE / OVERDUE / repayment window), template preview with variables,
  send over SMS / WhatsApp / email, one ledger row per channel (`SENT/FAILED/SKIPPED`), send log.
  Sends only **08:00–19:00 IST** (`OUTSIDE_SEND_HOURS`); a template that carries confirmation
  text needs its tick box ticked (`CONFIRMATION_REQUIRED`).
- Built-in catalogue keys: `reported_soft, reported_strict, cost_clock, eligibility_window,
  salary_day, ptp_confirmation, ptp_missed, part_payment, officer_assigned, no_response,
  settlement_offer, senior_review, notice_issued, visit_notice, repay_discount (MARKETING),
  payment_overdue, payment_due_soon`.
- **Repay-now discount**: sending `repay_discount` with a percent (1–7) and a repay-by date (≤ 30 days)
  creates a `repayment_discount_offer`. It unlocks when the loan is CLOSED and the latest VERIFIED
  payment's `paid_on ≤ repay_by`; the best offer is pinned at reborrow and consumed at disbursal; it
  reduces the processing fee and appears on the KFS.
- **Reapply allowance**: ADMIN one-use permission (reason + supporting document) letting a
  `PAST_DELINQUENCY`-disqualified customer reborrow (`NOT_DISQUALIFIED`, `ALLOWANCE_EXISTS`).
- **Templates library** (`/staff/admin/templates`): SMS / WhatsApp / Email templates with label,
  purpose (COLLECTIONS, REPAYMENT, DISCOUNTS, LOAN_UPDATES, KYC, REFERENCES, OTHER), category, ordered
  variables, provider id (DLT id / WhatsApp name), status DRAFT → SUBMITTED → ACTIVE / REJECTED;
  system-managed rows are protected (`TEMPLATE_PROTECTED`); "refresh WhatsApp" reads statuses from the
  provider.

### 9.8 Admin eSign documents (flag `esign-documents`)
ADMIN uploads any PDF (vendor / DSA / employee agreement), names up to 5 signers (external or
themselves), places signature boxes by **drag-and-drop** on a rendered PDF (`{page, x, y, width,
height}` in PDF points from the top-left), and sends **one** contract. Signers get an invite
(email/SMS), sign by Aadhaar eSign, land on the public `/esign/done` page. Detail page polls every
10 s while SENT; refresh and resend per signer. Page numbers go to the provider as JSON numbers; the
coordinate origin is configurable (`NAVIX_ESIGN_COORDINATE_ORIGIN`).

### 9.9 Admin tools
Staff users (create, role, status, disable — revokes sessions), invites (7-day token), blocklist,
company expenses (+ receipt upload), payment settings (UPI QR, account PDF), all-applications
register, rejection register, onboarding waitlist (full PAN/Aadhaar), Settings (onboarding-paused,
applications-closed, PayU gateway, payment block), Provider API dashboard (catalog, history with
LIVE/MANUAL source, detail, ADMIN "execute" a provider call from a workbench), bureau backfill /
rescore (preview a cohort, execute, sweep the sub-floor backlog), bureau-challenge outreach,
recompute outstanding for all loans.

### 9.10 Global search (flag `global-search`, default on)
Cmd/Ctrl+K palette. **Pages** resolve locally from the nav definition (so it never offers a page the
role cannot open). **Records** from `GET /api/staff/search?q=&limit=` — groups customer · application
· loan · collections · lead · staff · blocklist, chosen by role and delegated to the same scoped
service each list page uses; mobile/PAN masked; a group the role can't see is absent. Query
interpretation: 10-digit → mobile, PAN pattern → PAN, digits → id, else text. Recents = query strings
per staffer, cleared on sign-out. Hidden for DSA. Hits deep-link with `?q=` / `?open=`.

### 9.11 Reports centre (ADMIN real role) & customer export
`GET /api/reports` (catalog) and `GET /api/reports/{key}?from=&to=` (inclusive IST period; both
omitted = all time) return a typed table (columns + raw rows + headline figures). The frontend writes
**CSV and branded PDF from the same table**. PAN masked; no Aadhaar or bank account. 43 reports in 10
groups — Appendix C. **Customer data export**: field allowlist → match ids (same segment/DPD logic as
the Customers register, ≤ 50,000, `truncated` flag) → rows in pages of ≤ 200 ids; with
`includeIdNumbers=true` full Aadhaar/PAN/UAN/account numbers (the one exception; logged who + how
many, never a value); two concurrent exports max (`EXPORT_BUSY` 503, client retries).

## 10. Credit decisioning

### 10.1 Bureau credit brief & star rating
A bureau pull produces `BureauReportFacts` (score, bureau name, tradelines, DPD history, enquiries,
defaults, write-offs/settlements). The brief's identity comes from our KYC profile, not the bureau
copy. Stars (`CreditRatingCalculator`):
- Base from score: ≥780 → 5.0; 750–779 → 4.5; 720–749 → 4.0; 690–719 → 3.5; 660–689 → 3.0;
  631–659 → 2.5; 601–630 → 2.0; ≤600 → 1.0.
- Deductions: worst DPD > 10 days in the last 12 months −1.0; an account currently past due −0.5;
  > 3 enquiries in 30 days −0.5; no personal loan in the last 9 months −0.5.
- Caps last: any default → ≤ 2.5; any written-off/settled → ≤ 2.0; score ≤ 600 → 1.0.
- Clamp [1, 5], round to 0.5. A missing fact is skipped, not penalised. No score → unrated (0.0,
  "SCORE UNAVAILABLE", not persisted).
- Verdict: ≥ 4.5 STRONGLY RECOMMEND · ≥ 3.5 RECOMMEND · ≥ 2.5 "REFER — MANUAL REVIEW" · else NOT
  RECOMMENDED.
- BRE rules (staff BRE tab): min score 600, max DPD 10 days, 9-month lookback, ≤ 3 recent enquiries.
- Render a branded `CREDIT_BRIEF` PDF (OpenPDF) to S3; ingest the vendor's report PDF as
  `BUREAU_REPORT`. Recompute stored stars at startup under the current rules (async).
- **Auto-reject below 550 exists but is off** (`bureau-auto-reject`). Every result goes to a human.

### 10.2 Verification checks
`check_type` values: PAN, EMAIL, ADDRESS, DIGILOCKER, AADHAAR, AADHAAR_PHOTO, BUREAU, SALARY,
EMPLOYMENT, PENNY_DROP, SELFIE, AGREEMENT, ESIGN, AADHAAR_DUPLICATE, BUREAU_CONSENT, PAN_AADHAAR_LINK,
EMAIL_OTP, OFFICIAL_EMAIL_OTP, EMANDATE.
- **REQUIRED** to submit (attempted): PAN, EMAIL, BUREAU, SALARY. **REQUIRED_SANCTION** (dashboard
  signal, non-blocking): AADHAAR, SELFIE, ADDRESS, ESIGN.
- Manual override allowed (`KNOWN_CHECKS`): PAN, EMAIL, ADDRESS, DIGILOCKER, AADHAAR, BUREAU, SALARY,
  EMPLOYMENT, PENNY_DROP, SELFIE, AGREEMENT, ESIGN, AADHAAR_DUPLICATE, PAN_AADHAAR_LINK. Advisory,
  not overridable: BUREAU_CONSENT, EMAIL_OTP, OFFICIAL_EMAIL_OTP.
- Inherited on reborrow: PAN, EMAIL, BUREAU, SALARY, AADHAAR, SELFIE, ADDRESS, EMPLOYMENT.
- Name match threshold 0.60. **No verification hard-blocks a borrower**: a failed check is REVIEW
  for a human (the fraud rules in §7.2 are the deliberate exception).
- Employment (EPFO/UAN) is advisory; a vendor outage parks the check and an hourly job re-runs it.

## 11. Integrations (every one behind a port, mock by default)

| Port | Capability | Real provider (production) | Flow shape |
|---|---|---|---|
| `VerificationPort` | PAN | Signzy → Fintrix → Digitap | sync |
| | email deliverability + employer match | Signzy → Digitap | sync |
| | address | Signzy reverse-geocode → Digitap | sync |
| | employment (UAN) | Digitap | sync, retried hourly |
| | bureau | Fintrix CRIF (`crif_combine`) → Digitap Experian; walks past a no-hit | sync, ~100 s; KBA challenge answered straight to Fintrix |
| | penny-drop | Signzy | sync |
| | DigiLocker | Signzy | redirect + poll |
| | selfie liveness | Digitap Face Liveness v4 → Signzy liveness iframe | sync / iframe + poll |
| `EsignPort` | Aadhaar eSign | Signzy Contract API (eMudhra) | initiate → redirect → poll; signed callback only accelerates |
| `MandatePort` | eMandate + NACH debit | Digio | redirect + poll; debit present + polling reconcile |
| Payment gateway | repayment checkout | PayU Hosted Checkout | form POST → return/webhook → verify_payment |
| `SmsGateway` | SMS | UltronSMS (DLT, sender `DHANBT`) | sync GET |
| `WhatsAppGateway` | WhatsApp templates | SmartChat (Meta templates) | sync |
| `MailPort` | email | `log` · SMTP · AWS SES (bounce/complaint via SNS→SQS) · Resend | sync |
| `DocumentStoragePort` | documents | S3 with SSE-KMS, presigned PUT/GET (15 min) | browser ↔ S3 direct |
| Captcha | bot check | Cloudflare Turnstile | server siteverify (action + hostname) |
| Network guard | geo + VPN | Vercel geo header + proxycheck.io | edge middleware |
| On-demand tools | skip tracing; bank-statement analysis | Digitap Skip Tracing Lite; Signzy BSA (async 3-call) | staff-triggered only, billable |

Rules:
- `RoutingVerificationPort` walks one **global** ordered chain (`NAVIX_VERIFICATION_CHAIN`, default
  `signzy,fintrix,digitap`). Adapters opt out by throwing `CapabilityNotSupportedException` (skip),
  fall through on `VerificationException` (tried and failed), stop on
  `TerminalVerificationException` (a definitive answer, e.g. PAN not found = FAIL). Bureau precedence:
  acceptable answer > masked-mobile hint > first unacceptable > last real failure.
- **Every provider call is recorded** in `provider_api_execution` (credentials redacted, request id
  for log correlation). An hourly health monitor alerts ADMINs (`PROVIDER_CAPABILITY_DOWN` when ≥ 10
  calls in 24 h and no success; `PROVIDER_BALANCE_EXHAUSTED` pushed when a prepaid balance empties;
  one alert per key per 6 h). History purges nightly.
- **Our DB row is the source of truth** for every redirect/poll flow; provider callbacks only speed
  up the closed-tab case.
- Mock adapters return deterministic results driven by input (e.g. PAN ending `0000` → not found,
  mobile ending `9999` → bureau no-hit), so every branch is testable offline. `NAVIX_BUREAU_FIXTURE`
  loads a bundled credit report.
- SMS bodies must match the registered DLT template character-for-character, use `Rs.` (not ₹), and
  contain only the whitelisted URL. WhatsApp template wording lives with Meta — code holds only
  `(type, templateName, orderedKeys…)`; never reorder keys.

## 12. Security & compliance

- **JWT**: HS256 (`AUTH_SECRET` ≥ 32 chars), audiences `staff` (1 day, `sid` claim) and `borrower`
  (7 days). `JwtAuthFilter` validates, requires the staff `sid` to be the current session, populates
  `ActorContext`, grants `ROLE_<role>` + `ROLE_<AUDIENCE>`, clears in `finally`.
- **One live staff session** per staffer (`SESSION_CONFLICT` unless `force`); disabling a user or
  changing their role revokes it (cache evicted after commit).
- **SecurityConfig**: stateless, CSRF off (BFF origin checks instead), 401 entry point. Public:
  `/api/auth/**`, `/api/contact`, `/api/webhooks/**` (each verifies its own secret), actuator health,
  OpenAPI. Staff audience: `/api/staff/**`, `/api/admin/**`, `/api/dsa/**`, `/api/collections/**`,
  `/api/dashboard/**`, `/api/mandates/**`, `/api/customer-messages/**`. Everything else
  authenticated. Services enforce `requireRole` + SoD on top.
- **DSA exclusion**: because some guards are deny-lists, `DSA` is explicitly rejected on every
  customer/application/loan/decision/search/skip-trace/BSA read. Any new staff-open surface must add
  the DSA rejection (a DSA token satisfies the staff audience).
- **Borrower ownership**: every borrower read is ownership-checked; staff-only fields (credit score,
  ★, BRE, AADHAAR_DUPLICATE, bureau report) are stripped from borrower responses.
- **Rate limits** (in-memory, single instance): password login 3 failures / 15 min → 30 s cool-off
  (`TOO_MANY_ATTEMPTS`; successes don't count); forgot-password 3 per email per 15 min; OTP 5 sends /
  5 verifies per 15 min; email OTP TTL 600 s.
- **Turnstile** on staff login, borrower password login and both forgot-password forms (actions
  `staff-login`, `borrower-password-login`, `borrower-forgot-password`, `staff-forgot-password`);
  unset secret = skipped (dev/CI).
- **BFF**: cookies httpOnly + sameSite lax + secure; login routes reject cross-site requests
  (`application/json` + `Sec-Fetch-Site: same-origin`); safe `?next=` redirects only; `X-Request-Id`
  end-to-end.
- **Headers**: CSP (self + Turnstile + GA + S3 + provider frames; `form-action` PayU), HSTS 2 years,
  `X-Frame-Options SAMEORIGIN`, `nosniff`, strict referrer, Permissions-Policy (camera only for the
  liveness provider, geolocation self, no microphone).
- **Network guard** (§13.3).
- **PII**: PAN/mobile/account masked in lists and search; full Aadhaar + card images are a
  product-approved exception visible to staff; documents in S3 with SSE-KMS and short-lived presigned
  GETs; never log tokens, OTPs or PII; provider logs redacted; CSV exports neutralise formulas.
- **ProductionConfigGuard** (fails closed; bypass only with `NAVIX_ALLOW_DEV_DEFAULTS=true`): refuse
  to boot with the default/short `AUTH_SECRET`, SMS mock or dev-echo, mock eSign, `log` email while
  email is enabled; on ready, refuse Digio mock with `digio-mandate` on, and PayU on with missing
  credentials or a sandbox URL. eSign with the real provider refuses to boot without the callback URL
  + secret.
- Legal content: KFS with all-in APR (GST excluded), sanction letter naming the NBFC lender, fair
  practices, grievance, privacy, terms, refund policy; draft policies `noindex`.

## 13. Frontend architecture

### 13.1 Stack
Next.js 15 App Router (`src/`), React 19, TypeScript 5.7 strict, Tailwind 3.4, TanStack Query 5
(server state), Zustand (signup draft only), react-hook-form + zod, lucide-react icons, recharts,
jsPDF + jspdf-autotable (exports), pdfjs-dist (PDF preview + signature placer), d3-geo + topojson
(India map). Vitest (node + jsdom projects) and Playwright. Deployed on Vercel, functions pinned to
region `bom1` (same region as the backend).

### 13.2 Route groups
- `(marketing)` — `/`, `/about`, `/careers`, `/contact` (form → `/api/contact`), `/products`,
  `/how-it-works`, `/calculator`, `/faq` (FAQPage JSON-LD), `/help`, `/blog`, `/blog/[slug]` (Article
  JSON-LD; 6 posts), legal: `/fair-practices`, `/grievance`, `/privacy`, `/terms`, `/refund-policy`;
  draft policies `noindex` (`/corporate-governance`, `/interest-rate-policy`, `/kyc-aml-policy`,
  `/risk-management`); `/partners`, `/reviews` `noindex`; `/esign/done` (external signers). Marketing
  is scoped under `.navix-mkt` with Organization + WebSite JSON-LD, and GA4 runs only on marketing
  pages. Calculator: interest = A × R% × T; APR = ((10 + R·T) / 90) × (365 / T) × 100 (fee + interest
  on the 90% received; GST excluded); donut + rate table.
- `(borrower)` — `noindex`; header with account menu; footer with the NBFC-partner disclaimer;
  `OnboardingGate` (when paused only `/apply`, `/login`, `/settings`, `/support` are reachable).
  Routes: §7. Redirect-only stubs are not needed in a rebuild.
- `staff/` — `noindex`; `StaffShell`; public pages `/staff/login`, `/staff/activate`,
  `/staff/forgot-password`, `/staff/reset-password`; `/staff` redirects DSA → `/staff/dsa/leads`,
  others → `/staff/dashboard`.
- `/network-blocked` (403 rewrite target, `?reason=country|vpn|proxy|tor`); `robots.ts`,
  `sitemap.ts`, `opengraph-image.tsx` (1200×630, navy + emerald wordmark).

### 13.3 Middleware (`src/middleware.ts`)
1. **Network guard** (edge): a verified crawler (Google/Bing/Apple user agent **and** an IP inside
   that engine's published CIDR feed; feeds cached 12 h; fails closed) passes. `x-vercel-ip-country`
   not in `NETWORK_GUARD_ALLOWED_COUNTRIES` (default `IN`) → block. Private/loopback/CGNAT IP or no
   API key → pass. A valid `dbx_net_ok` cookie (`exp.sig`, HMAC-SHA256(key, `ip|exp`), 30 min,
   IP-bound) → pass. Otherwise proxycheck.io (`vpn=3&asn=1`, 1.5 s timeout): TOR / VPN / proxy →
   block. Vendor error → **fail open** (not cached as clean). Blocked pages → rewrite
   `/network-blocked` (403); blocked `/api/*` → 403 envelope `NETWORK_BLOCKED`. Kill switch
   `NETWORK_GUARD_DISABLED=true`. Excluded: static assets, `/api/payu/*`, image routes.
2. **Staff gate**: `/staff/*` (except the public pages) requires a `navix_staff` cookie whose JWT
   `exp` is in the future (decoded, not verified — real auth is the backend); otherwise delete the
   cookie and redirect to `/staff/login?redirect=<path>`.

### 13.4 BFF (`src/app/api/*`)
- Auth handlers: staff `login` (sets the cookie, strips the token, `force` for session takeover),
  `logout`, `me`, `accept-invite`, `forgot-password`, `reset-password`; borrower `otp/request`,
  `login`, `password-login`, `logout`, `me`, `set-password`, `forgot-password`, `reset-password`,
  `onboarding` (anonymous `{paused}`, fails open to false).
- Proxies: `borrower/{applications, loan, notifications, onboarding-waitlist, preferences,
  referral}`; `staff/{applications, applications-closed, bank-analysis, collections,
  customer-messages, customers, dashboard, decisions, dsa, invites, leads, loan, loans, mandates,
  notifications, onboarding-waitlist, payment-gateway, provider-apis, referral, reports, search,
  skip-trace, users}`; `admin/{blocklist, bureau-challenge, dsa, esign-documents, expenses}`; plus
  `feature-flags`, `payment-settings`, `storage` (presign only — bytes go browser ↔ S3), `contact`,
  `payu/return`, `payu/webhook`.
- `proxyToBackend(req, path, token)` forwards method, query, JSON body, `Authorization: Bearer`,
  `X-Request-Id` (generated if absent) and `X-Acting-Role`; returns status + body verbatim; a network
  failure → 502 `BACKEND_UNREACHABLE`. The `loan` proxies restrict POST to an allow-list regex
  (borrower `^\d+/(repayments|payu/orders)$`; staff
  `^(\d+/repayments(/(admin-record|\d+/(verify|reject)))?|payu-review/\d+/resolve)$`).
- Use optional catch-alls `[[...path]]` (a required `[...path]` does not match the bare base path).

### 13.5 Client libraries
- `lib/api/applications.ts`: one `bff()` helper unwraps `ApiResponse<T>` and throws
  `ApplicationApiError {code, message, requestId}`. Typed API objects: `publicApi`, `borrowerApi`,
  `journeyApi`, `verificationApi`, `offerApi`, `staffApi`, `customersApi`, `loansApi`, `leadsApi`,
  `dsaApi`, `adminDsaApi`, `dashboardApi`, `reportsApi`, `adminApi`, `paymentSettingsApi`,
  `featureFlagsApi`, `searchApi`, `referralApi`, `staffReferralApi`, `esignDocsApi`, `storageApi`,
  `collectionsApi`, notifications APIs, `providerApi`, `customerMessagesApi`, `storedTemplatesApi`,
  `mandatesApi`, `skipTraceApi`, `bankAnalysisApi`, `paymentGatewayApi`, `intakeSwitchApi`,
  `customerExportApi`. Helpers `rupeesToPaise`, `paiseToINR`, `formatInr` (lakh/crore).
- `lib/api/live-journey.ts`: the borrower seam — session, stored app id, `useLiveApplication`
  polling, `createOrResumeDraft`, `submitOnboarding`, `applyForAmount`, status → stage mapping,
  `routeForBlockedStart` (`ONBOARDING_PAUSED` → `/apply`, `APPLICATIONS_CLOSED` →
  `/applications-closed`), journey and offer guards.
- `lib/auth/rbac.ts` (§8.2), `lib/auth/working-role.ts` (§8.3), `lib/calc/*` (§3 + KPIs + ledger),
  `lib/export/*` (CSV: RFC-4180, formula-neutralised with a leading `'`, UTF-8 BOM; PDF: navy header
  band, emerald rule, logo, title, "Downloaded by <name> · <role>", confidential footer, ₹ → "Rs."),
  `lib/reports/{categories,periods}.ts`, `lib/staff/*` (register framework, global search).

### 13.6 Design system
- **Font**: Inter (400–800) for every role; `font-serif` = display weight, `font-mono` = tabular
  figures (`tnum`).
- **Colours** (tokens; re-skin by changing values, never names): navy `#0C2540` (900 `#081A31`,
  700 `#12365C`, 600 `#1B4A7A`, 500 `#2C6298`), accent **emerald `#14A06B`** (token name `gold`; dark
  `#0B6B46`, soft `#A7E8CE`), cream/ivory `#FDFBF6`, ink `#0C2238`, line `#EAE1D0`, success
  `#1C9B6A`, warning `#8A5A06`, error `#B3261E`, info `#2C6298`.
- **Type scale at 80%** via tokens (body 12.8 px; xs 9.6 … 5xl 38.4). Never scale the `html`
  font-size.
- Radius 8/12/16/20/26; shadows tinted `rgba(12,37,64,…)`; 4 px spacing grid; container 1200.
- Component classes: `.btn` (+ `-gold`, `-navy`, `-outline`, `-ghost`, `-lg`, `-sm`, `-block`),
  `.card`, `.field` (+ `.req`, `.hint`, `.err`), `.cal-*` (month grid), `.staff-data-table` (zebra,
  sticky identity/actions columns, change flash), `.pill-tabs`, `.cust-rail*`, ledger badges `.lb-*`.
- UI kit: button, input, select, combobox, card, badge, table, tabs, pill-tabs, status-badge (one
  status → tone map), skeleton / EmptyState / ErrorState(onRetry) (**every screen handles
  loading / empty / error / retry**), money (paise, right-aligned, tabular), toast (staff only),
  dialog, confirm-dialog, drawer (focus trap), InfoTooltip (ⓘ on dashboard cards and column headers),
  zoomable QR, Turnstile widget.
- Borrower screens are mobile-first. Staff tables are dense (`.navix-crm` density) and scroll
  horizontally.

## 14. Feature flags

Table `feature_flag(flag_key, enabled, description)`, read uncached with
`isEnabled(key, defaultWhenMissing)`. Flags change **only by SQL**, except the three ADMIN-flippable
switches in Staff → Settings (`onboarding-paused`, `applications-closed`, `payu-repayment`). A
read-only `GET /api/feature-flags` serves UI gating. Risk-bearing flags default **off when the row is
missing**, so deleting a row fails safe.

| Key | Seed | Missing ⇒ | Gates |
|---|---|---|---|
| `referral` | true | env default (true) | refer-a-friend API + pages |
| `fintrix-bureau` | true | true | Fintrix CRIF bureau leg |
| `bureau-auto-reject` | **false** | false | auto-reject below 550 |
| `digitap-pan` / `digitap-email` | false | false | Digitap fallback legs |
| `digitap-crif` | (no row) | false | Digitap CRIF bureau leg |
| `employment-auto-retry` | true | true | hourly UAN re-run |
| `global-search` | true | **true** | Cmd/Ctrl+K palette + endpoint |
| `onboarding-paused` | **true** | false | waitlist mode |
| `applications-closed` | (no row) | false | refuse all new applications |
| `digio-mandate` | false | false | eMandate offer step |
| `digio-debit` | false | false | NACH debit presentation |
| `payu-repayment` | false | false | PayU checkout |
| `admin-messaging` | false | false | messaging, template writes, discounts |
| `reference-otp` | false | false | reference OTP |
| `esign-documents` | false | false | admin eSign documents |

UI semantics: nav-item flags and `global-search` hide only on an explicit `false`; the `digio-debit`
and `admin-messaging` UI require an explicit `true`.

## 15. Notifications & background jobs

### 15.1 Engine
Domain code publishes a Spring application event. `NotificationEventListener`
(`@TransactionalEventListener(AFTER_COMMIT) @Async`) maps it to a `NotificationType`, resolves the
audience (`RecipientPolicy`: TO_BORROWER, TO_ASSIGNED_EXECUTIVE, TO_CREDIT_TEAM, TO_CREDIT_HEADS,
TO_CREDIT_EXECUTIVES, TO_DISBURSEMENT_HEADS, TO_ACCOUNTANTS, TO_COLLECTION_HEADS,
TO_COLLECTION_EXECUTIVES, TO_ADMINS, TO_STAFF_SUBJECT), renders templates and sends per channel
(IN_APP always; SMS/WhatsApp/EMAIL subject to preferences, suppression and configured template ids).
Each attempt writes `notification_delivery`. Categories: KYC, CREDIT, DISBURSEMENT, REPAYMENT,
COLLECTIONS, STAFF_IAM, SECURITY, SYSTEM. Email carries plain text plus branded HTML. STAFF_IAM mail
ignores the staff opt-out. Full type list: Appendix D.

The inbox API (`/api/notifications`, scoped to the caller) supports list (paged), unread-count, mark
one read and mark all read. One shared bell component polls it for both audiences.

### 15.2 Scheduled jobs (single instance — add a distributed lock before scaling out)

| Job | Default schedule | Does |
|---|---|---|
| Payment reminders | 09:00 daily | `PAYMENT_DUE_SOON` from 7 days before due through grace; `PAYMENT_OVERDUE` for 7 days after; stops at zero |
| Employment retry | hourly :30 | re-run parked UAN checks (batch 50, backoff); flag `employment-auto-retry` |
| Provider health | hourly :15 | capability-down alerts |
| NACH debit sweep | 10:00 IST | reconcile in-flight debits, then present at DPD 1/3/5/9 (flag `digio-debit`) |
| PayU reconcile | every 5 min | re-verify INITIATED/PENDING orders (not flagged) |
| Provider history purge | 02:15 daily | delete expired `provider_api_execution` rows |
| On startup | — | rescore stored credit stars (async); reap interrupted lead-import jobs |

## 16. Infrastructure, delivery & non-functional requirements

- **Topology**: Vercel (frontend + BFF, `bom1`) → AWS ALB (HTTP; 120 s idle timeout, since bureau
  calls take up to ~100 s) → ECS Fargate (1 vCPU / 2 GB, private subnets, NAT with a static egress IP
  for vendor allow-lists) → RDS PostgreSQL, S3 (SSE-KMS), SSM Parameter Store, SES + SNS/SQS; region
  `ap-south-1`. The task definition sets only the environment name and region; every other setting is
  imported from SSM `/navix/<env>/…` via `spring.config.import`.
- **CI/CD** (GitHub Actions, one workflow). On every push: backend `mvnw install` → unit tests →
  Testcontainers IT; frontend `tsc --noEmit` + ESLint. On push to `main`: build the backend image
  (`linux/amd64`, tagged with the full commit SHA), register a task-definition revision **cloned from
  the live one with only the image swapped**, update the service, wait for it to stabilise, curl
  health; and run `vercel deploy --prod`. `workflow_dispatch` with a tag rolls back. A preflight
  checks the eSign callback parameters exist.
- **Docker**: multi-stage Maven → JRE 21 image, non-root user, `MaxRAMPercentage=75`, health check on
  `/actuator/health` (actuator exposes only health + info). `docker-compose.yml` = Postgres 16 +
  Adminer for local work.
- **Observability**: a request-logging filter with MDC `requestId` (from `X-Request-Id`); provider
  calls joinable by request id; the ADMIN Provider API dashboard; provider health alerts.
- **Performance budgets**: no whole-table reads on hot pages. Registers are server-paged (size ≤ 100)
  and filtered in SQL. Batch per-page lookups (verification completeness, settlements, outstanding)
  instead of per-row queries. Compute dashboard aggregates server-side. Tune polling per surface
  (maker-checker queues 8 s). Indexes: `application_event(action, at)`, `loan(status, due_date)`,
  `payment(status, paid_on)`, partial `loan_application(loan_id)`, `(staff_id, created_at)` on the
  call logs.
- **Time**: every business date is IST; run the JVM with `-Duser.timezone=Asia/Kolkata`.
- **Simulation environment** (optional, last): a separate stack whose staff login the BFF routes when
  the email equals `SIM_LOGIN_EMAIL` (header `X-Sim-Gateway`), with exports prefixed `SIMULATION-` and
  an undismissable banner.

---

# Part B — Build plan (workflow phases)

## 17. How to run the build with workflows

- Each phase below lists **inputs** (the phases it depends on), **parallel lanes** (independent work
  for separate agents), **deliverables** and an **acceptance gate**. Run a phase's lanes
  concurrently; one integration agent then merges, runs the gate, and fixes failures before the next
  phase starts.
- Lanes must not edit the same files. Shared contracts (enums, DTOs, the ApiResponse envelope, the
  rbac matrix) are created first in W0/W1 and are read-only for later lanes.
- Each lane ships with tests: backend service tests for success plus every error code it introduces;
  controller authz tests (wrong role → 422 `FORBIDDEN_ROLE`, anonymous → 401, DSA rejected where §12
  says); frontend vitest for pure logic; one Playwright happy path per major screen.
- Suggested size: 2–5 agents per phase, roughly 45–60 agents over the whole build. W6–W8 can overlap
  once W5's gate passes, because they touch different modules.

## 18. Phases

### W0 — Foundation & contracts
- **Lanes**: (a) Maven parent + modules `navix-common`, `navix-iam`, `navix-loan`,
  `navix-collections`, `navix-verification`, `navix-notification`, `navix-storage`,
  `navix-income-risk`, `navix-app` (the only bootable one); Java 21, Spring Boot 3.4, Flyway,
  springdoc. (b) Next.js skeleton, Tailwind tokens + `globals.css` component classes + UI kit
  (§13.6), route groups, providers. (c) docker-compose Postgres, `.env.example` for backend and
  frontend, CI workflow (tests only, no deploy yet).
- **Common contracts**: `ApiResponse<T>` envelope `{success, data, error:{code,message}}`;
  `BusinessException` → 422; `ResourceNotFoundException` → 404; `GlobalExceptionHandler`;
  `FeatureFlagService` + table; `ActorContext/CurrentActor/ActingRole`; `Masking`; `Aadhaar`
  (Verhoeff + `matchesMasked`); `Mobiles`; the port interfaces (§11) with mock implementations.
- **Gate**: the app boots against Postgres, Flyway runs, `/actuator/health` is UP; the frontend
  renders a token showcase page; CI is green.

### W1 — Identity, auth & RBAC
- **Inputs**: W0.
- **Lanes**:
  - (a) Staff auth: BCrypt login, staff JWT with `sid`, session registry + revocation, invites,
    forgot/reset, rate limiter, Turnstile verifier, staff CRUD, `/me`.
  - (b) Borrower auth: OTP request/verify via the `SmsGateway` mock, password login + set-password,
    forgot/reset, `customerId` derived from the mobile, 7-day JWT.
  - (c) `SecurityConfig`, `JwtAuthFilter`, `ProductionConfigGuard`, request-id logging.
  - (d) Frontend: BFF auth routes, cookies, `rejectCrossSite`, the middleware staff gate, `/login`,
    `/staff/login`, `/staff/activate`, forgot/reset pages, `rbac.ts` + the working-role store + role
    switcher, `StaffShell` + nav from `staff-nav.ts`.
- **Gate** (Playwright): borrower OTP login with mock code `123456`; staff login; 3 wrong passwords
  → `TOO_MANY_ATTEMPTS`; a second staff login → `SESSION_CONFLICT`, and `force` succeeds; a disabled
  staffer's token → 401; the guard refuses to boot with the default secret unless dev defaults are
  allowed.

### W2 — Lifecycle core & loan math
- **Inputs**: W1.
- **Lanes**: (a) `ApplicationStatus` + transition map + `application_event` +
  `ApplicationFlowService` skeleton (create DRAFT, cancel, reject with blocks, `assertNotBlocked`).
  (b) `LoanMath` in Java + `loan-math.ts`, with **G1–G10 as shared fixtures** (one JSON file both
  test suites read). (c) `RepaymentService.breakdown/outstandingAsOf` and the schedule endpoint.
- **Gate**: every illegal transition → `ILLEGAL_TRANSITION`; G1–G10 pass in both languages; a property
  test shows the outstanding never decreases as `asOf` advances (until the 30-day penalty cap).

### W3 — Borrower intake & verification
- **Inputs**: W2.
- **Lanes**:
  - (a) `customer_profile` + profile APIs (ownership, Aadhaar rules, lock, DOB/UAN validation).
  - (b) `RoutingVerificationPort` + mock adapters + `provider_api_execution` recording +
    `ApplicationVerificationService` (PAN, email + both email OTPs, bureau + KBA, salary, employment,
    consent).
  - (c) S3 storage port (+ a local filesystem/MinIO mock), the document upload allow-list, presign.
  - (d) `JourneyService` resume + the 12 `/signup/*` screens, the self-employed branch,
    `/credit-question`, `/application-rejected`.
  - (e) Credit brief: facts parser, star calculator (§10.1), PDF renderer.
- **Gate**: an end-to-end Playwright intake reaches `KYC_PENDING` on mocks; `KYC_INCOMPLETE` without
  the four card images; Aadhaar mismatch → `FRAUD_REJECTED` with a 365-day block; a duplicate Aadhaar
  → a staff-only REVIEW row, then `AADHAAR_DUPLICATE` auto-reject at submit; table tests for the star
  calculator.

### W4 — Credit desk & offer journey
- **Inputs**: W3.
- **Lanes**:
  - (a) Credit queue, assign/reassign (ACTIVE executives only), sanction / reject-lead /
    mark-pending, SoD + working-role scoping, `/staff/applications` per-role rendering.
  - (b) `OfferService` + the 11 `/loan/*` screens: amount, locked date, DigiLocker mock redirect,
    references + OTP flag, summary, selfie, address, KFS + sanction letter PDF, eSign mock + drawn
    fallback, celebration, disbursal account + penny-drop lock.
  - (c) eSign port wiring + the callback webhook with a shared secret.
- **Gate** (Playwright): an Executive sanctions; the borrower walks the offer journey to
  `DISBURSEMENT_PENDING`; an Executive cannot decide an unassigned file; ADMIN can, solo, by switching
  working role; the timeline shows "(as Credit Executive)".

### W5 — Disbursement, loans, repayment
- **Inputs**: W4.
- **Lanes**:
  - (a) Disbursement decision with txn ref, loan minting (due date, grace, discount consumption),
    auto-assignment of a collections officer.
  - (b) Manual repayment (record, proof upload, dedupe), Accountant verify/reject, close at zero,
    transactions ledger.
  - (c) Borrower `/dashboard`, `/repay`, `/loans`, `/transactions`, the payment settings block.
  - (d) Reborrow + carry-over + standing + reapply allowance, `/reloan`.
- **Gate**: an accept without a txn ref → `TXN_REF_REQUIRED`; a full loan cycle reaches CLOSED; a clean
  borrower's reborrow lands in `SANCTIONED` with the short journey and a fresh eSign; a 6-day-late
  history → `PAST_DELINQUENCY`.

### W6 — Collections, settlements, mandates & NACH
- **Inputs**: W5.
- **Lanes**:
  - (a) Cases, worklist, DPD buckets, interactions, field payments, the Collection Head desk,
    executive scoping (`CASE_NOT_ASSIGNED`).
  - (b) Settlements with SoD and the settlement-capped outstanding.
  - (c) `MandatePort` mock + mandate attempts, the offer step, Mandate tab actions (override, ceiling,
    cancel, cancel-active, change account).
  - (d) Debit service, scheduler, manual debit, reconcile, terminal denylist, refunds owed.
- **Gate**: a proposer approving their own settlement → `SOD_VIOLATION`; of two concurrent debits on
  one loan, the second refuses (`DEBIT_IN_FLIGHT`); an unknown failure code is re-presented and a
  terminal one is not; a NACH success closes the loan with no Accountant step.

### W7 — Payment gateway, notifications, messaging
- **Inputs**: W5.
- **Lanes**:
  - (a) PayU service + hash + return/webhook + reconcile + review queue + Settings toggle (a mock
    gateway that simulates success, failure, pending and amount mismatch).
  - (b) Notification engine, inbox API, bell, preferences, the SES suppression listener (mockable),
    the reminders scheduler, every Appendix D type.
  - (c) Admin customer messaging, catalogue, templates library, repay-now discount offers, the
    send-hours rule.
- **Gate**: duplicate PayU signals credit once; an amount mismatch lands in review and is not
  credited; an opted-out borrower gets in-app only; a send at 20:00 IST → `OUTSIDE_SEND_HOURS`.

### W8 — CRM, leads, DSA, referral
- **Inputs**: W3 (+ W5 for commission accrual).
- **Lanes**: (a) customers register + segments + customer 360 (21 tabs) + ADMIN corrections + change
  log + dedupe. (b) Leads, import jobs, telecalling, call logs, remarks. (c) DSA portal + admin +
  commission lifecycle + outreach. (d) Referral.
- **Gate**: a DSA token is rejected on every customer/application/loan/search endpoint; a PAN held by
  another DSA and a PAN held by a customer return the same `LEAD_ALREADY_KNOWN`; commission goes
  ACCRUED → PAYABLE → PAID, and VOID on settlement.

### W9 — Staff analytics, reports, search, admin tools
- **Inputs**: W5–W8.
- **Lanes**:
  - (a) Dashboard endpoints + tabs + targets (vivid, interactive, records drawer).
  - (b) Reports centre (43 keys, Appendix C) + CSV/PDF exporters + the customer data export.
  - (c) Global search.
  - (d) Admin pages: staff, invites, blocklist, expenses, settings, waitlist + intake switches,
    all-applications, rejections, Provider API dashboard + health monitor, bureau backfill, staff
    performance + decision history.
- **Gate**: every report renders for "all time" and "this FY" with identical CSV and PDF content;
  search never returns a row the role's list page would hide; waitlist mode routes a new borrower to
  `/apply`.

### W10 — Admin eSign documents & on-demand vendor tools
- **Inputs**: W4 (the eSign port), W8.
- **Lanes**: (a) eSign documents: upload, signers, the drag-and-drop signature placer on pdf.js, one
  contract, invites, `/esign/done`, refresh/resend. (b) Skip Tracer and Bank Statement Analysis tabs
  (mock adapters, history tables, a confirmation before each billable action).
- **Gate**: signature boxes round-trip exactly in PDF points; a call with the flag off →
  `FEATURE_DISABLED`.

### W11 — Marketing site, SEO, network guard, security headers
- **Inputs**: W0; can run any time after it.
- **Lanes**: (a) marketing pages + calculator + blog + legal + JSON-LD + sitemap/robots/OG.
  (b) Network guard + verified crawlers + `/network-blocked`. (c) CSP/HSTS/Permissions-Policy, the
  `staff.` host redirect, GA on marketing pages only.
- **Gate**: Lighthouse SEO ≥ 95 on `/`; a non-IN country header → the 403 page; a spoofed Googlebot
  user agent from a non-Google IP is blocked; no CSP violations on the borrower journey.

### W12 — Hardening & deploy
- **Inputs**: all phases.
- **Lanes**: (a) the Testcontainers integration test (full lifecycle on a real Postgres) and the
  Playwright suite (auth, onboarding, offer journey, rbac, repay, staff). (b) Production config guard
  review, secret scan, PII log audit, performance pass (no N+1 queries, paged registers).
  (c) Dockerfile, ECS task-definition template, CI deploy jobs, runbook; optional simulation stack.
- **Gate**: CI fully green; a clean `docker compose up` + backend + frontend runs the whole demo
  offline on mocks; the app refuses to boot with demo settings when dev defaults are not allowed.

## 19. Definition of done (every feature)
Unit tests (success + each error code) · authz tests (role, ownership, DSA) · every async state
handled (loading / empty / error / retry) · money in paise end to end, `formatInr` on display · due
dates shown as dates with the salary-day caption · SoD and working-role scoping server-side · an
audit row for every state change · notifications published, never sent inline · a feature flag where
§14 lists one · no secrets and no PII in logs · typecheck + lint clean.

---

# Appendices

## A. Enumerations

- **LoanStatus**: APPLIED, UNDER_REVIEW, APPROVED, DOCS_SIGNED, BANK_VERIFIED, DISBURSING, ACTIVE,
  REPAID, OVERDUE, IN_COLLECTIONS, CLOSED, DECLINED.
- **PaymentStatus**: PENDING_VERIFICATION, VERIFIED, REJECTED. **PaymentMethod**: UPI,
  BANK_TRANSFER, NACH, PAYU.
- **JourneyService.Step**: START, OTP, SET_PASSWORD, EMPLOYMENT, EMPLOYER, EMAIL, BANK, PAYSLIPS,
  AADHAAR, PAN_CARD, CONSENT, SUBMITTED, DONE.
- **OfferStep**: OFFER_AMOUNT, OFFER_REPAYMENT_DATE, OFFER_DIGILOCKER, OFFER_REFERENCES,
  OFFER_SUMMARY, OFFER_SELFIE, OFFER_ADDRESS, OFFER_SANCTION_LETTER, OFFER_ESIGN, OFFER_SANCTIONED,
  OFFER_DISBURSAL_ACCOUNT, OFFER_MANDATE, OFFER_DONE. OFFER_ESIGN is handled on the sanction-letter
  screen.
- **Document types**:
  - uploadable: BANK_STATEMENT, BANK_PROOF, AADHAAR_FRONT, AADHAAR_BACK (DigiLocker fallback),
    AADHAAR_CARD_FRONT, AADHAAR_CARD_BACK, PAN_CARD_FRONT, PAN_CARD_BACK
  - via the salary step: SALARY_SLIP
  - generated: SANCTION_LETTER, SIGNED_AGREEMENT
  - staff-only: CREDIT_BRIEF, BUREAU_REPORT, REAPPLY_SUPPORT
  - anything else → `UNSUPPORTED_DOC_TYPE`
- **Risk category**: A, B, C, D (staff-only; affects the limit and required checks, never the price).
- **Collections**: DpdBucket UPCOMING, T0_T7, T8_T30, T30_T60, T60_T90, T90_PLUS · SettlementStatus
  PROPOSED, APPROVED, REJECTED · CollectionPaymentKind PART_PAYMENT, FULL_PAYMENT, SETTLEMENT ·
  CollectionPaymentStatus PENDING_HEAD, PENDING_ACCOUNTANT, VALIDATED, REJECTED.
- **Mandate**: PENDING_AUTHORIZATION, ACTIVE, AUTHORIZATION_FAILED (also used for cancelled), EXPIRED,
  BANK_UNSUPPORTED · **MandateDebit**: SCHEDULED, IN_PROGRESS, SUCCEEDED, FAILED, FAILED_TECHNICAL,
  CANCELLED, SKIPPED · trigger SCHEDULED, MANUAL.
- **PayU order**: INITIATED, PENDING, SUCCESS, FAILED, EXPIRED.
- **DSA commission**: ACCRUED, PAYABLE, PAID, VOID · DSA lead status (in the view): NOT_APPLIED,
  APPLIED, IN_PROGRESS, DISBURSED, REPAID, DECLINED.
- **Referral**: PENDING, QUALIFIED, REJECTED · payout PENDING, PAID · beneficiary REFERRER, REFERRED.
- **Blocklist type**: PAN, AADHAAR_REF, PHONE, DEVICE, BANK_ACCOUNT.
- **Notification channel**: IN_APP, SMS, EMAIL, WHATSAPP · delivery PENDING, SENT, FAILED, SKIPPED,
  BOUNCED.

## B. Error codes (business errors are HTTP 422 unless noted)

- Authz / lifecycle: FORBIDDEN_ROLE, FORBIDDEN_OWNER, SOD_VIOLATION, ILLEGAL_TRANSITION,
  ACTOR_NOT_STAFF, LAST_ADMIN, SELF_MODIFY, CREDIT_FILE_NOT_ASSIGNED, NOT_ELIGIBLE, NO_PRIOR_LOAN.
- Disbursement: TXN_REF_REQUIRED.
- Intake: KYC_INCOMPLETE, KYC_HAS_FAILED_CHECK, ACTIVE_APPLICATION, ACTIVE_LOAN, RETURNING_BORROWER,
  NOT_NEW_BORROWER, APPLICATIONS_CLOSED, ONBOARDING_PAUSED, ONBOARDING_OPEN.
- Identity: INVALID_AADHAAR, AADHAAR_LOCKED, AADHAAR_DUPLICATE_UNRESOLVED, DUPLICATE_PAN,
  DUPLICATE_MOBILE, INVALID_DOB, INVALID_UAN, PROFILE_LOCKED, MOBILE_IDENTITY_COLLISION.
- Documents: UNSUPPORTED_DOC_TYPE, UPLOAD_UNAVAILABLE.
- DigiLocker: DIGILOCKER_NOT_READY, DIGILOCKER_UPSTREAM_DOWN, DIGILOCKER_CONSENT_DENIED,
  DIGILOCKER_PROVIDER_ERROR.
- Bureau KBA: BUREAU_CHALLENGE_STALE, BUREAU_CHALLENGE_ANSWER_INVALID.
- Penny drop: PENNY_DROP_LOCKED, PENNY_DROP_FAILED.
- Offer / eSign: AMOUNT_NOT_CHOSEN, BELOW_DRAWN_AMOUNT, REFERENCES_REQUIRED, REFERENCE_IS_SELF,
  REFERENCE_DUPLICATE, REFERENCE_OTP_PENDING, REFERENCE_OTP_INVALID, REFERENCE_OTP_EXPIRED,
  REFERENCE_OTP_LIMIT, REFERENCE_OTP_COOLDOWN, SANCTION_LETTER_MISSING, ESIGN_NOT_STARTED,
  AGREEMENT_NOT_SIGNED, ESIGN_PROVIDER_FAILED, ESIGN_ALREADY_COMPLETED, SIGNATURE_LOCATION_INVALID.
- Mandate: MANDATE_NOT_ENABLED, MANDATE_ATTEMPTS_EXHAUSTED, MANDATE_ALREADY_ACTIVE,
  MANDATE_BANK_UNSUPPORTED, MANDATE_ACCOUNT_UNCONFIRMED, MANDATE_CEILING_OUT_OF_RANGE,
  MANDATE_ACTIVE_NOT_CANCELLABLE, MANDATE_REQUIRED, OUTSIDE_MANDATE_WINDOW.
- Debit: DEBIT_DISABLED, DEBIT_NOT_CONFIGURED, DEBIT_IN_FLIGHT, DEBIT_ATTEMPTS_EXHAUSTED,
  DEBIT_UNCONFIRMED, NO_USABLE_MANDATE, LOAN_NOT_DEBITABLE, AMOUNT_EXCEEDS_MANDATE.
- Repayment / PayU: LOAN_SETTLED, AMOUNT_EXCEEDS_OUTSTANDING, PAYMENT_ALREADY_VERIFIED,
  NOTHING_OUTSTANDING, PAYU_DISABLED, PAYU_NOT_CONFIGURED, PAYU_IN_PROGRESS, PAYU_PAYMENT_RECEIVED,
  MANUAL_PAYMENT_PENDING, ORDER_NOT_IN_REVIEW.
- Discount / reapply: DISCOUNT_OFFER_USED, ALLOWANCE_EXISTS, NOT_DISQUALIFIED.
- Collections: SETTLEMENT_NOT_PENDING, PAYMENT_NOT_PENDING_HEAD, PAYMENT_NOT_PENDING_VALIDATION,
  CASE_NOT_ASSIGNED, LOAN_NOT_COLLECTIBLE.
- Messaging: OUTSIDE_SEND_HOURS, CONFIRMATION_REQUIRED, TEMPLATE_NOT_SENDABLE, TEMPLATE_LOCKED,
  TEMPLATE_PROTECTED, DLT_ID_IN_USE, WHATSAPP_UNAVAILABLE, FEATURE_DISABLED.
- DSA / leads / referral: LEAD_ALREADY_KNOWN, LEAD_NOT_FOUND, LEAD_RATE_LIMITED,
  OUTREACH_RATE_LIMITED, COMMISSION_EXISTS, REFERRAL_DISABLED, SELF_REFERRAL, ALREADY_REFERRED.
- Vendor tools: SKIP_TRACE_NO_IDENTIFIER, SKIP_TRACE_FAILED, BSA_NO_STATEMENTS, BSA_IN_PROGRESS,
  BSA_FAILED.
- Auth: INVALID_CREDENTIALS, SESSION_CONFLICT, TOO_MANY_ATTEMPTS, CAPTCHA_FAILED, NO_PASSWORD_SET,
  WEAK_PASSWORD, INVALID_OTP, OTP_COOLDOWN, OTP_ATTEMPTS_EXCEEDED, INVITE_EXPIRED, EXPIRED_TOKEN.
- Transport (not 422): 401 UNAUTHENTICATED; 403 FORBIDDEN / NETWORK_BLOCKED; 404 not found;
  502 BACKEND_UNREACHABLE (BFF); 503 EXPORT_BUSY.

## C. Reports catalog (43 keys in 10 groups)

| Group (seg) | Keys |
|---|---|
| Business overview (`overview`) | platform-summary, monthly-performance, platform-guide ("How DhanBoost works") |
| Customers (`customers`) | customers, customers-overpaid, customers-not-overpaid, customers-outstanding, customers-repeat, customer-transactions, employer-concentration |
| Disbursement (`disbursement`) | disbursals, disbursement-pending, turnaround-time |
| Repayments & collections (`collections`) | dues-upcoming, collection-efficiency, repayments, repayments-pending, closed-loans, collection-cases, settlements, nach-refunds |
| Risk & portfolio quality (`risk`) | overdue, asset-classification (RBI SMA/NPA), vintage, credit-score-bands, loan-book, written-off |
| Applications & credit (`credit`) | applications, application-funnel, credit-decisions, rejections |
| Staff & productivity (`staff`) | staff, staff-activity, collection-officer-performance |
| Finance & tax (`finance`) | fee-income, income-realised, expenses |
| Compliance & audit (`compliance`) | audit-trail, blocklist |
| Growth & partners (`growth`) | leads, lead-conversion, dsa-commissions, referral-payouts |

Period presets: today, yesterday, this-week, last-week, last-7, this-month, last-month, last-30,
this-quarter, last-quarter, this-fy, last-fy (April–March), all, custom.

## D. Notification types (type — category — channels — audience)

| Type | Category | Channels | Audience |
|---|---|---|---|
| KYC_SUBMITTED | KYC | in-app, email | credit heads, admins |
| KYC_APPROVED, KYC_REJECTED, KYC_REMINDER | KYC | in-app, SMS, email, WA | borrower |
| REBORROW_PREAPPROVED, REBORROW_REVIEW_APPROVED, REBORROW_REVIEW_REJECTED | KYC | in-app, SMS, WA | borrower |
| KYC_REOPENED_RESCORE | KYC | in-app, email, WA | borrower |
| WAITLIST_RECEIVED, BUREAU_QUESTION_PENDING, VERIFICATION_STEP_LINK | KYC | in-app, email | borrower |
| LOAN_APPLIED, CREDIT_RECOMMENDED | CREDIT | in-app | credit heads |
| CREDIT_ASSIGNED | CREDIT | in-app | assigned executive |
| CREDIT_APPROVED | CREDIT | in-app, email | borrower, disbursement heads, admins |
| CREDIT_REJECTED, LOAN_SANCTIONED | CREDIT | in-app, SMS, email, WA | borrower |
| SANCTIONED_AMOUNT_REVISED, LOAN_LIMIT_REVISED | CREDIT | in-app, email, WA | borrower |
| DISBURSEMENT_FAILED | DISBURSEMENT | in-app | disbursement heads |
| DISBURSEMENT_REJECTED, SANCTION_LETTER_SIGNED | DISBURSEMENT | in-app, email, WA | borrower |
| LOAN_DISBURSED | DISBURSEMENT | in-app, SMS, email, WA | borrower |
| REPAYMENT_RECORDED | REPAYMENT | in-app, WA | accountants, borrower |
| REPAYMENT_VERIFIED | REPAYMENT | in-app, SMS, WA | borrower |
| REPAYMENT_REJECTED, PAYMENT_DUE_SOON, PAYMENT_OVERDUE, LOAN_CLOSED | REPAYMENT | in-app, SMS, email, WA | borrower |
| COLLECTION_CASE_OPENED | COLLECTIONS | in-app | collection heads + executives |
| SETTLEMENT_PROPOSED, COLLECTION_PAYMENT_TO_APPROVE | COLLECTIONS | in-app | collection heads |
| SETTLEMENT_APPROVED | COLLECTIONS | in-app, SMS, WA | borrower |
| SETTLEMENT_REJECTED | COLLECTIONS | in-app | the proposer |
| COLLECTION_PAYMENT_TO_VALIDATE | COLLECTIONS | in-app | accountants |
| COLLECTION_PAYMENT_VALIDATED, COLLECTION_PAYMENT_REJECTED | COLLECTIONS | in-app | the raiser + collection heads |
| APPLICATION_CANCELLED | SYSTEM | in-app, WA | borrower |
| PROVIDER_BALANCE_EXHAUSTED, PROVIDER_CAPABILITY_DOWN | SYSTEM | in-app, email | admins |
| MANDATE_BANK_UNSUPPORTED, REFERRAL_PAYOUT_PENDING | SYSTEM | in-app | disbursement heads |
| MANDATE_SETUP_INVITE | SYSTEM | in-app, SMS, email, WA | borrower |
| MANDATE_CANCELLED | SYSTEM | in-app, email | borrower |
| REFERRAL_REWARD_CREDITED | SYSTEM | in-app, SMS, WA | borrower |
| STAFF_INVITED, STAFF_CREATED, STAFF_DISABLED | STAFF_IAM | email | the staffer |
| STAFF_ROLE_CHANGED | STAFF_IAM | in-app, email | the staffer |

Plus an eSign-document invite/outcome event for external signers.

## E. Configuration (names only — never commit values)

**Backend**: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `AUTH_SECRET`, `AUTH_TTL_SECONDS`,
`BORROWER_AUTH_TTL_SECONDS`, `NAVIX_ENV`, `NAVIX_APP_BASE_URL`, `NAVIX_ALLOW_DEV_DEFAULTS`,
`NAVIX_VERIFICATION_CHAIN`, `NAVIX_VERIFICATION_BUREAU_NO_HIT_FALL_THROUGH`, `NAVIX_BUREAU_FIXTURE`,
`SIGNZY_*`, `DIGITAP_*`, `FINTRIX_*`, `NAVIX_STATIC_EGRESS_IP`, `NAVIX_ESIGN_PROVIDER`,
`NAVIX_ESIGN_CALLBACK_URL`, `NAVIX_ESIGN_CALLBACK_SECRET`, `NAVIX_ESIGN_COORDINATE_ORIGIN`,
`NAVIX_DIGIO_*` (`PROVIDER`, `BASE_URL`, `CLIENT_ID`, `CLIENT_SECRET`, `WEBHOOK_SECRET`,
`CORPORATE_CONFIG_ID`, `CORPORATE_ACCOUNT_NUMBER`, `NARRATION`, `EXPIRE_IN_DAYS`, `DEBIT_CRON`),
`NAVIX_PAYU_*` (`KEY`, `SALT`, `CHECKOUT_URL`, `COMMAND_URL`, `RETURN_URL`, `ALLOW_SANDBOX`,
`CARD_FEE_BPS`, `RECONCILE_CRON`), `NAVIX_S3_BUCKET`, `NAVIX_S3_PRESIGN_TTL`, `NAVIX_S3_KMS_KEY_ID`,
`NAVIX_SMS_*` (incl. `MOCK`, `MOCK_CODE`, `SENDER_ID`, `PEID`, per-type `DLT_<TYPE>`,
`OTP_TEMPLATE`), `NAVIX_WHATSAPP_*`, `NAVIX_EMAIL_PROVIDER` (log|smtp|ses|resend), `NAVIX_EMAIL_FROM`,
`NAVIX_EMAIL_ENABLED`, `RESEND_API_KEY`, `NAVIX_SES_CONFIG_SET`, `NAVIX_SES_EVENTS_ENABLED`,
`NAVIX_SES_EVENTS_QUEUE`, `NAVIX_CAPTCHA_SECRET`, `NAVIX_CAPTCHA_HOSTNAMES`, `NAVIX_REMINDERS_CRON`,
`NAVIX_EMPLOYMENT_RETRY_CRON`, `NAVIX_PROVIDER_HEALTH_CRON`, `NAVIX_REFERRAL_ENABLED`,
`NAVIX_REFERRAL_REWARD_PAISE`, `NAVIX_NOTIF_*`, `NAVIX_LOG_LEVEL`.

**Frontend**: `BACKEND_BASE_URL` (server-only), `NEXT_PUBLIC_API_BASE_URL`,
`NEXT_PUBLIC_TURNSTILE_SITE_KEY` (deploy before the backend secret), `NEXT_PUBLIC_GA_ID`,
`GOOGLE_SITE_VERIFICATION`, `PROXYCHECK_API_KEY`, `NETWORK_GUARD_ALLOWED_COUNTRIES`,
`NETWORK_GUARD_DISABLED`, `SIM_LOGIN_EMAIL`, `SIM_BACKEND_BASE_URL`, `SIM_GATEWAY_KEY`,
`E2E_BASE_URL`.

## F. Deliberately not reproduced
- Legacy modules `navix-onboarding` / `navix-kyc` and the non-live parts of `navix-income-risk`.
- The retired disbursement-request chain.
- Redirect-only stub routes and unused components.
- An inbound path into `DISBURSEMENT_FAILED` (none exists today).
- The historical migrations.

Known product gaps that stay out of scope: a real NEFT/IMPS payout API, a Digio webhook receiver, UPI
AutoPay, mandate amendment, distributed scheduler locks, FK constraints, and PII-at-rest encryption.

## G. Known defects in the current platform — do NOT reproduce

Found while extracting the use cases (details and code references in the `§Z` / "Contradictions"
section of each `docs/prd/` file). The rebuild implements the **intended** behaviour on the right.

| # | Current behaviour (defect) | Build this instead |
|---|---|---|
| D1 | **Fixed 2026-11-13.** `LoanService.disburse` recomputed the due date from the salary day at disbursal and ignored `approved_repayment_date` | book the loan on the approved (signed) repayment date; fall back to the salary formula only when that date has passed |
| D2 | Credit vs disbursement SoD is role-only (`actorOf` unused); ADMIN can record and verify the same repayment, raise and validate the same collection payment | actor-level checks: recorder ≠ verifier, raiser ≠ validator, outside ADMIN's explicit solo mode |
| D3 | **Fixed 2026-11-13 (V103).** The blocklist screened nothing | one block: a staff block OR an active entry on any identifier refuses new applications/reborrows; block/unblock from either screen |
| D4 | Referral codes cannot be redeemed (no screen calls apply/validate) | a code field at signup + live validation |
| D5 | `DISBURSEMENT_FAILED` has no inbound transition; its tab, segment and retry endpoint are dead | either a "transfer failed" action into it, or drop the state |
| D6 | Reminder cron runs at 09:00 server time (UTC), i.e. 14:30 IST; the sweep stops scanning a loan once it is IN_COLLECTIONS | schedule in `Asia/Kolkata`; keep reminding through the overdue window |
| D7 | The bureau-question path (`/credit-question` → `/loan/status`) never calls submit-kyc, leaving the file in DRAFT | submit after the question is answered or skipped |
| D8 | Address-proof uploads on `/signup/submitted` reach S3 but are never recorded as documents | record them as documents staff can see |
| D9 | NACH credits are stored with method `BANK_TRANSFER` | store `NACH` |
| D10 | Nothing caps the sanctioned amount or the ADMIN limit override (₹10,00,000 caps only the formula) | cap both at the max loan |
| D11 | Applications never move to OVERDUE / DEFAULTED / WRITTEN_OFF (only the loan goes to IN_COLLECTIONS) | mirror the loan's delinquency onto the application status |
| D12 | Confirming the unchanged salary account still runs the ₹1 penny drop, and a provider error counts as a strike | skip the drop for the verified salary account; don't count provider errors |
| D13 | Several UI permission gates disagree with backend checks (e.g. bulk reject offered to Collection Head; Collection Head working role lacks `collections:interact`) | one shared permission matrix, tested both sides |
| D14 | Settlements dashboard and hardship/revised plan exist only as untracked UI / an unused table | build them fully or leave them out |
| D15 | The KFS PDF says "no auto-debit mandate" even when `digio-mandate` is on | render the mandate clause from the flag |
