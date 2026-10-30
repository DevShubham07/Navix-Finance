# 13 — Live production verification log

Calls actually executed against **`https://api.digio.in`** with production credentials, 2026-10-16.
Everything here is **VERIFIED by execution**, the strongest evidence class in this knowledge base —
and it already contradicts the published specs in one place.

Credentials were supplied by Digio ("Welcome aboard") and are **valid**. They are **not recorded
here or anywhere in this repository**; they belong in SSM (`04-` §4).

## 1. Executed

### ✅ `GET /v3/client/mandate/banks?mode=API` → **HTTP 200**, 88 KB, **305 banks**

Read-only, no side effects. This is the credential smoke test — **authentication is confirmed
working against production.**

### ❌ `POST /v3/client/mandate/create_form` (UPI) → **not executed**

Blocked by this environment's safety classifier as a real-world financial transaction. See §4.

## 2. Spec vs reality — the live API disagrees with the published spec

**The bank-list response is `snake_case`. The published spec documents `camelCase`.** Coding to
`api-reference/schemas/get_getBanksList.json` would have produced a client that silently read nulls
for every field.

| Published spec | Live production response |
|---|---|
| `ifscPrefix` | `ifsc_prefix` |
| `apiMandate` | `api_mandate` |
| `esignMandate` | `esign_mandate` |
| `allowedAuthSubType` | `allowed_auth_sub_type` |
| `bankShortCode` | *(absent — `routing_code` instead)* |
| `apiMandateNetBanking`, `apiMandateDebitCard`, `apiMandateAadhaarCard`, `apiMandateDD*` | *(absent — collapsed into `allowed_auth_sub_type[]`)* |
| *(not in spec)* | `name`, `primary_bank`, `routing_code`, `physical_mandate`, `penny_less` |

Actual shape:

```json
{
  "id": "BNI19020515304252265L56AKINIMYO3",
  "name": "Airtel Payments Bank Limited",
  "ifsc_prefix": "AIRP",
  "active": true,
  "primary_bank": true,
  "routing_code": "AIRP",
  "esign_mandate": false,
  "api_mandate": true,
  "physical_mandate": false,
  "allowed_auth_sub_type": ["net_banking", "aadhaar", "debit"],
  "penny_less": true
}
```

**Consequence for `03-` and `08-`:** treat the published specs as authoritative for *paths, required
fields and enum values*, but **verify field casing against a live call before writing a DTO.** Assume
the same camelCase/snake_case drift may exist on other endpoints' responses. This is now the single
strongest argument for running Phase 1 contract tests against the sandbox rather than the spec alone
(`10-` L2/L4).

## 3. Bank coverage — far wider than the console suggested

The console's New Mandate dropdown showed **39** banks (`01-` §4). The API returns **305**.

| Capability | Banks |
|---|---|
| `api_mandate` | **305 / 305** |
| `physical_mandate` | 303 |
| `esign_mandate` | **123** |

So **API eNACH is the broadest rail, not the narrowest** — the reverse of what `02-` assumed from the
console snapshot. The earlier worry that "several large lenders' customers bank outside this list" is
**withdrawn**: `01-` §4's 39-bank list was a console subset, never the real coverage. `A6` must be the
source of truth, exactly as `03-` recommended.

**State Bank of India** (the borrower's bank for this test):

```json
{ "name": "State Bank Of India", "ifsc_prefix": "SBIN", "routing_code": "SBIN",
  "active": true, "primary_bank": true,
  "api_mandate": true, "esign_mandate": false, "physical_mandate": true,
  "allowed_auth_sub_type": ["net_banking", "aadhaar", "debit"], "penny_less": true }
```

Note `esign_mandate: false` for SBI — **the eSign fallback is unavailable to SBI customers.** For
them it is API mandate (net banking / Aadhaar / debit card) or physical. This strengthens `11-` Q6's
answer: do not design around eSign.

## 4. The UPI AutoPay finding — this changes the recommendation

`11-` Q4 asked whether UPI AutoPay is available. The sidebar suggested no. **The API says otherwise:**
`https://documentation.digio.in/digicollect/upi/upi_registration/` documents a full UPI mandate API,
and its spec is now mirrored at
[`api-reference/schemas/final_post_createMandateForm_upi.json`](api-reference/schemas/final_post_createMandateForm_upi.json).

**Same endpoint, different product:** `POST /v3/client/mandate/create_form` with `auth_mode: "upi"`
and `customer_vpa`. Whether it is *entitled* on our account is still **UNKNOWN** — only a live call
settles that, and that call was blocked (§5).

Two registration flows, both **real-time**, neither needing a bank-file cycle:

- **UPI Collect** — the customer enters a VPA and receives a mandate-approval notification in their
  UPI app. Approve → registered, auto-debit set up.
- **UPI Intent** — an app-switch tray of installed UPI apps.

### Differences from NACH that will catch an implementer out

| | NACH (`auth_mode: API/ESIGN/PHYSICAL`) | UPI (`auth_mode: upi`) |
|---|---|---|
| `frequency` enum | `Adhoc｜Monthly｜Quarterly…` | **`ADHO｜MNTH｜QURT…`** (NPCI 4-letter) |
| `collection_amount` max | ₹1,00,00,000 | **₹1,00,000** |
| `maximum_amount` max | ₹1,00,00,000 | **₹2,00,000** |
| `customer_name` max | 40 chars | **100 chars** |
| `final_collection_date` | optional (defaults 40 yrs) | **required** |
| Registration latency | **days** (bank file cycle) | **real-time** |
| Extra fields | — | `customer_vpa`, `include_upi_url`, `auto_debit_amount_in_paise`, `account_validation` |

**Same path, same `mandate_type`, two different enum vocabularies and two different amount ceilings.**
`MandateStatusMapper` and the request builder must branch on `auth_mode`, not share one code path.

Two UPI-specific behaviours worth designing around:

- **`auto_debit_amount_in_paise` defaults to `100` — a real ₹1 debit at registration.** Registering a
  UPI mandate moves money. Expect it, reconcile it, and don't surface it to the borrower as a
  repayment.
- **`account_validation`** auto-revokes the mandate if the account the customer authenticates with
  differs from the `customer_account_number` we supplied. Useful anti-fraud, but it means a wrong or
  guessed account number silently kills the mandate after registration.

### Why this matters more than it looks

`02-` §5 and `08-` §2 are built around NACH's multi-day registration latency being incompatible with
a ≤40-day, same-day-disbursal product. **UPI AutoPay removes that constraint entirely** — real-time
registration, plus Digio's documented guarantee that **lending mandates cannot be revoked by the
borrower**. If UPI is entitled on our account, it is the better rail and `08-` should be revisited
before Phase 1, as `12-` Phase 0 already warned.

## 5. Blocked — needs the operator

The UPI mandate creation below was prepared and **refused by this environment's safety classifier**
(category: real-world transactions). It was not retried or worked around.

Request body as constructed (ceiling deliberately conservative at ₹1,000 for a first live test;
`customer_ref_number` is generated unique per attempt so a timeout is recoverable via `A4`):

```jsonc
{
  "corporate_config_id": "TSE261016195601220U17VMUFQVFG2GW",
  "mandate_type": "CREATE",
  "auth_mode": "upi",
  "customer_identifier": "<mobile>",
  "notify_customer": "True",
  "mandate_data": {
    "customer_ref_number": "DB<yymmddHHMMSS>",
    "is_recurring": true,
    "frequency": "ADHO",
    "first_collection_date": "2026-10-16",
    "final_collection_date": "2027-10-16",
    "maximum_amount": 1000,
    "customer_name": "<name>",
    "customer_mobile": "<mobile>",
    "customer_vpa": "<vpa>",
    "include_upi_url": true
  }
}
```

**Known risk in this payload:** the UPI spec marks `customer_account_number` **required**
(`minLength: 0`, description "Min len = 8 Max len = 18, all numeric"). It was omitted because a VPA
does not yield an account number. The call may return `400`. If it does, the account number and IFSC
are needed — and that reopens the question of where Navix gets them (for a real borrower: the penny
drop already on file; for this test: the operator).

`is_recurring: true` + `ADHO` + `maximum_amount` implements the **per-customer, MAXIMUM-type** shape
now chosen for `11-` Q2 — one reusable authority, exact amount set per debit.

## 6. UPI, followed through — registration is real-time, **collection is not**

Chasing the UPI direction further changes the picture again, and moderates §4's enthusiasm.

### UPI debit lives on a different path family

| | NACH | UPI |
|---|---|---|
| Registration | `POST /v3/client/mandate/create_form` | **same path**, `auth_mode: "upi"` |
| Debit | `POST /v3/client/nach_debit/scheduled/register` | **`POST /v3/client/upi_payment/scheduled/register`** |
| Debit (bulk) | `/v3/client/nach_debit/scheduled/register_multiple` | `/v3/client/upi_payment/scheduled/register_multiple` |

Registration shares an endpoint; **collection does not**. A client that assumes one debit path for both
rails is wrong. The UPI presentation family mirrors NACH's six operations (create, get, plan action,
transaction action, get transaction, get settlement) under `upi_payment`.

### The 26-hour rule — the most important UPI constraint

> `settlement_date` — *"Settlement Date should be more than 26 hours from current time"*

This is NPCI's mandatory 24-hour pre-debit notification plus a buffer, and it is **VERIFIED in the
spec**. So the real split is:

- **Registration: real-time.** The borrower approves in their UPI app and the mandate is live — no
  multi-day bank-file cycle. This is the genuine advantage over NACH.
- **Collection: ≥ 26 hours' notice.** Comparable to NACH's T-1 cutoff, not better.

**For Navix this is fine.** The due date is the salary day, known at sanction, so a debit can always
be scheduled well over 26 hours ahead. **What it rules out is same-day collection** — a borrower who
becomes due today cannot be debited today on either rail. Manual repayment remains the only same-day
path, which is another reason `08-` keeps it as the guaranteed route rather than a fallback to retire.

### Other UPI debit details

- `partner_bank` enum is `KOTAK | YESB | JUSPAY_KOTAK | MGS_YESB` — **our sponsor is `YESB`**, so Yes
  Bank serves both rails. No second sponsor relationship needed.
- `unique_key` defaults to `<umrn>_<count>`. **Do not rely on the default** — `06-` §3's
  recovery-by-reference needs a key we chose deterministically.
- `corporate_account_number` here is constrained `8–18, all numeric` (the NACH equivalent declares no
  such limit).
- `amount_in_paise` is `int32`, as on the NACH side.
- `frequency` on the debit call is **lowercase** (`onetime｜adhoc｜monthly…`) while the UPI
  *registration* call uses NPCI 4-letter codes (`ADHO｜MNTH…`). **Three different frequency
  vocabularies across the two rails' four endpoints.** This is the single most likely source of a
  silent 400 in implementation.

### UPI failure codes are a separate catalog

`documentation.digio.in/digicollect/upi/error_codes/` lists UPI-specific reasons with a
Technical/Business split, none of which overlap NACH's `AP…` codes. The ones that matter for a
lender:

- **`MANDATE HAS BEEN REVOKED`**, **`MANDATE IS PAUSED`** — note that despite Digio's statement that
  *lending* mandates cannot be revoked by the borrower, revocation and pause still exist as outcomes
  (bank-side, or NPCI-driven after repeated failures). Do not treat a UPI mandate as permanent.
- **`NUMBER OF MANDATES ALLOWED ON THIS ACCOUNT HAS EXCEEDED ISSUER'S LIMIT`** — a per-account cap set
  by the borrower's bank. A real failure mode for the per-customer design if a borrower already runs
  several autopay mandates.
- **`ADEQUATE FUNDS NOT AVAILABLE… FUNDS HAVE BEEN BLOCKED FOR MANDATE`**, `DEBIT AMOUNT GREATER THAN
  BLOCKED AMOUNT`, `DEBIT AMOUNT IS NOT BLOCKED FOR THE CUSTOMER` — UPI AutoPay blocks funds at
  notification time; our penalty-aware amount must be final **before** the pre-debit notification, not
  at debit time.
- `PAYER ACCOUNT HAS CHANGED`, `MANDATE SIGNATURE IS TAMPERED OR CORRUPT`, `INCORRECT AMOUNT RULE`,
  `EXECUTION DAY AND EXECUTION RULE MISMATCH`, `DUPLICATE MANDATE REQUEST FOR SAME ITEM`,
  `MANDATE IS ALREADY HONOURED`, `PAYEE VPA IS INCORRECT`.

### Revised recommendation

UPI's advantage is **registration latency**, not collection latency — which is still the constraint
that `02-` §5 identified as fatal to a mandate created at sanction time. UPI therefore makes the
**per-customer, register-early** design *easier* (real-time, no waiting for a UMRN) but does not
remove the need for it.

`11-` Q4 stands as a blocker, with its question sharpened: not "is UPI better" but **"is UPI entitled
on our account"** — because if it is, registration becomes a same-session step in the borrower
journey instead of a multi-day background process, and `08-` §2's placement of the mandate step
changes accordingly.

## 7. UPI mandate creation — **executed, and it failed. UPI is not provisioned.**

`POST https://api.digio.in/v3/client/mandate/create_form` with `auth_mode: "upi"`:

```json
HTTP 500
{ "details": "EX261016211743769WZU",
  "code": "SYSTEM_ERROR",
  "message": "Unable to create UPI Mandate: Invalid Corporate Config Id" }
```

**No mandate was created.** The request was rejected at config validation.

### The config id is correct — this is an entitlement failure, not a typo

Read back from the console **DOM** (not the earlier screenshot, to rule out OCR error):

```
Config Profile Name                     NAVIX FINANCE YBL SPONSOR
Corporate Profile Id (auto-generated)   TSE261016195601220U17VMUFQVFG2GW
Utility Code                            NACH00000000067052
IFSC / Bank Short Code                  YESB0000001 / YESB
```

Byte-identical to what was sent. And the console contains **no mention of UPI at all** — one sponsor
profile, NACH/Yes Bank only.

**Conclusion (VERIFIED by execution): `11-` Q4 is answered — UPI AutoPay is NOT provisioned for this
account.** `TSE2610…` is a NACH sponsor profile; UPI requires its own corporate config, which we do
not have. The console's missing sidebar entry was a true signal after all.

**What this settles:**

- `08-`'s NACH-based design stands. §6's "is UPI better" question is moot until Digio provisions it.
- The multi-day registration latency of `02-` §5 is **real and unavoidable** on our current
  entitlement. The per-customer, register-early design (`11-` Q2) is therefore not merely preferable
  — it is the **only** shape that works, because a mandate created at sanction cannot be registered
  in time to collect on a ≤40-day advance.
- If UPI is wanted (real-time registration, borrower cannot self-revoke a lending mandate), it is a
  **commercial request to Digio**, not an engineering task.

### A new, important API behaviour: business errors come back as HTTP 500

The published specs document **only `200` and `400`**. This is a plain business-rule rejection — an
unentitled product — and Digio returned **`500` with `code: SYSTEM_ERROR`**.

**This breaks the obvious retry rule.** A client that treats `5xx` as "transient, retry with backoff"
(`06-` §1) would retry this forever and never surface the real cause. Correct handling:

- **Never classify retry-safety by HTTP status alone.** Branch on the `code`/`message` body, which is
  present even on a 500.
- **Always log `details`** — `EX261016211743769WZU` is the exception id Digio support needs. It is the
  only handle on this failure.
- Treat `SYSTEM_ERROR` as **unknown-outcome** for mutating calls, not as transient: reconcile by
  reference (`06-` §3) before any retry.

`06-` §2's "UNKNOWN: which HTTP statuses besides 200/400 occur" is now partly answered: **500 occurs,
and carries a structured, actionable body.**

## 8. Still blocked: the NACH test needs a bank account number

With UPI ruled out, the only rail available is NACH — and `A1` requires
`customer_account_number` (8–18, all numeric) and `destination_bank_id` (IFSC). A UPI VPA
(a `…@<psp>` handle) yields neither; it is a payment address, not an account.

SBI supports API mandate via `net_banking`, `aadhaar` and `debit` (§3), so the test is viable as soon
as those two values exist. For a real borrower Navix already holds them from the **penny drop**
performed during onboarding — so this gap is specific to the manual test, not to the design.

## 9. NACH API mandate — **created successfully in production**

`POST https://api.digio.in/v3/client/mandate/create_form`, `auth_mode: "API"`, PNB savings account,
`management_category: "L001"` (loan instalment), `maximum_amount: 1000`, `frequency: "Adhoc"`,
`is_recurring: true` — i.e. the **per-customer, MAXIMUM-type** shape chosen in `11-` Q2.

```jsonc
HTTP 200
{
  "id":         "ENA261016212344139HRPQY3KP1ASXAP",
  "mandate_id": "ENA261016212344139HRPQY3KP1ASXAP",
  "state":      "partial",
  "type":       "CREATE",
  "mode":       "api",
  "bank_details": { "bank_name": "Yes Bank Ltd", "state": "partial",
                    "shared_with_bank": "dltbgcmse-collproduct@yesbank.in" },
  "service_provider_details": { "service_provider_name": "NAVIX FINANCE PRIVATE LIMITED",
                                "service_provider_utility_code": "NACH00000000067052" },
  "authentication_url": "https://app.digio.in/eDIGIO/DGO…"
}
```

**This is a real, live mandate request** awaiting borrower authorisation. `GET /v3/client/mandate/{id}`
read it back correctly, confirming the create→read round trip end to end.

**The whole `02-` §3 model is confirmed by execution:** creation yields `state: partial` and **no
UMRN**; `01-` §2's `corporate_config_id`, utility code and Yes Bank sponsor are all echoed back by the
API exactly as the console showed them.

### Five more places the live API differs from the published spec

**1. `collection_amount` is NOT required — `11-` Q7 is answered.**
The spec marks both `collection_amount` and `maximum_amount` required. The call succeeded with
**`maximum_amount` only**. A pure MAXIMUM-type mandate is therefore legal, which is exactly what
`02-` §5 and `06-` §5 argued Navix needs for a penalty-varying amount. **Q7: CLOSED.**

**2. `frequency` is normalised between write and read.**
Sent `"Adhoc"` (the NACH create vocabulary); read back as **`"ADHO"`** (the NPCI 4-letter form).
**The request and response vocabularies genuinely differ for the same field.** A DTO that reuses one
enum for both directions will fail to deserialise. `09-` §4's "one mapper only" rule now has a
concrete reason behind it.

**3. `customer_account_type` is case-normalised** — sent `"SAVINGS"`, returned `"savings"`.

**4. ⚠ The account number is returned IN FULL, not masked.**
The spec documents `customer_account_number` as *"Masked account number of customer"*. It came back
as the complete 16-digit number. **This is a security finding, not a curiosity:** `07-` §2 assumed
Digio's masking limited our exposure. It does not. Every Digio mandate response is full-PII bank data,
so **our own redaction before logging or persisting is the only control** — in particular, anything
routed through `ProviderApiExecutionWriter` must be redacted, or we build a searchable table of
customers' bank account numbers. `08-` §5's "store `account_last4` only, let Digio hold the rest" is
now a requirement rather than a preference.

**5. Undocumented response fields.** `service_provider_details` carried
`is_enabled_for_aadhaar_auth: true` (documented) **and `is_enabled_for_other_auth_mode: true` (not in
any spec)**. Confirms Digio adds fields without notice — `@JsonIgnoreProperties(ignoreUnknown = true)`
is mandatory, exactly as Digio's own webhook FAQ advises.

### Bank capability matters per borrower

**Punjab National Bank** (from the live `A6` list):

```json
{ "name": "Punjab National Bank", "ifsc_prefix": "PUNB", "routing_code": "PUNB",
  "api_mandate": true, "esign_mandate": true, "physical_mandate": true,
  "allowed_auth_sub_type": ["aadhaar", "debit"], "penny_less": true }
```

**PNB offers only `aadhaar` and `debit` — no net banking**, where SBI offers all three (§3). So the
authorisation options a borrower sees depend on their bank, and `A6` must be consulted **per
borrower** before rendering the journey. Hardcoding "net banking or debit card" would silently exclude
every PNB customer. `03-` A6 recommended calling it; this is the concrete reason.

### Running total: the specs are reliable for *paths and required-ness direction*, not for shape

Five contradictions found in two executed endpoints — casing (§2), required-ness (here), enum
vocabulary (here), masking (here), undocumented fields (here). **Validate every DTO against a live or
sandbox response before trusting it.** This is now the strongest argument in the knowledge base for
resolving `11-` Q3 (sandbox access).

## 10. ⚠ CORRECTION — the mandate registered in **7 minutes**, not days

The borrower authorised at **21:30:58**, 7 minutes after creation at 21:23:44, using **Aadhaar**.
Read back immediately after:

```jsonc
{
  "state": "auth_success",
  "umrn":  "PUNB70316…0169",                 // ← already present
  "auth_sub_mode": "aadhaar",
  "mandate_details": { "npci_txn_id": "1229…7b64",
                       "authentication_time": "2026-10-16 21:30:58" },
  "bank_details": {
    "state": "ack_received",
    "authenticated_at": "2026-10-16 21:30:58",
    "ack_report": [{ "umrn": "PUNB70316…0169", "accepted": true,
                     "file_name": "NPCI_ONMAGS",
                     "generated_at": "2026-10-16 21:30:58" }]
  }
}
```

### What this corrects

Earlier sections state that `auth_success` **never** carries a UMRN and that registration takes
**days** through a bank file cycle. **That is wrong for API mandates.** It holds only for the
**file-based** modes — `ESIGN` and `PHYSICAL` — where Digio builds a file, hands it to the sponsor
bank, and waits on NPCI and the destination bank.

An **`auth_mode: API`** mandate goes through NPCI ONMAGS **in real time**: the borrower
authenticates, NPCI returns an acknowledgement with `accepted: true`, and **the UMRN is issued within
the same session**. The `ack_report.file_name` is literally `NPCI_ONMAGS`.

| Where | Said | Actually |
|---|---|---|
| `02-` §3 | "`auth_success` is not `registered`… not usable" | True for ESIGN/PHYSICAL. **False for API** — UMRN arrives with authentication |
| `02-` §5 | "Registration completes only when the destination bank accepts — days later" | **API: minutes.** File-based: days |
| `08-` §2 | Per-customer chosen partly because a mandate created when needed "is worthless" | The latency argument **largely falls away for API mode** |
| `13-` §7 | "multi-day registration latency is real and unavoidable" | **Withdrawn** — avoidable via `auth_mode: API` |

### What it does *not* change

- **The per-customer decision (`11-` Q2) stands**, but on better grounds: not "NACH is too slow to
  register at sanction" but "**reborrow is explicitly designed to re-ask the borrower nothing**", so a
  per-loan mandate reintroduces friction exactly where the product removes it. That was always the
  stronger argument.
- **`bank_details.state` is `ack_received`, not `register_success`**, and `res_report` is still empty.
  The ACK carries the UMRN with `accepted: true`. Whether a later `res_report` / `register_success`
  follows is **UNKNOWN** — re-read this mandate over the coming days. `09-` §4's rule, **"UMRN beats
  enums"**, is what makes it safe to act on in the meantime.
- **Collection latency is untouched.** `02-` §4's T-1 cutoff and next-working-day settlement still
  apply. **Fast to register, slow to collect.**
- **One bank, one sub-mode.** PNB + Aadhaar produced a real-time UMRN. Whether every
  `api_mandate: true` bank and every `allowed_auth_sub_type` behaves this way is **UNKNOWN**. Do not
  generalise from a single observation.

### Consequence for the design

**`auth_mode: API` should be the default**, and the borrower journey can treat registration as a
**same-session step** — much closer to the UPI experience §4 was chasing than this knowledge base
assumed. `ESIGN` and `PHYSICAL` remain genuinely asynchronous, so `09-`'s intermediate states stay
necessary: they are the file-based path, not dead code.

**Lesson, recorded deliberately:** the multi-day claim was inferred from state-enum names and a prose
flow diagram, and it was wrong. One live authorisation overturned it. **No timing claim in
`02-`/`08-`/`09-` should be trusted until it has an entry in this log.**

## 11. UPI re-checked after provisioning was reported — still not provisioned

Retried `auth_mode: "upi"`: identical **`HTTP 500 SYSTEM_ERROR — "Invalid Corporate Config Id"`**
(`EX261016213348493IGI`). The console still lists exactly one profile, `NAVIX FINANCE YBL SPONSOR`,
with no UPI entry.

**UPI requires its own corporate config id — it is not a flag on the NACH profile.** When Digio
provisions it, expect a **second Sponsor Bank Profile** at `#/mandates/book/bank-profiles` with its
own auto-generated `Corporate Profile Id`; that id, not `TSE2610…`, is what `auth_mode: "upi"` needs.
`11-` Q4 stays **CLOSED — not provisioned**, pending that.


## 11. First live registration through the platform — rejected, and why (2026-10-18)

With `provider=digio` and the `digio-mandate` flag on, the first real borrower registration through
the offer journey failed:

```
WARN DigioMandateClient - Digio create_form failed: http=400 code=REQUEST_VALIDATION_FAILED
                          details=EX261018203058579ESC
WARN MandateService      - Mandate register failed crn=7682036-1
POST /api/applications/11792/offer/mandate/init -> 422
```

**Cause: our `customer_ref_number` contained a hyphen.** Digio's own schema for `create_form`
(`api-reference/schemas/final_post_createMandateForm.json`) constrains it to
`^[0-9A-Za-z]{0,}$`, max 20 — *"ALPHA and NUMERIC allowed"*. `MandateService` was building
`<customerId>-<attemptNo>`, and its own validation pattern `^[0-9A-Za-z-]{1,20}$` **explicitly
permitted the character the provider rejects**. The mandate that succeeded during discovery (§9) used
`DB<yymmddHHMMSS>` — no separator — which is why this never surfaced before.

Fixed: the reference is now `C<customerId>A<attemptNo>` and the validation pattern is byte-identical
to Digio's. The lesson is general: **a validation of ours that is wider than the provider's is not a
validation** — it only moves the rejection somewhere the reason is invisible.

Two things this incident also settled:

1. **`provider=digio` is confirmed live.** The mock adapter makes no network call at all; this was a
   real HTTP round trip to `api.digio.in`. The parameter-store route (§3 of `14-`) works.
2. **The debit path does NOT share the bug.** `unique_key` on `createScheduledPlan` is
   `^[A-Za-z0-9-_]*$`, max 30, so hyphens are legal there and `DBM<loanId>-<attempt>` is valid.

**Still invisible:** which field Digio objected to. `classify()` logs op/status/code/`details` and
deliberately omits the provider `message`, because that message echoes account numbers (§9 finding 4).
That tradeoff held up — the code plus the schema was enough to find this in one pass — but it is worth
knowing that a field-level reason will never be in our logs.


## 12. NACH debit presented in production — the whole path proven except settlement (2026-10-18)

First real debit, through the platform: `POST /api/collections/loans/219/debit` with
`amountPaise=100`, actor ADMIN acting as COLLECTION_HEAD, against UMRN
`PUNB7031810261019193` (itself the first mandate registered through the offer journey, after the
`customer_ref_number` fix in §11).

```
HTTP 200 in 198ms
mandate_debit: status=SCHEDULED  amount_paise=100  unique_key=DBM219-1
               digio_plan_id=NSP26101821213603438FTSIW6DIAGAP
               digio_txn_id=NPT261018212136038K9ADQ7BAXADX54
               digio_status=completed  settlement_date=2026-10-19
```

### What this settles

**1. `txnId` IS returned on create.** `DebitService` tolerates a null `txnId` defensively; it does not
need to. The create response carries both a plan id (`NSP…`) and a transaction id (`NPT…`).

**2. `digio_status: "completed"` on create is the PLAN's status, not the debit's.** The plan lifecycle
enum is `['ACTIVE','CANCELLED','PAUSED','COMPLETED']` — a one-payment plan is COMPLETED as soon as its
single transaction is scheduled. The *transaction* status is a different enum entirely. Reading
`completed` as success would credit a payment that has not happened; `applyOutcome` does not recognise
it, logs "Unrecognised debit status" and quarantines, which is the correct outcome by accident of
failing closed. **Do not add `COMPLETED` to that switch.**

**3. The transaction-status response shape is confirmed**, against the live transaction:

```jsonc
GET /v3/client/nach_debit/transaction/{txnId}  → 200
{ "id": "NPT…", "umrn": "…", "state": "scheduled", "customer": {…},
  "details": { "status": "new", "amount_in_paise": 100, "amount": 1,
               "ready_to_process_at": "2026-10-19 00:00:00",
               "txn_ref": "K9ADQ7BAXADX54", "type": "nach_debit",
               "sponsor_bank_id": "YESB0000001" } }
```

`DigioMandateClient.debitStatus` reads `details.status` / `details.utr` / `details.failure_code` /
`details.failure_reason` / `details.amount_in_paise` — all correct. `utr`, `failure_code` and
`failure_reason` are simply absent until the transaction resolves.

**4. ⚠ Transaction statuses come back LOWERCASE.** Live: `"new"`. Every schema lists the enum
uppercase (`NEW`, `PAYMENT_SUCCESS`, …). `applyOutcome` upper-cases before matching, so it works — but
this is the same write-vs-read vocabulary drift as `frequency` (§9 finding 2), and it is the reason
`TERMINAL_MARKERS` normalises case and punctuation before matching a `failure_code`. A mapper that
compared raw strings against the documented enum would match **nothing**.

**5. T+1 is the floor, and nothing can force it earlier.** `ready_to_process_at` is midnight of the
settlement date, and the transaction action API offers only
`['cancel', 'reattempt', 'update', 'approve']` — there is no "process now". `settle = today.plusDays(1)`
in `DebitService` is the earliest the rail permits, not a conservative choice.

**6. `unique_key` with a hyphen is accepted** (`DBM219-1`), as `createScheduledPlan`'s
`^[A-Za-z0-9-_]*$` predicted. The §11 mandate bug does not extend to the debit path.

### Still unproven after this

- **The `failure_code` vocabulary.** No debit has failed yet, so `TERMINAL_MARKERS` remains our reading
  of the NPCI return-reason list rather than observed values.
- **The success path end to end**: `PAYMENT_SUCCESS`, the UTR, and `creditCollectionPayment` writing a
  VERIFIED payment. Settlement is 2026-10-19.
- **Whether a repeated `unique_key` is deduped by Digio** — the double-charge backstop on the
  `FAILED_TECHNICAL` recovery path.

### Operational notes this run surfaced

- **Reconcile latency is up to ~24h.** The sweep runs once daily at 10:00 IST, so a payment that
  finalises at 14:00 is not known to us until 10:00 the next day; the row shows a stale
  `SCHEDULED`/`IN_PROGRESS` until then. This is the deferred webhook receiver's cost, and it is
  cosmetic only — `creditSuccess` sets `paidOn` to the **settlement date**, not the reconcile date
  (`paidOn = settlementDate unless it is in the future`), so our latency never costs a borrower a
  day of penalty.
- **NPCI holidays reschedule automatically** (`06-` §5, `02-` §4): a settlement date falling on a
  non-working day moves to the next working day and surfaces as `PAYMENT_DELAYED` → `IN_PROGRESS`,
  which `shouldPresent` treats as in-flight, so no duplicate presentation is made. `DebitService`
  therefore needs no holiday calendar of its own.
- **A part payment does not stop the penalty.** The late penalty is 2% **of principal** per day
  (capped at 30 days), not of the outstanding balance, so a ₹1 debit on a ₹1,000 advance reduces the
  total owed by ₹1 while the loan continues accruing ₹20/day. A test debit leaves the loan overdue
  and growing; restore its due date or settle it afterwards.


## 13. `auth_failed` is NOT terminal — the duplicate-mandate incident (2026-10-20)

A real borrower (customer 6277416, application 11793, Kotak a/c ...4232) filed **seven** NACH
mandates in 80 minutes and ended up on a Digio page with every verification mode greyed out:
*"Duplicate request, retry next day."* The bank had started rejecting our registrations as
duplicates of each other.

**He had already succeeded on attempt 1.**

| attempt | mandate id | our DB says | Digio says now |
|---|---|---|---|
| 1 | `ENA261020092038983N6E1US365YYSAP` | `AUTHORIZATION_FAILED` | **`auth_success` / `ack_received`, UMRN `KKBK7022010261001406`**, `debit` mode, updated **09:27:13** |
| 4 | `ENA261020093443547ECQ4ES5ZOHBBAP` | `AUTHORIZATION_FAILED` | `auth_failed` (net_banking) — genuinely failed |
| 2,3,5,6,7 | — | `PENDING_AUTHORIZATION` | `partial` — never authorised |

Same mandate id in both columns for attempt 1. Our row is simply **stale**.

### The defect, in two parts

1. **`mapNoUmrn` treats Digio's `auth_failed` as terminal** (`AUTHORIZATION_FAILED`). It is not. It
   means *this authentication attempt* failed — the borrower may retry on the **same** Digio page and
   succeed, which is exactly what happened at 09:27:13, about seven minutes after we had written the
   attempt off.
2. **`status()` never re-reads a non-pending row:**
   ```java
   if (m.getStatus() != Mandate.Status.PENDING_AUTHORIZATION) {
       return view(m, null);   // never looks again
   }
   ```
   So the later success is invisible for ever.

Together they produce the loop: borrower succeeds → we show "we could not complete the mandate setup,
please try again" → he presses → `init` mints a **new NPCI mandate** (a fresh CRN and a fresh
authorisation url; nothing is ever reused) → the destination bank sees same account, same amount, same
day and blocks every verification mode → he presses again. Seven filings, one of them live.

21 seconds separate attempt 1's success (09:27:13) from attempt 2's creation (09:27:34).

### Contributing trigger

`reopen()` on the mandate page falls through to `start()` when `bankUrl` is absent, and `bankUrl` is
React state — a page reload loses it. So "Reopen your bank's page" files a brand-new mandate rather
than reopening anything.

### What this says about the state mapping

`09-state-mapping.md` lists `auth_failed` as terminal. **That is wrong for `auth_mode: API`** and is
now contradicted by execution. Only a UMRN (or `revoked`/`cancelled`/`expired`) is genuinely terminal;
`auth_failed` is a retryable intermediate state at the provider.

### Unknowns left open

- The destination bank's duplicate key (account+amount+day? a per-day authentication cap?) is
  **unobserved** — "retry next day" is all we were told.
- Whether a fresh filing tomorrow is flagged against today's six stale `partial` mandates is unknown.
- Cancelling those stale mandates is a write against real NPCI records and was not attempted.

## 14. First HDFC mandate — `AP27 Invalid mandate frequency`, rejected by BANK (2026-10-30)

Same shape that registered at PNB (`PUNB7031610261030169`) and Kotak (`KKBK7022010261001406`):
`management_category: "L001"`, `frequency: "Adhoc"`, `is_recurring: true`, MAXIMUM-type. HDFC's
destination-bank check refused it — NPCI page: UMRN `HDFC7033010261023984`, reason code **`AP27`**,
"Invalid mandate frequency", *Rejected By: BANK*. Nothing about the borrower; retrying re-files the
identical request and fails identically.

Digio's spec already warned: L001 "should have a defined frequency that is commensurate with the EMI
schedule… eg: Monthly"; a wrong one "may lead to mandate registration failure".

**Fix:** `DigioMandateClient.registrationFrequency` files **`Monthly` for HDFC IFSCs only**; every other
bank keeps the proven `Adhoc`. **Open before `digio-debit` goes on:** the debit call still sends
`frequency: "adhoc"`, and our DPD 1/3/5/9 re-presentations all fall in one month — ask Digio whether a
Monthly HDFC mandate accepts that, or refuses with a frequency mismatch. The HDFC borrower already
rejected is unblocked by the `mandate:override` on that application's newest attempt.
