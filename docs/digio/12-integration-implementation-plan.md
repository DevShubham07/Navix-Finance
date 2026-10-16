# 12 — Phased implementation plan

**Do not start Phase 1 while `11-` Q1, Q2, Q3 or Q13 is open.** Phase 0 exists to close them.

## Phase 0 — Unblock (no code)

| Task | Owner | Blocks |
|---|---|---|
| Obtain sandbox + production credentials and the rotation procedure | Whoever owns the Digio relationship | Everything |
| Confirm sandbox access and whether it can simulate bank rejections | same | `10-` L4 |
| Confirm the NPCI management category approved for utility `NACH00000000067052` | Digio + Yes Bank | First real call |
| Decide **per-loan vs per-customer** mandate (Q2) | Product + compliance | Schema, journey, consent |
| Decide whether mandate terms enter the KFS (Q14) | Compliance | Borrower journey |
| Ask about UPI AutoPay availability (Q4) | Commercial | May change the whole design |
| Enable 2FA on the Digio console | Ops | — |

**Exit:** Q1, Q2, Q3, Q13 answered. If Q4 comes back "yes, UPI available", **stop and re-evaluate** —
real-time registration and non-revocable lending mandates would change `02-`, `08-` and `09-`
materially. Better to redo a document than a schema.

## Phase 1 — Adapter and contract (no borrower exposure)

`MandatePort`, `DigioMandateClient`, `DigioMockClient`, `DigioConfig` with fail-fast validation,
`MandateStatusMapper`, money conversion (`08-` §3–4).
**Tests:** `10-` L1 + L2 — mapper exhaustive over every enum value in `09-`, request builders
validated against `api-reference/schemas/`.
**Exit:** every spec'd request can be built and every spec'd response parsed, against the mock. No
production call. No schema change yet.

## Phase 2 — Persistence and webhook receiver

Migration **V81** — `mandate`, `mandate_debit`, `digio_webhook_event` (`08-` §5).
`DigioWebhookController` at `POST /api/webhooks/digio`, added to `SecurityConfig`'s `permitAll` list
beside the existing provider callbacks, following `SignzyContractWebhookController`: verify HMAC over
the **raw body**, persist, **return 200 always**, process async, **re-read from Digio**.
**Tests:** `10-` cases 22–28 — duplicate, out-of-order, forged, replayed, unknown-event, handler-throws.
**Exit:** webhook configured in the **sandbox** dashboard and delivering end to end.

> Build the receiver *before* configuring the webhook. A configured endpoint that 404s starts the
> 24-hour clock to account-level disablement (`05-` §5).

## Phase 3 — Registration, staff-only

`MandateService.register` + `A3` status + `A5` cancel. `A6` live-banks lookup to decide which methods
a borrower's bank supports. Staff-triggered only, behind flag `digio-mandate`
(`defaultWhenMissing = FALSE`). Re-enable
`frontend/src/components/staff/customer-360/mandate-tab.tsx` to show real status.
**Tests:** `10-` 1–12, 31, 34.
**Exit:** a staff member can register and cancel a sandbox mandate and watch it reach `ACTIVE`.

## Phase 4 — Borrower journey

Mandate step in the borrower flow via the **BFF**; `authentication_url` reaches the borrower session
only. Redirect-return handling plus a poll, with **our row as the source of truth** — the repo's
standing rule for every redirect flow. Notifications through `navix-notification` (publish an event;
never make business logic depend on delivery).
**Exit:** a borrower completes a sandbox mandate on a real device.

## Phase 5 — Debit presentation

`C1`/`C2`, the reconciliation sweep (`08-` §6) including the **T-1 cutoff**, `C4` cancellation,
`Payment` creation on `NACH.DEBIT.SUCCESS`, re-presentation only where **Allow RPN** permits.
**Tests:** `10-` 13–21, 29–33 — case 18 (duplicate debit after timeout) is the one that must not ship
red.
**Exit:** sandbox debit succeeds, closes the loan via the existing `outstandingAsOf` path, and a
forced timeout provably does not double-debit.

## Phase 6 — Production rollout

1. Production credentials in SSM; production webhook configured; **both event families enabled**.
2. **Smoke on a staff member's own account** with written approval (`10-` L5). Cancel the same day.
3. Flag on for an **allowlist of internal borrowers**.
4. Gradual ramp, holding at each step for a **full settlement cycle** — T-1 → T → EOD confirmation is
   two to three days, so a one-day soak proves nothing.
5. Manual repayment stays available throughout. It is the guaranteed path, not a fallback to retire.

## Observability

Minimum before Phase 6:

- Counters by mandate state and debit state; alert on `REGISTRATION_FAILED` and `FAILED_TECHNICAL` rate.
- **Webhook-silence alert** — no Digio webhook in N hours. This is the one that catches account-level
  disablement, and the email Digio sends goes to `info@dhanboost.com`, not to on-call.
- Reconciliation-sweep outcome logged every run; alert if it is finding many missed transitions
  (webhooks are broken) or if it fails to run (collection stops).
- Alert when a due loan with an `ACTIVE` mandate has **no debit presented** by the T-1 cutoff.
- Digio call latency and error rate; `details` exception ids retained for support.
- **Never** log account numbers, `access_token`, `authentication_url` or the `Authorization` header.

## Rollback

| Scenario | Action |
|---|---|
| Registration misbehaving | Flag `digio-mandate` off → journey reverts to manual. Existing mandates idle harmlessly |
| Debits misbehaving | Stop the presentation sweep first (it is what moves money), keep reading status |
| Double-debit suspected | **Stop the sweep immediately**, reconcile by `unique_key` (`C5b`), cancel what is cancellable (`C4`), refund the rest manually |
| Webhooks disabled by Digio | Reconciliation sweep carries the load; fix the endpoint; re-enable in the dashboard |
| Full retreat | Flag off, `NAVIX_DIGIO_ENABLED=false`. **Leave the tables** — they are the audit trail for mandates that exist at Digio and at NPCI whether or not we read them |

A flag flip does not cancel mandates already filed with NPCI. Backing out of the *code* is easy;
backing out of the *authorities* means calling `A5` for each one. Plan for that before Phase 6, not
during an incident.
