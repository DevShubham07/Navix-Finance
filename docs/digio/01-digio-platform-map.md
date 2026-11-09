# 01 — Digio platform map and our account

Explored 2026-10-16 in the authenticated production console, `https://enterprise.digio.in`, signed in
as `info@dhanboost.com`.

## 1. Product and account context

| Fact | Value | Evidence |
|---|---|---|
| Console host | `enterprise.digio.in` (production). `app.digio.in` and `drive.digio.in` redirect to a login | VERIFIED |
| Environment | **Production.** No sandbox session exists on this machine | VERIFIED |
| Account email | `info@dhanboost.com` | VERIFIED |
| Legal entity on the account | **NAVIX FINANCE PRIVATE LIMITED** | VERIFIED |
| Products in the sidebar | DigiDocs · Form Center (Beta) · **Mandates** (Mandate Book, Presentations, Subscriptions) · **KYC** (DigiStudio, Requests, Utils, VideoKYC) · Utilities (Reports, Jobs) | VERIFIED |
| Two-factor auth | **Not enabled** — profile offers "Enable Two Factor Auth" | OBSERVED |
| Webhooks | **Not configured** — the profile's Webhooks section contains only a "Configure Webhook" button | OBSERVED |
| API credentials UI | **None found** — and that is by design: Digio **issues credentials manually by email**. Production credentials were received and **verified working** 2026-10-16 (`13-`) | VERIFIED |
| UPI AutoPay | **Not in the sidebar** — but the **API and its spec exist** (`13-` §4) and may still be entitled. Console absence is not proof | OBSERVED → `11-` Q4, now a blocker |

The account is consistent with CLAUDE.md's standing note: the product is DhanBoost, the internal
namespace and the **legal entity stay NAVIX**. Digio holds the legal entity name; the login is the
DhanBoost address.

## 2. The sponsor bank profile — the single most important account fact

`#/mandates/book/bank-profiles` — exactly **one** profile, **VERIFIED**:

| Field | Value |
|---|---|
| Config Profile Name | `NAVIX FINANCE YBL SPONSOR` |
| **Corporate Profile Id** (= the API's `corporate_config_id`) | `TSE261016195601220U17VMUFQVFG2GW` |
| Sponsor bank | **Yes Bank Ltd** — IFSC `YESB0000001`, short code `YESB` |
| Service Provider Name | `NAVIX FINANCE PRIVATE LIMITED` |
| **Utility Code** | `NACH00000000067052` |
| Login Id for mandate file name | `NACH00000000067052` |
| Identifier for bank direct integration | `dltbgcmse-collproduct@yesbank.in` |

Two things follow.

- `corporate_config_id` is the **required first field of every create/amend call**. It is account
  configuration, not a credential — it is useless without the client id/secret — but it belongs in SSM
  with the rest of the Digio config, not in source.
- **INFERRED, high confidence:** the id prefix `TSE2610161956…` encodes 2026-10-16 19:56 — the profile
  was created **the same day this exploration ran**. Combined with an empty Mandate Book and no
  configured webhook, this is a **freshly provisioned account that has never been used**. Nothing here
  is load-bearing yet, which is the best possible time to design the integration — and it also means
  no production traffic exists to learn from.

## 3. Navigation inventory

| Section | Route | Purpose | Status |
|---|---|---|---|
| Mandate Book | `#/mandates/book` | Registration register; search by UMRN, file name, NACH ID, customer identifier, sub-user, CRN | EXPLORED — **empty** (no rows; paginator shows a stock "Page 1 of 250") |
| New Mandate | `#/mandates/book/generate` | Manual create/amend/cancel form | EXPLORED (opened, **never submitted**) |
| Sponsor Bank Profiles | `#/mandates/book/bank-profiles` | Corporate config, utility code, sponsor bank | **VERIFIED** — §2 |
| Verify Bank Account | `#/mandates/book/verify-bank-account` | Penny-drop style account check | NOT STARTED — Navix already has penny-drop via Signzy/Digitap |
| Redo Physical Mandate | `#/mandates/book/redoPhysicalMandate` | Re-submit a scanned physical form | NOT STARTED |
| Mandate Analytics | `#/mandates/book/mandate-analytics` | Registration funnel | NOT STARTED |
| Presentations | sidebar | **Debit** register (the money leg) | NOT STARTED — API covered in `03-` |
| Subscriptions | sidebar | Recurring debit plans | NOT STARTED |
| Profile | `#/profile` | Account, Subordinate Users, Service Health Monitor, Merchant Details, **Webhooks**, Plan Ledger | EXPLORED |
| Usage / Activity | `#/usage`, `#/activity` | Consumption and audit | NOT STARTED |
| Reports / Jobs | sidebar | MIS downloads, bulk CSV jobs | NOT STARTED |
| DigiDocs / Form Center / KYC / VideoKYC | sidebar | Other Digio products | NOT APPLICABLE — Navix's KYC and eSign are already Signzy/Digitap/Fintrix |

Sections marked NOT STARTED were deliberately skipped: they are reporting or alternative-channel
surfaces that do not change the API design, and opening them answers no question in `11-`.

## 4. What the console tells us the account can do

Read from the New Mandate form's own `<select>` options — **VERIFIED** as the account's enabled
configuration, since the form is rendered against our corporate profile.

- **Authentication modes offered:** `Aadhaar Mandate` (eSign), `Physical Mandate`, `API Mandate`.
  All three of the API's `auth_mode` values are available to us. **No UPI option.**
- **Instrument:** `DEBIT` (also `CREDIT` in a filter, not in creation).
- **Debit amount type:** `FIXED` or `MAXIMUM`.
- **Account types:** `SAVINGS`, `CURRENT`, `CC`, `SB_NRE`, `SB_NRO`, `OTHER`.
- **Customer notification channels:** `ALL`, `SMS`, `WHATSAPP`.
- **Management categories** include the two that matter to a lender:
  `Loan instalment payment (L001)` and `Own retail portfolio mandate- EMI (O001)`.
  **CONFLICT:** `O001` and `Own retail portfolio mandate- Security` appear in the console but are
  **absent from the API's `management_category` enum** in the published spec, which instead carries
  `T002`/`S001` and a duplicated `F001`. See `11-` Q5 — do not send `O001` over the API until confirmed.
- **Live destination banks (39)** offered for API mandate: AU Small Finance, Axis, Bank of Baroda,
  Bank of India, Bank of Maharashtra, Canara, Central Bank of India, City Union, DCB, Deutsche,
  Dhanalaxmi, Federal, HDFC, ICICI, IDBI, IDFC First, India Post Payments, Indian Bank, Indian
  Overseas, Indrayani Co-op, IndusInd, Karnataka, Karur Vysya, Kotak, Oriental Bank of Commerce, PNB,
  RBL, Saraswat, Shree Kadi Nagarik Sahakari, South Indian, Standard Chartered, Tamilnad Mercantile,
  The Adinath Co-op, Catholic Syrian, HSBC, J&K Bank, UCO, Union Bank, Yes Bank.
  This list is **also available over the API** — `GET /v3/client/mandate/banks` (`03-` A6) — and that,
  not this snapshot, is what the integration should read. Several large lenders' customers bank
  outside this list; for them only eSign or physical will work.
- **Mandate Book filter states** (the operator-facing vocabulary, useful for staff UI wording):
  Signed/Authenticated · Partial · Bank/NPCI reject · UMRN received · UMRN pending · Registration
  Pending · Registered · Registration overdue · Registration failed · Transfer failed · UMRN overdue.
- **Cancellation reasons** offered in the console: `C001` customer request, `C002` corporate request,
  `C003` account closed/frozen/inoperative. The API enum additionally carries `C004` (cancelled from
  bank side) — **INFERRED** as inbound-only, not something a corporate initiates.

## 5. Coverage

| Area | Status |
|---|---|
| Product navigation | EXPLORED |
| Account configuration (sponsor, utility code, config id) | **VERIFIED** |
| Enabled auth modes / categories / banks | **VERIFIED** (console) |
| Webhook configuration | EXPLORED — absent |
| API credential provisioning | **BLOCKED** — no UI, no document; `11-` Q1 |
| Sandbox access | **BLOCKED** — no session, no credentials; `11-` Q3 |
| UPI AutoPay enablement | **BLOCKED** — absent from sidebar; `11-` Q4 |
| Presentations / Subscriptions / Reports consoles | NOT STARTED — API path documented instead |
