# 06 — Errors, retries and edge cases

## 1. Failure taxonomy

The single most important distinction: **an HTTP 200 from Digio means the request was accepted, not
that the mandate registered or the money moved.** Classify by business outcome, never by status code.

| Class | What it is | Retry? | Recovery |
|---|---|---|---|
| **Permanent** | `AP02` account closed, `AP05` no such account, `AP16` minor, `AP17` NRE, `AP18` CC, `AP31` joint account | **Never** | Collect a different account, or fall back to manual repayment |
| **Transient** | Network timeout, `AP29` backend connectivity, `DIGIO_TECHNICAL_ERROR`, 5xx | **Yes**, with backoff | Reads: retry freely. Writes: **recover-then-retry** (§3) |
| **Business rejection** | Bank/NPCI declined for a real reason — `AP08` name mismatch, `AP10` amount exceeds limit, balance insufficient | **No** (the call worked) | Surface to the borrower / staff; fix the data and create a *new* mandate |
| **Customer-caused** | `AP30` browser closed mid-transaction, `AP33` rejected on pre-login, `AP46` no response, abandonment | **No** | Re-offer the mandate journey; the old request stays `partial` until it expires |
| **Pending** | `partial`, `AWAITING_ACK`, `AWAITING_RES`, `PAYMENT_DELAYED`, `NACH.DEBIT.DELAYED` | **Not a failure** | Wait; reconcile on schedule. **Do not re-create** |
| **Unknown outcome** | Timeout or connection reset on a `POST` | **Never blind-retry** | Reconcile by our own reference key (§3) before deciding anything |

## 2. Error response shape

`400` from any endpoint: `{ "code": …, "message": …, "details": … }`, some adding `errors`.
`details` *"usually contains the exception id for reference"* — **log it**; it is what Digio support
will ask for.

**Partly answered by a live call ([`13-`](13-live-verification-log.md) §7): `500` occurs, and carries a
structured body.** A plain business rejection (an unentitled product) returned
`HTTP 500 {"code":"SYSTEM_ERROR","message":"Unable to create UPI Mandate: Invalid Corporate Config Id"}`.

**So never classify retry-safety by HTTP status alone** — a client treating `5xx` as transient would
retry that forever. Branch on the `code`/`message` body, which is present even on a 500, and treat
`SYSTEM_ERROR` on a mutating call as **unknown-outcome** (reconcile by reference, §3) rather than
transient.

**Still UNKNOWN:** the full catalog of `code` values, and whether `401`/`403`/`429` occur. Do not
assume `429` signals rate limiting — `11-` Q8.

## 3. Recovery from an unknown outcome — the one that actually matters

Digio offers **no idempotency key** (`03-` §4). A timeout on a create is therefore ambiguous, and the
naive reaction (retry) is the dangerous one: a duplicate mandate is cleanup, a duplicate debit is a
borrower debited twice and a regulatory problem.

The only safe procedure, and the reason both reference fields are mandatory in our client:

**Mandate create (`A1`) timed out** → `GET /v3/client/mandate/by_crn/{customer_ref_number}` (`A4`).
Exists → adopt it. Absent → safe to create.

**Debit create (`C1`) timed out** → `GET /v3/client/nach_debit/scheduled/by_reference_id/{unique_key}`
(`C5b`). Exists → adopt it. Absent → safe to create.

This works only if `customer_ref_number` and `unique_key` were set **before** the call and are
deterministic (derived from our own ids), not random. Generate them from `customer_id` /
`loan_application.id` / repayment attempt — never a UUID minted per attempt, or the recovery read
looks up a key the timed-out request never used.

## 4. NPCI registration reject codes (`AP…`)

**VERIFIED** — https://documentation.digio.in/digicollect/nach/nach_registration/error_codes/.
Mode: NPCI ONMAGS API e-Mandate (debit card / net banking / real-time Aadhaar). `000` = success.

**Account-state rejections (permanent — stop asking):** `AP01` blocked · `AP02` closed · `AP03` frozen ·
`AP04` inoperative · `AP05` no such account · `AP06` not a CBS number · `AP07` refer to branch, KYC
incomplete · `AP16` minor account · `AP17` NRE account · `AP18` CC account not allowed · `AP19` PF ·
`AP20` PPF · `AP31` joint account · `AP32` wallet account · `AP34` account not registered for net
banking · `AP47` net-banking view rights only.

**Data mismatches (fix our data, then re-create):** `AP08` account holder name mismatch with CBS ·
`AP09` account type differs from CBS · `AP27` invalid frequency · `AP48`/`AP51` Aadhaar not matching /
not linked to the debtor account.

**Amount/limit:** `AP10` exceeds e-mandate limit · `AP12` EMI above the account's allowed limit ·
`AP13` invalid monthly EMI — full loan amount mentioned. *`AP13` is a direct warning against putting a
whole advance into a field the bank expects to hold an instalment.*

**Legal holds (permanent):** `AP21` attachment order · `AP22` court order · `AP24` death of holder ·
`AP25` insolvency · `AP26` lunacy.

**Authentication (customer may retry):** `AP11` authentication failed · `AP14` invalid credentials ·
`AP23` rejected per customer confirmation · `AP33` user rejected on pre-login page · `AP39` OTP
invalid · `AP40` max OTP tries · `AP41` OTP expired.

**Debit card:** `AP35` invalid card number · `AP36` invalid expiry · `AP37` invalid PIN · `AP38`
invalid CVV · `AP42` not activated · `AP43` blocked · `AP44` hotlisted · `AP45` expired.

**Technical / abandonment:** `AP28` generic registration failure · `AP29` backend technical or
connectivity error · `AP30` **browser closed mid-transaction** · `AP46` no response from customer ·
`AP15` not registered, insufficient balance maintained.

**NPCI ONMAGS technical errors `151`–`197`** are *our* integration's faults, not the customer's —
malformed or missing merchant fields (`156` utility code, `161` frequency, `162`/`163` collection
dates, `165`/`167` amounts), checksum/signature failures (`179`, `180`, `189`, `190`, `195`–`197`) and
decryption errors (`181`–`185`). Any of these in production means **our request construction is
broken**; they must page someone, not be shown to a borrower.

## 5. NPCI debit reject reasons

**VERIFIED** — the presentation error-code page. Split into `Return` and `Reject` types, each with a
documented "Allow RPN" (re-presentation) flag and a monthly allowed count.

**Return (the debit was attempted and bounced):** account closed · no such account ·
**balance insufficient** *(flagged "Allow RPN: Yes" — the one reject worth re-presenting)* · not
arranged for · payment stopped by drawer · payment stopped under court order · mandate not received ·
invalid IFSC/MICR · frequency mismatch · **duplicate transaction — already debited under ACH or NACH**
· mandate expired · incorrect amount, mandate/transaction mismatch · customer name mismatch · returned
per customer request · KYC pending · documents pending for holder turning major · account inoperative ·
dormant · zero-balance/first-transaction restrictions · small account, first transaction from base
branch · amount exceeds per-transaction debit limit · account reached maximum debit limit · network
failure (CBS) · account holder expired · **mandate cancelled** · account blocked or frozen · customer
insolvent/insane · refer to branch · bank extension case.

**Reject (the debit was never valid):** **invalid UMRN or inactive mandate** · mandate not valid for
debit · debtor account/bank/currency mismatch · **amount exceeds mandate max amount** · mandate amount
mismatch · **date before mandate start date** · **date after mandate end date** · mandate user number
mismatch · **duplicate reference number** · invalid date · invalid amount · item unwound · item
cancelled · settlement failed · invalid file format · cancelled by user · invalid Aadhaar format.

The `Reject` list is mostly a list of our own bugs. Three deserve design attention now:

- **"amount exceeds mandate max amount"** — the reason `02-` §5 argues for a `MAXIMUM`-type mandate
  with real headroom. A mandate capped at the expected repayment will reject the moment a late penalty
  pushes the figure above it.
- **"date before / after mandate start/end date"** — `first_collection_date` and
  `final_collection_date` must bracket every debit we will ever present, including a late one.
- **"duplicate reference number"** — our `unique_key` must be genuinely unique per debit *attempt*,
  while still being deterministic enough for the §3 recovery read. Encode the attempt number.

## 6. Edge cases

| Case | Behaviour | Navix handling |
|---|---|---|
| Customer abandons the auth link | Stays `partial`; expires after `expire_in_days` (1–90) | Track as pending, not failed. Re-offer. Never auto-recreate on a timer |
| Auth link expires | `state: expired` | Terminal. New mandate required |
| Debit date is an NPCI holiday | **Automatically rescheduled to the next working day** (VERIFIED) | Expect settlement later than requested. Do not treat the gap as a failure |
| Debit requested after the T-1 cutoff | Enters the following day's cycle | Our scheduler must present **at least one full day before** the due date |
| Mandate registers *after* the loan is already repaid | UMRN arrives late | Must not trigger a debit. Gate presentation on live loan state, not mandate state |
| Borrower repays manually before the debit lands | Debit already queued | **Cancel the transaction** (`C4`, `cancel` — allowed only before initiation). Past that point, expect a successful debit of money we no longer need and an obligation to refund |
| Duplicate webhook | Explicit, at-least-once | Dedupe on envelope `id` (`05-`) |
| Out-of-order webhook | No ordering guarantee | Monotonic handlers only |
| Webhooks disabled after 24h of non-200 | Account-level, manual re-enable | Scheduled reconciliation is the safety net, plus our own alerting |
| Provider outage | Not documented; no status page found | Fail closed on *registration* (ask the borrower later); never block disbursal on it |
| Bank cancels the mandate | `MNDT.BANKCANCEL`, state `revoked` | Terminal. Fall back to manual repayment and tell collections |
