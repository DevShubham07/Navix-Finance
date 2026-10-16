# Glossary

Terms as Digio, NPCI and this knowledge base use them. Where Digio's wording differs from ours, both
are given — the API's spelling always wins in code.

| Term | Meaning |
|---|---|
| **NACH** | National Automated Clearing House — NPCI's bulk, file-based debit/credit system |
| **eNACH / e-Mandate** | Electronic mandate registration, no paper. Authorised by net banking, debit card, Aadhaar or eSign |
| **ONMAGS** | NPCI's Online Mandate Gateway Service — the gateway an API mandate redirects through. Source of the `AP…` and `151`–`197` codes |
| **UMRN** | Unique Mandate Reference Number — 20 chars, issued by the destination bank **on successful registration**. **Without it no debit can be presented.** The ground truth that a mandate is live |
| **`ENA…` id** | Digio's own mandate id (`mandate_id` / `id`), returned at creation, before any UMRN exists |
| **`NSP…` id** | Digio's debit **plan** id (`scheduled_plan_id`) |
| **`NPT…` id** | Digio's individual **transaction** id (`transaction_id`) |
| **CRN** | Customer Reference Number — our `customer_ref_number`. Must be unique; the only way to look a mandate up by *our* key (`A4`) |
| **`unique_key`** | Our per-debit reference (≤30 chars). The nearest thing to an idempotency key; `C5b` reads a plan back by it |
| **Utility Code** | The corporate's NPCI identifier. Ours: `NACH00000000067052` |
| **Sponsor bank** | The bank that submits our files to NPCI. Ours: **Yes Bank** (`YESB0000001`) |
| **Destination bank** | The borrower's bank — registers the mandate and performs the debit |
| **Corporate config / `corporate_config_id`** | Digio profile bundling utility code, service provider and sponsor bank. Required on every create/amend |
| **Management category** | NPCI's use-case code. `L001` = loan instalment. **Must match what NPCI enabled for our utility code** |
| **Presentation** | Asking for money against a registered UMRN. Digio's word for a debit request |
| **Debit plan / scheduled plan** | A presentation, possibly repeating (`payment_count`) |
| **Settlement** | Collected money reaching the corporate account. Separate from the debit succeeding |
| **Consolidated settlement / CID** | Batched settlement, emailed. **Digio PA users only** |
| **PA user** | Payment Aggregator customer of Digio — unlocks `split_settlement_info`, `settlement_config_profile_id`, `C6`. **Unknown whether we are one** |
| **RPN / "Allow RPN"** | Re-presentation — whether a failed debit may be retried. Flagged per NPCI reject reason; "balance insufficient" is the notable **Yes** |
| **Return vs Reject** | NPCI's split: a *Return* was attempted and bounced (account/balance); a *Reject* was never valid (bad UMRN, amount over the ceiling, date outside the window) |
| **`auth_mode`** | How the customer authorises: `API` (net banking/debit card/Aadhaar) · `ESIGN` (Aadhaar eSign) · `PHYSICAL` (wet signature) |
| **`auth_sub_mode`** | Narrower: `net_banking｜debit｜aadhaar` (and `otp｜other｜pan｜customer_id｜direct_bank` on read-back) |
| **FIXED vs MAXIMUM mandate** | `collection_amount` is the fixed EMI; `maximum_amount` is the per-instruction ceiling. A varying amount needs **MAXIMUM** |
| **`access_token`** | Short-lived, **customer-facing** token letting an SDK skip first-factor auth. **Not** API authentication |
| **`authentication_url`** | The link the borrower opens to authorise. Carries the token — borrower session only |
| **`X-Digio-Checksum`** | Webhook authenticity header: HMAC-SHA256 hex of the raw payload, account-level secret |
| **T / T-1** | Debit day and the day before. Requests must be in by **late night on T-1**; the file is built at 00:00 on T |
| **UPI AutoPay** | NPCI's UPI recurring mandate. Real-time; **lending mandates cannot be revoked by the borrower**. Not enabled on our account |
| **DigiCollect** | Digio's product name for the NACH/UPI mandate and collection suite |
| **Sub-user** | A scoped Digio login; `sub_user_identifier` attributes a mandate or debit to one |

### Navix terms used here

| Term | Meaning |
|---|---|
| **Paise** | Navix's money unit — integer `long`, HALF_UP, never floats |
| **`outstandingAsOf`** | `RepaymentService` computing the penalty-aware balance **on read**. The single "amount owed" everywhere |
| **`PENDING_VERIFICATION`** | A recorded payment awaiting Accountant confirmation. Where a mandate debit would land |
| **Reborrow** | A returning borrower's new advance, reusing their saved profile — the flow a per-customer mandate suits |
| **SoD / maker-checker** | Separation of duties; one actor never both proposes and approves |
