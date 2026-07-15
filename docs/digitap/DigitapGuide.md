# Digitap API Guide (NAVIX integration reference)

*A durable reference of the Digitap verification APIs handed to NAVIX as PDFs in the "production credentials"
package (8 suites: KYC v4.7, Employment v2.3, Credit Analytics v2.7, Location v1.3, Email v1, Face-Match v1.2,
KYC-OCR v1.1, FL Web SDK v1.4). Compiled **2026-07-11**. Every Digitap API here maps to a step in NAVIX's
9-step onboarding (§11 of the root `CLAUDE.md`). Digitap is a candidate provider to sit **behind NAVIX's
existing provider-neutral verification seam** (`VerificationPort`), alongside / instead of Fintrix and Signzy.*

> **Provider:** Digitap · **Docs:** delivered as PDFs (no self-serve API portal was scraped)
> **Production base URLs:** `https://svc.digitap.ai` (KYC-validation, Employment `/cv`, Email `/cv`) · `https://api.digitap.ai` (Credit-Analytics, Location `/ent`, Face-Match `/fmfl`, KYC-OCR `/ocr`)
> **UAT/DEMO base URLs:** `https://svcdemo.digitap.work` · `https://apidemo.digitap.work`
> **Auth:** header `Authorization: Basic base64(client_id:client_secret)` (**HTTP Basic** — client_id/secret issued on registration, **different per environment**) + `Content-Type: application/json`
> **All endpoints are `POST` + JSON** (except the FL Web SDK, which is client-side JS). No real credentials were in the PDF hand-off — every value is a placeholder.

## Files in this folder

- **`DigitapGuide.md`** — this master guide: catalog + one section per NAVIX-relevant API (endpoint, request table, curl, sample responses).
- **`digitap-apis.json`** — machine-readable single source of truth (all 43 endpoints, fields, samples, per-API `host`). **If this doc and the JSON disagree, the JSON wins.**
- **`NAVIX_MAPPING.md`** — how each Digitap API plugs into NAVIX's existing verification code (the `VerificationPort` seam, penny-drop, bureau, onboarding steps).

## Digitap vs Signzy at a glance (both are candidate providers)

| | Signzy (`docs/signzy/`) | Digitap (this folder) |
|---|---|---|
| Auth | `Authorization: <opaque token>` (raw, not Bearer) | `Authorization: Basic base64(client_id:client_secret)` |
| Hosts | one (`api.signzy.app`) | **two** (`svc.digitap.ai`, `api.digitap.ai`) |
| Envelope | verification → `result:{}`, bureau → `{statusCode,message,data:{}}` | mostly `{http_response_code, result_code, request_id, client_ref_num, result:{}}`; OCR/Face-Match use `{status, statusCode, result:{}}`; Location uses `{code, model:{}}` |
| Catalog size | 11 APIs | **43 APIs** across 8 suites |
| Bank penny-drop | ✅ Hybrid Bank Account Verification | ❌ **not in this package** (bank verification is a separate Digitap suite not handed over) |
| PAN 206AB | `/pan/compliance-206-individual-search` | `/validation/kyc/v1/form206ab_compliance_status` |

## Response envelope conventions

- **Standard envelope** (KYC, Employment, Credit): `{ http_response_code, result_code, request_id, client_ref_num, result: {...} }`.
  - `result_code` **101** = success / record found · **102** = invalid id-number or combination of inputs (for Credit-Analytics, 102 = *retry via the masked-mobile flow*) · **103** = name / record not found.
  - `client_ref_num` is **your** caller-supplied correlation id, echoed back — store it like NAVIX stores a `txnId`.
- **OCR & Face-Match** (`api.digitap.ai/ocr`, `/fmfl`): `{ status, clientRefId, reqId/ocrReqId, statusCode, result: {...} }`.
- **Location** (`api.digitap.ai/ent`): `{ code, model: {...} }`, correlation key `uniqueId`.
- **Async two-step flows** (EPFO Employee Search, EPFO Passbook, FL Web SDK): an init/OTP call returns a `txn_id`/token, then a fetch call returns the payload.

---

## Catalog (all 43 APIs)

Full field tables + samples for every row are in **`digitap-apis.json`**. Detailed prose below covers the NAVIX-mapped ones; ancillary rows are catalog-only.

### KYC Validation suite — host `https://svc.digitap.ai`

| # | API | Endpoint (`POST`) | NAVIX step |
|---|-----|-------------------|-----------|
| 1 | PAN Basic (V1) | `/validation/kyc/v1/pan_basic` | PAN (name/status gate) |
| 2 | PAN Basic (V2) | `/validation/kyc/v2/pan_basic` | PAN (name/status gate) |
| 3 | PAN Details | `/validation/kyc/v1/pan_details` | PAN |
| 4 | PAN Details (Backward-Compatible) | `/validation/kyc/v1/pan_details_bc` | PAN |
| 5 | **PAN Details Plus** | `/validation/kyc/v1/pan_details_plus` | **PAN** (richest — demographics + address + Aadhaar-link + employment flags) |
| 6 | **PAN 206AB Compliance Status** | `/validation/kyc/v1/form206ab_compliance_status` | PAN (206AB/206CCA gate) |
| 7 | PAN ITR Validation | `/validation/kyc/v1/itr_basic` | income cross-check |
| 8 | PAN to Name | `/validation/kyc/v1/pan_to_name` | PAN |
| 9 | PAN to Father's Name | `/validation/kyc/v1/pan_to_fname` | PAN |
| 10 | PAN Profile | `/validation/kyc/v1/pan_profile` | PAN |
| 11 | PAN Account Link | `/validation/misc/v1/pan-account-linkage` | ancillary |
| 12 | VoterID Validation | `/validation/kyc/v1/voter` | identity (ancillary) |
| 13 | Passport Validation | `/validation/kyc/v1/passport` | identity (ancillary) |
| 14 | **PAN Aadhaar Link** | `/validation/kyc/v1/pan_aadhaar_link` | PAN↔Aadhaar link gate |
| 15 | PAN to Masked Aadhaar | `/validation/kyc/v1/pan_to_masked_aadhaar` | DigiLocker/Aadhaar KYC |
| 16 | Aadhaar to Masked PAN | `/validation/kyc/v1/aadhaar_to_masked_pan` | DigiLocker/Aadhaar KYC |
| 17 | **Driving Licence** | `/validation/kyc/v1/dl` | identity (ancillary) |
| 18 | **Driving Licence Plus** | `/validation/kyc/v1/dl_plus` | identity + address + photo |
| 19 | Unique Disability ID | `/validation/kyc/v1/kyc_udid_verification` | ancillary |
| 20 | Aadhaar to Unmasked PAN | `/validation/kyc/v1/aadhaar_to_unmasked_pan` | DigiLocker/Aadhaar KYC |

### Employment Verification suite — host `https://svc.digitap.ai/cv`

| # | API | Endpoint (`POST`) | Enabled | NAVIX step |
|---|-----|-------------------|---------|-----------|
| 21 | UAN Basic Employment V1 | `/cv/uan_basic` | ⛔ deprecated | employment |
| 22 | UAN Basic Employment V2 | `/cv/v2/uan_basic/sync` | ✅ | employment |
| 23 | UAN Basic Employment V3 | `/cv/v3/uan_basic/sync` | ✅ | employment |
| 24 | UAN Basic Employment V4 | `/cv/v4/uan_basic/sync` | ✅ | employment |
| 25 | UAN Basic Employment V5 | `/cv/v5/uan_basic/sync` | ✅ | employment |
| 26 | UAN Advanced Employment V1 | `/cv/uan_advanced` | ⛔ deprecated | employment |
| 27 | UAN Advanced Employment V2 | `/cv/v2/uan_advanced/sync` | ✅ | employment |
| 28 | UAN Advanced Employment V3 | `/cv/v3/uan_advanced/sync` | ✅ | employment |
| 29 | **UAN Advanced Employment V4** | `/cv/v4/uan_advanced/sync` | ✅ | **employment** (highest coverage) |
| 30 | EPFO Employee Search | `/cv/v1/employee_name_search/request` + `/status` | ✅ | employment (async) |
| 31 | **TDS Quarterly** | `/cv/v1/tds_quarterly` | ✅ | SALARY signal (Salary/Non-Salary) |
| 32 | **EPFO Passbook Extraction** | `/cv/uan_passbook/get_otp` + `/get_passbook` | ✅ | **SALARY** (EPF contributions) |

### Credit Analytics suite — host `https://api.digitap.ai`

| # | API | Endpoint (`POST`) | NAVIX step |
|---|-----|-------------------|-----------|
| 33 | **Credit Analytics Request** | `/credit_analytics/request` | **BUREAU** (Experian) |
| 34 | Masked Mobile Report | `/credit_analytics/masked_mobile_report` | BUREAU (retry on `result_code 102`) |

### Location, Email, Face-Match, OCR, SDK

| # | API | Suite | Endpoint | Host | NAVIX step |
|---|-----|-------|----------|------|-----------|
| 35 | Email Verification | Email | `POST /cv/email_verification/v1` | `svc.digitap.ai` | **EMAIL** |
| 36 | Address → Lat/Long | Location | `POST /ent/v1/address-to-lat-long` | `api.digitap.ai` | ADDRESS |
| 37 | Lat/Long → Address | Location | `POST /ent/v1/address-verification` | `api.digitap.ai` | ADDRESS |
| 38 | Distance Between Two Points | Location | `POST /ent/v1/get-distance` | `api.digitap.ai` | ancillary |
| 39 | **Face Match** | Face-Match | `POST /fmfl/v2/face-match` | `api.digitap.ai` | **SELFIE** (face match) |
| 40 | KYC-OCR Aadhaar | KYC-OCR | `POST /ocr/v1/aadhaar` | `api.digitap.ai` | KYC OCR |
| 41 | KYC-OCR Voter ID | KYC-OCR | `POST /ocr/v1/voter` | `api.digitap.ai` | KYC OCR |
| 42 | KYC-OCR Passport | KYC-OCR | `POST /ocr/v1/passport` | `api.digitap.ai` | KYC OCR |
| 43 | **FL Web SDK** (Selfie Capture + Face Liveness) | FL Web SDK | client-side JS SDK | — | **SELFIE / liveness** |

---

## 5/6. PAN Details Plus & PAN 206AB Compliance  → NAVIX PAN step

Digitap's PAN family runs from a bare name/status check (`pan_basic`) up to the full profile (`pan_details_plus`). **PAN Details Plus** is the richest and the natural NAVIX PAN client: it returns demographics, full address, `aadhaar_linked`, PAN status, and employment flags (`is_salaried`, `is_director`, `is_sole_proprietor`) in one call.

**Endpoint:** `POST https://svc.digitap.ai/validation/kyc/v1/pan_details_plus`
**Request** — `client_ref_num` (M), `pan` (M)

```bash
curl --location 'https://svc.digitap.ai/validation/kyc/v1/pan_details_plus' \
--header 'Authorization: Basic <base64(client_id:client_secret)>' \
--header 'Content-Type: application/json' \
--data '{ "client_ref_num": "abcd123", "pan": "ABCPE1234Z" }'
```
```json
// 200, result_code 101
{ "http_response_code": 200, "result_code": 101, "request_id": "97a9f2ce-...", "client_ref_num": "abcd123",
  "result": { "pan": "ABCPE1234Z", "pan_type": "Individual", "fullname": "JOHN DOE",
    "first_name": "JOHN", "last_name": "DOE", "gender": "male",
    "aadhaar_number": "XXXXXXXX1234", "aadhaar_linked": true, "dob": "11/08/1970",
    "address": { "building_name": "...", "locality": "...", "pincode": "...", "city": "...", "state": "..." },
    "mobile": "...", "email": "...", "is_salaried": true, "is_director": false, "is_sole_proprietor": false,
    "pan_status": "...", "pan_allotment_date": "..." } }
```
> Unlike Signzy's masked `entityName`, Digitap PAN Details Plus returns the **unmasked `fullname`** + address + `is_salaried` — usable to *populate* the profile and cross-check the applicant's declared employment, not just gate. `aadhaar_linked:true` is the healthy state.

### PAN 206AB Compliance Status  (direct analogue of the Signzy 206AB you were testing)

**Endpoint:** `POST https://svc.digitap.ai/validation/kyc/v1/form206ab_compliance_status`
**Request** — `client_ref_num` (M), `pan` (M)

```bash
curl --location 'https://svc.digitap.ai/validation/kyc/v1/form206ab_compliance_status' \
--header 'Authorization: Basic <base64(client_id:client_secret)>' \
--header 'Content-Type: application/json' \
--data '{ "client_ref_num": "abcd123", "pan": "ABCPE1234Z" }'
```
```json
// 200, result_code 101
{ "http_response_code": 200, "result_code": 101, "request_id": "97a9f2ce-...", "client_ref_num": "abcd123",
  "result": { "pan": "ABCPE1234Z", "pan_name": "NXXXXT KXXXU", "specified_person": "N",
    "pan_operative_status": "operative", "fin_year": "2023-24", "pan_allotment_date": "2010-07-20" } }
```
> `specified_person:"N"` + `pan_operative_status:"operative"` is the compliant/healthy state. `specified_person:"Y"` = a **206AB/206CCA specified person** (non-filer whose TDS/TCS crossed the threshold — a risk flag; higher TDS applies). `pan_name` is partially masked. This is the Digitap equivalent of Signzy's `/pan/compliance-206-individual-search` (`isSpecified` ↔ `specified_person`, `panStatus` ↔ `pan_operative_status`).

---

## 14. PAN Aadhaar Link  → NAVIX PAN↔Aadhaar gate

**Endpoint:** `POST https://svc.digitap.ai/validation/kyc/v1/pan_aadhaar_link` — `client_ref_num`(M), `pan`(M), `aadhaar`(M). Supports request/response **JWE encryption**.
```json
{ "http_response_code": 200, "result_code": 101, "result": { "message": "Is already linked to given Aadhaar", "code": "LINK-001" } }
```
> `code` `LINK-001` = linked, `LINK-002` = not linked (full code list in the JSON). A cheap pass/fail gate that PAN Details Plus's `aadhaar_linked` also covers.

---

## 17/18. Driving Licence [Plus]  → NAVIX identity / address

`dl` validates a DL number + DOB; **`dl_plus`** additionally returns the driver's **address, split-address, blood group, vehicle-category details, status history, and a base64 `user_image`** — usable as a face-match reference or an address-verification source when Aadhaar isn't available.

**Endpoint:** `POST https://svc.digitap.ai/validation/kyc/v1/dl_plus` — `client_ref_num`(M), `dl_number`(M), `dob`(M).
```json
{ "http_response_code": 200, "request_id": "...", "result": {
  "dl_number": "AP11111100000000", "dob": "10-01-1986",
  "dl_validity": { "non_transport": { "from": "04-04-2018", "to": "03-03-2038" }, "transport": {"from":"","to":""} },
  "details_of_driving_licence": { "name": "...", "father_or_husband_name": "...", "address": "...", "split_address": {...},
    "status": "...", "user_blood_group": "...", "user_image": "<base64>", "expiry_date": "..." },
  "vehicle_category_details": [ ... ] } }
```

---

## 29. UAN Advanced Employment Verification (V4)  → NAVIX employment

The Employment suite has **no per-employee salary figure** anywhere — it is UAN/EPFO **employment** data. Basic vs Advanced: Advanced adds an EPFO PF-filing cross-check (`epfo_details`) + voluntarily-updated `additional_details` (bank, Aadhaar, PAN, relative name). V4/V5 add `employment_history[]` per UAN. **V4 Advanced** is the highest-coverage: lookup by any of PAN / mobile / UAN / dob / employee_name / employer_name.

**Endpoint:** `POST https://svc.digitap.ai/cv/v4/uan_advanced/sync`
**Request** — `client_ref_num`(M) + at least one identifier: `pan`, `mobile`, `uan`, `dob`, `employee_name`, `employer_name`, `name_match_method` (all optional individually).
```json
{ "http_response_code": 200, "result_code": 101, "client_ref_num": "abc", "result": {
  "uan": ["102028758928"],
  "summary": { "recent_employer_data": { "establishment_name": "NAMRA FINANCE LIMITED", "member_id": "...",
    "date_of_joining": "2023-08-07", "date_of_exit": "", "employer_confidence_score": ... }, "epfo": {...} },
  "uan_details": { "<uan>": { "basic_details": {...}, "employment_details": {...}, "additional_details": {...},
    "employment_history": [ { "establishment_name": "...", "date_of_joining": "...", "date_of_exit": "...",
      "employment_period_in_months": ..., "is_recent": true, "is_employed": true, "matched_name": "...", "is_name_exact": true } ] } },
  "epfo_details": { "matches": [...], "pf_filing_details": [...], "establishment_info": {...} } } }
```
> Current employer = the `employment_history` entry with empty `date_of_exit` / `is_recent:true`. Use this to **verify employment** (employer name match, tenure) — but for a **salary** figure use TDS Quarterly / EPFO Passbook below. Related: **EPFO Employee Search** (#30, async `request`→`status`) finds a UAN by name; the deprecated V1 variants (`/cv/uan_basic`, `/cv/uan_advanced`) are `enabled:false`.

---

## 31/32. TDS Quarterly & EPFO Passbook  → NAVIX SALARY

These are the closest thing to a salary signal in the Digitap package (there is **no direct CTC/salary API here** — that was Fintrix's salary-verification suite).

- **TDS Quarterly** (`POST /cv/v1/tds_quarterly`) — returns a `return_type: Salary | Non-Salary` flag plus aggregate establishment `wage_month`/`total_amount` PF-filing figures (company-wide, not per-employee). Good for confirming *salaried vs not*.
- **EPFO Passbook Extraction** (OTP-gated, two-step) — the strongest per-employee proxy: monthly EPF contributions (`cr_ee_share`/`cr_er_share`) trace the employee's basic wage.

**EPFO Passbook flow:**
1. `POST https://svc.digitap.ai/cv/uan_passbook/get_otp` — `client_ref_num`(M), one of `mobile`/`uan`(M), `pan`(o), `epf_balance`(o) → OTP sent to the UAN-registered mobile, returns `txn_id`.
2. `POST https://svc.digitap.ai/cv/uan_passbook/get_passbook` — `txn_id`(M) + `otp`(M) → the passbook.
```json
// get_otp
{ "message": "An OTP has been sent to your mobile number 98765XXXXX", "http_response_code": 200, "result_code": 101, "txn_id": "daf429a" }
// get_passbook (with epf_balance=true)
{ "result": {
  "employee_details": { "member_name": "SANJAY T M", "father_name": "...", "dob": "22-01-2001" },
  "est_details": [ { "est_name": "DIGITAP.AI ...", "member_id": "...", "doj_epf": "...",
    "pf_balance": { "net_balance": ..., "employee_share": {"debit":...,"credit":...,"balance":...}, "employer_share": {...} },
    "passbook": [ { "tr_date_my": "...", "cr_ee_share": "...", "cr_er_share": "...", "cr_pen_bal": "...", "particular": "...", "month_year": "...", "db_cr_flag": "..." } ] } ],
  "overall_pf_balance": { "current_pf_balance": ..., "employee_share_total": {...}, "employer_share_total": {...} } } }
```
> This is the OTP analogue of Signzy's *UAN Passbook without OTP* — Digitap requires an OTP to the registered mobile here. The monthly `cr_ee_share` (min ₹1,800 EPF contribution) is the wage signal NAVIX's `navix-income-risk` module can consume to back out basic → salary → `eligibleLimitPaise` (25% of salary).

---

## 33/34. Credit Analytics  → NAVIX BUREAU (Experian)

Pulls a full **Experian** bureau/CIR report (CAIS tradelines, CAPS enquiry summary, `BureauScore`) for a mobile number. **The payload is Experian's `INProfileResponse` shape** — very close to what NAVIX already parses from Fintrix's `individual_experian` (`samplepan.json`): `Current_Application`, `CAIS_Account`/`CAIS_Summary`, `TotalCAPS_Summary`/`CAPS`, and the score.

**Endpoint:** `POST https://api.digitap.ai/credit_analytics/request`
**Request** (consent is OTP-based — all mandatory unless noted): `client_ref_num`, `mobile_no`, `name_lookup`, `first_name`, `last_name`, `date_of_birth`(o), `email`(o), `pan`(o), `consent_message`, `consent_acceptance`, `device_type`, `otp`, `timestamp`, `device_ip`, `device_id`, `report_type`(o — json/xml/pdf).

```bash
curl --location 'https://api.digitap.ai/credit_analytics/request' \
--header 'Authorization: Basic <base64(client_id:client_secret)>' \
--header 'Content-Type: application/json' \
--data '{ "client_ref_num": "Test123", "mobile_no": "7908096603", "name_lookup": 0,
  "first_name": "Shubhra", "last_name": "Dutta",
  "consent_message": "I hereby authorize Experian to pull my credit report",
  "consent_acceptance": "Yes", "device_type": "web", "otp": "6307",
  "timestamp": "1408202417...", "device_ip": "192.168.0.1", "device_id": "..." }'
```
```json
// 200, result_code 101 (102 = retry via Masked Mobile Report; 103 = name not found)
{ "http_response_code": 200, "result_code": 101, "message": "success", "request_id": "62b96443-...",
  "result": { "result_json": { "INProfileResponse": {
    "Header": {...}, "UserMessage": { "UserMessageText": "Normal Response" },
    "CreditProfileHeader": { "ReportNumber": "...", "Version": "..." },
    "Current_Application": { "Current_Application_Details": {...} },
    "CAIS_Account": { "CAIS_Summary": { "Credit_Account": { "CreditAccountTotal": ..., "Active": ..., "Default": ..., "Closed": ... },
      "Total_Outstanding_Balance": { "Secured": ..., "UnSecured": ..., "All": ... } }, "CAIS_Account_DETAILS": [ /* tradelines */ ] },
    "TotalCAPS_Summary": {...}, "CAPS": {...}, "NonCreditCAPS": {...},
    "SCORE": { "BureauScore": "800", "BureauScoreConfidLevel": null } } } } }
```
> **The score lives at `result.result_json.INProfileResponse.SCORE.BureauScore`** (string numeric) → NAVIX `bureau_score`. `CAIS_*`/`CAPS`/`Current_Application` feed `BureauReportFacts` → the credit brief (§14), just like the Fintrix Experian response. **Masked Mobile Report** (`/credit_analytics/masked_mobile_report`) is the fallback when the primary returns `result_code 102` with masked candidate numbers — call it with the same `request_id`. `report_type` can return a pre-signed JSON/XML/PDF URL (valid ~1 hour). Optional JWE `encrypted_data` is documented generically.

---

## 39/43. Face Match & FL Web SDK  → NAVIX SELFIE / liveness

- **Face Match** (`POST https://api.digitap.ai/fmfl/v2/face-match`) — compares a live selfie (`person`, base64) against the document photo (`card`, base64); `clientRefId`(M). Returns `result.{is_same_face, same_face_confidence, is_person_image_blurry, is_card_image_blurry, ...}`.
```json
{ "status": "success", "statusCode": "200", "clientRefId": "12345", "reqId": "...",
  "result": { "is_same_face": true, "same_face_confidence": 0.9998, "is_person_image_blurry": false, "is_card_image_blurry": false } }
```
- **FL Web SDK** — a client-side JS SDK for **selfie capture + passive face liveness** (the interactive capture front-end that produces the `person` image for Face Match). Init config in the JSON catalog; UAT capture URL `https://selfiecaptureuat.digitap.work`. This is Digitap's counterpart to Signzy's *Liveness Secure* iframe — pair the SDK capture with the Face Match API for liveness + match.

> `same_face_confidence` (0–1) is the match score to threshold on; `is_same_face` is the boolean gate. Feed the DigiLocker/DL/OCR document photo as `card` and the SDK-captured selfie as `person`.

---

## 35–38, 40–42. Ancillary (Email, Location, KYC-OCR)

- **Email Verification** (`POST https://svc.digitap.ai/cv/email_verification/v1`) — `client_ref_num`(M), `email`(M), optional `individual_name`/`establishment_name` for name-match. Returns `result.summary.{is_verified, is_email_valid, is_individual_matched, is_establishment_matched}` + WHOIS + SMTP/MX validity. → NAVIX **EMAIL** onboarding step (replaces the cosmetic email check with format + domain + deliverability + name-match).
- **Location Services** (host `api.digitap.ai/ent`) — `address-to-lat-long`, `address-verification` (lat/long→address), `get-distance`; correlation key `uniqueId`, returns `{ code, model:{...} }`. → NAVIX **ADDRESS** step / geocoding (e.g. distance from a serviceable branch).
- **KYC-OCR** (host `api.digitap.ai/ocr`) — `aadhaar`, `voter`, `passport`; takes a base64/URL image (+ back side), `clientRefId`(M); returns structured `result[].details{...}` with optional Aadhaar masking, `verhoeffCheckPassed`, `qualityCheck` and `fraudCheck`. → document OCR to pre-fill KYC fields from an uploaded ID.

---

## Notes & caveats

- **Two hosts, one auth.** `svc.digitap.ai` and `api.digitap.ai` share the same `Basic base64(client_id:client_secret)` scheme, but **UAT and prod use different client_id/secret pairs**. Read each API's `host` in `digitap-apis.json`.
- **`client_ref_num` / `clientRefId` / `uniqueId`** is a *caller-supplied* idempotency/correlation id echoed in the response — always send a unique value and store it (like NAVIX's `txnId`).
- **No bank penny-drop and no direct-salary API in this package.** Bank-account verification and salary/CTC verification are separate Digitap suites that were **not** in the hand-off; NAVIX's existing Fintrix penny-drop + salary stay in place unless those suites are requested. The nearest salary proxies here are TDS Quarterly + EPFO Passbook (EPF contributions).
- **Consent is first-class** for Credit Analytics + EPFO Passbook (OTP-based `consent_*` / `otp` fields with a timestamp + device ip/id) — capture and store these for compliance.
- **PII in responses:** Aadhaar is masked by default (OCR `maskAadhaarNumber`, PAN `aadhaar_number: XXXXXXXX1234`); report/PDF/image outputs are **short-lived pre-signed URLs** (~1 hour) — download/store promptly, as NAVIX does for S3 documents.
- **Samples are from the docs, not live calls.** KYC/Face-Match/OCR PDFs give field *tables* (not literal request JSON), so those `requestSample`s were reconstructed from documented field names; response structures are copied faithfully with long arrays/base64/report bodies abbreviated. **`digitap-apis.json` is the source of truth.**
