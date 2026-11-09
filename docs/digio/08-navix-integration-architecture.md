# 08 — Mapping Digio onto the Navix architecture

**A proposal, reviewed by nobody. No code was written.** Every path below was checked to exist (or is
marked NEW). Read `11-` first — Q2 in particular can invalidate §2.

## 1. The business workflow, adapted to what Digio actually does

Generic step → what it becomes here, with the unsupported bits marked.

| # | Step | In Navix |
|---|---|---|
| 1 | Business event needs a mandate | Borrower reaches the offer journey, or (per §2) completes intake |
| 2 | Validate and prepare | Bank account already on file from the **penny drop** Navix performs — account number, IFSC, name. Digio's constraints apply: name ≤ 40 chars and **no apostrophes/hyphens**, account 8–18 **numeric** |
| 3 | Initiate | `POST /v3/client/mandate/create_form` (`A1`) with `customer_ref_number` = our deterministic key |
| 4 | Present authorisation | Redirect to `authentication_url` from a borrower page. **Not** `notify_customer:true` — Navix owns borrower messaging via `navix-notification`, and a second SMS channel from Digio would bypass SMS opt-out and the DLT templates |
| 5 | External processing | Digio → Yes Bank → NPCI → destination bank. **Days, not seconds** |
| 6 | Receive events / poll | `POST /api/webhooks/digio` (NEW) + a reconciliation sweep (§6) |
| 7 | Reconcile | Re-read `A3`; never trust the callback body |
| 8 | Expose internally | `mandate` status on the customer 360 **Mandate tab — the placeholder already exists** |
| 9 | Failure handling | `06-` |

**Not supported, and nothing to build for:** there is no "verify mandate instantly" call, no list API,
no idempotency key, and no synchronous debit. Design around their absence rather than hiding them
behind an abstraction that implies they exist.

## 2. The decision everything else hangs on

**Per-loan mandate** (register at sanction, cancel at closure) vs **per-customer mandate** (register
once, reuse across advances).

| | Per-loan | Per-customer |
|---|---|---|
| Registration latency vs ≤40-day tenure | Bad — days of a ≤40-day life spent registering | Good — registered long before it is needed |
| Reborrow (CLAUDE.md: the returning borrower re-walks only a short journey) | Re-registers every time, destroying the fast path | **Fits exactly** — nothing to re-ask |
| Amount | Known per loan | Must be a `MAXIMUM` mandate with headroom for any future advance |
| Consent clarity | Tightly scoped to one advance | Broader authority; **needs a clearer disclosure** (`07-` §3) |
| Cleanup | Cancel at closure | Lives on; must be cancellable on request and on exit |

**Recommendation: per-customer, `MAXIMUM`-type, `auth_mode: API`.**

> ⚠ The original rationale — "registration takes days, so it must happen during intake" — was **wrong
> for API mandates** ([`13-`](13-live-verification-log.md) §10: UMRN issued in 7 minutes). The
> recommendation stands, on the argument that was always stronger: **reborrow is explicitly designed
> to re-ask the borrower nothing**, so a per-loan mandate reintroduces friction exactly where the
> product removes it. Placement during intake is now a preference, not a necessity — with real-time
> registration the mandate step can sit anywhere in the journey.

**This is a product and compliance decision, not an engineering one.** It is `11-` Q2 and it is a
BLOCKER. Do not implement either shape until it is answered.

Corollary, whichever way it goes: **a mandate must never gate disbursal.** Navix disburses on the
Disbursement Head's release; making that wait on a destination bank's file cycle changes the product.
A mandate is a *collection convenience*, and manual repayment stays the guaranteed path.

## 3. Proposed adapter

**Do not put this behind `VerificationPort`.** That seam is a chain of interchangeable providers where
one opts out and the next answers (`RoutingVerificationPort`). A mandate is none of that: one
provider, a legal authority rather than a check, and a lifecycle that outlives the request. The repo
already made this exact call for Aadhaar eSign, which sits in its own `EsignPort` — *"one provider,
and a legal act rather than a check."* Follow that precedent.

```
backend/navix-collect/                                   (NEW module — or navix-loan if preferred)
├─ MandatePort.java              — the seam: register / status / cancel / presentDebit / debitStatus
├─ DigioMandateClient.java       — the only class that knows Digio exists
├─ DigioMockClient.java          — mirrors NAVIX_ESIGN_PROVIDER=mock; what CI and the demo seed use
├─ DigioConfig.java              — @ConfigurationProperties + fail-fast validation (04- §4)
├─ MandateService.java           — our state machine, idempotency, reconciliation
├─ MandateStatusMapper.java      — Digio state → Navix state (09-); the one place enums are read
└─ DigioWebhookController.java   — POST /api/webhooks/digio
```

Why a port with one implementation, when the house rule is to avoid exactly that: it is not
speculative generality but the **test seam**. Without it, every test either calls production Digio or
mocks HTTP. `DigioMockClient` is the second implementation, and it exists on day one.

## 4. Money — the one place to be pedantic

Navix money is **integer paise, `long`, HALF_UP, never floats**. Digio is inconsistent:

| Call | Field | Type |
|---|---|---|
| `A1` create mandate | `collection_amount`, `maximum_amount` | **rupees, decimal** (1 – 10,000,000) |
| `C1` create debit | `amount_in_paise` | **paise, `int32`** (ceiling ≈ ₹2.14 crore) |
| `C1` create debit | `amount` | rupees, string |

**Use `amount_in_paise` for every debit** — it is the only field that takes Navix's `long` paise with
no conversion at all. For the mandate ceiling, convert paise → rupees **once**, in
`DigioMandateClient`, with `BigDecimal` and HALF_UP, and never let a `double` near it. Nothing outside
that class should see a rupee.

## 5. Schema (NEW — migration V81+)

Two tables. No FK constraints, indexes only, matching the repo's documented (deferred) debt.

**`mandate`** — one row per Digio mandate
`id` · `customer_id` · `loan_application_id` (nullable — null for a customer-level mandate)
· `digio_mandate_id` (`ENA…`, unique) · `umrn` (nullable until registered; unique when present)
· `customer_ref_number` (unique — the `A4` recovery key) · `status` (our enum, `09-`)
· `digio_state` · `digio_bank_state` (both raw, for support)
· `auth_mode` · `auth_sub_mode` · `maximum_amount_paise` · `first_collection_date`
· `final_collection_date` · `bank_name` · `account_last4` · `ifsc`
· `reject_code` · `reject_reason` · `authenticated_at` · `registered_at` · `cancelled_at`
· `created_at` · `updated_at`

**`mandate_debit`** — one row per presentation attempt
`id` · `mandate_id` · `loan_id` · `payment_id` (nullable → `Payment`) · `digio_plan_id` (`NSP…`)
· `digio_txn_id` (`NPT…`) · `unique_key` (**unique** — the `C5b` recovery key)
· `amount_paise` · `settlement_date` · `status` · `digio_status` · `failure_code` · `failure_reason`
· `utr` · `attempt_no` · `created_at` · `updated_at`

**`digio_webhook_event`** — dedupe + audit
`id` · `digio_event_id` (**unique** — this constraint *is* the dedupe) · `event` · `entity`
· `entity_id` · `raw_payload` (jsonb, **redacted of account numbers**) · `received_at`
· `processed_at` · `processing_error`

Store the full account number **nowhere**. Digio returns it masked; keep `account_last4` and let Digio
hold the rest.

### Where it joins the existing model

A successful debit is **money received** and the repo already models that:
`Payment` (`PaymentStatus.PENDING_VERIFICATION` → verified by an Accountant) plus
`RepaymentService.outstandingAsOf`, which computes the balance on read and closes the loan at zero via
`ApplicationFlowService.closeForLoan`.

**Reuse it rather than building a parallel ledger.** `NACH.DEBIT.SUCCESS` creates a `Payment` — the
open question (`11-` Q15) is whether a bank-confirmed debit should be auto-verified or still pass the
Accountant's desk. Recommendation: **auto-verify**, because a settled NACH debit is better evidence
than the screenshot the manual flow accepts; but it is a maker-checker change and that is the
Accountant's call, not ours.

## 6. Scheduled reconciliation (NEW)

Non-negotiable. Webhooks can be disabled for 24 hours without us noticing (`05-` §5), and the only
state that matters — registration — is the one that can silently never arrive.

Follow `navix-app/.../scheduler/PaymentReminderScheduler`, cron from env
(`NAVIX_DIGIO_RECONCILE_CRON`):

1. Mandates in a non-terminal state older than N hours → `A3`, update.
2. Debits in flight past their settlement date → `C5c`, update.
3. Loans due tomorrow with a registered mandate and no debit → present (`C1`/`C2`), honouring the
   **T-1 cutoff** (`02-` §4). This is the job that collects money; it needs its own alerting.

## 7. Staff and borrower surfaces

- **`frontend/src/components/staff/customer-360/mandate-tab.tsx`** — exists, deliberately disabled,
  currently reads *"UPI Autopay / NACH mandates are not available yet."* Replacing its body with real
  status is the whole staff-side change. Re-enable it in `customer-tabs-registry` behind the flag.
- Borrower: a mandate step in the journey plus a status card. Through the **BFF** — `src/app/api/…`
  route handlers forwarding the JWT — never browser → Spring. `authentication_url` must reach the
  borrower's session only (`07-` §2).
- A manual "present debit" action for staff is an actor moving a borrower's money: role-gated,
  audited, with the `C4` cancel counter-action (`07-` §5).

## 8. Rollout controls

- **Feature flag `digio-mandate`**, SQL-only per the repo's convention, read with
  `defaultWhenMissing = FALSE` — this takes money-affecting action, which is precisely the test
  CLAUDE.md applies to `bureau-auto-reject`. Deleting the row must leave it off.
- `NAVIX_DIGIO_ENABLED` as the config-level kill switch (`04-`).
- Webhook path added to `SecurityConfig`'s `permitAll` list, alongside the existing provider callbacks.

## 9. Risks

| Risk | Why | Mitigation |
|---|---|---|
| Double debit after a timeout | No idempotency key | `unique_key` + `C5b` recovery, **never** blind retry (`06-` §3) |
| Mandate registers too late to be useful | Only a real risk for `ESIGN`/`PHYSICAL`; `API` is real-time (`13-` §10) | Default to `auth_mode: API`; never gate disbursal |
| Debit rejects on "amount exceeds mandate max" | Late penalty pushes the figure past a tight ceiling | `MAXIMUM` mandate with real headroom |
| Webhooks silently disabled | Account-level, after 24h of non-200 | Always return 200; reconcile on schedule; alert ourselves |
| A new Digio enum value breaks parsing | No changelog; fields added without notice | `@JsonIgnoreProperties(ignoreUnknown = true)`; map unknown states to a quarantine status, never crash |
| Leaking bank account numbers into provider logs | `ProviderApiExecutionWriter` records provider calls | Redact before write (`07-` §2) |
| Borrower debited after repaying manually | Debit already in the T-1 file | Cancel via `C4` before initiation; refund path beyond it |
