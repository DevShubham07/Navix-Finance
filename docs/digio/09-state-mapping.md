# 09 — State mapping

Navix has **no mandate state model today** (`00-` §3), so this proposes one rather than reconciling
with an existing enum. The Navix column is a proposal; the Digio columns are VERIFIED from the specs.

## 1. Digio's three state vocabularies

Digio exposes the same mandate through three fields that move independently. All three must be read.

1. **`state`** — Digio's own view:
   `partial · signed · expired · auth_success · auth_failed · cancelled · revoked`
   **CONFLICT:** the cancel spec's enum adds **`complete`**; the create and get specs omit it. Treat
   `complete` as possible and map it defensively.
2. **`bank_details.state`** — the banking rail (19 values):
   `created · ready · downloaded · failed · auth_success · successful · partial · sign_pending ·
   signed · transfer_failed · transfer_success · reject_spo_bank · accepted_spo_bank · awaiting_ack ·
   nack_received · ack_received · awaiting_res · register_failed · register_success · revoked`
   *(The create-mandate spec documents these UPPERCASE and the get spec lowercase — **CONFLICT**.
   Compare case-insensitively.)*
3. **`umrn`** — present or absent. The ground truth.

**The rule that overrides all mapping subtlety: a mandate is usable if and only if `umrn` is
present.** `state: auth_success` without a UMRN cannot fund a debit — `umrn` is the one required field
of `POST /v3/client/nach_debit/scheduled/register`. Where the enums and the UMRN disagree, the UMRN
wins.

## 2. Mandate state mapping

| Digio `state` | `bank_details.state` | UMRN | Proposed Navix state | Confidence | Trigger | Notes |
|---|---|---|---|---|---|---|
| `partial` | `created` / `ready` / `sign_pending` | no | `PENDING_AUTHORIZATION` | **High** | Created, awaiting the customer | Not a failure. Expires after `expire_in_days` |
| `signed` | `signed` | no | `AUTHORIZED_PENDING_REGISTRATION` | **High** | eSign/physical signed | Signed ≠ registered |
| `auth_success` | `auth_success` | no | `AUTHORIZED_PENDING_REGISTRATION` | **High** | `MNDT.AUTHSUCCESS` / `APIMNDT.AUTHSUCCESS` | **The dangerous one** — looks successful, cannot collect. Applies to `ESIGN`/`PHYSICAL`; an `API` mandate usually skips straight to the row below |
| `auth_success` | **`ack_received`** | **yes** | **`ACTIVE`** | **High — observed live** | `APIMNDT.AUTHSUCCESS` on an `API` mandate | **Verified `13-` §10:** ONMAGS returns the UMRN with the ACK, 7 min after creation. `res_report`/`register_success` may follow later; the UMRN is what matters |
| `auth_success` | `transfer_success` / `accepted_spo_bank` / `awaiting_ack` / `awaiting_res` / `ack_received` | no | `REGISTRATION_IN_PROGRESS` | **High** | Moving through the rail | Pure waiting |
| `auth_success` | **`register_success`** | **yes** | **`ACTIVE`** | **High** | `MNDT.DESTBANKACCEPT` / `APIMNDT.DESTACCEPT` | **The only state that can collect** |
| `auth_failed` | `failed` / `auth_success`→`failed` | no | `AUTHORIZATION_FAILED` | **High** | `MNDT.AUTHFAIL` | Terminal for this mandate; re-offer a new one. `AP11/14/30/33/39–41` |
| `auth_success` | `register_failed` / `nack_received` / `reject_spo_bank` | no | `REGISTRATION_FAILED` | **High** | `MNDT.DESTBANKREJECT` / `MNDT.SPOBANKREJECT` / `MNDT.NPCIREJECT` | Terminal. Keep `reject_code` — it decides whether retrying is pointless (`06-` §4) |
| any | `transfer_failed` | no | `REGISTRATION_FAILED` | Medium | `MNDT.TRANFAIL` | Digio→sponsor transfer failed; may be operationally retryable by Digio |
| `expired` | any | no | `EXPIRED` | **High** | `expire_in_days` elapsed | Terminal |
| `cancelled` | — | maybe | `CANCELLED` | **High** | `A5` / `MNDT.CANCELINIT` | Cancelled by us |
| `revoked` | `revoked` | maybe | `REVOKED` | **High** | `MNDT.BANKCANCEL` | Cancelled by the bank — **tell collections** |
| `complete` | — | — | `ACTIVE` *(provisional)* | **Low — CONFLICT** | Only in the cancel spec | Do not rely on it. Verify before trusting; `11-` Q16 |
| anything unrecognised | — | — | `UNKNOWN_QUARANTINE` | — | A new Digio enum value | **Never crash and never guess.** Hold, alert, reconcile by `A3` |

`downloaded` and `successful` appear in the enum with no documented meaning — **UNKNOWN**; they fall
into quarantine until observed.

**Deliberate collapses, and what each costs.** `REGISTRATION_IN_PROGRESS` flattens five rail states
(`transfer_success`, `accepted_spo_bank`, `awaiting_ack`, `ack_received`, `awaiting_res`): borrower-
and staff-facing behaviour is identical ("waiting on the bank"), and preserving five states would add
transitions nobody acts on. The cost is diagnostic granularity, paid back by storing the raw
`digio_state` and `digio_bank_state` on the row (`08-` §5), so support can see the real position
without our enum carrying it. `REVOKED` and `CANCELLED` are collapsed onto `AUTHORIZATION_FAILED` as implemented
(`MandateService.revoked`), and are evaluated **before** the "UMRN present ⇒ ACTIVE" rule — a mandate
the bank revoked comes back carrying its UMRN, and honouring it marked the row ACTIVE and let the file
reach disbursement with no live mandate. `AUTHORIZATION_FAILED` and `REGISTRATION_FAILED` are kept **apart** on
purpose — the first means the customer can try again, the second usually means their account cannot
hold this mandate at all.

## 3. Debit state mapping

Digio's transaction status (12 values) and the plan's `upcoming_transaction.state` (7 values) are
different vocabularies for the same thing.

| Digio txn `status` | Plan state | Proposed Navix | Confidence | Notes |
|---|---|---|---|---|
| `NEW` / `READY_TO_SEND` | `SCHEDULED` | `SCHEDULED` | **High** | Before the T-1 cutoff → still cancellable (`C4`) |
| `SUBMITTED_TO_BANK` / `ACCEPTED_SPO_BANK` | `PROCESSING` | `IN_PROGRESS` | **High** | **Past the point of cancellation** |
| `PAYMENT_DELAYED` | `DELAYED` | `IN_PROGRESS` | **High** | Not a failure. NPCI holidays reschedule automatically |
| `PAYMENT_AUTHENTICATED` | `AUTHENTICATED` | `IN_PROGRESS` | Medium | Authenticated ≠ settled. **Do not create a `Payment` here** |
| **`PAYMENT_SUCCESS`** | `SUCCESS` | **`SUCCEEDED`** | **High** | `NACH.DEBIT.SUCCESS` → create the `Payment` (`08-` §5) |
| `PAYMENT_FAILED` / `REJECT_SPO_BANK` | `FAILED` | `FAILED` | **High** | `failure_code` decides re-presentation: only "balance insufficient" is flagged **Allow RPN: Yes** |
| `SKIPPED` | `SKIPPED` | `SKIPPED` | **High** | Plan paused |
| `CANCELLED` | — | `CANCELLED` | **High** | Via `C4` |
| `DIGIO_TECHNICAL_ERROR` | — | `FAILED_TECHNICAL` | **High** | **Ours or Digio's fault, not the borrower's.** Alert; never show the borrower a failure message |

Settlement (`CONSOLIDATED.SETTLEMENT.*`) is money reaching the corporate account — **Digio-PA-only and
UNKNOWN for us** (`11-` Q11). It is treasury reconciliation, deliberately **not** modelled as a loan
state: the borrower's obligation ends at `PAYMENT_SUCCESS`.

## 4. Rules for the mapper

1. **Monotonic.** Webhooks have no ordering guarantee (`05-` §4). Never move backwards —
   a late `MNDT.AUTHSUCCESS` must not demote an `ACTIVE` mandate.
2. **UMRN beats enums.** Present ⇒ at least `ACTIVE`-eligible, whatever the strings say.
3. **Case-insensitive**, both state enums and webhook event names (`05-` §2).
4. **Unknown ⇒ quarantine**, never exception, never a guessed mapping.
5. **Keep the raw values** on the row. Our enum is for logic; `digio_state`/`digio_bank_state` are for
   the support engineer reading a failed mandate at 3am.
6. **One place.** `MandateStatusMapper` is the only code that reads a Digio enum (`08-` §3).
