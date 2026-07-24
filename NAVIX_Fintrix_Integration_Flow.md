# DhanBoost × Fintrix — API Integration Flow

> How the Fintrix (Digitap-backed) verification APIs map onto the DhanBoost borrower journey.
> **Base URL:** `https://admin.fintrix.tech/__api/api/v1/`
> **Auth (these APIs):** `Authorization: Basic <base64(client_id:client_secret)>` · `Content-Type: application/json`
> **Auth (DigiLocker APIs):** `X-Client-ID` + `X-Client-Secret` (different scheme — see `Digilocker_API_Guide.md`)
> All response shapes below are from **real sandbox responses** (2026-06-19).

## Scope decisions (locked)
- **No Account Aggregator, no bank-statement API.** Employment/salary basis comes from a **UAN API (to be provided)** — slot reserved in the flow.
- **Selfie: captured for records / manual review only — `vkyc_face_liveness` is NOT called** in the live flow (kept as optional, off).
- **Credit bureau: Experian primary → CRIF fallback.**
- **Aadhaar–PAN link does NOT need DigiLocker** — `pan_comprehensive` already returns `aadhaar_linked` + `masked_aadhaar`.

---

## 1. The APIs in scope

| # | Endpoint | Purpose | In live flow? |
|---|---|---|---|
| 1 | `pan_comprehensive` | PAN validation — name, DOB, gender, address, **aadhaar_linked**, masked Aadhaar | ✅ core |
| 2 | `cv_email_verification` | Official email + **EPFO employer** match (employment signal) | ✅ |
| 3 | `ent_address_verification` | Geo (lat/long) → address, pincode, state | ✅ |
| 4 | `individual_experian` | Credit bureau (Experian) — score + tradelines | ✅ primary |
| 5 | `individual_crif` *(CRIF)* | Credit bureau (CRIF) — score + accounts summary | ✅ fallback |
| 6 | `verification_pennydrop` | Bank account verify + **name-at-bank** + IFSC/bank details | ✅ payout gate |
| 7 | UAN API *(to be provided)* | Employment + salary basis (→ 25% limit, salary date) | ⏳ reserved |
| 8 | `vkyc_face_liveness` | Selfie liveness | ⛔ not called (selfie captured for records only) |
| + | DigiLocker (5 calls) | Aadhaar XML + PAN doc fetch | ✅ KYC docs |

> The exact CRIF endpoint path needs confirming (response sample was provided; path likely `individual_crif` or similar). Everything else is verified.

---

## 2. Mapped flow — DhanBoost step → API call

```
STEP 1 — SIGN-UP
  PAN entry        → pan_comprehensive   → name, DOB, gender, address, aadhaar_linked, masked_aadhaar
  Mobile + OTP     → DhanBoost's own OTP
  Employment/UAN   → [UAN API — to be provided]  → employer + salary basis
  Official email   → cv_email_verification → EPFO employer match (corroborates employment)
  Salary           → from UAN API (when live); declared in app meanwhile
  Selfie           → captured & stored (NO liveness API call)
  Address proof    → ent_address_verification → address, pincode, state (compare vs PAN/Aadhaar)

STEP 2 — KYC (identity)
  DigiLocker init/poll → digilocker_* → digilocker_aadhar_xml (name, DOB, address, photo)
  Aadhaar–PAN link → READ FROM pan_comprehensive.aadhaar_linked  ✅ (no separate call)
  Identity cross-match → PAN name/DOB  ==  Aadhaar XML name/DOB

STEP 3 — INCOME & RISK
  Employment       → UAN API + cv_email_verification
  Credit bureau    → individual_experian   (primary)
                       └─ on error / "No record" / Source Down → individual_crif (fallback)

STEP 4 — ELIGIBILITY & LIMIT
  salary (UAN/declared) → 25% cap → limit
  bureau score + obligations → risk → A/B/C/D

STEP 5 — TAKE LOAN
  Sign docs (agreement / sanction / KFS) — DhanBoost internal
  Bank verify      → verification_pennydrop → account_exists + full_name (NAME MATCH gate)
  Manual transfer → Accountant confirms — DhanBoost internal
```

---

## 3. Per-API reference (real responses)

### 3.1 `pan_comprehensive` — PAN + identity backbone ✅
```
POST /pan_comprehensive   { "id_number": "<PAN>", "remark": "..." }
```
Returns: `data.{ status:"valid", full_name, first/middle/last_name, dob, gender, category,
email(masked), phone_number(masked), aadhaar_linked:true, masked_aadhaar, tax,
address:{full,line_1,line_2,city,state,zip,country} }`.
**Use for:** PAN anchor + **Aadhaar-link check** (`aadhaar_linked`) + identity fields to cross-match Aadhaar. Sandbox rejects masked PANs — use real values.

### 3.2 `cv_email_verification` — official email + employer ✅
```
POST /cv_email_verification   { email, client_ref_num, individual_name, establishment_name }
```
Returns: `result.summary.{is_verified,is_email_valid,is_establishment_matched,is_individual_matched}`,
`result.establishment_details.matched_establishments[]{matched_establishment, est_id, source:"epfo", score}`,
`result.individual_details.{is_individual_matched,score}`, `result.additional_info.{is_webmail,is_generic_email,...}`.
**Use for:** confirm work email real + tied to employer via **EPFO** → employment corroboration.
**Decision rule (suggested):** require `summary.is_verified == true` AND `is_establishment_matched == true`; treat `is_generic_email==true` (gmail etc.) as NOT an official email.

### 3.3 `ent_address_verification` — geo address ✅
```
POST /ent_address_verification   { latitude, longitude, uniqueId }
```
Returns: `model.{address, pincode, district, state, country, withInIndia}`.
**Use for:** confirm current-address coordinates resolve to a real Indian address; compare `state`/`pincode` vs PAN/Aadhaar address.

### 3.4 `individual_experian` — bureau (PRIMARY) ✅
```
POST /individual_experian   { pan, name, mobile, consent:"Y", remark }
```
Returns: `data.{ credit_score, credit_report.SCORE.FCIREXScore, credit_report.CAIS_Account (tradelines),
credit_report.Current_Application.Current_Application_Details.Current_Other_Details.{Income,Employment_Status} }`,
plus top-level `message` (e.g. `"SYS100004 (No record found)"`).
**Use for:** risk score + active accounts. `consent:"Y"` mandatory.
**Fallback trigger:** if `message` indicates *no record*, or score absent, or `Source Down` → call CRIF.
> Note: sandbox test PAN returned `credit_score: 8` with "No record found" (thin-file test). Real PANs return a proper band.

### 3.5 CRIF — bureau (FALLBACK) ✅
```
POST /individual_crif (confirm path)   { name, dob, pan, mobile, consent... }
```
Returns: `data.HEADER.STATUS`, `data.ACCOUNTS-SUMMARY.{DERIVED-ATTRIBUTES, PRIMARY-ACCOUNTS-SUMMARY (active/overdue/balance/sanctioned), SECONDARY-ACCOUNTS-SUMMARY}`, `data.SCORES.SCORE.{SCORE-VALUE,SCORE-FACTORS}`, `data.EMPLOYMENT-DETAILS`.
**Use for:** same risk inputs when Experian has no/low data.
> Map both bureaus to ONE internal risk object: `{score, activeAccounts, overdueAccounts, totalBalance, enquiriesLast6m}` so A/B/C/D logic is bureau-agnostic.

### 3.6 `verification_pennydrop` — bank account (PAYOUT GATE) ✅
```
POST /verification_pennydrop   { account_number, ifsc, ifsc_details:true, remark }
```
Returns: `data.{ status:true, account_exists:true, full_name:"SHUBHAM", imps_ref_no,
ifsc_details:{bank,branch,city,state,ifsc,micr,...}, transaction_info{...} }`.
**Use for:** Step 5 — confirm account valid + **`full_name` matches applicant name** before manual transfer.
**Gate rule (suggested):** block disbursement unless `account_exists==true` AND fuzzy-match(`full_name`, applicant legal name) ≥ threshold.

### 3.7 `vkyc_face_liveness` — selfie (NOT in live flow) ⛔
Captured selfie is stored for manual review; this API is **not called** per current scope. Returns `data.{is_live, liveness_confidence, ...}` if enabled later.

### 3.8 UAN API — reserved ⏳
To be provided by vendor. Will supply employment + salary basis feeding the **25% limit** and **salary-day due date**. Until then, salary is declared in-app and corroborated by `cv_email_verification`.

---

## 4. Identity cross-match matrix (the "is this really them?" logic)

| Field | PAN API | Aadhaar (DigiLocker XML) | Penny-drop | Rule |
|---|---|---|---|---|
| Name | `full_name` | `full_name` | `full_name` | all three should fuzzy-match |
| DOB | `dob` | `dob` | — | PAN.dob == Aadhaar.dob |
| Aadhaar link | `aadhaar_linked` / `masked_aadhaar` | (full Aadhaar masked) | — | `aadhaar_linked==true`; masked digits agree |
| Address | `address.state/zip` | `address.state/zip` | bank `state/city` | consistency check, not hard block |

---

## 5. Open items for the vendor
1. **UAN API** — endpoint, request (UAN/mobile?), response (employer, salary?). Blocks salary-verified limit.
2. **CRIF endpoint path** + request schema (we have the response sample, need the call).
3. Penny-drop **name-match**: do they return a match score, or do we fuzzy-match `full_name` ourselves?
4. Experian/CRIF: confirm production score range + the exact "no record" flags for the fallback trigger.
