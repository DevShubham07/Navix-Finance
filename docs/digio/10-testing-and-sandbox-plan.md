# 10 — Testing and sandbox verification plan

**Nothing in this plan may be executed against production.** Every `create_form`,
`registration_cancel` and `nach_debit` call on `api.digio.in` files a real mandate with NPCI or moves
real money. There is no undo.

## 1. What Digio provides for testing — mostly UNKNOWN

| Facility | Status |
|---|---|
| Sandbox environment | **VERIFIED it exists** — `https://ext.digio.in:444`, console `ext-enterprise.digio.in`, separate credentials and webhook account (`04-`) |
| Sandbox credentials for us | **UNKNOWN.** None found; no session on this machine. `11-` Q3 |
| Webhook latency in sandbox | **VERIFIED ~5 minutes** (vs ~5 s production) — *"Sandbox is mainly for functional testing"* |
| Test customers / test bank accounts | **UNKNOWN** |
| Simulated bank outcomes (force a reject, force insufficient balance) | **UNKNOWN** — the critical gap. Without it, the whole `AP…` branch of `06-` is untestable end to end |
| Whether a sandbox debit simulates the T-1/T cycle or resolves instantly | **UNKNOWN** |
| Postman collection / sample payloads | Not found. The **OpenAPI specs** in `api-reference/schemas/` are the usable substitute |
| Mock webhook trigger | **UNKNOWN** |

Resolve `11-` Q3 before planning the schedule: if the sandbox cannot simulate rejections, the
rejection paths can only be covered by contract tests against a local mock, and that limitation must
be stated openly rather than discovered in production.

## 2. Test layers

**L1 — Unit, no network.** `MandateStatusMapper` (`09-`) and money conversion (`08-` §4). Cheap and
high-value: the mapper is where a new enum value or a dropped UMRN check does damage, and `LoanMath`
already sets the house standard for exhaustive money tests.

**L2 — Contract, against the specs.** Validate our request builders against
`api-reference/schemas/*.json` — required fields, enums, the `customer_name` pattern (no apostrophes),
the 8–18 numeric account, `narration` ≤ 20 chars. Catches `151`–`197` NPCI malformed-request errors
before they reach NPCI.

**L3 — Local mock.** `DigioMockClient` + a webhook fixture replayer. Where every row of §3 that
sandbox cannot produce gets covered, including duplicate, out-of-order and forged callbacks.

**L4 — Sandbox, end to end.** Only after Q3. Real HTTP, real webhooks, the 5-minute latency, the port
444 egress path.

**L5 — Production smoke.** One mandate, one small debit, on a **staff member's own bank account**,
with explicit written approval. Not a test suite — a go-live gate. It is still a real NACH mandate and
a real debit, and it must be cancelled afterwards.

## 3. Test matrix

Layer: where it can run. Nothing marked L5 runs without approval.

| # | Case | Pre-conditions | Expected Digio | Expected webhook | Expected Navix | Layer |
|---|---|---|---|---|---|---|
| 1 | Create mandate, valid | Customer with penny-dropped account | `200`, `ENA…`, `state: partial`, `authentication_url` | — | `mandate` row `PENDING_AUTHORIZATION` | L3/L4 |
| 2 | Create with invalid IFSC | — | `400` + `code`/`message` | — | No row; typed error surfaced | L2/L4 |
| 3 | Create with apostrophe in name | `O'Brien` | `400` (pattern) | — | **Caught before the call** by L2 validation | L2 |
| 4 | Create with 7-digit account | — | `400` | — | Caught before the call | L2 |
| 5 | Authorisation success | #1 | `state: auth_success`, **no UMRN** | `MNDT.AUTHSUCCESS` | `AUTHORIZED_PENDING_REGISTRATION` — **explicitly not `ACTIVE`, and not collectable** | L3/L4 |
| 6 | **Registered** | #5 | `register_success` + **UMRN** | `MNDT.DESTBANKACCEPT` | **`ACTIVE`**, UMRN stored | L3/L4 |
| 7 | Authorisation failed | — | `auth_failed` | `MNDT.AUTHFAIL` | `AUTHORIZATION_FAILED`, reject code kept | L3/L4 |
| 8 | Destination bank rejects | — | `register_failed` + `AP…` | `MNDT.DESTBANKREJECT` | `REGISTRATION_FAILED`; **permanent codes do not re-offer** | L3 |
| 9 | Customer abandons | #1, no action | stays `partial` | none | Stays pending; **no auto-recreate** | L3/L4 |
| 10 | Link expires | #9 past `expire_in_days` | `expired` | — | `EXPIRED`, terminal | L3 |
| 11 | Auth failure (401) | Bad credentials | auth error | — | Typed error; **no secret in the log** | L3/L4 |
| 12 | **Duplicate create after timeout** | #1 timed out | — | — | **Recovers via `A4` by `customer_ref_number`; exactly one mandate exists** | L3 |
| 13 | Create debit | #6 | `200`, `NSP…`, `SCHEDULED` | `NACH.DEBIT.TXN.CREATED` | `mandate_debit` `SCHEDULED` | L3/L4 |
| 14 | Debit success | #13 | `PAYMENT_SUCCESS` | `NACH.DEBIT.SUCCESS` | `Payment` created; `outstandingAsOf` falls; loan closes at zero | L3/L4 |
| 15 | Debit fails, insufficient balance | #13 | `PAYMENT_FAILED` | `NACH.DEBIT.FAILED` | `FAILED`; **re-presentation allowed (Allow RPN: Yes)** | L3 |
| 16 | Debit fails, mandate cancelled | Cancelled mandate | reject | `NACH.DEBIT.FAILED` | `FAILED`; **no re-presentation**; mandate → `REVOKED`/`CANCELLED` | L3 |
| 17 | **Debit exceeds mandate max** | Amount > `maximum_amount` | reject | `NACH.DEBIT.FAILED` | `FAILED`. **The late-penalty headroom case — must be covered** | L3 |
| 18 | **Debit duplicate after timeout** | #13 timed out | — | — | **Recovers via `C5b` by `unique_key`; exactly one debit. The highest-stakes test here** | L3 |
| 19 | Cancel a scheduled debit | #13 before initiation | `200` | — | `CANCELLED` | L3/L4 |
| 20 | Cancel after initiation | #13 past cutoff | error | — | Rejected cleanly; staff told it is too late | L3 |
| 21 | Debit on an NPCI holiday | Settlement = holiday | **auto-moved to next working day** | — | Later settlement is **not** treated as failure | L3/L4 |
| 22 | **Duplicate webhook** | Same `id` twice | — | 2 deliveries | **One state change.** 200 both times | L3 |
| 23 | **Out-of-order webhook** | `DESTBANKACCEPT` then a late `AUTHSUCCESS` | — | — | **Stays `ACTIVE`** — monotonic rule | L3 |
| 24 | **Bad `X-Digio-Checksum`** | Forged body | — | — | **Ignored, 200 returned, alert raised.** No state change | L3 |
| 25 | Missing checksum header | — | — | — | Same as #24 | L3 |
| 26 | **Replayed valid webhook** | Captured genuine payload, resent | — | — | Deduped on `id`; no state change | L3 |
| 27 | Unknown event / unknown enum | Fabricated event | — | — | **200, quarantined, alerted, no crash** | L3 |
| 28 | Webhook handler throws | Forced exception | — | — | **Still 200** (Digio's own best practice); error recorded | L3 |
| 29 | Provider timeout on read | Network fault | — | — | Retry with backoff; no duplicate side effect | L3 |
| 30 | Reconciliation sweep | Webhooks off for 24h | — | none | Sweep finds and applies every missed transition | L3/L4 |
| 31 | Cancel mandate | #6 | `cancelled` | `MNDT.CANCELINIT` | `CANCELLED`; no further debits presented | L3/L4 |
| 32 | Borrower repays manually before debit | #13 pending | — | — | Debit cancelled via `C4`; **no double collection** | L3 |
| 33 | Mandate registers after loan closed | Late UMRN | — | `MNDT.DESTBANKACCEPT` | `ACTIVE` but **no debit presented** | L3 |
| 34 | Live banks | — | `200` array | — | Cached; method offered per bank capability | L3/L4 |

Cleanup: every L4 mandate cancelled (`A5`) and every scheduled debit cancelled (`C4`) at the end of a
run. L5 artefacts cancelled the same day.

## 4. Coverage honesty

Cases **8, 15, 16, 17, 21** depend on bank behaviour. If the sandbox cannot simulate it (`11-` Q3),
they are covered at L3 against our own fixtures only — meaning **we will have tested our handling of
a reject we have never actually received.** Say so in the go-live review rather than reporting the
matrix as green.

## 5. Existing conventions to follow

- Backend unit suite: `./mvnw test` (Java 21 required; 17 fails the build).
- Integration: Testcontainers Postgres, `-Pit` profile, Docker required.
- Frontend: `npx tsc --noEmit` + ESLint. *(`npm run build`'s static-prerender step fails at
  `/staff/admin/staff` on a clean checkout — a known Next 15.1.3 issue, not our code.)*
- **CLAUDE.md's verification rule, which applies to everything above:** never report tests as passing
  from grepped output — check the exit code.
- **Two tests already fail on `main`** (`navix-loan`: date rot + a stale DigiLocker assertion) and
  halt the reactor before `navix-app`. Fix or exclude them before trusting a green run on this work.
