# DhanBoost — backend API surface

> Extracted from `CLAUDE.md` (2026-08-24) to keep the onboarding doc small. Reference material:
> read it when you need it, not on every session. Rules and invariants stay in `CLAUDE.md`.

> ⚠️ The controllers are the source of truth; this is a map, not a contract.

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
> - **Email OTP (both addresses).** `POST …/verify/email/otp` + `…/verify/email/otp/confirm` (PERSONAL)
>   and `POST …/verify/official-email/otp` + `…/verify/official-email/otp/confirm` (OFFICIAL/work).
>   Inbox control, distinct from `…/verify/email`, the provider deliverability + employer-match check
>   that runs against the *same* work address. Both write their own check type (`EMAIL_OTP` /
>   `OFFICIAL_EMAIL_OTP`), both deliberately outside `REQUIRED` and `KNOWN_CHECKS`, and the address is
>   always resolved server-side from the saved profile. Screen 6 is gated on them in
>   `JourneyService.emailsSettled`, **not** in `submit-kyc` — a work address we could not deliver to is
>   recorded `REVIEW` and waved through to the credit team (revamp.md decision 10), while an
>   unreachable personal address must be replaced.
> - **Payment block:** `GET /api/payment-settings` (any authed; presigned QR/PDF URLs), `PUT` (ADMIN).

| Method + path | Role | Purpose |
|---|---|---|
| `POST /` | borrower | create DRAFT |
| `POST /reborrow` | borrower | returning borrower: new advance reusing saved profile → `PRE_APPROVED` (→ `SANCTIONED` when a prior sanction carries over) / auto-`REJECTED` on a disqualifying history |
| `GET /?status=` · `GET /stats` | staff | list by status (stage queues) · per-status counts |
| `GET /credit-queue` · `GET /credit-executives` | CREDIT_HEAD | the credit queue · assignable **ACTIVE** executives |
| `GET /{id}` · `GET /{id}/events` | any | read application / audit trail |
| `GET /mine` | BORROWER | the caller's own applications, newest-first (backs the account-menu `/loans` + `/transactions`) |
| `GET /{id}/journey` · `POST /{id}/journey/{step}` | BORROWER | server-side intake resume pointer · advance a Phase-1 step |
| `POST /{id}/submit-kyc` | BORROWER | DRAFT → KYC_PENDING (gated on completeness) |
| `POST /{id}/self-employed` | BORROWER | intake gate: self-employed → REJECTED + 90-day block |
| `POST /{id}/kyc-decision` | credit roles / ADMIN | approve/reject KYC |
| `POST /{id}/apply` | BORROWER | set amount/purpose/salaryDay (from `KYC_APPROVED` or `PRE_APPROVED`) |
| `POST /{id}/assign` | CREDIT_HEAD | assign executive → CREDIT_EXEC_PENDING (self or an ACTIVE executive; reassignment keeps the stage) |
| `POST /{id}/sanction` | CREDIT_EXECUTIVE / CREDIT_HEAD | **the final credit decision**: sanctioned amount + repayment date → `SANCTIONED` |
| `POST /{id}/reject-lead` · `POST /{id}/mark-pending` | CREDIT_EXECUTIVE / CREDIT_HEAD | reject (30-day cooling-off) · park the file and ask for a document |
| `POST /{id}/accept-offer` | BORROWER | accept the sanctioned offer (end of the offer journey) |
| `POST /{id}/disbursement-decision` | DISBURSEMENT_HEAD | release with `txnRef` → DISBURSED→ACTIVE (no `txnRef` → `TXN_REF_REQUIRED`) / reject |
| `POST /{id}/force-disbursement-pending` | ADMIN | force `SANCTIONED` → `DISBURSEMENT_PENDING` |
| `POST /{id}/retry-disbursement` | DISBURSEMENT_HEAD | failed → DISBURSEMENT_PENDING |
| `GET /rejections?reason=` · `GET /telecalling` | staff | rejection register · the telecalling queue |
| `POST /{id}/verifications/{checkType}/retry` | staff (`verification:retry`) | re-run one provider check |
| `POST /{id}/cancel` | borrower/staff | → CANCELLED (pre-disbursement) |
| `PUT /{id}/profile` · `GET /{id}/profile` | borrower writes · any reads | applicant KYC details (PAN masked on read; the staff-only credit score/★ rating are **stripped** for a borrower reading their own profile) |
| `POST /{id}/documents` · `GET /{id}/documents` · `GET /{id}/documents/{docId}` | borrower uploads · any reads | documents (base64; metadata list + content for view/download) — the auto-generated `CREDIT_BRIEF` PDF rides this list |
| `GET /{id}/credit-brief` | staff only | bureau credit brief: 1–5★ rating + categorized facts (A/B/C) + summary + the `CREDIT_BRIEF` PDF doc id (`CreditBriefView`); borrower/anonymous → `FORBIDDEN_ROLE` |

### Offer journey (`/api/applications/{id}/offer`) — what the borrower walks while `SANCTIONED`

| Method + path | Role | Purpose |
|---|---|---|
| `POST /amount` | BORROWER | choose an amount within the sanctioned ceiling |
| `GET\|POST /references` | BORROWER writes · staff/ADMIN correct | the two references (ADMIN may correct them after the borrower has moved on) |
| `GET /summary` | BORROWER | the full offer: net disbursed, repayment date, total repayable |
| `POST /sanction-letter` | BORROWER | render + store the Key Fact Statement PDF (`SANCTION_LETTER`, S3) |
| `POST /esign/init` · `GET /esign/status` · `POST /esign` | BORROWER | Aadhaar eSign via Signzy's Contract API (redirect + poll) · the manual drawn-signature fallback (§14) |
| `GET\|POST /disbursal-account` | BORROWER | the account the money goes to; confirming it routes the file to `DISBURSEMENT_PENDING` |

### Staff performance & registers

| Method + path | Role | Purpose |
|---|---|---|
| `GET /api/staff/decisions` (+ `/summary`, `/inspectable`) | staff | decision history · per-employee performance totals · who the caller may inspect |
| `GET /api/loans` | ADMIN / COLLECTION_HEAD | the Loans register (`loan:register`) |
| `GET /api/dashboard/trends?days=` | staff | dashboard trend series |
| `GET /api/admin/bureau-backfill/preview` · `POST /execute?cohort=` · `POST /reject-sub-floor?limit=` | ADMIN | bureau rescore backfill (V62): preview a cohort · run it · sweep the sub-floor backlog |

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

### DSA portal (`/api/dsa`) and DSA administration (`/api/admin/dsa`)

`/api/dsa/**` is gated to the **`DSA` role only** (ADMIN oversight goes through `/api/admin/dsa`, so
the portal's "owner comes from the JWT" rule has no exception). Ownership is **always** resolved from
`ActorContext`, never from a request parameter, and a foreign lead id returns `LEAD_NOT_FOUND` rather
than `FORBIDDEN` so the endpoint is not an existence oracle. Attribution is by **PAN**.

| Method + path | Purpose |
|---|---|
| `POST /api/dsa/leads` | add a lead (PAN + name + mobile required). A PAN already held by another DSA **or** by an existing customer → a single generic `LEAD_ALREADY_KNOWN` (the two cases are indistinguishable); the attempt is logged to `dsa_lead_rejection` |
| `GET /api/dsa/leads` · `GET /{id}` · `PUT /{id}` | only the caller's own leads. The view echoes back what the DSA typed + a **coarse** conversion status (`NOT_APPLIED/APPLIED/IN_PROGRESS/DISBURSED/REPAID/DECLINED`) + net disbursed + commission — nothing read out of KYC, and **never a second loan** |
| `POST /api/dsa/leads/{id}/outreach` | SMS (fixed DLT template) or email (DSA-authored subject/body); rate-limited, every send audited to `lead_outreach` |
| `GET /api/dsa/commissions` · `GET /api/dsa/earnings` | own commission rows · totals |
| `GET /api/admin/dsa` (+ `/leads`, `/commissions`, `/outreach`) | ADMIN registers and roll-ups |
| `POST /api/admin/dsa/commissions/{id}/{pay,void,reassign}` · `POST /commissions` | ADMIN settles (txn id), voids, reassigns on dispute, or creates one manually — all appended to `dsa_commission_event` |

**Commission lifecycle:** `ACCRUED` at disbursal (`ApplicationFlowService.finalizeDisbursal`, beside
the referral hook) → `PAYABLE` when the loan fully repays (`RepaymentService.recomputeOutstanding`) →
`PAID` by ADMIN. It goes **`VOID`** instead on an approved settlement (a
`@TransactionalEventListener` on the existing `SettlementApprovedEvent`), a default, or a write-off.
Rate is `DSA_COMMISSION_RATE_BPS = 350`, **snapshotted per row** so changing it never rewrites history.

> ⚠️ **SMS outreach does not deliver yet.** No `DHANBOOST_DSA_LEAD_INVITE_V1` DLT template is
> registered, so live sends fail `006 Invalid template text` and are recorded `FAILED`. Email works.

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
| `POST /api/applications/{id}/verifications/{checkType}/decision` | credit roles / ADMIN | manual PASS/FAIL override (provider MANUAL, audited) |
| `GET /api/applications/verifications/overview` | staff | cross-application rows + status tallies |
| `POST /api/applications/{id}/send-reminder` | credit roles / ADMIN | nudge the borrower on outstanding steps (no-op when nothing pending) |
| `PUT /api/applications/{id}/profile/self` | BORROWER | self-edit non-identity profile fields (may invalidate the matching verification → re-verify) |
| `GET\|PUT /api/preferences` | BORROWER | notification settings (opt-out suppresses SMS/EMAIL, never IN_APP) |
| `GET\|PUT /api/staff/me` | staff | staff self-profile (role/status stay ADMIN-only); `PUT` also toggles `emailOptIn` (operational-email opt-out, null-guarded so a partial PUT leaves it untouched — STAFF_IAM account/security mail is never suppressible) |
| `GET/POST/DELETE /api/admin/expenses` (+`/{id}`) | ADMIN | company-expense ledger (+ receipt S3 keys) |
| `GET /api/applications/all` | ADMIN | full register of every application (complete + incomplete) |
| `GET /api/feature-flags` | any authed | dev-only flag states `{key: enabled}` for UI gating — **read-only, no write path** (flags change only via SQL, §12) |
