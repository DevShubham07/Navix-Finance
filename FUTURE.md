# FUTURE.md — Go-live roadmap (deferred work)

The forward plan for taking NAVIX to production. This file owns only the **not-yet-built** items.
Completed work is recorded in `handoff.md` §15 and `CLAUDE.md` §2.

> Companion docs: `CLAUDE.md` (onboarding / current state), `handoff.md` (execution plan + change
> log, esp. **§15** = the migration record), `dfd.md` (authoritative lifecycle + roles).

> ## ⚑ Update 2026-06-27 — P0–P8 production migration SHIPPED (most of A/B/C is now 🟢)
>
> The `finalplan.md` migration + the OTP integration landed and were **live-verified against AWS RDS,
> S3, the Fintrix sandbox, and the JWT auth chain** (see `handoff.md` §15). This supersedes large parts
> of the roadmap below — read the per-section 🟢 notes:
> - **A (auth):** 🟢 **Real JWT + Spring Security.** `JwtAuthFilter` replaced `DemoActorFilter`;
>   `SecurityConfig` requires a bearer on `/api/**` (401 otherwise); `AuthController` issues staff
>   (BCrypt vs `staff_user.password_hash`, V17) + borrower (OTP) JWTs; the BFF stores the JWT cookie +
>   forwards `Authorization: Bearer` and no longer injects `X-Demo-Actor-*`. **Remaining:** emailed
>   invites (A1), DLT-registered real SMS (A2 — code done, see below), middleware JWT-signature verify (A5).
> - **A2 (borrower OTP):** 🟡→🟢 **code complete.** Real UltronSMS gateway client + `BorrowerOtpService`
>   (random, single-use, TTL, attempt-cap). **Blocked on a DLT-registered template** (the gateway returns
>   `Invalid template text` for unregistered content — a TRAI requirement only the account owner can
>   fulfil). A **mock mode** (`NAVIX_SMS_MOCK=true` → fixed `123456`, no SMS) is wired for demo/testing.
> - **B1 (secrets):** 🟢 datasource + storage + Fintrix/DigiLocker + SMS creds in **SSM SecureString**.
> - **B2 (S3 docs):** 🟢 presigned PUT/GET + server-side ingest; `application_document.s3_object_key`
>   (V15). _Remaining:_ drop the legacy `bytea` column.
> - **C1 (Fintrix):** 🟢 real `pan_comprehensive`/email/address/penny-drop/liveness + Experian→CRIF.
> - **C2 (DigiLocker):** 🟢 real init→poll→complete + Aadhaar XML/PDF→S3 ingest.
> - **C3 (penny-drop):** 🟢 (the bank-account verify gate). _Remaining:_ the real NEFT/IMPS **payout**.
> - **New: 9-step verified onboarding** (`/verify/*`), **admin payment block**, **mock layer removed**,
>   and a **test suite** — `QA_CHECKLIST.md` (manual), ~136 backend tests + Playwright E2E (`frontend/e2e`),
>   `.github/workflows/ci.yml`.
>
> **Still genuinely deferred:** A1 (emailed invites + ADMIN-gated create), A2-DLT (real SMS delivery),
> A5 (verify JWT signature in middleware), C3-payout (real money movement), **D1 FK / D3 identity / D4
> PII**, and **E (compliance)**. The detailed sections below are kept for those.

> **Update 2026-06-25 — correctness & RBAC hardening pass** ([`PRODUCT_GAPS.md`](PRODUCT_GAPS.md)):
> several go-live items were partially hardened ahead of their full builds — `DemoActorFilter` now
> fails closed as `ANONYMOUS` (A3), the role switcher authenticates for real (A4), the middleware
> cookie mismatch is fixed (A5), UI RBAC gating + `cancel`/settlement role guards landed (A6), and the
> signup document upload is now a real file (B2). Money/lifecycle **bugs** found in the same pass
> (penalty-aware outstanding + closure, OVERDUE-on-read, closed-case filtering) are fixed in code and
> recorded in `CLAUDE.md` §2/§9 — not tracked here. **Still deferred:** full JWT/Spring Security (A)
> and emailed invites (A1). (The reborrow endpoint, D5, is now **done** — returning-borrower
> pre-approval + past-delinquency review gate, Flyway V14.)

> **Update 2026-06-27 — borrower account menu + ADMIN per-step control + per-stage demo-data
> populator.** Demo-layer features with no new go-live surface except A6 below: a borrower **account
> menu** (`/loans`, `/transactions`, `/support`, `/settings`) backed by `GET /api/applications/mine`;
> **ADMIN per-step control** (admin exempt from the credit SoD + active-executive `assign`, plus an
> "Assign to me" button in the console — see A6); and a **per-stage demo-data populator**
> (`scripts/populate-demo-data.ps1` / `populateDummyData.md`).

> ## ⚑ Update 2026-06-28 — bureau credit brief + 1–5★ rating + one-page PDF SHIPPED 🟢
>
> The bureau pull now harvests the **full** Experian report → a 1–5★ "should we recommend" rating, a
> NAVIX-branded one-page **PDF** (S3 + `CREDIT_BRIEF` document), and a score/★ headline on every staff
> detail surface (see `CLAUDE.md` §2). Staff-only (stripped from borrower paths). Lives behind the
> existing seams — no new go-live workstream. Genuinely-deferred **follow-ups** from this build:
> - **F1 — PDF ₹ glyph (cosmetic):** the brief prints `Rs` because the OpenPDF base-14 fonts have no
>   `₹` (U+20B9) glyph. Embed a Unicode TTF (e.g. Noto Sans) to render `₹` in the PDF (the web UI
>   already shows `₹`). _Done when:_ the PDF renders the rupee symbol.
> - **F2 — reborrow brief inheritance:** a `PRE_APPROVED` reborrow skips the bureau pull, so it has no
>   brief of its own; the staff brief is per-application. Surface the applicant's **latest** brief on
>   reborrow surfaces (fall back to `latestProfileForApplicant`). _Done when:_ a reborrow row shows the
>   prior brief.
> - **F3 — `CreditBriefDemoTest` is a non-asserting runner** (`navix-app`) that prints + writes
>   `target/credit-brief-sample.{txt,pdf}` on every `mvn test`. Relocate behind a tag/profile or delete.
> - **F4 — externalize rating thresholds:** the bands/penalties/cap are hardcoded in
>   `CreditRatingCalculator`; move to config if product wants to tune A/B/C weights without a rebuild.
> - **F5 — (optional) richer underwriting store:** only the summary facts JSON is persisted
>   (`applicant_profile.credit_brief_facts`). Persist per-account CAIS / DPD history (or a
>   `credit_assessment` table) if deeper underwriting/audit is needed.

> ## ⚑ Update 2026-06-28 — notification engine (in-app + SMS + email) SHIPPED 🟢
>
> A foundational, extensible **`navix-notification`** module is live: domain events →
> `@Async` `AFTER_COMMIT` listener → dispatcher → per-recipient `notification` rows + per-channel
> `notification_delivery`, surfaced by a shared **bell** on both the borrower and staff surfaces
> (`GET /api/notifications`). Lives behind clean seams (events in navix-common; the `SmsGateway`/
> `EmailClient` ports), so going live is again a **swap, not a rewrite**. Genuinely-deferred
> **follow-ups** from this build:
> - **G1 — real email delivery:** `EmailClient` ships with `LogEmailClient` (logs, mock ref);
>   `SmtpEmailClient` is wired but **off** (`navix.email.provider=log`). Flip to `smtp` + set
>   `spring.mail.*` (or swap in SES) to send for real. _Done when:_ a real email lands for a borrower
>   `KYC_APPROVED` / `LOAN_DISBURSED`. (Parallels the A2 SMS/DLT gate — the SMS channel rides the same
>   UltronSMS path and is likewise blocked on the DLT template.)
> - **G2 — push instead of poll:** the bell polls every 20s; move to SSE/WebSocket (or web-push) for
>   instant delivery + lower load. _Done when:_ the badge updates without a poll.
> - **G3 — delivery retry / outbox:** a `FAILED` `notification_delivery` is recorded but **not retried**.
>   Add a scheduled retry (or transactional-outbox) for transient SMS/email failures.
> - **G4 — recipient preferences + digest:** no per-user channel opt-out or quiet-hours/digest batching
>   yet; every type fires on every eligible channel. Add a preferences table + a daily staff-queue digest.
> - **G5 — drop the legacy `applicant_profile.email` nullability / unify contact identity:** email is a
>   nullable add-on (V22); fold it into the D3 applicant-identity unification.

Status legend: 🔴 not started · 🟡 partial scaffold exists · 🟢 done (moves out of this doc).

---

## 0. Swap seams — all shipped ✅

The demo was deliberately built so that going live is mostly **swapping adapters at known seams**,
not rewriting business logic. All core seams have been swapped.

| Seam | Was (demo) | Now (production) | Domain code that did NOT change |
|---|---|---|---|
| **Identity** ✅ | `DemoActorFilter` reads `X-Demo-Actor-*` | `JwtAuthFilter` validates the bearer → **same** `CurrentActor` | `ApplicationFlowService.requireRole` / SoD |
| **BFF session** ✅ | plain JSON cookies | JWT in the httpOnly cookie; `bff-proxy.ts` forwards `Authorization: Bearer` | the typed client `applications.ts`, all pages |
| **Doc storage** ✅ | `ApplicationDocument.data` = `bytea` | `s3_object_key` + presign (port `DocumentStoragePort`) | `ApplicantReviewService` callers, the review UI |
| **Salary/verify** ✅ | mock in `navix-verification` | real Fintrix clients behind `VerificationPort` | risk/eligibility callers |
| **KYC** ✅ | mock | real DigiLocker client (init→poll→complete) | the verification service callers |
| **Config/secrets** ✅ | local env | **SSM Parameter Store** (spring-cloud-aws import) | application code (reads via `@Value`/config) |

---

## 1. Remaining deferred work at a glance

| # | Workstream | Remaining | Priority |
|---|---|---|---|
| A | Auth hardening | emailed invites (A1), real SMS/DLT (A2), middleware JWT verify (A5), RBAC test matrix (A6) | P0 |
| B | AWS infrastructure | drop legacy `bytea` column (B2), deploy/CI pipeline (B3), observability (B4) | P1 |
| C | External integrations | real bank payout NEFT/IMPS (C3) | P1 |
| D | Data model hardening | FK constraints (D1), unify applicant identity (D3), PII encryption (D4) | P2 |
| E | Compliance & product alignment | lending model, copy, agreements, reporting | P2 (regulatory gate) |

---

## A. Auth hardening 🟡 — P0

JWT + Spring Security is live. `JwtAuthFilter` validates the bearer; `AuthController` issues staff
(BCrypt) + borrower (OTP) JWTs; the BFF forwards `Authorization: Bearer` and no longer injects
`X-Demo-Actor-*`. What remains:

### A1. Staff emailed invites 🟡
- **Now:** the admin **invites** + **activate** UI is wired end-to-end (`adminApi` → `/api/staff/invites`
  → `InviteService`), but **demo-grade**: `accept` takes `{token, name}` — **no password, no email, no
  ADMIN gate** on `create`; the one-time token is shown in the UI instead of emailed.
- **Target:** invite carries email+role (admin-issued); activation sets a **password** (BCrypt);
  invite link **emailed** (SES); `create` restricted to `ADMIN`.
- **Steps:** add `password_hash` to `StaffUser` (Flyway); `accept` → `{token, password, name}` hashes
  with `BCryptPasswordEncoder`; gate `POST /api/staff/invites` with `@PreAuthorize("hasRole('ADMIN')")`;
  send the invite link via SES (template + one-time token URL).
- **Touchpoints:** `InviteService`, `StaffUser`, `InviteController`, new `email` module/util.
- **Done when:** an admin invites by email → invitee sets a password → can authenticate; a
  non-admin cannot create invites.

### A2. Borrower OTP — DLT template registration 🟡
- **Code complete.** Real UltronSMS client + `BorrowerOtpService` (random, single-use, TTL,
  attempt-cap) are implemented. Mock mode (`NAVIX_SMS_MOCK=true` → `123456`) is wired for demo/testing.
- **Blocked on:** registering a DLT-approved SMS template (TRAI requirement — the gateway returns
  `Invalid template text` for unregistered content). Only the account owner can fulfil this.
- **Done when:** a live OTP is delivered via UltronSMS and the mock mode flag can be removed.

### A5. Middleware JWT-signature verify 🟡
- **Now:** `middleware.ts` gates `/staff/*` on **presence** of the `navix_staff` cookie — a forged
  cookie of that name passes.
- **Target:** verify the JWT signature + expiry + `staff` token type in middleware (or a server
  component), redirect to `/staff/login` otherwise.
- **Done when:** a forged/empty cookie → redirect; only a valid staff token reaches `/staff/*`.

### A6. RBAC + SoD test matrix 🟡
- **Now:** role checks live in the service (`requireRole`); SoD in `headDecision`; UI RBAC gating
  added (admin pages, collections settle-approve, PII review restricted to reviewer roles).
  Two intentional relaxations: Disbursement Head with txn id bypasses the Accountant gate; ADMIN is
  exempt from the Credit-Exec ≠ Credit-Head SoD and the active-executive `assign` requirement.
- **Target:** add method/endpoint `@PreAuthorize` as a second layer; an **authorization integration-test
  matrix** (every role × every endpoint, including the two SoD relaxations above).
- **Done when:** the test matrix proves each endpoint rejects every role that shouldn't call it,
  and `SOD_VIOLATION` fires when the same subject recommends and approves.

---

## B. AWS infrastructure 🟡 — P1

SSM secrets, S3 documents, and Fintrix/DigiLocker/penny-drop are live. What remains:

### B2. Drop legacy `bytea` document column 🟡
- S3 presigned PUT/GET + `s3_object_key` (V15) is live. The legacy `ApplicationDocument.data` `bytea`
  column is now unused but still in the schema.
- **Target:** Flyway migration to drop the `bytea` column + any code still referencing it.
- **Done when:** no file bytes are stored in the DB; bucket has SSE + lifecycle + least-priv IAM.

### B3. Deployment & CI pipeline 🔴
- `Dockerfile.backend` exists and the image is pushed to ECR (`382188661325.dkr.ecr.ap-south-1.amazonaws.com/navix-finance`).
- **Target:** ECS Fargate service on `navix-cluster` pulling from ECR; RDS Flyway on deploy; VPC/SGs/ALB
  + TLS; Next.js on Vercel with `BACKEND_BASE_URL` pointing at the ALB; CI/CD pipeline (GitHub Actions
  build → push → deploy on tag).
- **Done when:** a tagged release deploys to a non-prod env reproducibly; Flyway migrates RDS; health
  checks pass end-to-end.

### B4. Observability 🔴
- **Target:** structured logs (no PII — reuse `Masking`), metrics (`actuator` + Micrometer →
  CloudWatch/Prometheus), tracing, alerts on error rate / disbursement failures / queue age.
- **Done when:** dashboards exist for the loan funnel + the maker-checker queues; alerts page on-call.

---

## C. External integrations — remaining 🟡 — P1

Fintrix (salary verify + bureau), DigiLocker (KYC), and penny-drop (bank account verify) are all live
with real API calls. One integration remains:

### C3. Real bank payout (NEFT/IMPS) 🔴
- **Target:** real NEFT/IMPS payout at the **Accountant** disbursement step (`accountantValidate`),
  with webhook reconciliation → `DISBURSED` / `DISBURSEMENT_FAILED`.
- **Done when:** disbursement moves real money and the state machine reflects the bank's async result.

---

## D. Data model & platform hardening 🔴 — P2

- **D1. Foreign keys** — schema has indexes only; add FK constraints across the aggregate
  (`application_event`, `applicant_profile`, `application_document`, `loan`, payments). Also drop
  the legacy `disbursement_request` UUID maker-checker table (superseded by the single aggregate).
- **D3. Unify applicant identity** — the live `applicant_profile` (in `navix-loan`) is a
  self-contained KYC snapshot; unify it with `navix-onboarding.Borrower` + `navix-kyc.KycCase`
  (one borrower identity, linked to applications) so KYC isn't duplicated.
- **D4. PII at rest** — encrypt PAN/Aadhaar/bank columns (or tokenize); make `application_event`
  append-only at the DB level (revoke UPDATE/DELETE); confirm masking on every read path.

---

## E. Compliance & product alignment 🔴 — P2 (regulatory gate)

- **Lending model** — decide **lend-on-book vs NBFC partnership** (FLDG/DLG, co-lending);
  compliance, loan booking, and regulatory reporting follow from this.
- **Marketing/product mismatch** — the landing copy depicts a **generic multi-tenure EMI loan**,
  which **contradicts** the salary-linked single-repayment product. Reconcile copy + T&Cs +
  sanction-letter template with the real product (10% fee + 18% GST, 1%/day interest, salary-linked
  due ≤40d, 2%/day late cap 30d). Replace fictional partner names/CoR/CIN placeholders.
- **Sanction letter / agreement** — generate the real document at disbursement-accept → store in S3
  (currently a deferred no-op in `disbursementDecision`).
- **Reporting** — bureau reporting, regulatory returns, audit exports.

---

## Suggested sequencing

1. **A1/A2/A5** — close the remaining auth gaps (emailed invites, DLT SMS, middleware signature verify).
2. **A6** — prove the RBAC/SoD matrix with integration tests.
3. **B2** — drop the legacy `bytea` column now that S3 is live.
4. **C3** — real bank payout (money movement, once auth is locked down).
5. **B3/B4** — production deploy pipeline + observability.
6. **D** — hardening (FK, identity unification, PII encryption).
7. **E** — compliance gates the public launch; run in parallel from day one.

## Definition of done (go-live)

- ✅ JWT auth live — no `DemoActorFilter`, no `X-Demo-Actor-*` trust.
- ✅ Every `/api/**` (except auth/storage/health/docs) requires a valid JWT; role + SoD enforced.
- ✅ Secrets only in SSM; documents in S3 via presign. 🟡 Drop the legacy `bytea` column (B2).
- ✅ Fintrix + DigiLocker live with graceful failure. 🟡 **bank payout** (real NEFT/IMPS) still mocked.
- 🟡 **OTP:** real UltronSMS client done; **register a DLT template** to enable live delivery.
- 🟡 **Emailed invites:** wire SES + password-at-activation + ADMIN gate (A1).
- 🟡 **Middleware:** verify JWT signature + expiry (not just cookie presence) (A5).
- 🔴 FK constraints; legacy UUID `disbursement_request` dropped; PII encrypted/masked; audit immutable.
- 🔴 Product copy + agreements match the salary-linked single-repayment product + the chosen
  lending/regulatory model. Rotate the seeded default staff password + set a strong `AUTH_SECRET`.

---

_Last updated 2026-06-28 (notification engine — in-app + SMS + email — shipped; see the 2026-06-28
notification banner → follow-ups G1–G5. Credit brief + 1–5★ rating + PDF also shipped this day →
F1–F5). As each remaining item ships, mark it 🟢 and migrate the detail into `handoff.md`'s change
log + `CLAUDE.md`._
