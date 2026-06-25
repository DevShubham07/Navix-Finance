# FUTURE.md — Go-live roadmap (deferred work)

The forward plan for taking NAVIX from its current **demo-first** state to production. Covers the
deferred set: **real auth (JWT/Spring Security)**, **AWS infrastructure**, **Fintrix/DigiLocker +
bank integrations**, and the remaining **platform hardening**.

> Companion docs: `CLAUDE.md` (onboarding / current state), `handoff.md` (execution plan + change
> log), `dfd.md` (authoritative lifecycle + roles). This file owns the *not-yet-built* go-live set —
> i.e. task **#14: "DEFERRED: real auth, AWS/Fintrix infra, full platform"**.

> **Update 2026-06-25 — correctness & RBAC hardening pass** ([`PRODUCT_GAPS.md`](PRODUCT_GAPS.md)):
> several go-live items were partially hardened ahead of their full builds — `DemoActorFilter` now
> fails closed as `ANONYMOUS` (A3), the role switcher authenticates for real (A4), the middleware
> cookie mismatch is fixed (A5), UI RBAC gating + `cancel`/settlement role guards landed (A6), and the
> signup document upload is now a real file (B2). Money/lifecycle **bugs** found in the same pass
> (penalty-aware outstanding + closure, OVERDUE-on-read, closed-case filtering) are fixed in code and
> recorded in `CLAUDE.md` §2/§9 — not tracked here. **Still deferred:** the reborrow endpoint (D5),
> full JWT/Spring Security (A), and emailed invites (A1).

Status legend: 🔴 not started · 🟡 partial scaffold exists · 🟢 done (moves out of this doc).

---

## 0. Why this is safe to do later — the swap seams

The demo was deliberately built so that going live is mostly **swapping adapters at known seams**,
not rewriting business logic. Every seam below already exists; production replaces the
implementation behind it and leaves the domain code untouched.

| Seam | Demo today | Production swap | Domain code that must NOT change |
|---|---|---|---|
| **Identity** | `DemoActorFilter` reads `X-Demo-Actor-*` headers → `ActorContext`/`CurrentActor` | A JWT auth filter populates the **same** `CurrentActor` | `ApplicationFlowService.requireRole` / SoD, all `requireRole(...)` |
| **BFF session** | `bff-session.ts` stores plain JSON in `navix_staff`/`navix_borrower` cookies | Signed, httpOnly session referencing a real token; `bff-proxy.ts` sends `Authorization: Bearer …` instead of `X-Demo-Actor-*` | the typed client `applications.ts`, all pages |
| **Doc storage** | `ApplicationDocument.data` = `bytea`, base64 in/out | `navix-storage` presign + S3; column becomes `s3_key` | `ApplicantReviewService` method signatures, the review UI |
| **Salary verify** | mock in `navix-verification` | real Fintrix HTTP client behind the same service interface | risk/eligibility callers |
| **KYC** | mock in `navix-kyc` | real DigiLocker client behind the same interface | `KycService` callers |
| **Config/secrets** | local env / `application-local.yml` | SSM Parameter Store / Secrets Manager (spring-cloud-aws already wired) | application code (reads via `@Value`/config) |

**Principle:** keep services reading `CurrentActor` and calling interfaces. If a go-live change forces
edits deep in domain logic, stop — the seam was bypassed somewhere.

---

## 1. The deferred set at a glance

| # | Workstream | Current | Priority |
|---|---|---|---|
| A | Real authentication & authorization (JWT + Spring Security) | 🟡 demo headers; invite/activate UI wired (demo-grade) | **P0 (gate for everything)** |
| B | AWS infrastructure (secrets, S3, deploy, observability) | 🟡 spring-cloud-aws wired but offline | P1 |
| C | External integrations un-mocked (Fintrix, DigiLocker, bank) | 🟡 mock clients | P1 |
| D | Data model & platform hardening (FK, legacy tables, PII) | 🟡 (D2 collections-bridge + D5 repay done; FK/identity/PII remain) | P2 |
| E | Compliance & product alignment (NBFC/DLG, reporting, copy) | 🔴 | P2 (regulatory gate before launch) |

---

## A. Real authentication & authorization 🟡 — P0

Today identity is demo-only: BFF login endpoints set plain cookies and the backend trusts
`X-Demo-Actor-*` headers. **Anyone can mint any role.** This is the single biggest go-live gate.

What already exists (reusable):
- `navix-iam`: `StaffUser`, `StaffInvite`, `InviteService` (token + 7-day expiry → activates a
  `StaffUser` ACTIVE with a role), `StaffRole` enum, `SeparationOfDutiesGuard`.
- `ApplicationFlowService` already enforces **role-per-step + SoD** server-side via the
  `application_event` trail — this stays as-is.

### A1. Staff credentials + invite activation 🟡
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

### A2. Borrower authentication 🔴
- **Now:** demo OTP `123456` → `navix_borrower` cookie; `applicantId` derived from mobile.
- **Target:** real mobile-OTP (SMS provider) with rate-limiting + lockout; a persisted `Borrower`
  identity (unifies with `navix-onboarding.Borrower`, see D3).
- **Done when:** OTP is provider-sent, single-use, expiring; sessions map to a real borrower row.

### A3. JWT issuance + Spring Security filter (replace `DemoActorFilter`) 🔴
- **Target:** on login the backend issues a short-lived **JWT** (subject = user id, claims = role,
  name, token type staff|borrower) + a refresh token. A `OncePerRequestFilter` validates the JWT
  and populates **the same `ActorContext`/`CurrentActor`** that `DemoActorFilter` populates today.
- **Steps:** add `spring-boot-starter-security` + a JWT lib (e.g. `nimbus`/`jjwt`); `SecurityConfig`
  with stateless sessions, public paths (`/login`, `/actuator/health`, swagger in non-prod),
  everything else authenticated; **delete/disable `DemoActorFilter`** (keep behind a
  `demo` profile for local dev).
- **Touchpoints:** `navix-app/config` (new `SecurityConfig`, `JwtAuthFilter`), `navix-iam` (token
  service), `navix-common/security` (unchanged — that's the point).
- **Done when:** a request with no/invalid JWT to `/api/applications/*` → **401**; a valid token's
  role drives `requireRole`; `X-Demo-Actor-*` headers are ignored in prod.
- **Hardened 2026-06-25 (PRODUCT_GAPS B1):** `DemoActorFilter` now defaults to a non-privileged
  **`ANONYMOUS`** actor (was `ADMIN`), so a header-less or direct backend call already **fails closed**
  at `requireRole` — closing the "anything reaching the backend without headers is ADMIN" gap ahead of
  the full JWT swap.

### A4. BFF session swap 🔴
- **Hardened 2026-06-25 (PRODUCT_GAPS B2):** the staff **role switcher** (`staff-role-bar.tsx`) now
  calls the real `POST /api/auth/staff/login` so the `navix_staff` cookie reflects the chosen role
  (it previously only mutated client state, leaving the backend on the stale actor). The cookie is
  still plain JSON — the JWT/bearer swap below is unchanged.
- **Now:** `bff-session.ts` writes plain JSON into `navix_staff`/`navix_borrower`; `bff-proxy.ts`
  injects `X-Demo-Actor-*`.
- **Target:** the BFF stores the **JWT** (or an opaque session id) in an httpOnly, `Secure`,
  `SameSite` cookie and forwards `Authorization: Bearer <jwt>` to the backend; login routes call the
  real backend `/auth/login` instead of fabricating a session.
- **Touchpoints:** `lib/api/bff-session.ts`, `lib/api/bff-proxy.ts`, `app/api/auth/{staff,borrower}/*`.
- **Done when:** the browser never holds a role it can edit; the proxy carries a real bearer token.

### A5. Middleware → real verification 🟡
- **Now:** `middleware.ts` gates `/staff/*` on the **real `navix_staff`** cookie the BFF/login sets —
  the old `navix_session` mismatch is **fixed** (PRODUCT_GAPS B9), so the gate is no longer a no-op.
  It is still a **presence** check, so a forged cookie of that name passes.
- **Target:** verify the session JWT signature + expiry + `staff` token type in middleware (or a
  server component), redirect to `/staff/login` otherwise. (Cookie name already unified.)
- **Done when:** a forged/empty cookie → redirect; only a valid staff token reaches `/staff/*`.

### A6. RBAC + SoD hardening 🟡
- **Now:** role checks live in the service (`requireRole`); SoD in `headDecision`. Good, but
  defense-in-depth at the edge is missing.
- **Landed 2026-06-25 (PRODUCT_GAPS):** **UI RBAC gating** added (admin pages behind `staff:manage`,
  collections salary/employer + settlement-approve behind `collections:manage`, applicant-PII review
  restricted to reviewer roles, risk/score hidden from borrowers); service-layer role guards added to
  **`ApplicationFlowService.cancel`** (owning borrower / staff only) and collections **settlement
  `propose`/`approve`** (officer-or-head / head, before the proposer≠approver SoD).
- **Target:** add method/endpoint `@PreAuthorize` as a second layer; keep the event-trail SoD as the
  source of truth; add an authorization integration-test matrix (every role × every endpoint).
- **Done when:** the test matrix proves each endpoint rejects every role that shouldn't call it,
  and `SOD_VIOLATION` fires when the same subject recommends and approves.
- **Note:** the **Disbursement-Head ≠ Accountant** SoD is now *intentionally relaxed* — a Disbursement
  Head who enters a transaction id finalizes the release directly, skipping the accountant (the no-txn
  path still routes to the accountant). The **Credit-Exec ≠ Credit-Head** SoD is unchanged. The
  RBAC/SoD matrix must encode this product decision.

---

## B. AWS infrastructure 🟡 — P1

spring-cloud-aws is already on the classpath (you see the offline `ParameterStorePropertySources`
WARN at boot — harmless locally).

### B1. Secrets & config 🟡
- **Target:** all secrets (DB, `AUTH_SECRET`/JWT keys, Fintrix, DigiLocker, SMS/SES) in **SSM
  Parameter Store** (`/navix/<env>/…`) or **Secrets Manager**; nothing in the repo.
- **Done when:** prod boots reading config from SSM; no secret literals anywhere; rotation documented.

### B2. S3 document storage (replace inline `bytea`) 🟡
- **Now:** `ApplicationDocument.data` stores bytes inline; `navix-storage` `StorageController` exposes
  `presign-upload`/`presign-download` but the app doesn't use them. The signup **address-proof upload
  is now a real file** (PRODUCT_GAPS B7) — read as base64 and `POST …/documents` at submit, so staff
  review shows an actual document; it still lands in the inline `bytea` column (S3 swap below is what
  remains).
- **Target:** borrower uploads go **direct to S3 via presigned PUT**; the app stores only
  `s3_key` + metadata; staff view/download via **presigned GET**. Drop the `bytea` column (Flyway).
- **Touchpoints:** `ApplicantReviewService` (store key, not bytes), `ApplicationDocument` (s3_key),
  the borrower upload + staff review UI (use presign instead of base64), `navix-storage`.
- **Done when:** no file bytes transit the JSON BFF or the DB; bucket has SSE + lifecycle + least-priv IAM.

### B3. Deployment & data 🔴
- **Target:** containerized services (the `Dockerfile.backend` exists), **RDS Postgres** (Flyway on
  deploy), VPC/subnets/SGs, ALB + TLS, the Next.js app on its host of choice; CI/CD pipeline.
- **Done when:** a tagged release deploys to a non-prod env reproducibly; Flyway migrates RDS.

### B4. Observability 🔴
- **Target:** structured logs (no PII — reuse `Masking`), metrics (`actuator` + Micrometer →
  CloudWatch/Prometheus), tracing, alerts on error rate / disbursement failures / queue age.
- **Done when:** dashboards exist for the loan funnel + the maker-checker queues; alerts page on-call.

---

## C. External integrations un-mocked 🟡 — P1

### C1. Fintrix — salary verification 🟡
- **Now:** mocked in `navix-verification`. Base `https://admin.fintrix.tech/__api/api/v1/`, HTTP Basic
  `base64(CLIENT_ID:CLIENT_SECRET)`. See `NAVIX_Fintrix_Integration_Flow.md`.
- **Target:** real client behind the existing service interface; feeds salary → eligible limit (25%)
  + risk A/B/C/D. Handle timeouts/retries/circuit-breaker; cache verified salary.
- **Done when:** a real verification drives the limit/risk; failures degrade gracefully (manual review).

### C2. DigiLocker — KYC 🟡
- **Now:** mocked in `navix-kyc` (`KycCase`, `KycCheck`, `DigiLockerSession`). See `Digilocker_API_Guide.md`.
- **Target:** real DigiLocker OAuth + document pull; populate `KycCheck` results/scores; the staff
  KYC-approver decision reads real check outcomes.
- **Done when:** KYC approval is backed by real DigiLocker results, not seed data.

### C3. Bank — penny-drop + transfer 🔴
- **Target:** account verification (penny-drop) at bank-detail capture; real NEFT/IMPS payout at the
  **Accountant** disbursement step (`accountantValidate`), with webhook reconciliation →
  `DISBURSED`/`DISBURSEMENT_FAILED`.
- **Done when:** disbursement moves real money and the state machine reflects the bank's async result.

---

## D. Data model & platform hardening 🔴 — P2

- **D1. Foreign keys** — schema has indexes only; add FK constraints across the aggregate
  (`application_event`, `applicant_profile`, `application_document`, `loan`, payments).
- **D2. Collections on the real loans** — 🟢 **DONE.** `collection_case` / `settlement` now key off the
  real **bigint** loan id (Flyway V11); opening a case validates the loan via the `LoanDirectory` port
  and flips it `ACTIVE/OVERDUE → IN_COLLECTIONS`; officers + settlement proposer/approver are real staff
  (names, not UUIDs); cases are driven off real loans via `GET /api/collections/loans`. _Remaining:_ the
  legacy `disbursement_request` UUID chain is still **superseded by the aggregate** — drop it (D1/below).
- **D3. Unify applicant identity** — the live `applicant_profile` (in `navix-loan`) is a
  self-contained KYC snapshot; unify it with `navix-onboarding.Borrower` + `navix-kyc.KycCase`
  (one borrower identity, linked to applications) so KYC isn't duplicated.
- **D4. PII at rest** — encrypt PAN/Aadhaar/bank columns (or tokenize); make `application_event`
  append-only at the DB level (revoke UPDATE/DELETE); confirm masking on every read path.
- **D5. Borrower repay** — 🟢 **DONE.** `/repay` records a real manual payment
  (`POST /api/loan/{id}/repayments`, → PENDING_VERIFICATION); the **Accountant** verifies it, reducing
  the outstanding and closing the loan + application (`closeForLoan`, ACTIVE/OVERDUE → CLOSED) at zero;
  the page shows the prepayment-aware "pay today" amount via `…/outstanding`. _Remaining:_ **reborrow**
  (`/reloan` → a new draft reusing the profile) is still mock — add the endpoint and wire it off
  `borrowerApi`.

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

1. **A (auth)** first — nothing else is safe to expose without it. A3+A4+A5 together flip the app
   from demo to authenticated; A1/A2 make accounts real; A6 proves it.
2. **B1 (secrets)** alongside A (you need a JWT signing key + real DB creds out of SSM anyway).
3. **C1/C2** (Fintrix/DigiLocker) and **B2** (S3 docs) — make decisions real and storage real.
4. **C3** (bank payout) — money movement, once auth + reconciliation exist.
5. **D** (hardening) and **B3/B4** (deploy/observability) to productionize.
6. **E** (compliance) gates the actual public launch and runs in parallel from day one.

## Definition of done (go-live)

- No `DemoActorFilter`, no `X-Demo-Actor-*` trust, no role-pick login in prod.
- Every `/api/**` (except auth/health) requires a valid JWT; role + SoD enforced and test-proven.
- Secrets only in SSM/Secrets Manager;documents only in S3 (no `bytea`, no base64 over the BFF).
- Fintrix + DigiLocker + bank payout are live with graceful failure paths.
- FK constraints in place; legacy UUID tables resolved; PII encrypted/masked; audit immutable.
- Product copy + agreements match the salary-linked single-repayment product and the chosen
  lending/regulatory model.

---

_Last updated 2026-06-25. As each item ships, mark it 🟢 and migrate the detail into `handoff.md`’s
change log + `CLAUDE.md`._
