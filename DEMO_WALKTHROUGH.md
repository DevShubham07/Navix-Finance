# DEMO_WALKTHROUGH.md — recording script for the DhanBoost admin/staff console

A chaptered script for recording a walkthrough of the DhanBoost (NAVIX Finance) staff console —
fully offline, local stack. Companion docs: [`populateDummyData.md`](populateDummyData.md) (how the
demo data got there) and [`CLAUDE.md`](CLAUDE.md) (product rules referenced throughout).

Total runtime: roughly **28–30 minutes** if you record every chapter back to back. Cut chapters 12
and the optional beat in chapter 6 for a ~20-minute cut.

---

## Before you record

- [ ] `.\scripts\run-demo.ps1` has been run (or the stack is already up) — Postgres on `:5433`,
      backend on `:8090`, frontend on `:3000`.
- [ ] Backend log shows Flyway applied through the latest migration and the JDBC URL reads
      `localhost:5433` — **not** an RDS host (the SSM-hijack canary; see `CLAUDE.md` §13 Risks).
- [ ] `.\scripts\seed-demo-data.ps1 -Verify` → every line **PASS**, nothing **EMPTY**.
- [ ] Browser window sized consistently (1920×1080 or your target resolution); zoom at 100%.
- [ ] DevTools console closed, bookmarks bar hidden, other tabs closed.
- [ ] Know where the floating **"Act as role"** bar lives — bottom-right corner, every staff page.
- [ ] This doc open on a second screen or printed — the chapters below assume you're not
      improvising persona switches live.

**Sign-in for the whole recording:** `/staff/login` → **`meera.krishnan@navix.example`** /
**`Admin@12345`** (alternate ADMIN: `navixfinance@gmail.com` / `demo`). The login page is
**email + password only — there is no role picker**; every persona switch after this happens
through the role bar, not by logging out.

---

## Chapters

### 1. Sign in & dashboard — ~2 min
| | |
|---|---|
| URL | `/staff/login` → `/staff/dashboard` |
| Persona | ADMIN (Meera Krishnan) |

**Point at:** the login form itself first — "Sign in to the console", email + password, no role
picker, the "Separation of duties is enforced on every decision" footnote. After signing in: the
`Welcome, Meera` hero, subtitle `Administrator · live operations overview`; the **"Your work"** card
(ADMIN's queue is labelled "Live pipeline" — oversight across every queue, not a personal to-do
list); the three **Applications / Disbursals / Repayments** sparkline cards under "Last 30 days";
the **"Pipeline at a glance"** bar; the collapsed **"Transactions"** panel at the bottom
(ADMIN-only, company-wide inflow/outflow).

**Say:** every other role's dashboard shows a personal queue count ("N items need your action");
ADMIN's is deliberately different — it's an oversight surface, because ADMIN can act in any role.
The sparklines and pipeline bar are the same live counts every role sees, just with ADMIN's full
visibility.

**Trap:** the page renders an empty grey placeholder for a beat before `useStaffMe()` resolves the
session — a real flicker, not a recording glitch. Let it settle (well under a second) before you
start narrating.

---

### 2. Live pipeline — ~1.5 min
| | |
|---|---|
| URL | `/staff/applications` |
| Persona | ADMIN |

**Point at:** heading "Live applications", subtitle "Real backend state machine. Act on the queue
for your role to walk loans to ACTIVE."; the **"Review an application"** panel (open any app by ID);
then, because you're ADMIN, all eight queue panels stacked: KYC pending, Reborrow reviews, Credit
queue — assign an executive, Credit head decision, Credit executive review, Disbursement pending,
Accountant validation, Awaiting repayment (active & overdue), and the collapsed Closed panel.

**Say:** this page is a literal rendering of the state machine in `CLAUDE.md` §5 — every
panel here is one `ApplicationStatus`.

**Trap:** the **"Closed (fully repaid)"** panel is a native `<details>` that doesn't even fire its
query until you expand it (deliberate — keeps the console from polling the whole closed archive in
the background). Click it open before you talk about it, or the count will read blank.

---

### 3. Verification dashboard — ~2 min
| | |
|---|---|
| URL | `/staff/verifications` |
| Persona | ADMIN (or switch to KYC_APPROVER / Ananya Rao for realism) |

**Point at:** heading "Verification dashboard"; the **5 tiles** — Pending, Failed, In review, Passed,
Never run; the **4 triage buckets**, in priority order — "Has failures", "Awaiting borrower steps",
"All checks passed", "Not started"; click a card to open the check-by-check dialog, then click
**"Manual override"** on one check → the Approve (PASS) / Reject (FAIL) toggle + optional Remarks
textarea → Confirm.

**Say:** the required-check list is PAN, Email, Address, Aadhaar, Bureau, Salary, Penny-drop, Selfie
— all eight must clear before `submit-kyc` is even allowed. A manual override is itself audited
(the remarks land in the trail), so a KYC approver can clear a borderline case without pretending
the automated check passed.

**Trap:** this dashboard **only tracks DRAFT / KYC_PENDING / REVIEW_PENDING applications** — the
moment an application is decided (KYC_APPROVED onward, rejected, closed…) it disappears from every
bucket here. Don't go hunting for an approved applicant on this page; it's KYC purely as
"pending work", not a historical log.

---

### 4. KYC approvals — ~2 min
| | |
|---|---|
| URL | `/staff/kyc-approvals`, then `/staff/kyc-review` |
| Persona | switch to KYC_APPROVER (Ananya Rao) via the role bar |

**Point at:** on `/staff/kyc-approvals` — "Applications awaiting KYC clearance" (KYC_PENDING, plain
Approve/Reject); below it, **"Approve instant loans (credit clearance)"** — KYC-approved
applications the borrower has already chosen an amount on, with an info tooltip: *"Approve to send
straight to the Disbursement Head — no separate credit review needed."* Then navigate to
`/staff/kyc-review` — "Reborrow reviews", subtitle "Returning borrowers with a past overdue.";
the queue shows inline loan history per row; buttons are labelled **"Clear borrower" / "Reject"**
(not the generic Approve/Reject).

**Say:** the instant-loan panel is the "credit fast-path" — a KYC approver can wave an application
straight to disbursement, skipping the executive→head maker-checker chain entirely, when they judge
the request low-risk enough. The reborrow-review queue is a separate, permanently-kept-apart queue:
only borrowers who were *ever* overdue land here; a clean repayment history skips this step and goes
straight to `PRE_APPROVED`.

---

### 5. Credit — ~3 min
| | |
|---|---|
| URL | `/staff/applications` → **Open** on any row (detail popup) |
| Persona | CREDIT_HEAD (Priya Nair) to assign; then open any application detail as ADMIN |

**Point at:** "Credit queue — assign an executive" panel — a Select of **ACTIVE Credit Executives
only** (the activation-gating rule) + Assign button. Hit **Open** on a row to raise the application
detail popup: the header shows the **`CreditBadge`** — "CIBIL 778 · ★★★★☆ 4.0" style pill — plus the
stage's maker-checker action; the **Journey** tab has the stepper (click a node to open its step
popup) and the **Cost** card (fee/GST/net
disbursed/total repayable, or the pre-disbursement projection if no loan yet); **Loan history**;
**Customer review**; a collapsed **Audit log** ("every action, actor and timestamp").

**Say:** point directly at the CIBIL/★ badge and say out loud that this is staff-only — it is never
shown to the borrower, and risk categories A/B/C/D never drive a different price (CLAUDE.md product
rule: one price for everyone). If you land on a fast-tracked reborrow application, call out the gold
"Fast-track" pill next to the Journey heading.

**Trap:** the credit brief's "Download PDF" control won't resolve — no S3 offline, so
`documentId` is null. The score/★ rating/facts still render fully; just don't click the download
link on camera.

---

### 6. Maker-checker on camera — ~3 min
| | |
|---|---|
| Persona | role-bar: CREDIT_EXECUTIVE (Rahul Mehta) → CREDIT_HEAD (Priya Nair) |

**Point at:** the role bar itself — click it open, show the list of all 9 personas with the current
one marked; switch to **Rahul Mehta (Credit Executive)**, open an assigned app in
`CREDIT_EXEC_PENDING`, and sanction it. Switch to **Priya Nair (Credit Head)** to show that the Head
can reassign or make the same final decision from the single shared review stage.

**Say:** assignment controls ownership and queue visibility. The append-only event trail records
every assignment, reassignment, pending note, and final decision. The backend enforces the
`application_event` trail on every head-decision call and would reject it with `SOD_VIOLATION` if
the same actor tried to close the loop.

> **Correction to the original script — read before recording this beat.** The plan called for
> proving the lockout by staying signed in as one actor and "trying to act twice." **That does not
> work as written**, and re-running it live will look like a bug on camera:
> - `ApplicationFlowService.headDecision` (`navix-loan`) **explicitly exempts ADMIN** from the SoD
>   check — the code comment reads *"ADMIN is exempt — oversight may approve its own
>   recommendation (per-step control)"*. So if you stay signed in as Meera Krishnan (ADMIN) and
>   drive an application through exec-decision *and* head-decision yourself, it will **succeed
>   silently**, not throw.
> - The frontend helper `evaluateSoD` in `frontend/src/lib/auth/rbac.ts` — which the earlier brief
>   for this doc said "blocks the same actor even for ADMIN in the UI" — is **not actually called
>   anywhere** outside its own definition file. It is dead code; it does not gate any button on
>   this console today.
> - The role bar always logs a role in as that role's one fixed seeded persona (Rahul Mehta is
>   always `CREDIT_EXECUTIVE`, Priya Nair is always `CREDIT_HEAD`) — so no two non-admin personas
>   can ever collide on the same application either.
>
> Net effect: under the current build + seed, a real `SOD_VIOLATION` error **cannot be produced
> on screen** through the role bar. If you want the actual red error banner for the recording, the
> only way is an advanced, off-script move: go to `/staff/admin/staff`, temporarily change Rahul
> Mehta's role from `CREDIT_EXECUTIVE` to `CREDIT_HEAD` *after* he's recommended an application,
> sign in as him again via the role bar, and try to approve his own recommendation — then revert
> his role afterward. Treat this as optional; the two-persona handoff above is the reliable,
> on-script way to demonstrate SoD.

---

### 7. Disbursement — ~2 min
| | |
|---|---|
| URL | `/staff/applications` (signed in as the Disbursement Head) |
| Persona | DISBURSEMENT_HEAD (Vikram Shah) |

**Point at:** three stacked panels — **"Pre-approved — fast-track
release"** (reborrow apps that skipped credit), **"Standard disbursement"**, **"Disbursement
failed — retry"**; the transaction-id input + **"Approve & release" / "Reject"** buttons, with the
inline hint *"Enter a transaction id to release & activate immediately, or approve without one to
send it to the accountant."*

**Say:** demonstrate both branches if time allows — approve one with a txn id (jumps straight to
`ACTIVE`) and one without (routes to `ACCOUNTANT_PENDING`, feeding chapter 8's queue).

---

### 8. Accounting — ~2.5 min
| | |
|---|---|
| URL | `/staff/applications` → `/staff/accounting/transactions` |
| Persona | ACCOUNTANT (Deepa Iyer) |

**Point at:** "Transfers to confirm" (ACCOUNTANT_PENDING) with **Confirm transfer / Mark failed**;
the **"Repayments to verify"** queue below it (borrower self-reported UPI/bank payments); the
**"All transactions"** button in the page header, which opens the full ledger.

**Say:** confirming a transfer here is what actually mints and activates the loan
(`DISBURSED → ACTIVE`); verifying a repayment is what reduces the outstanding balance and can close
a loan at zero — both are manual, human confirmations per the product's no-auto-reconciliation rule.

**Trap:** on the transactions ledger, the **Period selector defaults to "This month"** — click
**"All time"** or the seeded rows (spread across the last 30 days) will look sparse or missing.
Direction tabs are All / Incoming / Outgoing; the three summary cards read Inflow/Outflow/Net for
whatever period is selected.

---

### 9. Collections — ~2.5 min
| | |
|---|---|
| URL | `/staff/applications` → a case detail → `/staff/collections/settlements` |
| Persona | COLLECTION_HEAD (Arjun Patel) / COLLECTION_EXECUTIVE (Sana Khan) via role bar |

**Point at:** on **Live applications**, the **"Awaiting repayment"** panel split into two columns —
**Overdue** (5 loans, 5–111 DPD) and **Active** (4) — each row showing the borrower's name, mobile,
due date and live DPD. On an overdue row the Collection Head picks a **collections executive
straight from the row** and hits Assign: that opens the collection case behind the scenes (there is
no separate "open a case first" step). Below it, the **DPD buckets** grid — Upcoming, 1–7, 8–30,
31–60, 61–90, 90+ — each with its own ⓘ tooltip, plus the DPD calculator. Open a case: Case / Loan /
Borrower cards (salary and employer fields are gated to `collections:manage`, i.e. hidden from a
plain Collection Executive), the Interactions log (type/outcome/promise-to-pay/proof), Assign
officer, and Propose settlement. Then `/staff/collections/settlements`: a table of Pending / Approved
/ Rejected settlements with Approve/Reject buttons visible only to `collections:manage`.

**Say:** open any overdue row's **Open →** popup and show the *Amount due* card — it spells the
arithmetic out, "1%/day × 28 days" interest and "2%/day × 30 days" penalty, so the net due is
auditable rather than a black-box figure. Also call out that `proofRef` is mandatory the moment an
interaction's outcome is `PAID` (`PROOF_REQUIRED` otherwise), and that settlement approval is
maker-checker too — the same Collection Executive who proposed one cannot also approve it.

---

### 10. Customers — ~2.5 min
| | |
|---|---|
| URL | `/staff/customers` → `/staff/customers/{id}` |
| Persona | ADMIN |

**Point at:** the search box (name or customer id); the table's CIBIL/★ column. Open a customer:
left column — Credit profile card, Current loan, Past loans, Payments, Applications (with a Cancel
button on any still-cancellable one), Change history; right column — Profile, **"Edit KYC / salary
(admin)"** form, **"Add to blocklist (admin)"**, and — a bonus beat not in the original outline but
worth showing — a red **"Danger zone"** card with a permanent customer-delete control gated behind
typing the customer's exact name to confirm.

**Say:** point out the caption on the admin edit form — *"Identity (PAN/Aadhaar/mobile) is locked.
Salary edits are audited and recompute the eligible limit."* Make one small edit (e.g. bump the
monthly salary) and immediately scroll to "Change history" to show the audit row it just wrote.

---

### 11. Administration — ~3.5 min
| | |
|---|---|
| URLs | `/staff/admin/staff`, `/staff/admin/invites`, `/staff/admin/blocklist`, `/staff/admin/payment-settings`, `/staff/admin/expenses`, `/staff/admin/all-applications` |
| Persona | ADMIN |

**Point at, per page:**
- **Staff** — "Create staff account" form (email/name/role/password) and the roster table, each row
  with its own Role/Status selects + **Save** / **Disable**.
- **Invites** — "Invite a staff member" form; on success, a copyable one-time **token chip**;
  table of prior invites (also with token chips).
- **Blocklist** — "Add to blocklist" (Type: PAN / Aadhaar ref / Phone / Device / Bank account,
  Value, optional Reason); table with **Remove**.
- **Payment settings** — "Payee details" form (UPI id, account name/number, IFSC, bank name) + UPI
  QR image and account-info PDF upload fields; a live "Current payee (borrower view)" preview.
- **Company expenses** — "Add expense" inline form (description/amount/paid to/notes/date/receipt);
  the table's footer **Total** row.
- **All applications** — search + a **Complete / Incomplete** completeness filter; the full register
  (every application, including unfinished DRAFTs) with a wide CSV/PDF export.

**Say:** across every one of these pages, point out that the **Export ▾** control in the header is
**ADMIN-only for the whole console** — it does not render at all for any other role (a
data-governance decision, hard-coded in `ExportMenu`), and it self-disables with the tooltip
**"Nothing to export"** whenever the on-screen table is empty.

**Trap:** the payment-settings QR image and account-info PDF preview stay blank offline (no S3
upload target locally) — mention it proactively rather than looking surprised when nothing renders.

---

### 12. Referral payouts — ~1.5 min
| | |
|---|---|
| URL | `/staff/disbursement/referrals` |
| Persona | DISBURSEMENT_HEAD or ADMIN |

**Point at:** the 4 summary cards (Pending, Paid, Total rewards, Total paid out); the Pending / Paid
tabs; on a pending row, the "Bank/UPI txn id" input + **"Mark paid"** button.

**Say:** a single qualifying referral creates *two* payouts — one for the referrer, one for the new
borrower — and the whole feature is gated by the DB-only `referral` feature flag (no admin UI to
toggle it; it's flipped by SQL, deliberately, per `CLAUDE.md` §12).

---

### 13. Closing beats — ~1.5 min
| | |
|---|---|
| Persona | ADMIN |

**Point at:** the notification bell (top-right, unread badge) → open the dropdown of recent items;
run an **Export → CSV** and **Export → PDF** on a populated table (Customers or Transactions work
well); the **Sign out** button in the header.

**Say:** be upfront that the ADMIN bell only has content because the seed inserts rows **directly
via SQL** — `RecipientPolicy.TO_ADMINS` exists in the notification engine's code but no
`NotificationType` actually targets admins yet, so nothing would ever land in this inbox through the
live API today. If you'd rather show the bell filling up "for real," switch to KYC_APPROVER,
ACCOUNTANT, or COLLECTION_HEAD via the role bar first — those roles receive notifications from
ordinary application events.

---

## If something looks empty

> Panel names given as *"(as ROLE)"* all live on **`/staff/applications`** — the single workbench
> that renders each role's own queues. Switch role with the floating role bar to see them.

| Page / panel | Depends on (seed group) | If it's empty |
|---|---|---|
| `/staff/dashboard` sparklines flat / 0% deltas | SQL trend-spreading step (`seed-demo-sql.sql`) | Re-run the seed; the three timestamp series must be spread across the last 14 days |
| `/staff/verifications` buckets thin | "Verification dash" personas (pre-submit / partial / one FAIL) | Confirm that persona group seeded; check `-Verify` output |
| "Applications awaiting KYC clearance" (as KYC_APPROVER) | KYC-queue personas (`KYC_PENDING`) | Re-run seed; check for API errors during that phase in the script's verbose log |
| "Reborrow reviews" (as KYC_APPROVER) | the late-repaid reborrow persona (`REVIEW_PENDING`) | Confirm the prior loan was backdated + repaid *after* its due date before the reborrow was submitted |
| Credit panels (as CREDIT_HEAD / CREDIT_EXECUTIVE) | KYC-approved+applied / exec-review / head-decision personas | Re-run seed; these three groups are sequential API calls — check for a stall partway through |
| Disbursement panels (as DISBURSEMENT_HEAD) | disbursement-pending / disbursement-failed personas | Same — check the disbursement-decision step didn't error |
| "Transfers to confirm" (as ACCOUNTANT) | accountant-pending personas | Confirm those apps used `disbursement-decision` **without** a `txnRef` |
| "Repayments to verify" (as ACCOUNTANT) | 3 unverified repayments (back-office data step) | Re-run the back-office repayments step |
| `/staff/accounting/transactions` looks empty | Period filter, **not** missing data | Switch the period to "All time" first |
| DPD buckets / "Overdue" column empty (as COLLECTION_*) | Overdue personas, SQL-backdated into DPD bands | Confirm `seed-demo-sql.sql` ran (backdates `loan.disbursed_on` / `due_date`) |
| `/staff/collections/settlements` | 2 proposed settlements (one approved, one rejected) | Re-run the back-office settlements step; needs two distinct staff tokens |
| `/staff/customers` sparse | any persona group | If the whole table is empty, seeding never completed — check `-Verify` |
| `/staff/admin/staff` only shows the 9 seeded + Meera | "create 1 staff user" back-office step | Re-run that step |
| `/staff/admin/invites` | 2 invites step | Re-run that step |
| `/staff/admin/blocklist` | 4 blocklist entries step | Re-run that step |
| `/staff/admin/expenses` | 8 expense rows step | Re-run that step |
| `/staff/admin/all-applications` shows fewer than ~49 rows | any persona group short-fired | Re-run `-Verify`; it enumerates every admin-visible endpoint and flags PASS/EMPTY |
| `/staff/disbursement/referrals` empty | referral personas + 2 payouts | Confirm the `referral` feature flag is `true` and that group seeded |
| Notification bell (as ADMIN) empty | ~8 direct-SQL-inserted rows for `recipient_type='STAFF', recipient_id=10` | This is the **only** way the ADMIN bell gets content — re-run `seed-demo-sql.sql`, or switch role via the role bar to see notifications arrive naturally |

For the seeding mechanics themselves (script invocation, offline env vars, full persona table, and
why some rows need raw SQL instead of the API), see [`populateDummyData.md`](populateDummyData.md).
