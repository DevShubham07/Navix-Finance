# 05 — Webhooks and asynchronous processing

Mandate registration and debit are **both asynchronous**. Webhooks are not an optimisation here; they
are how the integration learns anything at all. Everything below is **VERIFIED** from
https://documentation.digio.in/webhooks/structure/, `/webhooks/faq/` and `/digicollect/webhooks/`.

## 1. Configuration

- Enabled from the **enterprise dashboard → profile → Webhooks**. There is no API to configure them.
- **Account-level, not per-request.** No webhook URL field exists in any request schema.
- Endpoints **may differ per event type**, but the delivery **queue is per account**: a failure on one
  event type stalls and can disable *all* of them (FAQ 6).
- Webhooks are **enabled per event/service**. Turning on NACH registration events does **not** turn on
  NACH debit events — they must be enabled separately (FAQ 16). An easy way to ship a half-working
  integration.
- Sandbox and production are separate webhook accounts with separate secrets (FAQ 1, 11).

**Our account has no webhook configured** (`01-` §1). This is a BLOCKER — `11-` Q13.

## 2. Payload envelope

```jsonc
{
  "id":         "WHN210XXXXXXXXXXXX",  // unique notification id — THE idempotency key
  "created_at": 1612332250000,          // epoch millis
  "event":      "mndt.authsuccess",     // lowercase in the payload; the catalog documents uppercase
  "entities":   ["mandate"],            // which keys are present in payload
  "payload":    { "mandate": { /* entity object */ } }
}
```

Two details that will bite:

- **`event` arrives lowercase** in the observed sample (`"mndt.authsuccess"`) while the event catalog
  documents `MNDT.AUTHSUCCESS`. **Compare case-insensitively.**
- Digio promises **"at least once delivery"** and says explicitly there can be **multiple deliveries
  of the same event**. `id` is offered for exactly this: *"Can be used to resolve idempotency."*

A sample `mandate` entity carries: `id` (`ENA…`), `file_name`, `customer_ref_number`,
`scheme_ref_number`, `partner_entity`, `current_status`, `transfer_timestamp`, `transfer_fail_reason`,
`spo_bank_reject_reason`, `umrn`, `npci_result_timestamp`, `npci_reject_reason`,
`dest_bank_result_timestamp`, `dest_bank_reject_reason`, `dest_bank_reject_code`,
`pki_signature_details`, `customer_bank_name`, `customer_account_number`, `customer_ifsc_or_micr`,
`auth_type`, `tags`, `created_at`.

## 3. Event catalog

### Mandate registration — entity `MANDATE` (file-based) / `API_MANDATE`

| Event | Meaning |
|---|---|
| `MNDT.AUTHSUCCESS` | Signed/authenticated successfully |
| `MNDT.AUTHFAIL` | Authentication failed |
| `MNDT.TRANSUCCESS` / `MNDT.TRANFAIL` | Transferred to / failed transfer to sponsor bank |
| `MNDT.SPOBANKACCEPT` / `MNDT.SPOBANKREJECT` | Sponsor bank verdict |
| `MNDT.NPCIACCEPT` / `MNDT.NPCIREJECT` | NPCI verdict |
| **`MNDT.DESTBANKACCEPT`** | **Destination bank registered it — the mandate is live, UMRN usable** |
| `MNDT.DESTBANKREJECT` | Destination bank refused — registration failed |
| `MNDT.CANCELINIT` | Cancelled by corporate |
| `MNDT.BANKCANCEL` | Cancelled by destination bank |
| `APIMNDT.AUTHSUCCESS` / `APIMNDT.AUTHFAIL` | API-mandate authentication outcome |
| **`APIMNDT.DESTACCEPT`** / `APIMNDT.DESTREJECT` | **API mandate registered / rejected** |

**`…DESTBANKACCEPT` / `…DESTACCEPT` is the only event that means "we can collect".** Everything before
it is progress, not permission.

### Debit — entity `NACH_DEBIT`

`NACH.DEBIT.TXN.CREATED` · `NACH.DEBIT.TXN.SKIPPED` · `NACH.DEBIT.SUBMITTEDTOBANK` ·
`NACH.DEBIT.TRANSUCCESS` · `NACH.DEBIT.TRANFAIL` · `NACH.DEBIT.SPOBANKACCEPT` ·
`NACH.DEBIT.SPOBANKREJECT` · **`NACH.DEBIT.SUCCESS`** · **`NACH.DEBIT.FAILED`** ·
`NACH.DEBIT.AUTHENTICATED` · `NACH.DEBIT.DELAYED`

### Settlement — entity `CONSOLIDATED_SETTLEMENT`

`CONSOLIDATED.SETTLEMENT.CREATED` · `…INITIATED` · `…COMPLETED` (money reaching the corporate account).

### UPI — entities `UPI_MANDATE` / `UPI_DEBIT`

Documented but **not enabled for us** (`01-`). Recorded for completeness because they will matter if
UPI AutoPay is turned on: `UPI.MNDT.AUTHSUCCESS` · `UPI.MNDT.AUTHFAIL` ·
`UPI.MNDT.REGISTER.FAILURE` · `UPI.MNDT.AUTODEBIT.SUCCESS` · `UPI.MNDT.AUTODEBIT.FAILURE` ·
`UPI.MNDT.AUTODEBIT.REFUND.SUCCESS` · **`UPI.MNDT.REVOKED`** (*"Mandate Revoked after registration by
Bank side. This also contains cancellation based on NPCI Regulations. One example is multiple
execution failures."*) · `UPI.MNDT.PAUSED` · `UPI.MNDT.RESUMED` · `UPI.MNDT.REVOKE.FAILED` ·
`UPI.NACH.DEBIT.TXN.CREATED` · `…TXN.SKIPPED` · **`UPI.NACH.DEBIT.TXN.NOTIFIED`** (the mandatory
24-hour pre-debit notification; debit is attempted only if it succeeded) · `…SPOBANKACCEPT` ·
`…SUCCESS` · `…FAILED`.

## 4. Security

**VERIFIED** (FAQ 10, 11, 18):

- **`X-Digio-Checksum` header.** Digio sends an **HMAC-SHA256 hex digest of the payload**, keyed with a
  secret we set in the dashboard. Verify by recomputing over the **raw request body** and comparing.
- **Compare in constant time** — ours to get right, not Digio's.
- The secret is **account-level**, not per-event. Sandbox and production secrets can differ.
- Rotate it from profile → Webhooks.
- **IP allowlist** (`04-` §2) is available as a second layer.

**Explicitly absent — do not assume otherwise:**

- **No timestamp header and no documented replay protection.** The HMAC proves authenticity, not
  freshness: a captured payload replays forever. Mitigation is ours — dedupe on the envelope `id`
  (which `08-` stores with a unique constraint) and treat every handler as idempotent.
- **No event ordering guarantee.** Nothing in the docs promises order, and the rail is inherently
  out-of-order (a queued retry of an earlier event can land after a later one). Handlers must be
  **monotonic**: only ever advance state, never regress it.

Compute the HMAC over the raw body, before any JSON parse/re-serialise, or whitespace differences will
fail valid callbacks.

## 5. Delivery, retries and failure

**VERIFIED** (FAQ 3, 5, 9, 12, 14, 17):

| Property | Value |
|---|---|
| Success criterion | **HTTP 200.** Anything else is a failure |
| Timeout | **10 seconds** |
| Retry schedule | 5 min → 10 min → 15 min → 30 min (≈1 hour, first alert email), then **hourly up to 24 hours** |
| First failure alert | Email to the registered account address after ~1 hour of failure, containing the HTTP error code, the error message, and the **entity id** |
| After 24h of non-200 | **The webhook account is disabled** and a "Webhook disable" email is sent |
| Re-enabling | Manual: dashboard → Webhooks → "Click to Activate" |
| Events during an outage | **Queued and delivered** once re-enabled — nothing is lost |
| Head-of-line blocking | **Yes.** "event on top is reattempted till a 200 response is received" — one poison event stalls the account's whole queue |
| Typical latency | **~5 s production, ~5 min sandbox** |

Digio's own best-practice note is the design constraint, and it is correct:

> *"Do not execute business logic while receiving webhooks. Even if you execute, make sure a 200
> response is returned always… Log these cases, catch the errors and always return HTTP 200."*

And:

> *"Keep the flexibility of retrieving unknown fields… Reading of webhook data should not fail while a
> new parameter is received."* — i.e. `@JsonIgnoreProperties(ignoreUnknown = true)`, which is exactly
> how a new state enum value would otherwise take the integration down.

## 6. The receiver Navix must build

The split of responsibility is unambiguous.

| Digio guarantees | Navix must implement |
|---|---|
| At-least-once delivery | **Deduplication** on envelope `id` |
| HMAC-SHA256 authenticity | **Constant-time verification** + raw-body capture |
| Retry for 24h, then queue | **Always 200**, even on unknown entity, parse failure, or irrelevant state |
| A unique `id` per notification | **Idempotent, monotonic** state transitions |
| Nothing about ordering | **Order tolerance** — never regress a mandate's state |
| Nothing about replay freshness | **Replay rejection** via the dedupe table |
| An email when we break | **Alerting of our own** — the email goes to `info@dhanboost.com`, not to on-call |

Concrete shape, following `SignzyContractWebhookController` (`00-` §3) which already solves the same
problem for eSign:

1. **Receive** at `POST /api/webhooks/digio` — `permitAll` in `SecurityConfig`, self-authenticating.
2. **Verify** the HMAC over the raw body. On mismatch: log, return 200, do nothing. *(Signzy's
   controller returns 200 with `accepted:false` on a bad secret — same posture.)*
3. **Persist** the envelope to `digio_webhook_event` (unique on `digio_event_id`). A duplicate insert
   is the dedupe. Commit.
4. **Return 200 immediately.** Nothing below this line may affect the response.
5. **Process asynchronously** — publish a Spring event; `@TransactionalEventListener(AFTER_COMMIT)
   @Async` picks it up, per the repo's standing notification convention.
6. **Re-read from Digio** (`A3` / `C5c`) rather than trusting the payload, exactly as the Signzy
   controller re-reads the contract. A forged or replayed callback then costs at most one wasted GET.
7. **Reconcile on a schedule anyway.** Webhooks can be disabled for 24 hours without us noticing; a
   sweep over non-terminal mandates and in-flight debits is what makes the integration survive that.
   See `08-` §6.
