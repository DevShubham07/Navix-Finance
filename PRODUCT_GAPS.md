# NAVIX — End-to-end QA findings: bug list + product-gaps

_Produced 2026-06-25 by driving the running demo (frontend :3000, backend :8080) through the real
loan lifecycle with Chrome DevTools, plus a code audit of RBAC, the flow/SoD engine, `LoanMath`, the
Flyway schema, the DTO/masking layer, and every staff/borrower screen._

This document has four parts:
1. **Simulations run** (DRAFT→ACTIVE, due-today, overdue) and what they proved.
2. **Observation → screenshot map.**
3. **Bug list** — defects that will disturb frontend↔backend API linking (fix these).
4. **PM product-gaps** — per screen/role, should this entity see this field/table/action.
5. **Deferred features** — items that are really roadmap work, cross-referenced to `FUTURE.md`.

Screenshots referenced as `shot NN` are committed under
[`docs/product-gaps-shots/`](docs/product-gaps-shots/); each is linked inline and in the index below.

> **Demo-data note.** The overdue case was created by editing **loan #2** in the local Postgres
> (back-dating `disbursed_on`/`due_date`, rejecting the prior payment). No application code was
> changed. Loan #2 is now CLOSED (it was used to prove Bug #4).

---

## 1. Simulations run

| Simulation | Outcome | Key screenshots |
|---|---|---|
| **DRAFT → ACTIVE** (signup → KYC → credit assign/recommend/head-approve → disbursement fast-path) | ✅ Full state machine works; audit trail correct; SoD passed (Priya ≠ Rahul); 0 console errors, all 200s. | 01–18 |
| **Due today** (due_date = 2026-06-25) | ✅ Dashboard "Due in 0 days", repay "Pay today" = total = ₹19,650; record → accountant verify → loan + app **CLOSED**. | 19–23 |
| **Overdue, 12 DPD** (due_date = 2026-06-13) | ✅ behaviours rendered, but exposed **Bugs #3, #4, #5** (below). | 24–28 |

### Overdue numbers (loan #2, principal ₹15,000, 12 days past due, grace 1 → 11 penalty days)
- Interest (30d): ₹4,500 → stored `total_repayable` = **₹19,500**
- Late penalty: 1,500,000 × 2% × 11 = **₹3,300**
- `outstandingAsOf(today)` (penalty-aware) = **₹22,800**
- **What each surface showed:** borrower **dashboard ₹19,500** · borrower **repay "Pay now" ₹22,800** ·
  collections **buckets/case ₹19,500**. Three surfaces, two different "amount owed."

---

## 2. Observation → screenshot map

| # | Observation | Screenshot / evidence | Root cause (file) |
|---|---|---|---|
| O1 | **"Act as role" switcher is cosmetic** — banner changed to "Rahul Mehta" but the backend session stayed "Priya Nair / CREDIT_HEAD", giving "Not your step" until a real `/staff/login`. | Live: `GET /api/auth/staff/me` returned Priya while the banner showed Rahul; fixed only after re-login ([`shot 15`](docs/product-gaps-shots/15-credit-exec.png)). | `frontend/src/components/staff/staff-role-bar.tsx` → `signInStaff()` in `frontend/src/lib/mock/session.ts` (localStorage + plain cookie); never POSTs `/api/auth/staff/login`. |
| O2 | **Cosmetic document upload** — signup "upload" never POSTs; the KYC reviewer saw "No documents uploaded." | [`shot 11`](docs/product-gaps-shots/11-kyc-review.png) ("No documents uploaded.") | `frontend/src/app/(borrower)/signup/address-proof/page.tsx` only `setUploaded(true)`; `submitOnboarding()` in `frontend/src/lib/api/live-journey.ts` passes an empty `docs[]`. |
| O3 | **"Due in 0 days"** copy for a loan due today (should read "Due today"). | [`shot 19`](docs/product-gaps-shots/19-due-today-dashboard.png) | `frontend/src/app/(borrower)/dashboard/page.tsx` `daysBetween(now, due)` label. |

---

## 3. Bug list — will disturb API linking

Ordered by integration impact. "Proven" = reproduced live this session.

### B1 — `DemoActorFilter` defaults to role `ADMIN` when the role header is missing  · **HIGH**
`backend/navix-app/.../config/DemoActorFilter.java` (~line 37) falls back to `("demo","Demo User","ADMIN")`.
Any BFF route that forgets `X-Demo-Actor-Role`, or any direct call, silently runs as **ADMIN** —
bypassing every `requireRole`. During wiring this *masks* RBAC bugs (everything "works") and will blow
up the moment real auth is switched on. **Fix:** default to an unauthenticated/forbidden actor, not ADMIN.

### B2 — "Act as role" switcher never authenticates (O1)  · **HIGH** · _proven_
`staff-role-bar.tsx` + `lib/mock/session.ts signInStaff` mutate only client state. The `navix_staff`
cookie the BFF reads is stale, so staff API calls carry the **previous** role → "Not your step" /
wrong-actor / 401. This already cost a debugging detour this session. **Fix:** make the switcher call
`POST /api/auth/staff/login` (or hide it outside demo mode).

### B3 — Stored `outstanding` vs computed `outstandingAsOf` diverge  · **HIGH** · _proven_
The dashboard reads the **stored** `loan.outstanding` (no penalty); the repay page reads
`…/outstanding?asOf=` (penalty included). For the 12-DPD loan this was **₹19,500 vs ₹22,800**.
Collections also shows the stored ₹19,500. Three surfaces disagree on what the borrower owes.
`backend/navix-loan/.../RepaymentService.recomputeOutstanding` (stored = `totalRepayable − verified`)
vs `RepaymentService.outstandingAsOf` (adds `LoanMath.latePenaltyPaise`). **Fix:** make the stored field
penalty-aware, or make every read use `outstandingAsOf`.

### B4 — Loan closes ignoring the late penalty (revenue leak)  · **HIGH** · _proven_
`recomputeOutstanding` closes when `totalRepayable − verified == 0`. Paying **₹19,500** (the penalty-less
total) on a loan that actually owed **₹22,800** drove `loan.status=CLOSED`, `outstanding=0`, application
`CLOSED` — while `outstandingAsOf` **still computed ₹3,300** owed. The penalty silently vanished, and the
payment was even flagged `partial=false`. `RepaymentService` (~lines 110–123). **Fix:** closure must
compare against the penalty-aware balance.

### B5 — No ACTIVE → OVERDUE transition on the loan  · **MEDIUM** · _proven_
Nothing flips `loan.status`; a 12-days-late loan stayed **ACTIVE**. OVERDUE never appears from the backend
— `IN_COLLECTIONS` is only set when a collection case opens (`LoanDirectoryAdapter.markInCollections`,
called from `CollectionsService.openCase`). The borrower UI *derived* "12 days past due" from the date
client-side, but any backend/report logic keyed on `status == OVERDUE` will never fire. **Fix:** a
scheduled job (or compute-on-read status) to mark OVERDUE past due + grace.

### B6 — Closing a loan leaves its collection case open (orphan)  · **MEDIUM** · _proven_
After loan #2 closed, its `collection_case` row persisted (no resolution/close). Collections would keep
an open case against a fully-closed loan. **Fix:** close/resolve the case in the closure path.

### B7 — Signup documents never reach the backend (O2)  · **MEDIUM** · _proven_
`application_document` stays empty → staff doc-review looks broken even though the endpoint works.
`signup/address-proof/page.tsx` + `live-journey.ts submitOnboarding` (empty `docs[]`).

### B8 — `/reloan` has no backend endpoint  · **MEDIUM**
`frontend/src/app/(borrower)/reloan/page.tsx` calls mock `j.reborrow()`. Wiring reborrow will 404 until a
backend endpoint exists (FUTURE D5 "remaining").

### B9 — Middleware gates on the wrong cookie  · **MEDIUM**
`frontend/src/middleware.ts` gates `/staff/*` on `navix_session`, but the BFF sets `navix_staff`. A
forged/empty cookie passes; the gate is effectively a no-op (FUTURE A5). **Fix:** verify the real staff
cookie/JWT and unify the name.

### B10 — `cancel()` has no role check  · **MEDIUM**
`ApplicationFlowService.cancel` (~line 203) lets **any** actor (including a borrower) cancel a
pre-disbursement application. **Fix:** restrict to staff/ADMIN (or the owning borrower only).

### B11 — Collections settlement authz holes  · **MEDIUM**
`SettlementService.approve` enforces proposer ≠ approver SoD but **no `COLLECTION_HEAD` role guard**;
`propose` has no role guard at all; `CollectionsController` adds none. A `COLLECTION_EXECUTIVE` could
approve a settlement via direct API. **Fix:** add the role guard alongside the SoD check.

### B12 — `SecurityConfig` is `permitAll()` everywhere  · **HIGH (go-live)**
`backend/navix-app/.../config/SecurityConfig.java` (lines 40, 58) authorizes all requests. Every `/api/**`
is reachable directly, bypassing the BFF and all header injection; the only enforcement is service-layer
`requireRole`. Integration tests that assume HTTP auth will mislead. (FUTURE A3.)

---

## 4. PM product-gaps — should this entity see this?

### G1 — Applicant PII is open to every staff role  · **HIGH**
The "Open application by ID" review (`frontend/src/components/staff/live-pipeline.tsx` `ApplicantReview`,
surfaced on `staff/applications` and `staff/credit/[id]`) is **not role-gated**. Any role — including
`DEVELOPER` and `COLLECTION_*` — can pull full name, masked PAN/Aadhaar, **monthly salary**, employer,
and address. PM call: a developer should see **zero** PII; collections doesn't need salary. Scope the
review lookup by permission.

### G2 — Collections sees credit-assessment data  · **HIGH** · _proven ([shot 27](docs/product-gaps-shots/27-collections-case-dpd.png))_
`frontend/src/app/staff/collections/[loanId]/page.tsx` `BorrowerCard` shows **Monthly salary (₹60,000),
Employer, Salary bank** to a `COLLECTION_EXECUTIVE`. That's credit data, not need-to-know for chasing a
payment. Hide salary/employer for the officer role (or behind `collections:manage`).

### G3 — Admin pages have no UI role gate  · **HIGH**
`staff/admin/{staff,invites,blocklist}` render with **no `PermissionGate`**. Any staff who navigates sees
the staff roster, **one-time invite tokens**, and blocklist identifiers (PAN/Aadhaar-ref/phone/account).
Backend RBAC on `/api/staff` is also deferred → this is real exposure, not just cosmetic.

### G4 — Collections pages have no UI role gate  · **MEDIUM**
`collections/buckets` and `collections/settlements` render identically for Head and Executive; the
settlement **Approve** button is shown to the Executive (backend SoD is the only stop, and per **B11**
it's weak). Gate `settlements`/approve to `collections:manage`.

### G5 — Risk category & credit score shown to the borrower  · **HIGH (product rule)**
`frontend/src/app/(borrower)/profile/page.tsx` renders `riskCategory (A/B/C/D)` and `creditScore`.
`CLAUDE.md` is explicit: **risk categories are staff-only and must never be shown to borrowers** ("one
price for all"). Currently mock-fed, but the screen is built to display it — remove before it ever binds
to real data.

### G6 — Invite token shown in a table instead of emailed  · **MEDIUM**
`staff/admin/invites` displays the one-time token in the UI. Operational/security gap; FUTURE A1 calls
for an emailed one-time link.

### G7 — Stale role label on credit pages  · **LOW**
`staff/credit/*` render the header label "Credit Head" even when a Credit Executive is signed in —
confusing role context (cosmetic).

### G8 — "Executive assigns to another executive" — premise corrected  · _info_
The example doesn't hold at the backend: `/assign` is **CREDIT_HEAD-only** (`requireRole("CREDIT_HEAD")`,
and the assignee must be an ACTIVE `CREDIT_EXECUTIVE`), and the UI only renders the control under
`permission="loan:approve"`. So a Credit Executive **cannot** assign. The genuine adjacent gaps are
**G4 / B11** (collections propose/approve lack role guards).

### G9 — Whole-funnel stat cards to every role  · **LOW**
`staff/dashboard` shows KYC/credit/disbursement/accounting/collections counts to all roles. Reasonable
for ops visibility; a PM may still want to hide cards a role can't act on.

### G10 — "New loan" restarts the full 10-step signup  · **LOW**
Borrower nav "New loan" → `/signup/pan` instead of a profile-reusing reborrow (ties to **B8**).

---

## 5. Deferred features (cross-referenced to `FUTURE.md`)

These are known roadmap items, listed so they aren't confused with the bugs above:

- **Auth/JWT + Spring Security** (FUTURE §A) — resolves **B1, B2, B9, B12** (ADMIN default, switcher,
  middleware cookie, permitAll) and adds the @PreAuthorize matrix.
- **S3 docs + real upload** (FUTURE §B2) — the cosmetic upload (**B7**) becomes a real presigned PUT.
- **Fintrix / DigiLocker / penny-drop / e-sign / selfie** (FUTURE §C) — the cosmetic borrower steps.
- **Borrower reborrow endpoint** (FUTURE §D5 "remaining") — **B8 / G10**.
- **PII at rest + append-only events + FK** (FUTURE §D1/D4) — Aadhaar stored full, document `bytea`, no FKs.
- **Compliance / product copy** (FUTURE §E) — the marketing EMI-calculator mismatch **and G5**
  (risk category to borrower) belong here as product-alignment items.

> Recommended priority: **B3/B4** (money correctness) and **G5** (product-rule PII) first — they are
> real defects independent of the auth work; then the auth bundle (FUTURE §A) clears B1/B2/B9/B12/G3 in
> one pass.

---

## Screenshot index

All paths are relative to the repo root, under [`docs/product-gaps-shots/`](docs/product-gaps-shots/).

| Shot | Step | Relative path |
|---|---|---|
| 01 | Landing page | [`docs/product-gaps-shots/01-landing.png`](docs/product-gaps-shots/01-landing.png) |
| 02 | Signup — PAN / Aadhaar | [`docs/product-gaps-shots/02-pan.png`](docs/product-gaps-shots/02-pan.png) |
| 03 | Signup — employment | [`docs/product-gaps-shots/03-employment.png`](docs/product-gaps-shots/03-employment.png) |
| 04 | Signup — salary (limit ₹15,000) | [`docs/product-gaps-shots/04-salary.png`](docs/product-gaps-shots/04-salary.png) |
| 06 | Signup — bank | [`docs/product-gaps-shots/06-bank.png`](docs/product-gaps-shots/06-bank.png) |
| 07 | Signup — review & submit | [`docs/product-gaps-shots/07-review.png`](docs/product-gaps-shots/07-review.png) |
| 08 | KYC pending | [`docs/product-gaps-shots/08-kyc-pending.png`](docs/product-gaps-shots/08-kyc-pending.png) |
| 09 | Staff login (role picker) | [`docs/product-gaps-shots/09-staff-login.png`](docs/product-gaps-shots/09-staff-login.png) |
| 10 | Staff dashboard | [`docs/product-gaps-shots/10-staff-dashboard.png`](docs/product-gaps-shots/10-staff-dashboard.png) |
| 11 | KYC review — masked PAN; "No documents uploaded" (O2) | [`docs/product-gaps-shots/11-kyc-review.png`](docs/product-gaps-shots/11-kyc-review.png) |
| 12 | Borrower — choose amount | [`docs/product-gaps-shots/12-loan-apply.png`](docs/product-gaps-shots/12-loan-apply.png) |
| 13 | Loan status — applied | [`docs/product-gaps-shots/13-loan-status-applied.png`](docs/product-gaps-shots/13-loan-status-applied.png) |
| 14 | Credit queue — assign exec | [`docs/product-gaps-shots/14-credit-queue.png`](docs/product-gaps-shots/14-credit-queue.png) |
| 15 | Credit exec — recommend (O1 evidence) | [`docs/product-gaps-shots/15-credit-exec.png`](docs/product-gaps-shots/15-credit-exec.png) |
| 16 | Credit head — final approve (SoD) | [`docs/product-gaps-shots/16-credit-head.png`](docs/product-gaps-shots/16-credit-head.png) |
| 17 | Disbursement — fast-path | [`docs/product-gaps-shots/17-disbursement.png`](docs/product-gaps-shots/17-disbursement.png) |
| 18 | Borrower dashboard — ACTIVE | [`docs/product-gaps-shots/18-dashboard-active.png`](docs/product-gaps-shots/18-dashboard-active.png) |
| 19 | Due-today dashboard ("Due in 0 days" — O3) | [`docs/product-gaps-shots/19-due-today-dashboard.png`](docs/product-gaps-shots/19-due-today-dashboard.png) |
| 20 | Due-today repay | [`docs/product-gaps-shots/20-due-today-repay.png`](docs/product-gaps-shots/20-due-today-repay.png) |
| 21 | Repay — pending verification | [`docs/product-gaps-shots/21-repay-pending.png`](docs/product-gaps-shots/21-repay-pending.png) |
| 22 | Accountant — verify queue | [`docs/product-gaps-shots/22-accountant-verify-queue.png`](docs/product-gaps-shots/22-accountant-verify-queue.png) |
| 23 | Loan closed | [`docs/product-gaps-shots/23-loan-closed.png`](docs/product-gaps-shots/23-loan-closed.png) |
| 24 | Overdue dashboard — "12 days past due", total **₹19,500** (B3/B5) | [`docs/product-gaps-shots/24-overdue-dashboard.png`](docs/product-gaps-shots/24-overdue-dashboard.png) |
| 25 | Overdue repay — "Pay now (incl. penalty)" **₹22,800** (B3) | [`docs/product-gaps-shots/25-overdue-repay-penalty.png`](docs/product-gaps-shots/25-overdue-repay-penalty.png) |
| 26 | Collections buckets — loan #2 collectible, loan #1 at 8–30 DPD | [`docs/product-gaps-shots/26-collections-buckets.png`](docs/product-gaps-shots/26-collections-buckets.png) |
| 27 | Collections case — T8_T30 / 12 DPD, IN_COLLECTIONS, salary exposed (G2) | [`docs/product-gaps-shots/27-collections-case-dpd.png`](docs/product-gaps-shots/27-collections-case-dpd.png) |
| 28 | Closure bug — loan CLOSED at ₹19,500 while ₹3,300 penalty still computed (B4) | [`docs/product-gaps-shots/28-closure-bug-repaid.png`](docs/product-gaps-shots/28-closure-bug-repaid.png) |

_(Shot 05, the email step, was not captured.)_
