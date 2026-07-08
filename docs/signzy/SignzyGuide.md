# Signzy API Guide (NAVIX integration reference)

*A durable reference of the Signzy verification APIs available on the NAVIX Signzy account, scraped from the
authenticated portal on **2026-07-04**. Every Signzy API here maps to a step in NAVIX's 9-step onboarding
(§11 of the root `CLAUDE.md`). Signzy is a candidate provider to sit **behind NAVIX's existing
provider-neutral verification seam** (`VerificationPort`), alongside / instead of Fintrix.*

> **Provider:** Signzy · **Portal:** https://portal.signzy.app/#/my-apis/apis
> **Production base URL:** `https://api.signzy.app`
> **UAT/Testing base URL:** `https://api-preproduction.signzy.app`
> **Auth:** header `Authorization: <token>` (a raw opaque token from Signzy support/CSM — **not** `Bearer <t>`) + `Content-Type: application/json`
> **All endpoints are `POST` + JSON.** The portal "Test Mode" toggle switches test vs live credentials.

## Files in this folder

- **`SignzyGuide.md`** — this master guide: one section per API (endpoint, request table, curl, sample responses).
- **`signzy-apis.json`** — machine-readable single source of truth (endpoints, fields, samples). **If this doc and the JSON disagree, the JSON wins.**
- **`NAVIX_MAPPING.md`** — how each Signzy API plugs into NAVIX's existing verification code (the `VerificationPort` seam, penny-drop, bureau, onboarding steps).

## API catalog at a glance

The account exposes **12 doc pages / 11 distinct APIs** (the two Liveness pages document the same createUrl+getData pair). The portal's **Studio APIs** tab is empty. Two APIs show a **"REQUEST API"** button = **not enabled yet** on this account; the rest show "Try out this API" = enabled.

| # | API | Endpoint (`POST`) | Enabled | Maps to NAVIX step |
|---|-----|-------------------|---------|--------------------|
| 1 | Hybrid Bank Account Verification | `/api/v3/bankaccountverification/bankaccountverifications` | ✅ | **PENNY_DROP** (bank) |
| 2 | PAN 206AB Compliance | `/api/v3/pan/compliance-206-individual-search` | ✅ | PAN |
| 3 | DigiLocker — Create URL | `/api/v3/digilocker/createUrl` | ✅ | DigiLocker KYC (init) |
| 4 | DigiLocker — Get e-Aadhaar | `/api/v3/digilocker/geteaadhaar` | ✅ | DigiLocker KYC (fetch) |
| 5 | DigiLocker — Get e-Aadhaar with XML | `/api/v3/digilocker/geteaadhaarwithxml` | ✅ | DigiLocker KYC (fetch + XML) |
| 6 | Liveness Secure (createUrl + getData) | `/api/v3/liveness-secure/createUrl` · `/getData` | ✅ | SELFIE / liveness |
| 7 | Aadhaar to UAN | `/api/v3/employment-verification/aadhaar-to-uan` | ⛔ REQUEST | Employment (find UAN) |
| 8 | UAN Passbook without OTP | `/api/v3/underwriting/uan-passbook-without-otp` | ✅ | **SALARY** (EPFO) |
| 9 | eSign Using Doc Signer | `/api/v3/esign/docSigner` | ⛔ REQUEST | AGREEMENT e-sign |
| 10 | Experian Lite Bureau Report | `/api/v3/bureau/experian-lite` | ✅ | **BUREAU** (Experian) |
| 11 | CRIF Bureau Report | `/api/v3/bureau/crif` | ✅ | BUREAU fallback (CRIF) |

## Response envelope conventions

- **Verification APIs** (bank, PAN, DigiLocker, liveness, UAN) wrap the payload in `result: { ... }` (PAN returns fields at the top level).
- **Bureau APIs** (Experian, CRIF) wrap in `{ statusCode, message, data: { ... } }`.
- **DigiLocker** and **Liveness** are two-step async flows (init URL → user completes in an iframe → fetch/callback).

---

## 1. Hybrid Bank Account Verification  → NAVIX `PENNY_DROP`

Penny-less bank-account verification across 80+ banks with an automatic **penny-drop** fallback; returns account active status, name-match, and the account holder's name. **This is the direct Signzy equivalent of NAVIX's current Fintrix penny-drop.**

**Endpoint:** `POST /api/v3/bankaccountverification/bankaccountverifications`

**Request**

| Field | Type | Required | Description |
|---|---|---|---|
| `beneficiaryAccount` | String | ✅ | account number |
| `beneficiaryIFSC` | String | ✅ | IFSC code |
| `beneficiaryMobile` | String | — | mobile number |
| `beneficiaryName` | String | — | name (used for name match) |
| `nameMatchScore` | String | — | threshold; if API's score exceeds it → `nameMatch:"yes"` else `"no"` |
| `nameFuzzy` | String | — | enable fuzzy match (threshold 0.7) |
| `fetchAccountNumber` | boolean | — | `true` to echo account number in output |

**curl**
```bash
curl --location 'https://api-preproduction.signzy.app/api/v3/bankaccountverification/bankaccountverifications' \
--header 'Content-Type: application/json' \
--header 'Authorization: <Token>' \
--data '{
  "beneficiaryAccount": "<Account Number>",
  "beneficiaryIFSC": "<IFSC code>",
  "beneficiaryMobile": "",
  "beneficiaryName": "John Doe",
  "nameFuzzy": "true",
  "nameMatchScore": "0.9"
}'
```

**Response** — `result.{active, reason, nameMatch, mobileMatch, signzyReferenceId, nameMatchScore, auditTrail{nature,value,timestamp}, bankTransfer{response,bankRRN,beneName,beneMMID,beneMobile,beneIFSC}}`

```json
// 200 success
{ "result": {
  "active": "yes", "reason": "success",
  "nameMatch": "not available", "mobileMatch": "not available",
  "signzyReferenceId": "<Reference ID>",
  "auditTrail": { "nature": "BANK RRN", "value": "334912557776", "timestamp": "2023-12-15T07:11:09.151Z" },
  "nameMatchScore": "not available",
  "bankTransfer": { "response": "Transaction Successful", "bankRRN": "334912557776", "beneName": "<BENE NAME>", "beneMMID": "", "beneMobile": "", "beneIFSC": "KKBK0008066" }
} }
```
```json
// invalid account / IFSC
{ "result": {
  "active": "no", "reason": "Either ifsc or bank account is invalid",
  "nameMatch": "not available", "mobileMatch": "not available",
  "signzyReferenceId": "q3cYlqk...", "auditTrail": { "nature": "BANK RRN", "value": "", "timestamp": "" },
  "nameMatchScore": "not available"
} }
```
> `active` is the go/no-go. `bankTransfer.beneName` is the fetched name to name-match against the applicant. `auditTrail.value` (Bank RRN) is the audit reference — store it like NAVIX stores the penny-drop `txnId`.

---

## 2. PAN 206AB Compliance  → NAVIX PAN step

Checks a PAN for Section 206AB compliance; returns masked name, PAN-Aadhaar link status, operative status, and specified-person flag.

**Endpoint:** `POST /api/v3/pan/compliance-206-individual-search`

**Request** — `panNumber` (String, ✅)

**curl**
```bash
curl --location 'https://api-preproduction.signzy.app/api/v3/pan/compliance-206-individual-search' \
--header 'Authorization: <your-access-token>' \
--header 'Content-Type: application/json' \
--data '{ "panNumber": "XXXXX0000X" }'
```

**Response** (top-level fields, not wrapped)
```json
{
  "number": "XXXXX0000X",
  "entityName": "FXXL NXXE",
  "panAllotmentDate": "DD-MM-YYYY",
  "panAadhaarLinkStatus": "linked",
  "compliant": "true",
  "isSpecified": "No",
  "currentFY": "",
  "panStatus": "operative"
}
```
> `entityName` is **masked** — good for cross-checking against the applicant's declared name but not for populating it. `panStatus:"operative"` + `panAadhaarLinkStatus:"linked"` is the healthy state. `isSpecified:"Yes"` = non-filer with TDS/TCS ≥ ₹50k (a risk flag).

---

## 3–5. DigiLocker (Create URL → Get e-Aadhaar [with XML])  → NAVIX DigiLocker KYC

A two-step consent flow, exactly mirroring NAVIX's existing DigiLocker journey (`ApplicationVerificationService.digilocker*`):
1. **Create URL** → get a consent `url` (load in an iframe) + a `requestId`.
2. User authorizes in the DigiLocker iframe; on completion Signzy **POSTs the Aadhaar detail to your `callbackUrl`** (the completion signal — like NAVIX's redirect-driven callback).
3. **Get e-Aadhaar** (or the **with-XML** variant) using `requestId` to pull the identity.

### 3. DigiLocker — Create URL
**Endpoint:** `POST /api/v3/digilocker/createUrl`

**Request** (all optional) — key fields: `signup`(bool), `callbackUrl`, `successRedirectUrl`/`Time`, `failureRedirectUrl`/`Time`, `logoVisible`+`logo`, `docType`(array, default `["PANCR","ADHAR","DRVLC"]`), `purpose`(`kyc|verification|compliance|availing_services|educational`), `getScope`(bool), `internalId`, `persistPassword`, `pinlessAuthentication`(bool, Wayne domain only), `getBase64Files`, `getEAadhaarPdf`, `getEAadhaarJpeg`. Full table in `signzy-apis.json`.

**curl**
```bash
curl --location 'https://api-preproduction.signzy.app/api/v3/digilocker/createUrl' \
--header 'Authorization: <Your access token>' \
--header 'Content-Type: application/json' \
--data-raw '{
  "signup": true,
  "callbackUrl": "https://<callbackURL>.com/",
  "docType": ["PANCR","ADHAR"],
  "purpose": "kyc",
  "getScope": true,
  "internalId": "<Internal ID>",
  "getEAadhaarPdf": true,
  "getEAadhaarJpeg": true
}'
```

**Response**
```json
{ "result": {
  "url": "https://api.digitallocker.gov.in/public/oauth2/1/authorize?client_id=3DF9D55E&...&state=652d08105aac750011ff8a71",
  "requestId": "652d08105aac750011ff8a71"
} }
```
On `consentComplete`, the `callbackUrl` receives:
```json
{ "id": "...", "event": "consentComplete", "requestId": "...", "status": "success", "timestamp": 1712583747105,
  "aadharDetail": { "name": "...", "uid": "xxxxxxxx3445", "dob": "...", "gender": "...", "address": "...", "photo": "<url>",
    "splitAddress": { "district": ["BALLIA"], "state": [["UTTAR PRADESH","UP"]], "city": ["LOOHAR"], "pincode": "221717", "country": ["IN","IND","INDIA"] },
    "x509Data": { "...": "...", "validAadhaarDSC": "yes" }, "xmlFileLink": "<url>", "aadhaarJpeg": "<url>", "aadhaarPdf": "<url>" },
  "details": { "userDetails": { "digilockerid": "...", "name": "...", "dob": "...", "gender": "M", "eaadhaar": "Y", "mobile": "UNAVAILABLE" },
    "files": [ { "doctype": "ADHAR", "issuer": "UIDAI", "mime": ["application/pdf","application/xml"] },
               { "doctype": "PANCR", "issuer": "Income Tax Department" } ] } }
```

### 4. DigiLocker — Get e-Aadhaar
**Endpoint:** `POST /api/v3/digilocker/geteaadhaar`

**Request** — `requestId`(String ✅), `extraDigitalCertificateParams`(bool → also returns `signatureData`), `getBase64Files`, `getEAadhaarPdf`, `getEAadhaarJpeg`.

**curl**
```bash
curl --location 'https://api-preproduction.signzy.app/api/v3/digilocker/geteaadhaar' \
--header 'Authorization: <Your Access Token>' \
--header 'Content-Type: application/json' \
--data '{ "requestId": "652523835a9f9000112b1ee6", "extraDigitalCertificateParams": "true", "getEAadhaarPdf": true, "getEAadhaarJpeg": true }'
```

**Response** — `result.{name, uid(first 8 masked), dob(DD/MM/YYYY), gender, x509Data{...,validAadhaarDSC}, address, photo, splitAddress{...}, signatureData(only if extraDigitalCertificateParams=true)}, aadhaarJpeg, aadhaarPdf`
```json
{ "result": {
  "name": "NAME", "uid": "xxxxxxxx0353", "dob": "DD/MM/YYYY", "gender": "FEMALE",
  "x509Data": { "subjectName": "DS NATIONAL E-GOVERNANCE DIVISION 1", "certificate": "MIIFUj...<base64 abbreviated>...==",
    "details": { "issuer": { "commonName": "SafeScrypt sub-CA for Document Signer 2022" }, "notBefore": "2023-08-22T08:21:46.000Z", "notAfter": "2026-02-11T07:57:28.000Z" },
    "validAadhaarDSC": "yes" },
  "address": "address", "photo": "https://persist.signzy.tech/...jpeg",
  "splitAddress": { "district": ["THANJAVUR"], "state": [["TAMIL NADU","TN"]], "city": ["KUMBAKONAM"], "pincode": "612001", "country": ["IN","IND","INDIA"] },
  "aadhaarJpeg": "https://persist.signzy.tech/...jpeg", "aadhaarPdf": "https://persist.signzy.tech/...pdf"
} }
```
> The `x509Data.certificate` / `signatureData.KeyInfo.X509Certificate[]` values are **long base64 blobs** (Aadhaar's document-signer certs) — abbreviated here as `MIIF...==`. `validAadhaarDSC:"yes"` is the signature-valid flag you actually gate on.

### 5. DigiLocker — Get e-Aadhaar with XML
**Endpoint:** `POST /api/v3/digilocker/geteaadhaarwithxml` — **identical to Get e-Aadhaar**, but the response additionally carries **`result.xmlFileLink`** (URL of the signed Aadhaar XML). Use this when you need the machine-verifiable signed XML (offline Aadhaar XML verification), not just the parsed fields.
```json
{ "result": { "name": "NAME", "uid": "xxxxxxxx0000", "...": "...", "xmlFileLink": "https://persist.signzy.tech/api/files/750050689/download/00000000000.xml" } }
```

---

## 6. Liveness Secure  → NAVIX SELFIE / liveness

AI **passive** liveness in an iframe (no manual selfie click, <15s, >95% accuracy) with optional **face-match** against a provided image. Two-step async like DigiLocker.

**Init endpoint:** `POST /api/v3/liveness-secure/createUrl` → returns a `token` + `videoUrl` to load in an iframe (`allow="camera"`).
**Fetch endpoint:** `POST /api/v3/liveness-secure/getData` (body `{ "token": "..." }`) — or receive results on `callbackUrl`.

**createUrl Request** — key fields: `languageCode`(hi/en/es/kn/te/gu/as/ta/pa/bn/ml/mr/or/bho/ar/mni-Mtei/ur), `matchImage`(array of public ID/face image URLs for face-match), `hideBottomLogo`, `callbackUrl`, `redirectUrl`, `accentColor`/`backgroundColor`(hex or `transparent`), `additionalChecks`(flags mask/hat/glasses; 3 attempts), `reviewImage`, `allowCameraSwitch`, `faceMatchThreshold`(0–0.99, default 0.60), `piiDeletionTTL`(e.g. `6 months`, default 6 months).

**curl (createUrl)**
```bash
curl --location 'https://api-preproduction.signzy.app/api/v3/liveness-secure/createUrl' \
--header 'Content-Type: application/json' \
--header 'Authorization: XXXXXXXXXXX' \
--data '{
  "languageCode": "en", "accentColor": "#000000",
  "matchImage": ["https://domain/hosted_image.jpg"],
  "callbackUrl": "https://callback_URL.com", "redirectUrl": "https://redirect_URL.com",
  "reviewImage": "true", "additionalChecks": "true", "allowCameraSwitch": "true",
  "faceMatchThreshold": 0.6, "piiDeletionTTL": "6 months", "backgroundColor": "transparent"
}'
```
**createUrl Response** → `{ token, consumerId, videoUrl, ...echoed config }`

**getData Response**
```json
{ "result": {
    "consumerId": "<consumer-id>", "token": "__token__", "isUsed": 1,
    "capturedImage": "https://domain/captured_image.jpg",
    "passiveLiveliness": { "liveness": true, "score": 1 },
    "faceMatch": { "verified": false, "message": "Verification completed with negative result", "matchPercentage": "0.00%" },
    "status": false,
    "additionalChecks": { "status": true, "attemptNumber": 2, "failedChecks": [], "isFaceCovered": false }
  },
  "essentials": { "matchImage": ["..."], "callbackUrl": "...", "languageCode": "hi" },
  "id": "<unique-id>" }
```
> `result.status` is the **overall** pass/fail (liveness score + face-match). `isUsed:1` means the journey completed. Parent page listens for `postMessage` data `=== 'Verification Done'`.

---

## 7. Aadhaar to UAN  → NAVIX employment lookup  ⛔ *(REQUEST API — not enabled)*

Given Aadhaar + name + DOB, returns the individual's **UAN** (EPFO Universal Account Number). Feed the UAN into **UAN Passbook (§8)** to derive salary.

**Endpoint:** `POST /api/v3/employment-verification/aadhaar-to-uan`

**Request** — `aadhaar`(✅), `name`(✅), `dob`(✅, DD/MM/YYYY).

**curl**
```bash
curl --location 'https://api-preproduction.signzy.app/api/v3/employment-verification/aadhaar-to-uan' \
--header 'Authorization: <token>' \
--header 'Content-Type: application/json' \
--data '{ "name": "John Doe", "aadhaar": "123412341234", "dob": "25/06/1999" }'
```
**Response**
```json
{ "result": { "uan": "987698769876", "dob": "25/06/1999", "memberId": "", "name": "JOHN DOE", "pan": "ABCDE1234Z", "status_code": 200, "message_code": "Success", "message": "Success", "success": true } }
```
**Status codes:** 200 OK · 400 Validation Error · 404 User Not Found · 409 · 500 Upstream down / server error.

---

## 8. UAN Passbook without OTP  → NAVIX SALARY verification

Retrieves the full **EPF passbook** for a UAN (no OTP): employer history + per-month contribution entries. **Directly usable for salary estimation** — per the docs, basic salary is typically 40–50% of total, and EPF deductions (12% of basic, or a flat ₹1,800 min) let you back out salary.

**Endpoint:** `POST /api/v3/underwriting/uan-passbook-without-otp`

**Request**

| Field | Type | Required | Description |
|---|---|---|---|
| `uan` | String | ✅ | 12-digit EPFO UAN |
| `consentFlag` | String | ✅ | `"Y"` / `"N"` |
| `callBackUrl` | String | — | webhook for the async passbook response |
| `cutOffTime` | String | — | max wait (minutes) before timeout |
| `ttl` | Number | — | cache duration (days, 0–30) to serve previously fetched data |

**curl**
```bash
curl --location 'https://api.signzy.app/api/v3/underwriting/uan-passbook-without-otp' \
--header 'Content-Type: application/json' \
--header 'Authorization: <auth-token>' \
--data '{ "uan": "100601362414", "consentFlag": "Y", "callBackUrl": "", "cutOffTime": "", "ttl": 0 }'
```
**Response** — `result.{uan,name,dateOfBirth,mobile,gender,guardianName, employers[]{establishmentName,memberId,organizationType(EXEMPTED|NON_EXEMPTED),totalEmployerShare,totalEmployeeShare,totalPensionShare,dateOfJoining,dateOfExit,servicePeriod, passbookEntries[]{status,description,employeeShare,employerShare,pensionShare,month,year,dateOfApproval}}}`
```json
{ "result": {
  "uan": "101300XXXXX", "name": "JOHN DOE", "dateOfBirth": "1990-01-01", "mobile": "987654XXXX", "gender": "MALE", "guardianName": "JANE DOE",
  "employers": [
    { "establishmentName": "ABC FINANCE LTD", "memberId": "XYZ123456789", "organizationType": "NON_EXEMPTED",
      "totalEmployerShare": 2000, "totalEmployeeShare": 7000, "totalPensionShare": 5000,
      "dateOfJoining": "2024-09-11", "servicePeriod": "5 Months",
      "passbookEntries": [ { "status": "Credited", "description": "Cont. For Due-Month 012025", "employeeShare": "1,800", "employerShare": "550", "pensionShare": "1,250", "month": "12", "year": "2024", "dateOfApproval": "2025-01-07" } ] },
    { "establishmentName": "XYZ CAPITAL LTD", "memberId": "ABC987654321", "organizationType": "EXEMPTED", "dateOfJoining": "2024-01-01", "dateOfExit": "2024-09-10", "servicePeriod": "8 Months" }
  ]
} }
```
> Current employer = the one with no `dateOfExit`. Its latest `passbookEntries[].employeeShare` (₹1,800 = the min EPF contribution) is the salary signal NAVIX's income-risk module can consume.

---

## 9. eSign Using Doc Signer  → NAVIX loan AGREEMENT e-sign  ⛔ *(REQUEST API — not enabled)*

Applies a **Document Signer Certificate (Class 2 / Class 3)** digital signature to a PDF at configurable positions, with optional **eStamp / eChallan**. Returns the signed PDF. Useful for NAVIX's sanction-letter / loan-agreement generation → S3 (a 🔴 go-live item in `CLAUDE.md` §13).

**Endpoint:** `POST /api/v3/esign/docSigner`

**Request** — `pdf`(URL or base64, ✅), `signatureOptions`(`docSignerClass2|docSignerClass3`, ✅), `certificateId`(✅), `signatures[]`(✅: `pageNo[]` numbers or `"ALL"`/`"LAST"`, `signaturePosition[]` `TOPLEFT..BOTTOMRIGHT|CUSTOMIZE`, `xCoordinate[]`/`yCoordinate[]`, `height`≥70, `width`≥130), `logoUrl`(✅), `fileTtl`(✅, e.g. `"2 days"`), `reason`/`location`(Class 3 only), `estamp`(optional — `type`, `stampDetails[]{stateCode,articleCode,stampDutyValue,purposeOfStampDuty,count}`, `firstPartyName`, `secondPartyName`, `stampDutyPaidBy`, `considerationPrice`). Full request sample in `signzy-apis.json`.

**Response**
```json
{ "pdf": "", "signatureOptions": "", "signedPdf": "" }
```
```json
// 400 error shape
{ "name": "error", "message": "docSignerClass3CertificateValues is not allowed", "reason": "VALIDATION_ERROR", "type": "Bad Request", "statusCode": 400 }
```
**Error codes:** 400 Bad Request · 401 Authorization Failed · 409 Upstream Error · 500 Internal Server Error.

---

## 10. Experian Lite Bureau Report  → NAVIX BUREAU (Experian)

Full Experian credit report keyed on phone + name (+ PAN, optional DOB/pincode). **Crucially, the payload is the same `jsonExperianReport` structure NAVIX already parses** from Fintrix's `individual_experian` (`samplepan.json` shape) — `Current_Application`, `CAIS_Account`/`CAIS_Summary`, `TotalCAPS_Summary`, and `SCORE.FCIREXScore`.

**Endpoint:** `POST /api/v3/bureau/experian-lite`

**Two lookup methods:** (1) phone + firstName + lastName + PAN(optional); (2) phone + firstName + lastName + PAN + DOB + pincode.

**Request**

| Field | Type | Required | Description |
|---|---|---|---|
| `phoneNumber` | Number | ✅ | phone number |
| `pan` | String | — | PAN (part of Method 2) |
| `firstName` | String | ✅ | first name |
| `lastName` | String | ✅ | last name |
| `dateOfBirth` | Number | — | `yyyy-mm-dd` |
| `pincode` | Number | — | pincode |
| `alternateFlow` | String | — | `"masked-Mobile-Flow"` → use `consentMessageId:"CM_2"`, masks phone digits, no excel report |
| `consent` | Object | ✅ | `{consentFlag, consentTimestamp, consentIpAddress, consentMessageId}` (`CM_1` default) |

**curl**
```bash
curl --location 'https://api-preproduction.signzy.app/api/v3/bureau/experian-lite' \
--header 'Content-Type: application/json' \
--header 'Authorization: <Auth Token>' \
--data '{
  "phoneNumber": 1234567890, "pan": "ABCDE4289A",
  "firstName": "John", "lastName": "Doe",
  "dateOfBirth": "2024-04-01", "pincode": 560026,
  "alternateFlow": "masked-Mobile-Flow",
  "consent": { "consentFlag": true, "consentTimestamp": "<ts>", "consentIpAddress": "<ip>", "consentMessageId": "CM_1" }
}'
```
**Response** (top-level structure; the full report has CAIS account details, CAPS enquiries, etc.)
```json
{ "statusCode": 200, "message": "SUCCESS", "data": {
  "jsonExperianReport": {
    "Header": { "SystemCode": 0, "ReportDate": 20240404, "ReportTime": 122247 },
    "UserMessage": { "UserMessageText": "Normal Response" },
    "CreditProfileHeader": { "Version": "V2.4", "ReportNumber": 1712213567495 },
    "Current_Application": { "Current_Application_Details": { "Enquiry_Reason": 99, "Finance_Purpose": 99, "Amount_Financed": 0 } },
    "CAIS_Account": { "...": "CAIS_Summary + CAIS_Account_DETAILS[]" },
    "TotalCAPS_Summary": { "...": "CAPS enquiry counts" },
    "SCORE": { "FCIREXScore": 611, "FCIREXScoreConfidLevel": "" }
  },
  "excelExperianReport": "https://preproduction-persist.signzy.tech/.../report.xlsx"
} }
```
> `SCORE.FCIREXScore` (here 611) is the bureau score NAVIX stores as `bureau_score`. The report's `Current_Application_Details`, `CAIS_*`, and `CAPS` sections feed NAVIX's `BureauReportFacts` / credit-brief exactly as the Fintrix Experian response does. `excelExperianReport` is absent when `alternateFlow="masked-Mobile-Flow"`.

---

## 11. CRIF Bureau Report  → NAVIX BUREAU fallback (CRIF)

Full CRIF High Mark credit report; **all identity fields mandatory**. Maps to NAVIX's existing `CrifClient` (the Experian→CRIF fallback in `BureauService`).

**Endpoint:** `POST /api/v3/bureau/crif`

**Request** — `phoneNumber`(✅), `pan`(✅), `firstName`(✅, max 30 chars), `lastName`(✅), `dateOfBirth`(✅, `yyyy-mm-dd`), `gender`(✅, `Male|Female`), `address`(✅), `pincode`(✅), `consent`(✅, `{consentFlag,consentTimestamp,consentIpAddress,consentMessageId:"CM_1"}`).

**curl**
```bash
curl --location 'https://api-preproduction.signzy.app/api/v3/bureau/crif' \
--header 'Content-Type: application/json' \
--header 'Authorization: <kong auth>' \
--data '{
  "phoneNumber": "phone number", "pan": "pan number",
  "firstName": "first name", "lastName": "last name",
  "dateOfBirth": "yyyy-mm-dd", "pincode": "pin code",
  "gender": "Male/Female", "address": "coimbatore",
  "consent": { "consentFlag": true, "consentTimestamp": 1690215946415, "consentIpAddress": "192.168.0.1", "consentMessageId": "CM_1" }
}'
```
**Response** (top-level structure)
```json
{ "statusCode": 200, "message": "success", "data": {
  "crifReport": { "INDV-REPORT-FILE": { "INDV-REPORTS": [ { "INDV-REPORT": {
    "PERSONAL-INFO-VARIATION": {
      "NAME-VARIATIONS": [ { "VALUE": "XXXXX X", "REPORTED-DATE": "15-12-2024" } ],
      "ADDRESS-VARIATIONS": [ { "VALUE": "XXX XXXXX STREET XXXXX COIMBATORE 6410XX TN", "REPORTED-DATE": "31-05-2026" } ]
    },
    "SCORES": [ { "SCORE-VALUE": "690", "SCORE-FACTORS": "SF02|SF08|SF11|SF33|" } ],
    "INQUIRY-HISTORY": [], "RESPONSES": []
  } } ] } }
} }
```
> `SCORES[].SCORE-VALUE` (here 690) is the CRIF score. Note the CRIF report shape (`INDV-REPORT-FILE`) is **completely different** from Experian's (`jsonExperianReport`) — a Signzy adapter would need two response parsers, exactly as NAVIX keeps `ExperianClient` and `CrifClient` separate.

---

## Notes & caveats

- **Two APIs need enabling** — `aadhaar-to-uan` and `esign-using-doc-signer` show "REQUEST API" (request access from Signzy before use). The other nine are live.
- **Consent is first-class** for the bureau APIs (Experian/CRIF require a `consent` object with an audit-grade timestamp + IP + message id) — capture and store these for compliance.
- **PII in responses:** Aadhaar UID is masked (first 8 digits), Experian masks phone in masked-Mobile-Flow, and files are served as **short-lived `persist.signzy.tech` URLs** (`piiDeletionTTL`) — download/store promptly, like NAVIX does for S3 documents.
- **All samples above were captured from the portal's own docs pages** (test/placeholder data), not from live calls.
