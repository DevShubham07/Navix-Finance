# 02 — The eMandate product and its business workflow

## 1. Four distinct capabilities — do not conflate them

Digio's DigiCollect splits into capabilities that are separate APIs, separate registers in the
console, and separate webhook entity families. Treating "mandate" as one thing is the most expensive
mistake available here.

| # | Capability | What it does | API family | Webhook entity |
|---|---|---|---|---|
| 1 | **Registration** | Creates a mandate and gets the customer to authorise it; ends in a **UMRN** | `/v3/client/mandate/*` | `MANDATE`, `API_MANDATE` |
| 2 | **Presentation (debit)** | Asks for money against a registered UMRN on a date | `/v3/client/nach_debit/scheduled/*` | `NACH_DEBIT` |
| 3 | **Transaction management** | Cancels/pauses/resumes a debit plan or an individual transaction | `/v3/client/nach_debit/*/action` | `NACH_DEBIT` |
| 4 | **Settlement** | Moves collected money into the corporate account | `GET /v3/client/nach_debit/requester_settlement/{id}` | `CONSOLIDATED_SETTLEMENT` |

A registered mandate **collects nothing by itself.** Registration gives permission; every rupee needs
a separate presentation call. **VERIFIED** (distinct spec paths and distinct webhook entities).

## 2. Mandate methods available to us

All **VERIFIED** — from the API's `auth_mode` enum (`API | ESIGN | PHYSICAL`) and the console's own
Mode selector, which offers all three for our corporate profile.

| Method | `auth_mode` | How the customer authorises | Speed | Bank coverage | Fit for Navix |
|---|---|---|---|---|---|
| **API / eNACH** | `API` | Redirect to NPCI ONMAGS → net banking, debit card, or real-time Aadhaar at their own bank | **Real-time — UMRN issued in the same session** (verified, `13-` §10) | **305 banks — all of them** (live call, `13-` §3). Sub-modes vary per bank | **The default.** Broadest coverage and the only mode that registers immediately |
| **eSign / Aadhaar** | `ESIGN` | Aadhaar eSign on a prefilled NACH form | Signed in session; then file-based to the sponsor bank | **Only 123 of 305 banks — and not SBI** (`13-` §3) | Weak fallback. Narrower than API mandate, and Navix already spends a billable, legally-binding Aadhaar eSign on the loan agreement — `11-` Q6 |
| **Physical** | `PHYSICAL` | Download prefilled PDF, wet signature, scan, upload | Days | Any bank | Last resort / collections only |
| **UPI AutoPay** | `upi` | UPI PIN in any PSP app, via **Collect** (enter VPA) or **Intent** (app tray) | **Real-time** | UPI-enabled accounts | **Possibly the right answer.** Absent from our console, but the API exists (`13-` §4). Real-time registration removes §5's central constraint. Entitlement unverified — `11-` Q4 |

`auth_sub_mode` on read-back narrows API mandates to `net_banking | debit | aadhaar` (the mandate-info
spec also carries `otp | other | pan | customer_id | direct_bank`).

**VERIFIED, and material if UPI is ever enabled:** Digio documents that for UPI AutoPay, users may
create, approve, modify, pause or revoke mandates — *"with exception being lending where mandate cannot
be canceled/revoked by the borrower."* Lending mandates are protected from borrower self-revocation.
NACH gives no equivalent guarantee: `C001` (cancellation on customer request) exists as a reason code.

Also UPI-specific: every autopay debit needs a **pre-debit notification at least 24 hours ahead**, and
execution is only attempted if that notification succeeded. Auto-debits up to **₹15,000** need no PIN.

## 3. Registration lifecycle, stage by stage

| # | Stage | Actor | Trigger | Produces | Fails as |
|---|---|---|---|---|---|
| 1 | Prepare | Navix | Business event needs a mandate | Customer name/account/IFSC, amount, dates, category | Validation (`400`) |
| 2 | **Create** | Navix | `POST /v3/client/mandate/create_form` | `id`/`mandate_id` (**`ENA…`**), `state: partial`, optional `authentication_url` + `access_token` | `400` with `code`/`message` |
| 3 | Deliver | Digio or Navix | `notify_customer:true` sends SMS/WhatsApp/email with a gateway link; or Navix embeds `authentication_url` / uses an SDK | Customer at the auth screen | Link expires after `expire_in_days` (1–90) |
| 4 | **Authorise** | Customer | Net banking / debit card / Aadhaar / eSign / wet signature | `state: auth_success` or `auth_failed` | `AP…` codes (`06-`); abandonment leaves it `partial` |
| 5 | Transfer | Digio → sponsor bank | Automatic | `bank_details.state: transfer_success` | `transfer_failed` |
| 6 | Sponsor bank | Yes Bank | Automatic | `accepted_spo_bank` | `reject_spo_bank` |
| 7 | NPCI | NPCI | Automatic | accepted | rejected |
| 8 | **Destination bank** | Customer's bank | Automatic | **`register_success` + UMRN** — the mandate is live | `register_failed` with an `AP…` reject code |
| 9 | Cancel | Navix or bank | `POST /v3/client/mandate/registration_cancel` | `state: cancelled` / `revoked` | — |

> ⚠ **CORRECTED by a live test — [`13-`](13-live-verification-log.md) §10.** What follows holds for the
> **file-based** modes (`ESIGN`, `PHYSICAL`). For **`auth_mode: API`** the mandate clears NPCI ONMAGS in
> real time and **the UMRN is issued inside the authentication session** (observed: 7 minutes end to
> end, PNB + Aadhaar).

**The important asymmetry (file-based modes only): `auth_success` is not `registered`.** The customer finishing authorisation
is a *promising* outcome, not a usable one. Only stage 8 yields the **UMRN**, and **without a UMRN you
cannot present a debit** — `umrn` is the one required field of
`POST /v3/client/nach_debit/scheduled/register`. Any Navix logic that treats "borrower completed the
mandate screen" as "we can collect" is wrong. **VERIFIED** from the state enums and the debit spec.

Digio exposes this as two state fields that move independently:

- `state` — Digio's own view: `partial | signed | expired | auth_success | auth_failed | cancelled | revoked`
  (`complete` additionally appears in the cancel spec's enum — **CONFLICT**, see `09-`).
- `bank_details.state` — the banking rail: `created | ready | downloaded | failed | auth_success |
  successful | partial | sign_pending | signed | transfer_failed | transfer_success | reject_spo_bank |
  accepted_spo_bank | awaiting_ack | nack_received | ack_received | awaiting_res | register_failed |
  register_success | revoked`.

Read both. `09-` maps them.

## 4. Debit (presentation) lifecycle

**VERIFIED** from Digio's NACH presentation documentation:

- For a debit on day **T**, the API call or file upload must be in by **late night on T-1**.
- At **00:00 on T**, Digio builds an ACH debit file in NPCI format and hands it to the sponsor bank.
- The sponsor bank uploads to NPCI, ideally in the first window.
- **If T is an NPCI RTGS/NACH holiday, the system reschedules to the next working day automatically.**
- Debit status normally arrives **by end of day on the settlement date**, via the sponsor bank's H2H
  channel, and is pushed to the corporate by webhook immediately.

Transaction states: `NEW | READY_TO_SEND | SKIPPED | CANCELLED | SUBMITTED_TO_BANK |
DIGIO_TECHNICAL_ERROR | ACCEPTED_SPO_BANK | REJECT_SPO_BANK | PAYMENT_DELAYED | PAYMENT_AUTHENTICATED |
PAYMENT_FAILED | PAYMENT_SUCCESS`. The plan's `upcoming_transaction.state` uses a shorter vocabulary:
`SCHEDULED | SKIPPED | PROCESSING | DELAYED | FAILED | AUTHENTICATED | SUCCESS`.

**So a debit is never same-day and never synchronous.** The earliest money can move is the next
working day, and confirmation is end-of-day after that.

## 5. Where this collides with the Navix product

Stated plainly, because it drives every decision in `08-`:

| Navix reality | NACH reality | Consequence |
|---|---|---|
| One repayment, on the salary day, ≤ 40 days after disbursal | Mandates are built for recurring EMI streams | A one-shot mandate is legal (`is_recurring: false`, `frequency: Adhoc`) but the whole rail is optimised against us |
| Amount owed is **computed on read** and only final on the day of payment (interest to the day, 2%/day late penalty capped at 30 days) | The debit amount must be fixed by **T-1 late night** | We can only ever debit yesterday's computed figure. Use a **`MAXIMUM`-type mandate** and present the exact amount per debit; never a `FIXED` mandate |
| Disbursal is immediate on the Disbursement Head's release | **`API` mode: UMRN in minutes** (verified, `13-` §10). `ESIGN`/`PHYSICAL`: days | Use **`auth_mode: API`** and registration becomes a same-session step. Still never *gate* disbursal on it — bank and sub-mode coverage varies |
| Borrower may reborrow repeatedly, each advance a fresh `loan_application` | A UMRN is per-mandate, not per-loan | Either one mandate per loan (clean, slow, repeated friction) or one long-lived customer mandate reused across advances (fast, and the obvious fit for reborrow) — **`11-` Q2, the key architectural decision** |
| Repayment today: borrower uploads proof, Accountant verifies, loan closes at zero | A debit is an asynchronous bank event with no human in the loop | The existing `PENDING_VERIFICATION` → verified path is the natural landing spot for a mandate debit: it already models "money claimed, not yet confirmed" |

**Recommendation, to be reviewed, not implemented:** a **`MAXIMUM`-amount, customer-level mandate**
registered during onboarding (not at disbursal), with each advance's repayment collected as an
individual presentation for the exact computed amount on the due date. Rationale and alternatives in
`08-` §2; the open decision is `11-` Q2.

## 6. Diagrams

Sequence and state diagrams: [`workflows/mandate-lifecycle.md`](workflows/mandate-lifecycle.md).
