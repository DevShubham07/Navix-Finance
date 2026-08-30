# DhanBoost — Flyway migration catalog

> Extracted from `CLAUDE.md` (2026-08-24). The migration files themselves are the source of truth —
> each carries a header comment explaining *why*. This table is the index.

They live in `backend/navix-app/src/main/resources/db/migration/` and Flyway applies them on boot.

| Migration | What |
|---|---|
| `V1__init.sql` | no-op placeholder |
| `V2__core_schema.sql` | 21 tables + indexes + enum CHECK constraints (the real base schema) |
| `V3__loan_money_to_paise.sql` | loan money columns → `BIGINT` paise |
| `V4__money_to_paise_rest.sql` | remaining money columns → `BIGINT` paise |
| `V5__application_state_machine.sql` | `loan_application.status` → `ApplicationStatus`; add `purpose`, `assigned_executive_id`, `loan_id`; create `application_event` audit table |
| `V6__loan_application_amount_nullable.sql` | `amount_requested` nullable (DRAFT has no amount yet) |
| `V7__loan_application_salary_credit_day.sql` | add `salary_credit_day` |
| `V8__staff_roles_rename.sql` | role check-constraint + data: COLLECTION_HEAD / COLLECTION_EXECUTIVE / +DEVELOPER (DEVELOPER dropped in **V61**) |
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
| `V35`–`V43` | **V35** drop `customer_profile.aadhaar` · **V36** `customer_remark` · **V37** backfill `eligible_limit` to 25% · **V38** rebrand seed data → DhanBoost · **V39** drop the legacy `disbursement_request`/`approval_step` tables · **V40** `borrower_mobile_registry` · **V41** `customer_owner` + `customer_call_log` · **V42** `TELECALLER` role · **V43** `lead` |
| `V44`–`V47` | the **revamp Phase 1–4** set: **V44** Phase-1 onboarding (`journey_step`, server-side resume) · **V45** Phase 2 — **deletes `KYC_APPROVER`** (holders → `CREDIT_EXECUTIVE`), adds `SANCTIONED` + the credit-sanction columns, retires the credit maker-checker and `REVIEW_PENDING` · **V46** Phase 3 offer journey (`OfferStep`, references, eSign) · **V47** Phase 4 collections + payments |
| `V48__retire_accountant_disbursement_hop.sql` | **removes the accountant from disbursement**: files parked at `ACCOUNTANT_PENDING` are moved back to `DISBURSEMENT_PENDING` (each with an `application_event` row); the status stays in the enum/CHECK as **history only** |
| `V49`–`V54` | **V49** `provider_api_execution` (workbench call history) · **V50** `loan.closed_on` · **V51** `payment.rejection_reason` · **V52** `customer_profile.personal_email_verified` · **V53** `loan_application.created_at` · **V54** staff session registry |
| `V55__dsa_role_and_commission.sql` | **DSA** role (+ CHECK constraints + demo persona) · `lead.{owner_dsa_id, pan}` with a PAN-format CHECK and a **partial unique index on `pan` where `owner_dsa_id is not null`** (the cross-DSA duplicate guard) · `dsa_commission` (unique on `lead_id` → one commission per lead, i.e. first loan only) · `dsa_commission_event` (ADMIN override audit) · `dsa_lead_rejection` (PAN-enumeration audit) · `lead_outreach` (SMS/email send audit) |
| `V56__application_document_password.sql` | `application_document.file_password` — the borrower's optional key for a password-protected upload (bank statements / payslips), captured on the two signup upload screens and shown plainly to reviewing staff. Not a credential; never logged. |
| `V57__provider_api_execution_live_calls.sql` | extend `provider_api_execution` with `source` (MANUAL/LIVE) + `endpoint`/`http_status`/`check_type`/`request_id` — **every real** Signzy/Digitap/Fintrix call now lands in the ADMIN Provider API dashboard, joinable to a CloudWatch line by the MDC `requestId` |
| `V58__customer_call_log_loan.sql` | `customer_call_log.loan_id` (nullable) — tag a call with the loan it was about; omitted = a customer-level note, as before |
| `V59__staff_activity_attribution.sql` | real staff ids on `payment` (verify/reject), `interaction_log` and `customer_call_log` — `BaseAuditEntity.created_by` holds a mutable *display name* and can never key a per-employee aggregation |
| `V60__staff_activity_call_indexes.sql` | `(staff_id, created_at)` indexes on both call-log tables for the staff-performance dashboard |
| `V61__drop_developer_role.sql` | drop the `DEVELOPER` role — holders become `ADMIN`+`DISABLED` (not deleted, so historical `actor_id` rows stay resolvable) |
| `V62__bureau_backfill.sql` | `bureau_backfill_row` — one row per application per backfill run (cohort + outcome: REOPENED / REFRESHED / STILL_BELOW / NO_BRIEF / MISMATCH_REVIEW / FAILED / SKIPPED) |
| `V63__staff_email_preference.sql` | `staff_user.email_opt_in` (default true) — a staffer's opt-out of their own operational email. STAFF_IAM account/security mail ignores it |
| `V64__suspend_bureau_auto_reject.sql` | **suspends the credit-score auto-reject** (`bureau-auto-reject` flag, read with `defaultWhenMissing = FALSE`). The 600→550 floor move on the Experian→CRIF switch tripled live rejections (CRIF's median sits at 510); every bureau result now goes to a human until the floor is recalibrated |
| `V65__official_email_otp_verified.sql` | `customer_profile.official_email_otp_verified` — proves the borrower can open the *work* inbox, deliberately **not** named `official_email_verified` (that already means the provider deliverability + employer-match check on the same address). Also backfills `personal_email_verified` onto reborrow-cloned profiles |
| `V66__customer_profile_uan.sql` | `customer_profile.uan` — optional 12-digit EPFO UAN. When present it gives the Digitap `uan_basic` employment lookup its direct method (method 3) instead of the PAN/mobile fallback; EMPLOYMENT stays advisory either way |
| `V67__lead_pincode.sql` | `lead.pincode` (+ format CHECK) — the admin CSV lead import (§ ADMIN CSV lead import) carries a postal pincode, which no lead column held; additive only, telecaller/DSA leads keep null |
