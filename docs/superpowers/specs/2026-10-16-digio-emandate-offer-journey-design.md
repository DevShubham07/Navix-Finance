# Digio eMandate in the borrower offer journey — design

**Date:** 2026-10-16
**Status:** awaiting approval — nothing built yet
**Background:** [`docs/digio/`](../../digio/README.md) is the provider knowledge base. Read
[`13-live-verification-log.md`](../../digio/13-live-verification-log.md) first; it corrects several
claims in the rest of that set.

---

## 1. Intent and why

DhanBoost repayment is **manual today**: the borrower uploads a payment screenshot and an Accountant
verifies it. That is slow, staff-intensive, and depends on the borrower acting on their salary day.

A NACH eMandate gives us a standing authority to debit their account, so repayment can be **collected**
rather than **requested**. The borrower authorises once; every future advance reuses it.

Proven end to end against production on 2026-10-16: a real mandate was created, authorised by Aadhaar,
and issued a UMRN **7 minutes later**. The product is viable on this rail.

## 2. Goals

- A borrower completes a NACH mandate as the final step of the offer journey, before the file can reach
  `DISBURSEMENT_PENDING`.
- One mandate **per customer**, reused for every future advance.
- The mandate covers the **worst case the product allows**, including maximum late penalty.
- Staff can see a customer's mandate status.

## 3. Non-goals

- **Debit presentation.** Collecting against the UMRN is separate, larger work (scheduler, T-1 cutoff,
  `Payment` creation, re-presentation). This build **registers** mandates only; repayment stays manual.
- **UPI AutoPay.** Not provisioned — needs its own corporate config id
  ([`13-`](../../digio/13-live-verification-log.md) §11).
- **eSign and physical mandate modes.** `auth_mode: API` only; file-based modes take days and would
  break a blocking step.
- **Mandate amendment/cancellation UI.** APIs exist; no UI here. See §13 risk 2.
- **Webhook receiver.** §8.

## 4. Anti-goals — this build has failed if

- Any debit is presented or money moves.
- A borrower reaches `DISBURSEMENT_PENDING` without a registered mandate (flag on, bank capable).
- A returning borrower with a live mandate is asked to authorise again.
- A mandate's ceiling cannot cover what that borrower could owe.
- Full bank account numbers reach logs or the provider-call audit table.

## 5. Decisions made by the user

| Decision | Value |
|---|---|
| Placement | **Last step**, immediately after `OFFER_DISBURSAL_ACCOUNT` |
| Debit account | **The disbursal account** the borrower confirms at `OFFER_DISBURSAL_ACCOUNT` |
| Blocking | **Yes** — no mandate, no `DISBURSEMENT_PENDING` |
| Ordinary failure | **Borrower retries the screen.** No staff queue, no park |
| Bank cannot do API mandate | **Staff override** — the one case that parks for a human |
| Ceiling basis | **2× formula limit** (`LoanMath.eligibleLimitPaise`) |
| Scope | Per-customer mandate, reused across advances |

**Why last:** the disbursal account is penny-drop validated and is the account the borrower chose to be
paid into. The salary account captured at intake is never validated for the 8–18-numeric or
`api_mandate`-capable constraints Digio imposes. Placing the mandate after the disbursal account is the
only ordering where the account is known-good.

**Consequence the user accepted:** the borrower signs the agreement, confirms their account, and *then*
authorises the mandate. A failure leaves a signed, unfunded agreement whose file never routes onward.
Recoverable, not corrupt.

## 6. Mandate parameters — derived, not guessed

### Amount

Maximum exposure is exactly **2× principal**, bounded by the product's own caps (`LoanMath`: interest
1%/day capped at a 40-day tenure = +40%; late penalty 2%/day capped at 30 days = +60%).

`maximum_amount` = **2 × max(`LoanMath.eligibleLimitPaise(salary)`, sanctioned principal)**, in rupees.

> **Builder's call, flagged for review.** The user chose "2× formula limit". The `max(...)` floor is
> added because `customer_limit_override` (V69) and ADMIN sanctioned-amount corrections can both put a
> sanctioned principal **above** the formula limit, and a ceiling below the advance would reject at
> first debit with *"amount exceeds mandate max amount"* — anti-goal §4. The floor honours the choice
> while making the anti-goal unreachable. **Reject this and the override case ships broken.**

`collection_amount` is **omitted** — verified unnecessary despite the spec marking it required
([`13-`](../../digio/13-live-verification-log.md) §9), which yields the MAXIMUM-type mandate a
penalty-varying amount requires.

### Dates

- `first_collection_date` = **creation date (today, IST)**. "Date before mandate start date" is a
  rejection reason; it must precede every debit we will ever present.
- `final_collection_date` = **creation + 5 years**. "Date after mandate end date" is also a rejection.

### Type

`is_recurring: true`, `frequency: "Adhoc"`, `management_category: "L001"`, `auth_mode: "API"`.

**Not** `ONETIME` (forces `payment_count: 1`, dies after one advance) and **not** a `Monthly` EMI
frequency (does not describe a single salary-linked repayment).

**This exact combination is proven accepted by NPCI** — the live mandate used it and received a UMRN.

### Reference key (`customer_ref_number`)

**One `mandate` row per attempt**, not per customer. CRN = `<customer_id>-<attempt_no>`, ≤20 chars,
alphanumeric.

> **Builder's call.** The user's "borrower retries until it works" and Digio's "CRN unique per mandate"
> are incompatible with a CRN that is deterministic per customer: a retry after a genuine failure would
> collide with the failed mandate's CRN. Per-attempt CRN keeps **timeout recovery** working (look up by
> this attempt's CRN before re-POSTing — Digio has **no idempotency key**) while allowing retries.
> "Live mandate" for the skip rule = the customer's newest row **with a UMRN**.

## 7. Flow

```
… → OFFER_SANCTION_LETTER (agreement + signature) → OFFER_SANCTIONED
      → OFFER_DISBURSAL_ACCOUNT (penny drop / cheque upload)
          → OFFER_MANDATE  ← NEW, blocking
              → DISBURSEMENT_PENDING
```

1. `POST /api/applications/{id}/offer/mandate/init` → `create_form`, persist row, return
   `authentication_url`.
2. Borrower authorises on Digio's hosted page. **Options vary by bank** — PNB offers Aadhaar and debit
   card, SBI adds net banking.
3. Borrower returns; frontend polls `GET /api/applications/{id}/offer/mandate/status`, which
   **re-reads from Digio** and updates our row.
4. UMRN present → route to `DISBURSEMENT_PENDING`.

**Our row is the source of truth**, re-read from Digio rather than trusted from any callback — the
standing rule for every redirect flow in this codebase. Mirrors the existing `esign/init` +
`esign/status` pair on `OfferController`.

### Where the block is enforced

**`OfferService.confirmDisbursalAccount`** currently routes the file to `DISBURSEMENT_PENDING`. That
routing moves behind the mandate check; confirming the account now advances to `OFFER_MANDATE` instead.
New error code `MANDATE_REQUIRED`, following the existing `ESIGN_REQUIRED` pattern in
`ApplicationFlowService.acceptOffer`.

`JourneyService.deriveOffer` needs a matching clause — it is what actually holds a borrower, and
without it a file derives straight past the new step.

### Reborrow / returning borrower

`applicableOfferSteps` drops steps only when `app.getReappliedFrom() != null`, and only from
verification-check rows. **That mechanism does not cover this.** `OFFER_MANDATE` must be dropped
whenever the **customer** has a mandate row with a UMRN — reborrow or not — or a non-reborrow
application for an existing mandate holder re-asks (anti-goal §4).

### Bank incapable of API mandate

Before offering the step, resolve the disbursal account's IFSC prefix against
`GET /v3/client/mandate/banks` and check `api_mandate` + `allowed_auth_sub_type`. If the bank cannot:
park for **staff override** (new permission + queue surface), the one exception to "borrower retries".

## 8. Why no webhook receiver here

Webhooks are **not configured** on the account, and `auth_mode: API` mandates return the UMRN **within
the authentication session** — the borrower is on the screen polling, so step 3 suffices.

A receiver is required before **debit presentation**, where outcomes arrive hours later. Designed in
[`docs/digio/05-`](../../digio/05-webhooks-and-asynchronous-processing.md); built later.

**Accepted risk:** a mandate ACK'd but later rejected by the destination bank shows as registered until
the first debit fails.

## 9. Components

| Component | Status | Responsibility |
|---|---|---|
| `MandatePort` (`navix-common`) | NEW | The seam. Mirrors `EsignPort` |
| `DigioMandateClient` (`navix-verification`) | NEW | Only class that knows Digio. HTTP Basic, create + get |
| `DigioConfig` | NEW | `@ConfigurationProperties`, **fail-fast at startup** when enabled; follows `EsignProperties`/`EsignConfig` |
| `MandateService` (`navix-loan`) | NEW | Register, status, CRN recovery, bank-capability check, state mapping |
| `mandate` table | NEW migration **V82** (head is V81) | One row per attempt |
| `JourneyService` | EDIT | Add `OFFER_MANDATE("mandate")` after `OFFER_DISBURSAL_ACCOUNT`; skip rule; `deriveOffer` clause |
| `OfferService.confirmDisbursalAccount` | EDIT | Advance to `OFFER_MANDATE` instead of routing onward |
| `OfferController` | EDIT | `POST …/offer/mandate/init`, `GET …/offer/mandate/status` |
| BFF route + `/loan/mandate` screen | NEW | Mirrors the eSign screen |
| `frontend/src/lib/offer.ts`, `lib/domain/journey-steps.ts` | EDIT | **Hardcoded step lists that mirror the enum** |
| `components/staff/stage-signal.test.tsx` | EDIT | Asserts `total: 11`; will fail otherwise |
| `customer-360/mandate-tab.tsx` + `customer-tabs.tsx` | EDIT | Replace placeholder **and re-enable in the tab strip** |
| Staff override queue + permission | NEW | For bank-incapable files only |
| Feature flag `digio-mandate` | NEW row | `defaultWhenMissing = FALSE` — gates a money-affecting rail |

**Module placement is forced, not preferred:** `ProviderApiExecutionWriter` lives in `navix-app` and is
installed into `navix-verification`'s `ProviderCallLog`; `navix-loan` depends only on `navix-common`.
Putting the client in `navix-loan` would forfeit §10's audit/redaction requirement and the Provider API
dashboard. Port in `navix-common` + adapter in `navix-verification` is the established `EsignPort`
pattern.

### Schema (V82)

`mandate`: `id`, `customer_id`, `loan_application_id`, `attempt_no`, `digio_mandate_id` (`ENA…`,
unique), `umrn` (nullable, unique when present), `customer_ref_number` (unique), `status`,
`digio_state`, `digio_bank_state`, `auth_mode`, `auth_sub_mode`, `maximum_amount_paise`,
`first_collection_date`, `final_collection_date`, `bank_name`, `account_last4`, `ifsc`, `reject_code`,
`reject_reason`, `authenticated_at`, `registered_at`, `created_at`, `updated_at`.

No FK constraints, indexes only — matching the repo's documented, deliberate debt.

### Status model

`PENDING_AUTHORIZATION` → `ACTIVE` (UMRN present) / `AUTHORIZATION_FAILED` / `EXPIRED`, plus
`BANK_UNSUPPORTED` for the override path. Collapsed from
[`09-state-mapping.md`](../../digio/09-state-mapping.md) because this build has no file-based modes and
no debit. **Rule: UMRN present ⇒ ACTIVE**, whatever the enum strings say. Raw `digio_state` /
`digio_bank_state` stored for support.

## 10. Constraints the builder must honour

- **Money is integer paise (`long`), HALF_UP, never floats.** Digio's `maximum_amount` is **rupees
  decimal**; convert once, inside the adapter, with `BigDecimal`.
- **The live API contradicts its published spec** in six places — field casing, required-ness, enum
  vocabulary between write and read, PII masking, undocumented fields, HTTP status for business errors.
  Validate DTOs against real responses, not the spec.
- **The account number comes back IN FULL, not masked.** Store `account_last4` only; redact before
  logging and before anything reaches `ProviderApiExecutionWriter`.
- **`frequency` differs between write and read** — send `"Adhoc"`, expect `"ADHO"`. Do not reuse one
  enum for both directions.
- **Business errors arrive as HTTP 500** with `{code: SYSTEM_ERROR, message}`. Never classify retry
  safety by status code; branch on the body. Always log `details` (the Digio exception id).
- **Never blind-retry a create.** Recover by this attempt's CRN first.
- Credentials and `corporate_config_id` in **SSM SecureString** `/navix/<env>/navix/digio/…`.
- `customer_name` must match `^[0-9A-Za-z]{1,}[0-9A-Za-z\s.]{0,}$` — **no apostrophes or hyphens**,
  max 40 chars. Sanitise or the request 400s.
- Account number must be 8–18 **numeric**.

## 11. Testing

- Unit: amount derivation (incl. the override floor), date derivation, name sanitisation, state
  mapping, CRN/attempt sequencing, skip rule for an existing UMRN holder.
- Contract: request builder against
  `docs/digio/api-reference/schemas/final_post_createMandateForm.json`.
- **No test may call production Digio.** No sandbox access exists
  ([`11-` Q3](../../digio/11-open-questions-and-assumptions.md)); the mock client is the test seam.
- Per CLAUDE.md: check exit codes, never grep output. Two tests already fail on `main` (`navix-loan`,
  date rot + stale DigiLocker assert).

## 12. Left to the builder

- Screen copy, including how the 2× ceiling is disclosed. **KFS wording is a compliance question** —
  flag it, do not invent it.
- Polling interval and timeout; follow the eSign screen.
- Retry/backoff on Digio reads; caching of the 305-bank list (88 KB — cache by IFSC prefix, refresh
  daily).
- Shape of the staff override surface (which queue, which role).

## 13. Open risks

| Risk | Mitigation |
|---|---|
| Flag flipped on mid-journey: signed, account-confirmed files with no mandate | Evaluate at derive time; such files get the step. Flipping **off** must let a file parked on `OFFER_MANDATE` through |
| Salary rises above the mandate ceiling on a later advance | Amendment is a non-goal here, so the per-customer reuse claim degrades over time. **Needs the amend path before this is truly long-lived** |
| Mandate ACK'd then rejected by destination bank | Accepted (§8); surfaces at first debit |
| Real-time UMRN observed on one bank only (PNB + Aadhaar) | Status screen must tolerate a mandate that stays pending; blocking + no override makes this the likeliest stranding path |
| Staff override becomes the default escape hatch | Track override rate; it is meant for bank incapability only |
