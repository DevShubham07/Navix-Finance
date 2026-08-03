# DhanBoost — Product Walkthrough Summary

*A 5-page condensation of `DEMO_WALKTHROUGH.md` (13 chapters, ~28–30 min recording) plus the
product rules from `CLAUDE.md` that the walkthrough narrates. Read this if you want the story of
the demo without the shot-by-shot script.*

---

## Page 1 — What the product is, and what the demo proves

**DhanBoost** (internal namespace still `navix`, legal entity NAVIX Finance Private Limited) is a
**salary-linked, single-repayment lending platform**. A salaried borrower draws a short advance,
pays the fee upfront, and repays **once** on or just after their next salary credit.

The economics, in one table — all money is integer paise, HALF_UP:

| Rule | Value |
|---|---|
| Eligible limit | 25% of monthly salary, floored to ₹100 |
| Minimum loan | ₹1,000 |
| Processing fee | 10% of principal, upfront (deducted from disbursal) |
| GST | 18% on the fee, upfront |
| Interest | 1%/day on principal, over the actual tenure |
| Due date | the borrower's next salary credit, **≤ 40 days** after disbursal |
| Late penalty | 2%/day on principal, capped at 30 days |
| Repayment | a single installment |

Borrower **receives** `principal − fee − GST`; **repays** `principal + interest` (+ penalty if
overdue). Worked example: ₹10,000 → fee ₹1,000, GST ₹180, **net ₹8,820**; disbursed 3 June with a
salary day of 30 → due 30 June (27 days) → total repayable **₹12,700**.

**Risk categories A/B/C/D affect limits and required checks, never price.** One price for everyone
is a deliberate product rule, and the walkthrough says it out loud at the credit chapter.

### The three claims the walkthrough is built to demonstrate

1. **It is one real state machine, not a set of screens.** Every queue panel on the staff console
   is literally one `ApplicationStatus`. There is no stage-skipping: each transition is validated
   server-side against a transition map and written to an append-only `application_event` audit
   trail.
2. **Separation of duties is enforced in the backend, not in the UI.** The recommender and the
   approver are two different real logins with two different staff ids; the backend replays the
   event trail on every head-decision and rejects a self-approval with `SOD_VIOLATION`.
3. **Money is auditable end to end.** Every "amount due" on screen spells out its own arithmetic
   ("1%/day × 28 days" interest, "2%/day × 30 days" penalty), and the outstanding balance is
   recomputed on every read rather than trusted from a stored column.

### The demo stack

Fully offline and local: Postgres on `:5433`, backend on `:8090`, frontend on `:3000`
(`scripts/run-demo.ps1`), seeded by `scripts/seed-demo-data.ps1` with one persona at **every**
lifecycle stage plus every back-office surface. Sign-in for the whole recording is a single ADMIN
login (`meera.krishnan@navix.example`); every persona switch afterwards happens through the
floating **"Act as role"** bar in the bottom-right, never by logging out.

---

## Page 2 — The lifecycle, and chapters 1–4

### The spine

Everything is **one aggregate** — a single `loan_application` row whose `status` walks this path:

```
DRAFT → KYC_PENDING → KYC_APPROVED → (borrower applies: amount + purpose + salary day)
      → CREDIT_EXEC_PENDING → CREDIT_EXEC_APPROVED → CREDIT_HEAD_PENDING → CREDIT_HEAD_APPROVED
      → DISBURSEMENT_PENDING → ACCOUNTANT_PENDING → DISBURSED → ACTIVE → CLOSED
                                                              ↘ OVERDUE → DEFAULTED → WRITTEN_OFF
```

Rejections branch out at each decision point; `CANCELLED` is available pre-disbursement. Three
transitions are auto-routed by the system without a separate actor call
(`CREDIT_EXEC_APPROVED → CREDIT_HEAD_PENDING`, `CREDIT_HEAD_APPROVED → DISBURSEMENT_PENDING`,
`DISBURSED → ACTIVE`, which mints the loan).

Nine staff roles map onto it: KYC_APPROVER, CREDIT_HEAD, CREDIT_EXECUTIVE, DISBURSEMENT_HEAD,
ACCOUNTANT, COLLECTION_HEAD, COLLECTION_EXECUTIVE, ADMIN (oversight, bypasses role checks) and
DEVELOPER (read-only).

### Ch. 1 — Sign in & dashboard (~2 min)

The login page is **email + password only, no role picker**, footnoted "Separation of duties is
enforced on every decision." After signing in as ADMIN: a welcome hero, a **"Your work"** card, the
three **Applications / Disbursals / Repayments** sparklines over the last 30 days, a **"Pipeline at
a glance"** bar, and a collapsed ADMIN-only **Transactions** panel.

The narration point: every other role's dashboard shows a *personal* queue count ("N items need
your action"). ADMIN's is deliberately different — an oversight surface, because ADMIN can act in
any role.

### Ch. 2 — Live pipeline (~1.5 min)

`/staff/applications` is the single workbench. As ADMIN, all eight queue panels stack up: KYC
pending, Reborrow reviews, Credit queue (assign an executive), Credit head decision, Credit
executive review, Disbursement pending, Accountant validation, Awaiting repayment, and a collapsed
Closed panel. This page *is* the state machine rendered.

*Recording note:* the Closed panel is a native `<details>` that doesn't fire its query until
expanded — deliberate, so the console never polls the closed archive in the background.

### Ch. 3 — Verification dashboard (~2 min)

Five tiles (Pending, Failed, In review, Passed, Never run) over four triage buckets in priority
order: Has failures → Awaiting borrower steps → All checks passed → Not started. Opening a card
gives the check-by-check dialog; **Manual override** offers PASS/FAIL with a remarks field.

Eight required checks — **PAN, Email, Address, Aadhaar, Bureau, Salary, Penny-drop, Selfie** — must
all clear before `submit-kyc` is even permitted (`KYC_INCOMPLETE` otherwise). A manual override is
itself audited, so an approver can clear a borderline case without pretending the automated check
passed. The dashboard tracks only *pending* work (DRAFT / KYC_PENDING / REVIEW_PENDING); it is not
a historical log.

### Ch. 4 — KYC approvals (~2 min)

Two queues, deliberately kept apart. `/staff/kyc-approvals` holds applications awaiting KYC
clearance, plus a second panel — **"Approve instant loans (credit clearance)"** — the credit
fast-path, where an approver can wave a low-risk application straight to the Disbursement Head and
skip the executive→head chain entirely.

`/staff/kyc-review` is the **reborrow** queue: returning borrowers who were *ever* overdue, with
inline loan history per row and buttons labelled "Clear borrower" / "Reject". A returning borrower
with a clean history never appears here — they go straight to `PRE_APPROVED`.

---

## Page 3 — Credit, separation of duties, disbursement (chapters 5–7)

### Ch. 5 — Credit (~3 min)

The Credit Head assigns from a Select of **ACTIVE Credit Executives only** — an activation-gating
rule, not a cosmetic filter. Opening any application raises the detail popup:

- **Header** — the `CreditBadge`: "CIBIL 778 · ★★★★☆ 4.0". This comes from a real Experian/CRIF
  bureau pull, parsed into a 1–5★ recommendation plus a branded one-page PDF brief.
- **Journey tab** — a clickable stepper, plus the **Cost** card (fee / GST / net disbursed / total
  repayable, or a pre-disbursement projection when there's no loan yet).
- **Loan history**, **Customer review**, and a collapsed **Audit log** — every action, actor and
  timestamp.

The line to say on camera: the CIBIL score and ★ rating are **staff-only, never shown to the
borrower**, and they do not change the price. A fast-tracked reborrow shows a gold "Fast-track"
pill next to the Journey heading.

*Recording note:* the credit brief's "Download PDF" won't resolve offline (no S3), though the
score, rating and facts all render.

### Ch. 6 — Maker-checker on camera (~3 min)

The centrepiece. Switch to **Rahul Mehta (Credit Executive)** via the role bar, approve an
application in `CREDIT_EXEC_PENDING`; switch to **Priya Nair (Credit Head)**, open the same
application, now `CREDIT_HEAD_PENDING`, and approve. Two real logins, two real staff ids, one loan.

**An important correction the script carries:** you cannot produce a live `SOD_VIOLATION` error on
screen under the current build. Three reasons —

1. `ApplicationFlowService.headDecision` **explicitly exempts ADMIN** from the SoD check (oversight
   may approve its own recommendation, per-step). So driving both steps as Meera Krishnan succeeds
   silently rather than throwing.
2. The frontend helper `evaluateSoD` in `lib/auth/rbac.ts` is **dead code** — not called anywhere
   outside its own definition file. It gates no button today.
3. The role bar always logs a role in as that role's one fixed seeded persona, so two non-admin
   personas can never collide on the same application either.

The only way to force the red banner is off-script: temporarily change Rahul Mehta's role to
CREDIT_HEAD *after* he has recommended, sign back in as him, approve his own recommendation, then
revert. Treat it as optional — the two-persona handoff is the reliable demonstration.

### Ch. 7 — Disbursement (~2 min)

Three stacked panels for the Disbursement Head: **Pre-approved — fast-track release** (reborrows
that skipped credit), **Standard disbursement**, and **Disbursement failed — retry**. Each row
carries a transaction-id input with the hint: *"Enter a transaction id to release & activate
immediately, or approve without one to send it to the accountant."*

Both branches are worth showing. With a txn id, the flow service finalizes the release directly
(`DISBURSEMENT_PENDING → DISBURSED → ACTIVE`) — a deliberate, documented relaxation of the
Disbursement-Head ≠ Accountant separation. Without one, it routes to `ACCOUNTANT_PENDING` and feeds
the next chapter.

---

## Page 4 — Money, collections, customers (chapters 8–10)

### Ch. 8 — Accounting (~2.5 min)

Two queues for the Accountant: **"Transfers to confirm"** (Confirm transfer / Mark failed) and
**"Repayments to verify"** — borrower self-reported UPI/bank payments sitting in
`PENDING_VERIFICATION`.

The significance: confirming a transfer is what actually **mints and activates the loan**;
verifying a repayment is what **reduces the outstanding** and can close a loan at zero. Both are
manual human confirmations — the product has no auto-reconciliation, by design. Every maker-checker
step also has a reject path (reject a repayment, mark a transfer failed), SoD-checked and audited.

The header's **"All transactions"** button opens the company-wide ledger: outgoing disbursals and
incoming repayments, Direction tabs (All / Incoming / Outgoing), and Inflow / Outflow / Net summary
cards.

*Recording note:* the ledger's Period selector defaults to **This month** — switch to **All time**
or the seeded rows look sparse.

### Ch. 9 — Collections (~2.5 min)

On Live applications, the **"Awaiting repayment"** panel splits into **Overdue** (5 loans in the
seed, 5–111 DPD) and **Active** (4), each row showing name, mobile, due date and live DPD. The
Collection Head picks a collections executive **straight from the row** and hits Assign — that
opens the collection case behind the scenes; there is no separate "open a case first" step.

Below sits the **DPD buckets** grid — Upcoming, 1–7, 8–30, 31–60, 61–90, 90+ — each with its own ⓘ
tooltip, plus a DPD calculator. The bucket is always computed on read, never stored.

Inside a case: Case / Loan / Borrower cards (salary and employer fields gated to
`collections:manage`, i.e. hidden from a plain Collection Executive), an Interactions log
(type / outcome / promise-to-pay / proof), Assign officer, and Propose settlement.

Two rules to call out. `proofRef` is **mandatory** the moment an interaction's outcome is `PAID`
(`PROOF_REQUIRED` otherwise). And settlement approval is maker-checker: on
`/staff/collections/settlements`, the executive who proposed a settlement cannot approve it, and
Approve/Reject only render for `collections:manage`.

The **Amount due** card on any overdue row spells the arithmetic out — "1%/day × 28 days" interest,
"2%/day × 30 days" penalty — so the net due is auditable rather than a black-box figure.

### Ch. 10 — Customers (~2.5 min)

Search by name or customer id; the table carries a CIBIL/★ column. A customer page gives, on the
left: Credit profile, Current loan, Past loans, Payments, Applications (with Cancel where still
cancellable), and Change history. On the right: Profile, the ADMIN-only **Edit KYC / salary** form,
**Add to blocklist**, and a red **Danger zone** card whose permanent-delete control is gated behind
typing the customer's exact name.

The edit form's caption is the point: *"Identity (PAN/Aadhaar/mobile) is locked. Salary edits are
audited and recompute the eligible limit."* Best beat — bump a monthly salary, then scroll straight
to Change history and show the audit row it just wrote.

---

## Page 5 — Administration, closing beats, and what's still open

### Ch. 11 — Administration (~3.5 min)

Six ADMIN pages, each a small self-contained surface:

- **Staff** — create an account (email/name/role/password); roster rows with per-row Role/Status
  selects, Save and Disable.
- **Invites** — invite a staff member; on success a copyable one-time **token chip**, plus a table
  of prior invites.
- **Blocklist** — add by PAN / Aadhaar ref / Phone / Device / Bank account, with optional reason
  and a Remove action.
- **Payment settings** — payee details (UPI id, account name/number, IFSC, bank), the UPI QR image
  and account-info PDF uploads, and a live "Current payee (borrower view)" preview.
- **Company expenses** — inline add form and a footer Total row.
- **All applications** — the full register including unfinished DRAFTs, with search, a
  Complete/Incomplete filter, and wide CSV/PDF export.

The cross-cutting point: the **Export ▾** control is **ADMIN-only across the entire console** — it
does not render at all for other roles, hard-coded in `ExportMenu` as a data-governance decision,
and self-disables with "Nothing to export" on an empty table.

*Recording note:* the QR image and PDF preview stay blank offline — no S3 upload target locally.

### Ch. 12 — Referral payouts (~1.5 min)

Four summary cards (Pending, Paid, Total rewards, Total paid out), Pending/Paid tabs, and a
"Bank/UPI txn id" input with **Mark paid** on each pending row. A single qualifying referral creates
**two** payouts — referrer and new borrower — and the entire feature is gated by the DB-only
`referral` feature flag. There is deliberately no admin UI to toggle it; flags move by SQL, with no
write API, not even for ADMIN.

### Ch. 13 — Closing beats (~1.5 min)

The notification bell with its unread badge, an Export → CSV and Export → PDF on a populated table,
and Sign out. Be honest on camera that the ADMIN bell only has content because the seed inserts
rows directly via SQL: `RecipientPolicy.TO_ADMINS` exists in the notification engine but no
`NotificationType` targets admins yet. To show the bell filling naturally, switch to KYC_APPROVER,
ACCOUNTANT or COLLECTION_HEAD first.

### Honest gaps the walkthrough touches

| Gap | State |
|---|---|
| Live `SOD_VIOLATION` on screen | Not reproducible via the role bar (ADMIN exempt; `evaluateSoD` is dead code) |
| Credit-brief PDF download, payment QR / PDF preview | Blank offline — no S3 target locally |
| ADMIN notification inbox | Seeded by SQL only; no `NotificationType` targets admins |
| Borrower OTP SMS | Not sending in prod — DLT templates re-filed under the DhanBoost brand, awaiting operator approval; `NAVIX_SMS_MOCK=true` → `123456` locally |
| Real bank payout (NEFT/IMPS) | Manual accountant confirmation today; no payout rail wired |
| DB foreign keys, PII-at-rest encryption | Deferred (indexes only today) |

### Running order at a glance

| # | Chapter | Persona | ~min |
|---|---|---|---|
| 1 | Sign in & dashboard | ADMIN | 2 |
| 2 | Live pipeline | ADMIN | 1.5 |
| 3 | Verification dashboard | ADMIN / KYC_APPROVER | 2 |
| 4 | KYC approvals + reborrow reviews | KYC_APPROVER | 2 |
| 5 | Credit | CREDIT_HEAD / ADMIN | 3 |
| 6 | Maker-checker | CREDIT_EXECUTIVE → CREDIT_HEAD | 3 |
| 7 | Disbursement | DISBURSEMENT_HEAD | 2 |
| 8 | Accounting + ledger | ACCOUNTANT | 2.5 |
| 9 | Collections + settlements | COLLECTION_HEAD / EXEC | 2.5 |
| 10 | Customers | ADMIN | 2.5 |
| 11 | Administration (6 pages) | ADMIN | 3.5 |
| 12 | Referral payouts | DISBURSEMENT_HEAD | 1.5 |
| 13 | Closing beats | ADMIN | 1.5 |

**Full cut ≈ 29 min. For a ~20-minute version, drop chapter 12 and the optional SoD-error beat in
chapter 6.**
