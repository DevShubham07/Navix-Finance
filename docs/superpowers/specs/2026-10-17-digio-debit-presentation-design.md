# Digio NACH debit presentation — design

**Date:** 2026-10-17
**Status:** awaiting approval — nothing built yet
**Builds on:** [`2026-10-16-digio-emandate-offer-journey-design.md`](2026-10-16-digio-emandate-offer-journey-design.md) (mandate registration, shipped behind `digio-mandate`, disabled)
**Provider reference:** [`docs/digio/`](../../digio/README.md) — read [`13-live-verification-log.md`](../../digio/13-live-verification-log.md) first.

---

## 1. Intent and why

The eMandate build **registers** mandates. Nothing collects. Repayment today is still manual: the
borrower uploads a screenshot, an Accountant verifies it.

This build makes the mandate do its job — present a NACH debit against a registered UMRN, so
repayment is **collected** rather than requested. Two triggers:

- **Scheduled**, for overdue loans, at a fixed set of days past due.
- **Manual**, by collections staff working a case.

## 2. Goals

- A scheduled sweep presents a debit for an overdue loan with a usable mandate at **DPD 1, 3, 5 and 9**.
- A **Collection Executive** (own assigned cases) and a **Collection Head** can fire a debit manually
  from the customer pop-up.
- A successful debit creates a `Payment` and flows through the existing repayment path, so the
  outstanding falls and the loan closes at zero exactly as a manual payment does.
- Staff can see every attempt and its outcome against the customer.

## 3. Non-goals

- **UPI AutoPay debit** (`/v3/client/upi_payment/*`) — not provisioned on our account.
- **Mandate amendment or cancellation.**
- **Consolidated settlement reconciliation** (`C6`) — Digio-PA-only, and unverified for us.
- **A webhook receiver.** §7 explains how outcomes are read without one, and why that is a stopgap.
- Changing how the Accountant verifies manually-recorded payments.

## 4. Anti-goals — this build has failed if

- A borrower is debited **twice** for one repayment.
- A borrower is debited **after** they have already repaid, or for **more than they owe**.
- A debit is presented against a mandate that is not live, or for an amount above its ceiling.
- A hard NPCI rejection is re-presented, burning the monthly allowance and achieving nothing.
- Collected money is not reflected in `outstandingAsOf`, so the borrower appears still to owe it.
- Full bank account numbers reach logs, screens or the provider audit table.

## 5. Decisions made by the user

| Decision | Value |
|---|---|
| Retry schedule | **DPD 1, 3, 5, 9** — days past due, not buckets |
| Manual debit authority | **Collection Executive and Collection Head** |
| Approval | **Executive fires directly — no maker-checker** |
| Amount | **Default to full outstanding; staff may override downward** |
| Surface | The customer pop-up's existing **Mandate tab** |
| Successful debit | **Auto-verified** — counts immediately, no Accountant step |
| DPD basis | **Counted from the due date**, not from the end of the grace day |
| Debit landing after a manual repayment | **Parked as an overpayment with a refund flag**, on a staff queue |

> **Two consequences of the above, accepted by the user and recorded so they are not re-litigated:**
>
> - **Auto-verification removes the Accountant checkpoint** for NACH money. The case for it: a
>   bank-confirmed debit carrying a UTR is stronger evidence than the screenshot the manual flow
>   already accepts, and `outstandingAsOf` counts only `VERIFIED` payments — so without it, collected
>   money would not reduce the balance and the retry chain would fire again against a borrower who
>   has already paid.
> - **Counting DPD from the due date puts the first debit inside the grace day**, before any late
>   penalty accrues and while the product still treats the borrower as not formally late. That is a
>   deliberate choice to collect sooner. **Flag for compliance** — it may belong in the KFS.
>
> **Recorded departure, deliberate.** Direct firing diverges from `CollectionPaymentService`, a
> sibling in the same module that runs `raise` → `headApprove` → `validate`, and from CLAUDE.md's
> standing "SoD is mandatory" rule. The case for it: a debit executes an authority the **borrower**
> already granted, so it is operational rather than an approval step. The cost: one actor moves real
> money from a borrower's account with only an audit trail behind it. **Do not "fix" this back
> without asking.**

## 6. The constraint that shapes everything: timing

**VERIFIED** from Digio's NACH presentation documentation:

- A debit for day **T** must be requested by **late night on T-1**.
- At **00:00 on T** Digio builds the ACH file and hands it to the sponsor bank.
- If T is an **NPCI holiday**, the system reschedules to the next working day automatically.
- The outcome normally arrives **by end of day on the settlement date**.

**So money never moves same-day, and the outcome is never synchronous.** "Present at DPD 1" means
*request* on DPD 1 for settlement on DPD 2, with the answer that evening.

The sweep must therefore run **before the cutoff**, and its cron needs to be chosen with the cutoff
and the IST/UTC offset in mind — `RepaymentService` already documents why a bare `LocalDate.now()`
is a bug here.

## 7. Reading outcomes without a webhook receiver

Debit outcomes are asynchronous and arrive hours later. Digio's designed channel is a webhook; we
have none, and the account has none configured.

**This build polls instead:** a reconciliation sweep reads every in-flight debit by transaction id
(`GET /v3/client/nach_debit/transaction/{txnId}`) and applies the outcome.

That is a deliberate stopgap, and it is honest about its limits: polling is later and noisier than a
push, and it scales with the number of in-flight debits rather than with events. **A webhook receiver
remains the right answer** — designed in [`docs/digio/05-`](../../digio/05-webhooks-and-asynchronous-processing.md)
— and when it lands it should supersede the sweep as the primary path, with the sweep retained as
the safety net it should always have been.

### How a successful debit becomes money

`RepaymentService.recordPayment` always writes `PENDING_VERIFICATION`, and `outstandingAsOf` sums
only `VERIFIED` payments. Auto-verification therefore needs its **own entry point** for a system
actor — not an ad-hoc call to `verifyPayment`, whose `stampDecider` expects a staff actor and whose
SoD stamp would be meaningless here.

**Builder's calls, flagged for review:**
- Use `PaymentMethod.NACH` (the enum value exists, marked `[FUTURE]`).
- Set `txnRef` to the **UTR** returned by Digio. `recordPayment` already dedupes on `txnRef` per
  loan, so this makes a replayed outcome harmless for free.
- The loan must be re-checked at outcome time: if it settled in the meantime, `recordPayment` throws
  `LOAN_SETTLED` — that is the refund path (§5), not an error to swallow.

## 8. Re-presentation — when a retry is legitimate

NPCI classifies debit failures as **Return** or **Reject**, each carrying an "Allow RPN" flag and a
monthly allowed count ([`docs/digio/06-` §5](../../digio/06-errors-retries-and-edge-cases.md)).

**The DPD 1/3/5/9 chain is not an unconditional four attempts.** Before each subsequent attempt, read
the previous failure's code:

- **Re-presentable** — notably *balance insufficient*, the one explicitly flagged "Allow RPN: Yes".
  Continue the chain.
- **Terminal** — account closed, no such account, mandate cancelled, invalid/inactive UMRN, amount
  exceeds mandate max, date outside the mandate window, customer deceased, legal hold. **Stop the
  chain**, mark the mandate or loan accordingly, and leave it to collections.

Re-presenting a terminal rejection burns the monthly allowance and cannot succeed. Treat the reject
code as the authority on whether to continue, never the attempt counter alone.

**Builder's call:** an unrecognised or unlisted reject code is treated as **terminal**. Failing closed
costs one missed retry; failing open burns the allowance and may breach NPCI limits.

**Builder's call:** a missed sweep day (an outage on DPD 3, say) is **caught up** at the next run if
the loan is still eligible — the schedule is a set of opportunities, not a calendar that expires.

**Builder's call:** the amount is fixed **at request time**; it cannot be otherwise, given the T-1
cutoff. A successful debit for less than the full outstanding (a staff-chosen part payment) leaves a
balance and the chain **continues** while outstanding is above zero.

## 9. Flow

```
overdue loan + usable mandate
   └─ sweep at DPD 1 ─► present debit (T-1 cutoff) ─► settles next working day
                              │
                              ├─ SUCCESS → Payment → outstandingAsOf falls → loan may close
                              ├─ FAILED, re-presentable → next scheduled attempt (3, 5, 9)
                              └─ FAILED, terminal      → stop; surface to collections

collections staff (own case)
   └─ manual debit from the customer pop-up → same presentation path, same guards
```

### Guards before any presentation, scheduled or manual

Every one of these must hold, in both paths — the manual path is not a bypass:

1. The loan is **live and overdue**, with a non-zero `outstandingAsOf`.
2. The customer has a mandate the gate would accept (`hasUsableMandate`) — ACTIVE, UMRN present,
   bound to this account, ceiling sufficient.
3. The amount is **> 0**, **≤ outstanding as of today**, and **≤ the mandate ceiling**.
4. **No debit is already in flight** for this loan. This is the double-debit guard and it matters
   most on the manual path, where a staff member may fire while the sweep has already queued one.
5. The settlement date falls **inside** the mandate's `first_collection_date`/`final_collection_date`
   window.

### Money

Amounts are **integer paise** end to end. Digio's debit API takes **`amount_in_paise`** (`int32`),
which is the one field in the whole provider surface that matches our model with no conversion —
use it, never the rupee `amount` field.

The default amount is `RepaymentService.outstandingAsOf(loanId, today)`, the same penalty-aware
figure every other surface shows. Staff may override **downward only**, never above outstanding or
the ceiling.

## 10. Components (indicative — the plan decides exact placement)

| Component | Responsibility |
|---|---|
| `MandatePort` additions | `presentDebit`, `debitStatus`, `cancelDebit` — the existing seam in `navix-common`, extended |
| `DigioMandateClient` additions | `/v3/client/nach_debit/scheduled/register`, `/transaction/{txnId}`, `/transaction/action` |
| `DebitService` | Guards, amount derivation, `unique_key`, re-presentation eligibility, outcome application |
| `mandate_debit` table | **Not built.** Columns are sketched in [`docs/digio/08-` §5](../../digio/08-navix-integration-architecture.md); one row per attempt. Migration head is V83, so this is **V84** |
| Scheduled sweep | Present at DPD 1/3/5/9 **before the cutoff**; reconcile in-flight debits. Lives in `navix-app/.../scheduler/`, following `PaymentReminderScheduler` and `EmploymentRetryScheduler` |
| Collections surface | Manual debit action in the customer pop-up Mandate tab, role-gated |
| Feature flag | **`digio-debit`**, separate from `digio-mandate`, shipped disabled, `defaultWhenMissing = FALSE` |

### Placement and the module boundary — builder's calls

- **`DebitService` lives in `navix-loan`**, beside `MandateService` and `RepaymentService`, which it
  must both use.
- **`navix-collections` depends only on `navix-common`**, so the manual path cannot call `navix-loan`
  directly. It goes through a directory interface in `navix-common`, exactly as
  `CollectionPaymentService` already reaches loans via `LoanDirectory`. Follow that precedent rather
  than inventing a new seam.
- **The mandate is keyed by application, not loan** (`mandate.loan_application_id`; `hasUsableMandate`
  takes a `LoanApplication`). A debit starts from a loan, so the resolution is
  loan → application → mandate, and `MandateService` needs a loan-keyed accessor exposing what
  `usableMandate` currently computes privately.
- **DSA is explicitly rejected** on every new endpoint, per CLAUDE.md §7's deny-list rule — a new
  staff-open surface is open-by-default otherwise.

**Idempotency:** Digio provides none. `unique_key` is our only recovery handle — deterministic per
loan and attempt, so a timed-out create is resolved by reading the plan back
(`by_reference_id/{refId}`) rather than re-POSTing. The registration build established this pattern
and it is non-negotiable here, where a blind retry debits a borrower twice.

**Scheduled and manual attempts must occupy different `unique_key` namespaces**, or a manual debit
will collide with a scheduled one for the same loan and attempt number — and a colliding key is
indistinguishable from a recovery.

### Concurrency — builder's call, and the anti-goal depends on it

"Check whether a debit is in flight, then insert" is a race: between the sweep and a staff click,
and between two sweep instances (ECS can run more than one task, and there is no ShedLock in this
repo). Two mechanisms, both required:

1. A **partial unique index** on `mandate_debit(loan_id)` restricted to in-flight statuses, so the
   database refuses a second concurrent attempt outright.
2. A **`pg_advisory_xact_lock`** on the loan, taken before the in-flight check — the same mechanism
   the registration build uses on the customer for the identical problem.

**The row must be committed before the Digio POST**, so a crash between the two leaves a recovery
handle rather than an untracked real debit. This mirrors the ordering the registration build settled
on after review.

## 11. Authorisation

- `COLLECTION_EXECUTIVE` — may debit **only their own assigned cases**. The module already enforces
  this (`CASE_NOT_ASSIGNED`); reuse it rather than inventing a check.
- `COLLECTION_HEAD` — may debit any case.
- Every attempt is audited with the actor, the amount, and whether it was scheduled or manual.
- `DSA` is excluded, as from every other customer surface.

## 12. Left to the builder

- Cron expression and its relationship to the T-1 cutoff and IST.
- Whether the sweep presents for all four DPD days in one pass or one day per run.
- Exact pop-up interaction and confirmation copy. **It must state the amount and the account's last
  four digits before firing** — this moves real money.
- Whether a terminal rejection should also mark the mandate unusable, or only the loan.

## 13. Open risks

| Risk | Note |
|---|---|
| **Double debit** | The worst outcome in this build. Guarded by the in-flight check plus `unique_key` recovery; needs a test that forces a timeout |
| Borrower repays manually while a debit is queued | Cancel the transaction (`cancel` is allowed only *before* initiation); past that, expect a successful debit of money no longer owed and a refund obligation |
| Polling instead of webhooks | §7 — later, noisier, scales with in-flight volume |
| Terminal rejection re-presented | §8 — gate on the reject code, not the attempt count |
| Direct firing, no second pair of eyes | §5 — the user's explicit decision |
| Mandate revoked after going ACTIVE | Known gap from the registration build: `status()` refreshes only pending rows, so a Digio-side revocation is never re-synced. A debit would fail at the bank. The reconciliation sweep is the natural place to fix this |
