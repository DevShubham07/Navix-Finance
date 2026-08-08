# DhanBoost — full user-journey revamp (4 phases)

> **Status:** approved spec, 2026-08-06. Phase 1 is being built now; Phases 2–4 are specified here so the
> schema and state machine are designed once. Every decision below was explicitly confirmed — see
> [Confirmed decisions](#confirmed-decisions). `AGENTS.md` / `CLAUDE.md` document the implemented lifecycle.

## Context

The current product runs a 12-step verified onboarding (`/signup/mobile-otp → email → address → digilocker →
pan → pan-consent → bureau → salary → penny-drop → selfie → agreement → review`) that fires five external APIs
before a human ever sees the file, then walks a four-actor credit maker-checker (KYC Approver → Credit Head →
Credit Executive → Credit Head) before disbursement. Loan size is a formula (25% of salary) and the due date is
derived (`dueDateFromSalary`, ≤40 days).

That is being replaced end to end with a **thin intake, a human decision in the middle, and heavy verification
at the end**:

* **Phase 1** — a 10-screen intake. Only three API calls, all at the last step. Self-employed applicants are
  auto-rejected into an admin list.
* **Phase 2** — `KYC_APPROVER` is deleted. **Credit Head assigns → Credit Executive verifies and sanctions**
  an amount, repayment date and remarks. That decision is final; credit has no further role.
* **Phase 3** — the borrower returns to an approved offer: amount → locked repayment date → DigiLocker →
  references → summary → selfie → geo-address → sanction letter → eSign → 🎉 → disbursal account.
* **Phase 4** — Disbursement Head releases with a txn id (no accountant hop); the Accountant moves to
  validating collections-side payments; an engine rule auto-rejects anyone who ever repaid >5 days late.

**Build order: Phase 1 first, then review.**

---

## Repo state at the time of planning (`a83213e` → `da2d9ba`)

The customer-first CRM landed mid-planning. Everything below is folded into the plan.

| What landed | Effect on this plan |
|---|---|
| **Migrations V41–V43** (`customer_owner_and_call_log`, `telecaller_role`, `lead`) | Phase 1 migration is **V44**, Phase 2 **V45**, Phase 3 **V46**. |
| **`TELECALLER` role** (V42) — `customer:view` + `leads:manage`, deliberately **no lifecycle authority** | Untouched by the Phase-2 role rework. It never participates in maker-checker, so deleting `KYC_APPROVER` doesn't interact with it. |
| **New permissions `loan:pipeline`, `customer:assign`, `leads:manage`** | The `ROLE_PERMISSIONS` edit in Phase 2 is larger than originally scoped — `loan:pipeline` gates the workbench and must move onto the surviving credit roles. |
| **`staff-role-bar.tsx` + `staff-personas.ts` DELETED**; BFF role-login shortcut removed | Staff login is now email + password only; nothing to change there. |
| **Customer-first CRM revamp** — `CustomerOwner`, `CustomerCallLog`, `customer-tabs.tsx`, segmented nav | The Phase-2 details popup sits alongside a much richer customer surface; reuse `customer-tabs.tsx` rather than adding a parallel one. |
| **`lib/customers/segments.ts`** — `incomplete` (DRAFT), `hold` (DISBURSEMENT_FAILED), `unallocated`, etc. | The new `SANCTIONED` status **must be added to `APPROVED_APP`** or every sanctioned customer silently falls through to "pending". |
| **A `Lead` entity now exists** — pre-customer telecaller DSA intake, explicitly *not* a customer | **Naming collision.** The Phase-2 buttons say "Accept lead / Reject lead / Mark lead pending" and refer to a *loan application*, not this `Lead`. UI labels stay; **no backend type may be named `Lead`** — use `ApplicationDecision` / `sanction*`. |
| **`verifyPan` now persists DOB** and returns `fullName`, `gender`, `panStatus`, `compliant`, `panNumber` in `derived` | Decision 12 (name + DOB from PAN-206) is a **one-line addition** — persist `fullName` the same way DOB already is. |
| **`verifyEmail` already persists the contact email and never hard-blocks on provider failure** | Decisions 10 and 15 are already the codebase's behaviour. |
| **`da2d9ba`** — server-side mobile backfill so bureau consent works on a resumed draft | Must be **preserved**: the new screen 9 does the same OTP step-up and hits the same `MOBILE_MISSING` dead end. |
| **Frontend now has vitest** (`vitest.config.ts`, `segments.test.ts`) | Verification gains `npx vitest run`. |

---

## Confirmed decisions

### Scope & sequencing
| # | Decision |
|---|---|
| 1 | Build **phase by phase**; Phase 1 lands first for review. |
| 2 | Delete **`KYC_APPROVER` only**. `CREDIT_HEAD` + `CREDIT_EXECUTIVE` absorb its work. |
| 3 | **Wipe all in-flight DRAFTs** on deploy — everyone starts fresh. Logins are not deleted. |
| 4 | Keep the **`/signup/*` (intake) / `/loan/*` (post-approval)** URL scheme. |
| 5 | In Phase 1, **neutralise the legacy borrower pages** (`/loan/apply`, `/loan/salary` → redirects; `/dashboard`, `/loan/status` trimmed to "with our credit team"); rebuild properly in Phase 3. |

### Which APIs run, and when
| # | Decision |
|---|---|
| 6 | **Phase 1:** PAN-206, work-email verification, bureau — **all three fire on screen 9**, nowhere earlier. |
| 7 | Keep the **separate OTP bureau-consent step**; it becomes screen 9. |
| 8 | **Phase 3:** DigiLocker, then selfie/liveness and geo-address just before the sanction letter, then eSign (mocked). |
| 9 | **Penny drop only ever fires when the borrower types a different account** — keeping the salary account never triggers it, first loan included. |
| 10 | A failed Phase-1 check **still goes to the credit team**, flagged red. No auto-reject on a technical failure. |
| 11 | A failed Phase-3 check **passes through silently**; it surfaces only in the Verification Dashboard for later staff review. |

### Phase 1 intake
| # | Decision |
|---|---|
| 12 | **Name and DOB come from PAN-206** at screen 9 — no name/DOB fields anywhere in the intake. |
| 13 | "Previous salary date" is a **full date**; `salary_credit_day` is derived from it. |
| 14 | Monthly salary = **net / in-hand**, taken as declared, no automated cross-check. |
| 15 | **Personal email = contact address** (approvals, resets, statements). **Only the work email is API-verified.** |
| 16 | Bank account number + IFSC are **stored in full and visible to all staff**. |
| 17 | **Exactly 3 payslips**, PDF/JPG/PNG, no exceptions. |
| 18 | Screen 10's extra documents are an **optional accelerator**. |
| 19 | The **PEP tick is mandatory** — no PEP path exists; Continue stays disabled until ticked. |
| 20 | Self-employed → auto-reject + **90-day cooling-off keyed on mobile**. |
| 21 | Self-employed extra fields: **name, DOB, annual income**. |
| 22 | **Drop the referral-code field** from signup (the reward program's machinery stays, feature-flagged). |
| 23 | Password rule **6–10 chars, alphanumeric + special, for borrowers AND staff**. |
| 24 | Forgot-password keeps the **existing reset-link flow** (unchanged). |
| 25 | **Retire all three legacy legal docs** (`loan-agreement`, `loan-sanction`, `privacy-declaration`) — the T&C covers screen 1, the eSign covers the loan. |
| 26 | Each screen's fields are **saved to the server on Continue**, so another device resumes with them filled in. In-progress typing is not autosaved. |

### Phase 2 credit
| # | Decision |
|---|---|
| 27 | **The Credit Executive's decision is final** → straight to disbursement. No Head counter-approval. |
| 28 | **Both** Credit Head and Credit Executive can set the salary/repayment date. The borrower never can. |
| 29 | **Delete `REVIEW_PENDING`** — the auto-reject engine rule replaces the manual reborrow review queue. |
| 30 | "Mark lead pending" = **a tag + reason**; the lead stays in the queue. No status change, no borrower notice. |
| 31 | "Reject lead" → **notify the borrower with no reason given**, and record it in `/staff/admin/rejections` tagged `MANUAL` with the Executive's remarks (staff-only). |
| 32 | Decision history: **own decisions always; Heads see their team's; ADMIN sees everyone's**. |
| 33 | **No 25% cap** — the Credit Executive types the amount. Strip the 25% copy from every UI surface. |

### Phase 3 offer
| # | Decision |
|---|---|
| 34 | The borrower **may take less** than sanctioned: presets at 25/50/75/100% of the ceiling, slider ₹1,000 → ₹X. |
| 35 | The repayment date is **preselected and locked**; `Back` / `Lock this date`, then the same button becomes `Request for loan`. |
| 36 | The loan summary uses the **screenshot layout plus a highlighted "You will receive ₹22,050"** line. GST stays folded into the fee's "(excluding GST)" caption. |
| 37 | Expected disbursal date: **calendar days, exactly as stated** — before 18:00 IST → today, else tomorrow. No weekend/holiday roll. |
| 38 | The sanction letter / eSign document is a **full Key Fact Statement** — amount, fee, GST, net credited, interest, tenure, due date, total repayable, late-penalty and prepayment terms, grievance officer. |
| 39 | References: family + work dropdown (Parent, Spouse, Sibling, Relative, Friend, Colleague, Manager, Neighbour); the two mobiles must differ from each other **and** from the borrower's own. Capture only — not wired to the referral rewards. |
| 40 | Penny-drop abuse control: **3 failures per borrower → 12-hour lock → 3 more**. A success clears the counter. |
| 41 | A sanction **never expires** — the borrower can return at any time and finish at the same step. |

### Phase 4 disbursement, collections, re-apply
| # | Decision |
|---|---|
| 42 | Disbursement Head releases directly with a txn id; **no accountant hop**. |
| 43 | Collections: **Part/Full → straight to the Accountant. Settlement → Collection Head approval first, then the Accountant.** |
| 44 | The **Accountant** is the single checker of collections-side payments, with remarks back to the Collection Executive and Head. |
| 45 | Re-apply shows **amount + locked date + eSign** always; penny drop only if the account changes. |
| 46 | Re-apply **carries over** the sanctioned ceiling and the salary day from the last loan. |
| 47 | Auto-reject: repaid **more than 5 days after the due date** (counted from the due date, not the 1-day grace) or never fully repaid. |

### Cross-cutting
| # | Decision |
|---|---|
| 48 | Keep the existing website colour theme — **not** the orange in the reference screenshots. |
| 49 | Payment-safety line appears on: `/support`, `/grievance`, `/privacy`, `/faq`, `/contact`, `/help`, `/fair-practices`, **and the marketing footer**. |
| 50 | Support phone stays **+91 97167 60246** site-wide; the T&C document's `8510028510` is stale. |
| 51 | Grievance Redressal Officer: **Lalit Kumar, 9716760246, grievance@dhanboost.com**. |

### Implementation choices not separately confirmed
* T&C is accepted **once per borrower**, re-prompted only if the version string changes; the per-loan legal act
  is the eSign.
* The **eSign mock** renders the sanction-letter PDF, offers "Sign now", and returns success.
* **OTP resend cap stays at 3** (existing behaviour).
* **Collection Head can raise a collections payment** as well as the Executive.
* **Disbursement Head keeps a reject option** (existing behaviour).
* **`TELECALLER` is left completely alone.**

---

## Cross-cutting foundations (built in Phase 1)

### C1. Server-authoritative journey step — cross-device resume

Today resume is a localStorage breadcrumb (`navix.onboarding.lastStep`, in
`frontend/src/app/(borrower)/signup/layout.tsx`) and each step page hard-codes its own `next`. A second device
has no breadcrumb, so it restarts.

* Add `loan_application.journey_step varchar(40)`.
* New `JourneyService` in `navix-loan` — one ordered step registry spanning all phases, with `current(app)`
  derived from `status` + `journey_step` + `application_verification` rows. Every borrower write advances it
  **and persists that screen's fields** (decision 26).
* New `GET /api/applications/{id}/journey` (BORROWER, ownership-checked) → `{phase, step, route, completed[]}`.
* Frontend: replace `frontend/src/lib/onboarding.ts`'s `ONBOARDING_STEPS` + `nextAfterStep` with a registry at
  `frontend/src/lib/journey.ts` mirroring the backend, plus a `useJourneyGuard()` hook mounted in the wizard
  layout that `router.replace()`s when the URL doesn't match the server's step. Each step page hydrates its form
  from `GET /{id}/profile` rather than the zustand draft.
* The app-id pointer already recovers on a fresh device via `pickCurrentAppId()` → `GET /mine`
  (`frontend/src/lib/api/live-journey.ts`) — reuse it, don't rebuild.

### C2. Theme discipline (decision 48)

Rebuild every screen with existing tokens only — navy `#0C2540`, gold token `#14A06B`, cream `#FDFBF6`,
`font-serif` (Bricolage display) / `font-sans` / `font-mono`, and the `globals.css` component classes `.btn*`
`.card` `.field` `.cal-*` `.checkbox`. No new colours.

### C3. Strip the 25%-of-salary cap (decision 33)

Borrower surfaces: `(borrower)/dashboard/page.tsx`, `signup/salary/page.tsx`, `signup/review/page.tsx`,
`loan/apply/page.tsx`, `loan/salary/page.tsx`, `lib/calc/loan-math.ts`, `lib/domain/loan.ts`. Staff copy:
`components/staff/application-detail-dialog.tsx`, `app/staff/applications/page.tsx`.
Backend `LoanMath.eligibleLimitPaise` / `EligibilityService` survive as **staff guidance only** — the `apply`
gate becomes "≤ the credit-sanctioned amount".

### C4. Payment-safety rotating notice (decision 49)

New `frontend/src/components/site/payment-safety-ticker.tsx` — CSS marquee, honours `prefers-reduced-motion`
(static fallback). Text in `frontend/src/lib/brand.ts` as `PAYMENT_SAFETY_NOTICE`:

> Always use our secure Repayment on Website Link for loan payments. Do not make direct bank payments to fake
> UPI links or unauthorised payment links. DhanBoost is not responsible for payments made to other accounts.

Mount on `(borrower)/support`, marketing `grievance` / `privacy` / `faq` / `contact` / `help` /
`fair-practices`, and `components/site/marketing-footer.tsx`.

### C5. Grievance redressal officer (decisions 50, 51)

Add to `frontend/src/lib/brand.ts`:
`grievanceOfficer: { name: "Lalit Kumar", phone: "9716760246", email: "grievance@dhanboost.com" }`.
Surface in `(marketing)/_content/grievance.ts` (Level 2 block) and `(borrower)/support/page.tsx` — which
currently **hardcodes** `SUPPORT_EMAIL`/`SUPPORT_PHONE` instead of importing `BRAND`; fix that.

### C6. Password policy (decision 23)

`navix-app/.../auth/PasswordPolicy.java` — one rule for both audiences: **6–10 chars, ≥1 letter, ≥1 digit,
≥1 special**. Frontend `policyOk` in `signup/set-password`, `(borrower)/reset-password`,
`staff/reset-password`, `staff/activate` all updated.
⚠️ The seeded `Admin@12345` is 11 characters — existing logins keep working (BCrypt compare doesn't re-check
policy), but any staff password *reset* must now be ≤10 characters.

---

## PHASE 1 — new borrower onboarding  ✅ BUILT

### The 10 screens

| # | Route | Screen | Writes / fires |
|---|---|---|---|
| 1 | `signup/start` | PAN + mobile · "I agree to the Terms & Conditions" (opens modal) · "I confirm I am not a Politically Exposed Person or related to one" — **both mandatory** | draft; `terms_version/accepted_at`, `pep_declared_at` |
| 2 | `signup/otp` | Verification code + **Resend** (max 3) | borrower session (`ensureBorrowerSession`) |
| 3 | `signup/employment` | Picklist: **Salaried** / **Self-employed** | `employment_status` |
| 4 | `signup/set-password` | Optional, **Skip** allowed. 6–10 chars, alnum + special | `borrower_credential` |
| 5a | `signup/employer` *(salaried)* | Company name · **previous salary date** (full date picker) · monthly salary (**net/in-hand**) | profile; `salary_credit_day` derived |
| 5b | `signup/self-employed` | Name · date of birth · annual income → Submit | profile + **auto-reject + 90-day block** |
| 6 | `signup/email` | Personal email + official work email | `email` = personal/contact, `official_email` = work |
| 7 | `signup/bank` | Salary bank · account number · IFSC · mobile linked to that account | profile — **stored in full**, no penny drop here |
| 8 | `signup/payslips` | **Exactly 3** payslips, PDF/JPG/PNG | `SALARY` check + S3 documents |
| 9 | `signup/consent` | Re-enter OTP + tick "I consent to a credit bureau enquiry" → **fires PAN-206, work-email verification, bureau** | `BUREAU_CONSENT`, `PAN`, `EMAIL`, `BUREAU` rows; **name + DOB land here** |
| 10 | `signup/submitted` | "Your application is with our credit team" · **optional** extra docs (electricity/gas bill, rent agreement) · support number · calm UI | `submit-kyc` → `KYC_PENDING` |

Every field is mandatory except screen 4 (skippable by design) and screen 10's extra documents.

### Screen 9 — consent, verification, failure handling

* The OTP + consent tick reuse `recordBureauConsent(appId, otp, consentText)` via `OtpVerifierPort`.
  **Preserve the `da2d9ba` mobile backfill** — a returning borrower who skips screens 1–2 otherwise dead-ends on
  `MOBILE_MISSING`.
* Three calls run behind one "Verifying your details…" progress state: `verifyPan`, `verifyEmail` (on the
  **work** address), `pullBureau`. The bureau score is never shown to the borrower.
* `verifyPan` already persists DOB and already returns `fullName` in `derived` — add the matching
  `profile.setFullName(r.fullName())` write (decision 12), with the same don't-overwrite guard as DOB.
* **A FAIL does not block (decision 10).** The submission gate changes from "every required check is PASS or
  REVIEW" to "every required check has been **attempted**" — a row exists in any terminal status
  (`PASS`/`REVIEW`/`FAIL`). `KYC_INCOMPLETE` now only fires when a check was never run (e.g. no payslips).
* When PAN-206 fails the name is unknown; staff surfaces render **"PAN ABCDE1234F · name unavailable"** rather
  than a blank row.

### Terms & Conditions modal

* Content → `frontend/public/legal/terms-and-conditions.txt`, version `terms-and-conditions@1`. Full text from
  `Term and Condition Declaration .docx`, including the Grievance Officer and Contact Information sections.
* New `frontend/src/components/borrower/terms-modal.tsx` on `components/ui/dialog.tsx`.
  **Must use `!max-w-3xl !w-[min(52rem,94vw)]`** — `globals.css`'s un-layered `.modal { max-width: 460px }`
  outranks plain Tailwind utilities (same trick as `application-detail-dialog.tsx`). Body
  `max-h-[70vh] overflow-y-auto`; footer **Decline** (`btn btn-outline`) / **Agree** (`btn btn-gold`).
* **Decline** → close, checkbox stays unchecked, Continue stays disabled.
* Re-prompted only when the version string changes.

### Self-employed auto-reject + 90-day block

* Persist name / DOB / annual income (reuse `customer_profile.annual_salary_paise`), set
  `employment_status = SELF_EMPLOYED`, then `DRAFT → REJECTED` via
  `ApplicationFlowService.autoReject(appId, reasonCode, detail)`. Requires adding `REJECTED` to `DRAFT`'s set in
  `ApplicationStatus.TRANSITIONS` (the DB CHECK already permits the value).
* New table `application_rejection` — `application_id, customer_id, mobile, reason_code, reason_detail, auto,
  blocked_until, created_at`. Reason codes: `SELF_EMPLOYED`, `PAST_DELINQUENCY` (Phase 4), `MANUAL` (Phase 2).
* `blocked_until = now() + 90 days`, keyed on **mobile**. `assertCanStartNewApplication` gains the block check —
  a blocked applicant gets the same neutral message, including if they now pick Salaried.
* Borrower only ever sees: *"You are not eligible at the moment. Please try again later."*
* New ADMIN page `/staff/admin/rejections` with a reason filter, reusing the `ExportMenu` CSV/PDF pattern.

### Verification gating split

* `REQUIRED_INTAKE` = `PAN, EMAIL, BUREAU, SALARY` → gates `submit-kyc` (attempted, not passed).
* `REQUIRED_SANCTION` = `AADHAAR, SELFIE, ADDRESS, ESIGN` → Phase 3, non-blocking (decision 11).
* **`PENNY_DROP` is no longer a required check anywhere** — per decision 9 it may legitimately never run.

### Migration `V44__phase1_onboarding.sql`

```
loan_application  + journey_step varchar(40)
customer_profile  + official_email varchar(255)
                  + salary_account_number varchar(32)
                  + salary_ifsc varchar(16)
                  + salary_account_mobile varchar(15)
                  + previous_salary_date date
                  + terms_version varchar(40)
                  + terms_accepted_at timestamptz
                  + pep_declared_at timestamptz
new table         application_rejection
delete            all loan_application rows in DRAFT + their child rows   (decision 3)
```

---

## PHASE 2 — roles + credit workbench  ✅ BUILT

### Delete `KYC_APPROVER` (decision 2)

Migration `V45` rewrites the `staff_user` role CHECK (following `V42__telecaller_role.sql`) and moves existing
rows to `CREDIT_EXECUTIVE`; seeds in V10/V19/V38 updated. Code: `StaffRole.java`,
`frontend/src/lib/auth/rbac.ts`, `components/staff/pipeline/hooks.ts`, `RecipientPolicy.TO_KYC_APPROVERS` →
`TO_CREDIT_TEAM`, `AudienceResolver`, `ApplicationFlowService.CANCEL_STAFF_ROLES`, `SecurityMatrixIT`.
`requireRole("KYC_APPROVER")` in `decideKyc` / `manualDecision` / `sendKycReminder` becomes a varargs
`requireAnyRole("CREDIT_HEAD", "CREDIT_EXECUTIVE")`. `decideReview` is deleted with `REVIEW_PENDING`.
`TELECALLER` is untouched.

### The new state machine

```
DRAFT ──submit-kyc──▶ KYC_PENDING ──Credit Head: assign──▶ CREDIT_EXEC_PENDING
  │                                                              │
  │ auto-reject                              Credit Executive: Accept lead
  ▼                                             (amount + date + remarks)
REJECTED  ◀── Reject lead ────────────────────────────────────┤
                                                              ▼
                                                         SANCTIONED   ← new
                                            (borrower completes Phase 3)
                                                              │ eSign + account confirmed
                                                              ▼
                                              DISBURSEMENT_PENDING
                                                              │ Disb. Head + txnRef
                                                              ▼
                                                    DISBURSED ─▶ ACTIVE
```

`SANCTIONED` is new and unavoidable — the borrower's Phase-3 journey sits between the credit decision and
disbursement. **It must be added to `APPROVED_APP` in `frontend/src/lib/customers/segments.ts`**, or every
sanctioned customer falls through that function's default and shows as "pending" in the CRM.
`CREDIT_HEAD_PENDING` / `CREDIT_HEAD_APPROVED` / `REVIEW_PENDING` stay in the enum for historical rows but leave
the live path. Reborrow: `DRAFT → PRE_APPROVED` (clean) or `DRAFT → REJECTED` (auto); `PRE_APPROVED → SANCTIONED`
on re-apply.

New `loan_application` columns: `sanctioned_amount_paise`, `approved_repayment_date`, `sanction_tenure_days`,
`sanction_remarks`, `sanctioned_by`, `sanctioned_at`, `marked_pending_at`, `pending_reason`.
`LoanService.disburse` prefers `approved_repayment_date` over `loanMath.dueDateFromSalary`, **falling back to
the computed date if the approved one is already in the past** (a never-expiring sanction, decision 41, would
otherwise produce a negative tenure).

> **Naming:** the UI labels stay "Accept lead / Reject lead / Mark lead pending", but a `Lead` entity now exists
> for telecaller DSA intake. No backend type in this work may be called `Lead` — use `sanction*` /
> `ApplicationDecision`.

### Details popup

Widen `components/staff/application-detail-dialog.tsx` from `!max-w-5xl !w-[min(72rem,96vw)]` to
`!max-w-[80vw] !w-[80vw]`, body `max-h-[80vh]`. **Font size unchanged** (`text-[13px]`) — "highlighted" is
delivered by emphasising `KV` values (`font-semibold text-navy`) and card separation, not by scaling type. Add a
**Wait time** block (time since submission + time in current stage) from `application_event`. Reuse
`components/staff/customer-tabs.tsx`.

Queue row actions become **Reject lead · Mark lead pending · View details · Accept lead**.
**Accept lead** opens `components/staff/sanction-dialog.tsx`: sanctioned amount · bank account + IFSC
(prefilled, read-only) · salary date · a **live repayment schedule preview** (reference date, tenure, total
repayable via `lib/calc/loan-math.ts`) · remarks · Submit.

**Decision history** — `GET /api/staff/decisions?staffId=` returning own rows by default; a Head may pass a
subordinate's id, ADMIN any id. New `/staff/my-decisions` page with a team switcher for Heads.

---

## PHASE 3 — post-approval borrower journey  ✅ BUILT

**Delivered as specified below, with these notes:**
* `SANCTIONED` is the borrower's own stage. `JourneyService` gained a second registry
  (`OfferStep`, 11 steps) sharing the one `journey_step` column; the two are read under mutually
  exclusive statuses. `POST /{id}/journey/{step}` now takes the step as a **string** and resolves it
  against either registry — binding it to the intake enum silently 400'd every `OFFER_*` advance.
* New `OfferService` + `OfferController` under `/api/applications/{id}/offer/*` (amount, references,
  summary, sanction-letter, esign, disbursal-account) — borrower-only and ownership-checked.
* `acceptOffer` is now **gated on a terminal `ESIGN` row**. Phase-3 identity failures pass through
  silently (decision 11), but the signature is the borrower's agreement to the KFS, not a check —
  without the gate the endpoint was a way to reach disbursement having signed nothing.
* The penny-drop strike log lives in its own bean (`PennyDropGuard`) with `REQUIRES_NEW` writes: a
  failed attempt is *rejected*, so recording the strike in the caller's transaction rolled it back
  and the lock could never fire. Caught on the live walk, not by the unit tests.
* The disbursal account is on `loan_application`, **not** `customer_profile` — profile is where the
  borrower's *salary* lands and is what credit verified against; overwriting it on a change would
  destroy exactly that. Both are surfaced in full on the Disbursement Head's card, whose penny-drop
  line now reads three ways ("Verified" / "Not verified" / **"Not run — borrower kept their salary
  account"**), since the never-checked case is not the same as a failed one.
* The eSign mock is registered from a `@Bean` (`EsignConfig`), not a scanned `@Component`:
  `@ConditionalOnMissingBean` on a component evaluates against a half-built registry and silently
  registers nothing — the app failed to start that way.
* The sanction letter fails **loudly** (`SANCTION_LETTER_UNAVAILABLE`) if S3 is unreachable, unlike
  the best-effort credit brief: the borrower has to read and sign this one.
* **Not verifiable locally:** the sanction-letter PDF store, eSign, selfie and DigiLocker all need
  AWS credentials or a live provider. The renderer, the offer service and the journey are covered by
  unit tests; the rest of the chain was walked over HTTP with an eSign row seeded.

| # | Route | Screen |
|---|---|---|
| 1 | `/loan/amount` | "You are eligible for up to ₹X, approved by our credit team". Presets at 25/50/75/100% of the ceiling, slider ₹1,000 → ₹X. No coupon field. |
| 2 | `/loan/repayment-date` | Calendar preselected to `approved_repayment_date`, **not changeable**. `Back` / `Lock this date` → the same button becomes `Request for loan`. |
| 3 | `/loan/digilocker` | DigiLocker (move `signup/digilocker` + `kyc/digilocker/callback`; the live-flow gotchas in CLAUDE.md §14 still apply). |
| 4 | `/loan/references` | "Refer & Earn Gifts and Vouchers" — 2 × (full name, 10-digit mobile, relation). Relations: Parent, Spouse, Sibling, Relative, Friend, Colleague, Manager, Neighbour. Both mobiles must differ from each other and from the borrower's own. |
| 5 | `/loan/summary` | Loan Amount · Interest Rate · Tenure · Processing Fee (excluding GST) · Repayment · **You will receive ₹X** (highlighted) · Expected Disbursal Date (today if now < 18:00 IST, else tomorrow; calendar days). |
| 6 | `/loan/selfie` | Signzy liveness (move `signup/selfie`, incl. the Digitap face-match fallback). |
| 7 | `/loan/address` | Geo address verification (move `signup/address`). |
| 8 | `/loan/sanction-letter` | **Preview sanction letter** — a full Key Fact Statement PDF via OpenPDF, same approach as `CreditBriefService`, stored to S3 as `SANCTION_LETTER`. No submit button. |
| 9 | `/loan/esign` | **Mocked** behind a new `EsignPort` seam in `navix-verification` + `MockEsignAdapter`; writes an `ESIGN` verification row and a `SIGNED_AGREEMENT` document. |
| 10 | `/loan/sanctioned` | "Your loan has been SANCTIONED! One more step to go." + CSS confetti (`prefers-reduced-motion` respected) + `Continue` + a Sanctioned → Disbursal indicator. |
| 11 | `/loan/disbursal-account` | Confirm the salary account (number, IFSC, holder name) **or** enter another. **Penny drop fires only on a changed account.** On failure: "Invalid account details, please enter the bank details again." 3 failures → 12-hour lock → 3 more; a success clears the counter. |

Migration `V46`: `application_reference`; `penny_drop_attempt` + `customer_penny_drop_lock(customer_id,
locked_until)`; `loan_application.disbursal_account_*`.
Notifications: reuse `CREDIT_APPROVED` for the approval email + in-app; add `LOAN_SANCTIONED`.
⚠️ **SMS will silently no-op** — only the legacy `NAVIX_OTP_LOGIN_V2` template is DLT-approved; all 15
`DHANBOOST_*_V1` templates are still pending (`docs/sms-dlt/DLT_SUBMISSION_TRACKER.md`).

---

## PHASE 4 — disbursement, collections, relogin, engine  ✅ BUILT

**Delivered as specified below, with these notes:**
* **Migration `V47`** — `collection_payment` (+ a partial unique index on `(loan_id, txn_ref)` that
  ignores rejected rows, so an officer can re-raise a corrected payment) and
  `loan_application.reapplied_from`.
* **The accountant disbursement hop is gone entirely (`V48`).** V47 left `ACCOUNTANT_PENDING`
  reachable so parked files could be walked out; that half-measure is now closed. **The Disbursement
  Head's transaction id *is* the validation** — there is no second desk behind them, no
  `accountant-validate` endpoint, and no `AccountantActions` in the console. V48's migration moves
  the files still parked there back onto the Head's desk (with an `application_event` row, so no
  status changes without a trace) rather than stranding them. `ACCOUNTANT_PENDING` stays in the enum
  and the DB CHECK because the audit trail records it on every loan that ever went through the old
  chain — the vocabulary is history, only the route is gone.
* **`retryDisbursement` returns a failed transfer to `DISBURSEMENT_PENDING`.** The Head who owns the
  release retries it.
* **The Accountant's dashboard no longer counts applications at all** — their work is money coming
  back in: repayments to verify, and collections payments to validate.
* **A collections payment credits the loan through one seam**, `LoanDirectory.creditCollectionPayment`
  (implemented in `navix-loan`, called only from the Accountant's validation). It records **and
  verifies** in one step — the validation *is* the verification, and leaving it PENDING would queue
  the same payment for the same person twice. `collection_payment.ledger_payment_id` is the
  idempotency guard.
* **A `SETTLEMENT` payment must name an open settlement on the case.** Without that, "settlement"
  would be a way to write down a balance nobody agreed to; the Collection Head's approval of the
  *concession* is what makes it one. A payment against an already-approved settlement skips the
  head-approval hop, since the approval it was waiting for has happened.
* **Remarks are mandatory on an Accountant rejection** and are notified to both the officer who took
  the payment and the Collection Head — a rejection the officer can't act on is just a payment that
  vanished.
* **The re-apply journey is genuinely shorter, and says so.** `JourneyService.applicableOfferSteps`
  drops DigiLocker / references / selfie / address for a re-apply, and `JourneyView` now carries the
  step list, so the wizard reads "Step 1 of 7" rather than lying with "of 11". The list is keyed on
  `reapplied_from`, not on "does a check row exist", so it stays stable for the whole journey.
* **The carry-over copies evidence, not consent.** The Aadhaar/selfie/address rows, the references and
  the disbursal account move to the new application (each verification re-stamped "Carried over from
  application N", so staff never see borrowed evidence passed off as a check run today). The **eSign
  is deliberately not carried** — every advance is signed against its own Key Fact Statement. The
  repayment date is recomputed from the carried salary day; copying the old one would sanction a date
  already in the past.
* **The engine rule is now a severity test, not "ever late."** `isDisqualifiedByHistory` replaces
  `hasPastDelinquency`: >5 days past the due date, or never fully repaid. This matters more than it
  looks — V45 turned the delinquent fork into an outright auto-reject, so the old predicate would have
  permanently barred anyone who was ever a single day late.

**Two bugs the live walk caught that the unit tests didn't:**
1. **A paid full-and-final left the loan open.** Approving the settlement *payment* and approving the
   settlement itself were two separate clicks, and `RepaymentService.outstandingAsOf` only caps
   against an **approved** settlement — so a borrower who paid the agreed ₹8,000 on a ₹15,150 balance
   still owed ₹7,150. `headApprove` now does both halves of the one decision; the re-walk closed the
   loan at zero.
2. **Every re-apply penny-dropped.** `copyProfileForReborrow` predates V44 and never carried
   `salary_account_number` / `salary_ifsc`, so the Phase-3 confirm screen compared the typed account
   against nothing, called it "changed", and fired a penny drop on a borrower who had changed nothing
   — the opposite of decision 45. The profile copy now carries the V44 intake fields (bank, official
   email, previous salary date, terms + PEP), and `confirmDisbursalAccount` also treats **the account
   the last advance was paid into** as an unchanged account.

⚠️ **Not verifiable locally:** the notification fan-out for the four new types needs a running
dispatcher with staff inboxes; it is unit-tested at the listener but not walked end-to-end. SMS
remains dark (DLT), so these are in-app only by design.

* **Disbursement Head releases directly**: `txnRef` becomes required on accept; the `ACCOUNTANT_PENDING` branch
  leaves the disbursement path (`accountantValidate` stays for historical rows). Reject stays available.
* **Accountant** narrows to repayment verification + collections payment validation; drop the disbursement
  queue from their `RoleQueues`.
* **Collections** (decisions 43, 44): the Collection Executive (or Head) tags a payment `PART_PAYMENT` /
  `FULL_PAYMENT` / `SETTLEMENT`, raising a `collection_payment` row.
  * Part / Full → straight to the Accountant's validation queue.
  * Settlement → **Collection Head approves first** (existing settlement maker-checker), then the Accountant.
  * The Accountant validates or rejects **with remarks**, notified back to the Collection Executive and Head.
* **Relogin** already supports OTP + password; forgot-password keeps the existing reset-link flow.
* **Re-apply** (decisions 45, 46): `reborrow()` → `PRE_APPROVED` → `SANCTIONED`, carrying over the last loan's
  sanctioned ceiling and salary day. The borrower sees **amount → locked date → summary → sanction letter →
  eSign → 🎉**, then straight to the Disbursement Head. DigiLocker, selfie, geo-address and references are
  reused; penny drop fires only if they change the account.
* **Engine rule** (decision 47) — replace `ApplicationFlowService.hasPastDelinquency` with a severity check: any
  prior loan repaid **more than 5 days after its due date** (counted from the due date, not the 1-day grace) or
  never fully repaid (DEFAULTED / WRITTEN_OFF / still outstanding past due) → **auto-reject** with reason
  `PAST_DELINQUENCY` into `application_rejection` and the admin register. 1–5 days late passes straight through.

---

## Verification (Phase 1)

**Run it** — `.\scripts\run-demo.ps1` (backend :8090, Postgres :5433) or `docker compose up -d` +
`./mvnw -pl navix-app spring-boot:run`, then `cd frontend && npm run dev`. Set `NAVIX_SMS_MOCK=true`
(OTP `123456`) and `NAVIX_BUREAU_FIXTURE=classpath:samplepan.json` for an offline bureau pull. Java 21 required.

**Acceptance walk**
1. `/signup/start` — PAN + mobile; open the T&C modal, **Decline** → Continue stays disabled; reopen, **Agree**;
   leave the PEP box unticked → Continue still disabled; tick it → Continue.
2. OTP `123456`; confirm **Resend** works and caps at 3.
3. Pick **Self-employed** → name / DOB / annual income → Submit → the neutral "not eligible" screen. Confirm
   `/staff/admin/rejections` shows the row with reason `SELF_EMPLOYED` and `blocked_until` 90 days out; then
   start a fresh application on the same mobile and confirm it is blocked with the same neutral message,
   including when picking **Salaried**.
4. Fresh mobile, pick **Salaried** → password rules (`abc12!` passes, `abc123` fails, 11 chars fails) and the
   **Skip** path → company / last-paid date / net salary → both emails → bank details (confirm **no** penny drop
   fires) → exactly 3 payslips (2 must not let you continue) → **consent screen**: OTP + tick → watch PAN-206,
   work-email verification and the bureau pull land (`GET /api/applications/{id}/verify/summary`) and the
   borrower's **name and DOB appear** on the profile → the "with our credit team" screen; skip the extra
   documents, then upload one and confirm it reaches the staff documents tab.
5. **Resumed-draft consent** — sign in as an existing borrower and start a new application (skipping screens
   1–2); confirm screen 9's OTP step-up still resolves a mobile and does not fail `MOBILE_MISSING` (`da2d9ba`).
6. **Failure path** — force a PAN failure (bad PAN) and confirm the application still reaches `KYC_PENDING`,
   shows red in the staff details popup, and renders "name unavailable" rather than a blank name.
7. **Cross-device resume** — mid-flow, sign in to the same account in a private window and confirm it lands on
   the same step **with the previously submitted fields filled in**. Then clear localStorage in the original tab
   and reload.
8. **CRM segments** — confirm a fresh application shows under **Pending** and a wiped/abandoned one no longer
   appears under **Incomplete** on `/staff/customers`.
9. Confirm no 25%-cap copy remains on `/dashboard` or in `/signup/*`; that `/loan/apply` and `/loan/salary`
   redirect; that the payment-safety ticker renders on all seven pages and the marketing footer; and that the
   grievance officer is named on `/grievance` and `/support`.

**Checks** — `cd backend && ./mvnw test`; `cd frontend && npx tsc --noEmit`, `npx eslint .`, `npx vitest run`.
Do **not** run `npm run build` while `npm run dev` is up (it corrupts `.next`); `npm run build`'s
static-prerender step fails at `/staff/admin/staff` on a clean checkout — a known Next 15.1.3 issue.

## Risks

These follow from decisions taken deliberately; listed so they stay visible.

* **Unverified disbursal accounts** (decision 9) — a borrower who keeps their salary account is never
  penny-dropped, so the first disbursal can go to an account number that was only typed in. The Credit
  Executive's read of the details popup is the only check on it.
* **Unreviewed Phase-3 failures** (decision 11) — a failed DigiLocker, selfie or geo-address does not block and
  does not flag anyone; it only appears in the Verification Dashboard. Money can be released before anyone looks.
* **Stale sanctions** (decision 41) — an offer never expires, so a borrower can return months later against a
  bureau pull and a repayment date that are long out of date. Guarded in code against negative tenure, but the
  credit decision itself is not re-tested.
* **Wiping DRAFTs destroys the telecaller's chase list** (decision 3) — DRAFT is exactly the `incomplete`
  customer segment the CRM surfaces as call targets. V44 empties it. Irreversible; confirm the target database.
* **Bank PII** (decision 16) — full account numbers and IFSCs are now stored and readable by every staff role,
  including `TELECALLER`, which holds `customer:view`. Never logged, never in exports.
* **Shorter staff passwords** (decision 23) — staff passwords are capped at 10 characters.
* **Deleting `KYC_APPROVER`** (Phase 2) touches seeds, tests, notification audiences and the security matrix.
* **SMS stays dark** until the DhanBoost DLT templates are approved. Any backend redeploy must be built from
  **ECS task-def revision 4** (it pins `NAVIX_SMS_OTP_TEMPLATE` to the approved NAVIX wording).
