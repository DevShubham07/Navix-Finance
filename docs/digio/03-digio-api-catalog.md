# 03 — Digio API catalog

**19 operations**, every one taken from Digio's own OpenAPI 3.0.1 specs, mirrored in
[`api-reference/schemas/`](api-reference/schemas/). Unless a row says otherwise:

- **Evidence status: VERIFIED** (published spec + documentation page).
- **Auth:** HTTP Basic, scheme `api_auth` (`components.securitySchemes`), same for every endpoint.
- **Base URL:** `https://api.digio.in` (prod) / `https://ext.digio.in:444` (sandbox) — `04-`.
- **Error shape:** `400` → `{ code, message, details }`, some endpoints add `errors`.
- **Idempotency: UNKNOWN.** No endpoint documents an idempotency key or header, and none was found in
  any spec. Treat every `POST` as **not safe to blind-retry** — see §4.
- **Rate limits: UNKNOWN.** Not documented anywhere we found. `11-` Q8.
- **Pagination: not applicable.** No list endpoint exists; everything is fetched by a known identifier.

## A. Registration — `/v3/client/mandate/*`

### A1 · Create Mandate Request — **the entry point**
`POST /v3/client/mandate/create_form` · spec `final_post_createMandateForm.json`

Top-level: `corporate_config_id`\*, `mandate_type`\* (`CREATE`), `mandate_data`\*, `expire_in_days`
(1–90), `auth_mode` (`API|ESIGN|PHYSICAL`), `sub_user_identifier`, `customer_identifier`,
`notify_customer` (`True|False`), `generate_access_token`, `include_authentication_url`.

`mandate_data` — \* = required:

| Field | Type | Constraints / notes |
|---|---|---|
| `management_category`\* | enum | `C001 B001 D001 E001 I001 I002 L099 L002 L001 M001 U099 F001 T001 U001 U003 U005 U006 S001`. **Must match the category enabled for our utility code at NPCI.** `L001` = loan instalment. *(The spec lists `F001` twice and carries `T001`; the console additionally offers `T002`, `O001` — see `11-` Q5)* |
| `is_recurring`\* | boolean | `false` = one-time debit |
| `frequency`\* | enum | `Adhoc IntraDay Daily Weekly Monthly BiMonthly Quarterly Semiannually Yearly`. Must be commensurate with the category |
| `first_collection_date`\* | date | `yyyy-mm-dd`. "the date from which you want the mandate to be registered" |
| `final_collection_date` | date | Max **40 years** out. Defaults to 40 years if omitted |
| `collection_amount`\* | number | 1 – 10,000,000. Fixed-amount (EMI) mandates. **Rupees, decimal** |
| `maximum_amount`\* | number | 1 – 10,000,000. Per-instruction ceiling. **Rupees, decimal** |
| `customer_name`\* | string | ≤ 40, `^[0-9A-Za-z]{1,}[0-9A-Za-z\s.]{0,}$` — **no apostrophes or hyphens**. Should match destination-bank records |
| `customer_account_number`\* | string | 8–18, **all numeric** |
| `customer_account_type`\* | enum | `SAVINGS CURRENT OTHER`. **CC / NRI / joint not supported** |
| `destination_bank_id` | string | IFSC (≤11 alnum) or MICR (≤9 numeric) |
| `destination_bank_name` | string | ≤ 140 |
| `customer_ref_number` | string | ≤ 20 alnum. **"Please pass this attribute unique"** — our reconciliation key; `A3` looks mandates up by it |
| `scheme_ref_number` | string | ≤ 20 alnum, second reconciliation reference |
| `customer_mobile` / `customer_email` | string | ≤ 34 / ≤ 50 |
| `mandate_creation_date_time` | date-time | Defaults to system date |

Both are marked required, but the docs call them alternatives — **resolved by a live call: only
`maximum_amount` is needed.** A pure MAXIMUM-type mandate created successfully with
`collection_amount` omitted ([`13-`](13-live-verification-log.md) §9). `11-` Q7 CLOSED.

**200** → `id`, `mandate_id` (**`ENA…`**), `state`, `type`, `mode`, `created_at`,
`bank_details{shared_with_bank, bank_name, state}`, `service_provider_details{…}`, `sub_user{…}`,
`authentication_url` (only if requested), `access_token{id, entity_id, valid_till, …}` (only if
requested; used to bypass first-factor auth in the SDKs).

**Side effect: files a real mandate.** Not retry-safe — a repeat creates a second mandate.

### A2 · Amend Mandate Request
`POST /v3/client/mandate/create_form` (same path, `mandate_type: AMEND`) · `final_post_amendMandateForm.json`

`mandate_data` requires `original_mandate_id` (the **UMRN**, exactly 20 chars), `amendment_reason`
(`A001` customer request / `M036` represent with CBS account number), plus `frequency`,
`first_collection_date`, `collection_amount`, `maximum_amount`. Same response shape as A1.

### A3 · Get Mandate Details — **the reconciliation workhorse**
`GET /v3/client/mandate/{mandate_id}` · `final_get_getMandateInfo.json`

Returns the full mandate: `state`, `type`, `umrn`, `mode`, `auth_sub_mode`, `created_at`,
`updated_at`, `npci_auth_failed_error`, `npci_auth_reject_reason`,
`mandate_details{…}` (masked `customer_account_number`, dates, amounts, `npci_txn_id`,
`sponsor_bank_name`, `auth_type`, `authentication_time`, `frequency` in **NPCI 4-letter form** —
`ADHO INDA DAIL WEEK MNTH QURT MIAN YEAR ONETIME BIMN`), and
`bank_details{state, shared_at, authenticated_at, ack_report[], res_report[]}` where each report entry
carries `umrn`, `accepted`, `reject_code`, `reject_reason`, `message_id`, `file_name`, `generated_at`.

Read-only, retry-safe. **This is the source of truth** — never trust a webhook body over it.

### A4 · Get Mandate Details by CRN
`GET /v3/client/mandate/by_crn/{crn}` · `get_getMandateInfoByCrn.json` — same object as A3 but returns
an **array** (one CRN may carry several mandates). Read-only. The closest thing to a list API: it works
only if `customer_ref_number` was set at creation.

### A5 · Cancel Registered Mandate
`POST /v3/client/mandate/registration_cancel` · `final_post_registrationCancelForm.json`

Body: `digio_mandate_id` (the `ENA…` id), `umrn`, `dest_ifsc`, `reason` ∈
`C001` customer request · `C002` corporate request · `C003` account closed/frozen/inoperative ·
`C004` cancelled from bank side. **The spec marks none of them required** — supply all four anyway.
Response is the full mandate object. **UNKNOWN:** which states permit cancellation; the spec states no
precondition. `11-` Q9.

### A6 · Live Banks
`GET /v3/client/mandate/banks?mode=API|ESIGN|PHYSICAL&penny_less=true|false` · `get_getBanksList.json`

Array of banks with `active`, `ifscPrefix`, `bankShortCode`, `allowedAuthSubType[]`, `esignMandate`,
`apiMandate`, `apiMandateNetBanking`, `apiMandateDebitCard`, `apiMandateAadhaarCard`,
`apiMandateDDNetBanking`, `apiMandateDDDebitCard`, `apiMandateDDOtp`. Read-only, cacheable.

**Call this before offering a borrower a method.** It is the only honest way to know whether their
bank can do API mandate at all — and per-bank, which sub-mode. Hardcoding `01-` §4's 39-bank snapshot
would rot.

### A7 · Share Mandate Files
`POST /v3/client/mandate/share` — `{mandate_id, partner_entity_email}` → `{success}`. Re-sends the
mandate file to a bank. Operational tool, not part of the happy path.

## B. Physical NACH only

| ID | Operation | Endpoint | Notes |
|---|---|---|---|
| B1 | Download prefilled NACH PDF | `POST /v3/client/mandate/{id}/generate_file` | `id` is the `ENA…` id. Returns **binary** `application/octet-stream` |
| B2 | Submit scanned image | `POST /v3/client/mandate/submit_signed_mandate` | `multipart/form-data`: `file` (binary) + `request` (`{id}`, content-type `text/plain`) |
| B3 | Submit scanned image (raw) | `POST /v3/client/mandate/submit_signed_mandate_raw` | Raw-body variant |
| B4 | Download mandate files | `GET /v3/client/mandate/download?mandate_id=` | XML + processed images |

Only relevant if Navix ever supports a wet-signature fallback. Out of scope for v1.

## C. Presentation (debit) — `/v3/client/nach_debit/*`

### C1 · Create debit for a single UMRN — **how money is actually collected**
`POST /v3/client/nach_debit/scheduled/register` · `post_createScheduledPlan.json`

| Field | Notes |
|---|---|
| `umrn`\* | From a **registered** mandate. Without it there is no debit |
| `corporate_account_number`\* | Where collected money settles. **One of** this / `settlement_config_profile_id` / `split_settlement_info` is required (the latter two are Digio-PA-only) |
| `frequency`\* | `onetime adhoc intraday daily weekly monthly quarterly semiannually yearly bimonthly` — **lowercase here**, unlike A1 |
| `amount` **or** `amount_in_paise` | One is mandatory. `amount_in_paise` is **`int32`** → ceiling ≈ ₹2.14 crore. **Use `amount_in_paise`** — it is the only field in the whole surface that matches Navix's integer-paise money model with no float conversion |
| `settlement_date` | `yyyy-mm-dd`. Defaults to the mandate's `first_collection_date`, else today |
| `payment_count` | Number of consecutive debits. **Must be 1 when `frequency` is `onetime`** |
| `unique_key` | ≤ 30, `^[A-Za-z0-9-_]*$`. "should be unique for each debit request". **The nearest thing to an idempotency key** — and `C5` reads a plan back by it |
| `narration` | ≤ 20, `^[0-9A-Za-z\& .]{0,}$` — **what the borrower sees on their bank statement**. Defaults to the corporate name |
| `destination_*`, `customer_*` | Only when the mandate was not created at Digio |
| `optional_attr` | **Mutual-fund/ICCL only. Do not send it** — the spec is explicit: "For Non MF use cases, please DO NOT pass optional_attr" |

**200** → `id` (the **`NSP…`** plan id), `status`, `upcoming_transaction{state, present_at,
scheduled_settlement_date}`, `customer{…}`, `corporate{utility_code, sponsor_ifsc, …}`, `partner{…}`,
`details{next_scheduled_settlement_date, payment_count, paid_count, amount_in_paise, mandate_id,
client_ref_id, frequency, narration, …}`, `sponsor_bank_name`, `created_at`.

**Side effect: schedules a real debit against a customer's bank account.** Not retry-safe.

### C2 · Create debits for multiple UMRNs
`POST /v3/client/nach_debit/scheduled/register_multiple` — `{requests: [...]}`, **max 200 per request**,
each entry identical to C1. The right call for a daily due-date batch.

### C3 · Debit plan action
`POST /v3/client/nach_debit/scheduled/action` — `{scheduled_plan_id*, action*}` where action ∈
`cancel | pause | resume`. Returns the plan with `last_transaction{…}` and the embedded `mandate{…}`.

### C4 · Transaction action
`POST /v3/client/nach_debit/transaction/action` — `{transaction_id*, action*}` where action ∈
`cancel | reattempt | update | approve`. The spec constrains the first two: **`cancel` only before
initiation, `reattempt` only after a failure.** `update` and `approve` are undocumented — `11-` Q10.
`transaction_id` is the **`NPT…`** id.

### C5 · Read-backs

| ID | Endpoint | Returns |
|---|---|---|
| C5a | `GET /v3/client/nach_debit/scheduled/{planId}` | Plan by `NSP…` id |
| C5b | `GET /v3/client/nach_debit/scheduled/by_reference_id/{refId}` | Plan by the `unique_key` we chose — **the recovery path when a create times out** |
| C5c | `GET /v3/client/nach_debit/transaction/{txnId}` | Transaction by `NPT…` id, incl. `details{status, amount_in_paise, failure_code, failure_reason, utr, txn_ref, settlement_desc}` |

All read-only and retry-safe.

### C6 · Consolidated settlement
`GET /v3/client/nach_debit/requester_settlement/{id}` — **Digio PA users only.** The `CID` arrives by
email. Returns `final_amount`, `fee1`, `fee2`, `settlement_utr_number`, `bank_txn_ref`,
`debit_txn_details[]`. **UNKNOWN** whether our account is a PA user — `11-` Q11.

## 4. Retry safety — read before writing a client

| Operation | Retry safe? | Why |
|---|---|---|
| A3, A4, A6, C5a/b/c, C6 | **Yes** | Pure reads |
| A1 create, A2 amend | **No** | Each call files a new mandate. On timeout, **recover via A4 by `customer_ref_number`** — do not re-POST |
| C1, C2 create debit | **No** | Each call schedules a real debit. On timeout, **recover via C5b by `unique_key`** — do not re-POST |
| A5 cancel, C3, C4 | **Conditional** | Likely idempotent in effect (cancelling a cancelled thing), but **UNKNOWN** and unconfirmed. Treat as unsafe |

Digio provides **no idempotency key**. `customer_ref_number` and `unique_key` are the only handles that
make a timeout recoverable, which makes setting them non-optional in our client — an omitted
`unique_key` turns a network blip into an unresolvable "did we debit them twice?".
