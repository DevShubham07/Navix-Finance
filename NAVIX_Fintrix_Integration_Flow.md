# DhanBoost × Fintrix — API Integration Flow

> How the Fintrix (Digitap-backed) verification APIs map onto the DhanBoost borrower journey.
> **Base URL:** `https://admin.fintrix.tech/__api/api/v1/`
> **Auth (these APIs):** `Authorization: Basic <base64(client_id:client_secret)>` · `Content-Type: application/json`
> **Auth (DigiLocker APIs):** `X-Client-ID` + `X-Client-Secret` (different scheme — see `Digilocker_API_Guide.md`)
> All response shapes below are from **real sandbox responses** (2026-06-19).

> **⚠ Status (2026-08-22): most of this doc is historical.** The multi-API Fintrix integration
> described below (PAN, email, address, Experian, penny-drop, DigiLocker, selfie liveness) was
> **removed** — those capabilities are now served by Signzy (primary) / Digitap (fallback) via
> `RoutingVerificationPort`, see the root `CLAUDE.md` §14. **Only the bureau leg survives, and it
> came back as a single, different endpoint:** `POST /crif_combine` — CRIF Highmark, not Experian,
> and not the guessed `individual_crif`. Fintrix is now the bureau **primary** (Digitap Credit
> Analytics is the fallback; Signzy no longer does bureau at all). Section 3.5 below is rewritten
> for the real, verified `crif_combine` contract; every other section (1, 2, 3.1–3.4, 3.6–3.8, 4, 5)
> documents the **retired** integration, kept here only so the code's history makes sense — do not
> treat it as current. The live client is `FintrixCrifClient`
> (`backend/navix-verification/src/main/java/com/navix/verification/client/FintrixCrifClient.java`).

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
| 5 | `crif_combine` *(CRIF Highmark)* — see §3.5 | Credit bureau — score + tradelines + enquiries | ✅ **now primary** (retired rows above are gone; only this survives) |
| 6 | `verification_pennydrop` | Bank account verify + **name-at-bank** + IFSC/bank details | ✅ payout gate |
| 7 | UAN API *(to be provided)* | Employment + salary basis (→ 25% limit, salary date) | ⏳ reserved |
| 8 | `vkyc_face_liveness` | Selfie liveness | ⛔ not called (selfie captured for records only) |
| + | DigiLocker (5 calls) | Aadhaar XML + PAN doc fetch | ✅ KYC docs |

> ~~The exact CRIF endpoint path needs confirming (response sample was provided; path likely `individual_crif` or similar).~~ **Resolved 2026-08-22 — see §3.5:** the real path is `crif_combine`, and it is CRIF Highmark. Everything else in this table describes the retired integration (see the status note above).

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

### 3.5 CRIF — `crif_combine` — bureau (now PRIMARY) ✅ **live, verified 2026-08-22**

This supersedes the guess below the old table (`individual_crif`) — the real, confirmed endpoint
is **`crif_combine`**, and it is CRIF Highmark, wired as the bureau **primary** (Experian/Digitap
Credit Analytics is now the fallback, reached via Digitap directly — Signzy's bureau legs are
retired). Implemented in `FintrixCrifClient` / parsed by `CrifHighmarkFactsParser`
(`backend/navix-verification/src/main/java/com/navix/verification/{client,support}/`).

```
POST /crif_combine
Authorization: Basic <base64(client_id:client_secret)>
Content-Type: application/json

{ "name": "...", "mobile": "9000000001", "remark": "<client ref>", "consent": "yes" }
```

**No PAN input** — Fintrix decides identity from name + mobile alone and hands back a PAN/DOB in
the report body for the caller to cross-check against the already-verified KYC identity (that
cross-check lives in `ApplicationVerificationService`, not the client).

**Response envelope:**
```
{
  "success": true,
  "canonical": {
    "timestamp": "...", "transaction_id": "...", "status": "success",
    "data": {
      "name": "...", "mobile": "...",
      "credit_report": { "HEADER": {...}, "REQUEST": {...}, "PERSONAL-INFO-VARIATION": {...},
        "ACCOUNTS-SUMMARY": {...}, "RESPONSES": { "RESPONSE": [ {...} ] },
        "INQUIRY-HISTORY": { "HISTORY": [ {...} ] }, "TRENDS": {...}, "SCORES": { "SCORE": {...} } },
      "credit_report_link": "https://.../report.pdf?...&amp;..."
    }
  },
  "is_sandbox": false, "request_id": "...", "transaction_id": "..."
}
```

The report is **CRIF Highmark**-shaped, not Experian — `HEADER` / `REQUEST` /
`PERSONAL-INFO-VARIATION` / `ACCOUNTS-SUMMARY` (`PRIMARY-ACCOUNTS-SUMMARY` /
`SECONDARY-ACCOUNTS-SUMMARY` / `DERIVED-ATTRIBUTES`) / `RESPONSES.RESPONSE[].LOAN-DETAILS`
(the tradelines) / `INQUIRY-HISTORY.HISTORY[]` (enquiries) / `TRENDS` (pipe-delimited score
history) / `SCORES.SCORE.SCORE-VALUE`. The score is a **numeric string** at
`credit_report.SCORES.SCORE.SCORE-VALUE`; the report id is
`credit_report.HEADER.REPORT-ID`.

**Five traps, all handled in the live parser/client — don't relearn them the hard way:**

1. **`credit_report_link` is a presigned URL, valid ~1 hour.** It is ingested to S3 as a staff-only
   `BUREAU_REPORT` document immediately after the pull — a delayed read would 403. The link travels
   `FintrixDtos.CrifResponse.creditReportLink()` → `BureauCheck.reportUrl` (a provider-neutral field,
   so the vendor envelope never reaches the loan classpath) →
   `ApplicationVerificationService.storeAndScrubBureauReport`. Ingest is best-effort: a failure is
   logged and the pull still stands on its parsed facts. Afterwards the link is **stripped** from the
   stored envelope (replaced with `"[ingested]"`) — `rawResponseJson` is persisted to
   `application_verification.raw_response` and `provider_api_execution`, and the staff provider-report
   pane and the credit-brief PDF appendix flatten every leaf, so an un-stripped signed URL would be
   printed into a PDF.
2. **The link's query separators are HTML-escaped (`&amp;` not `&`)** — the vendor emits it
   escaped even though this is a JSON API. Unescape before making the GET or every param after the
   first is dropped.
3. **`ACCOUNTS-SUMMARY`'s own balance aggregates are unreliable.** A real production response
   carried `PRIMARY-CURRENT-BALANCE = "0"` while its tradelines (`RESPONSES.RESPONSE[].
   LOAN-DETAILS.CURRENT-BAL`) summed to roughly ₹8–9L. Total/secured/unsecured balances must be
   **summed from the tradelines** (split on `SECURITY-STATUS`), never read off the summary node.
4. **Money fields are Indian-grouped strings** — `"10,00,000"`, `"1,12,685"`, occasionally with a
   sub-paise decimal tail (e.g. an `OBLIGATION` value like `"21001.861309715707"`) — not plain
   numbers. Strip the commas and truncate the decimal (display-only precision) before parsing.
5. **There is no sandbox.** `is_sandbox` comes back `false` on every call; every pull is live and
   billable. Offline/demo work goes through `NAVIX_BUREAU_FIXTURE` instead, which serves a bundled,
   redacted `crif_combine` capture (`docs/fintrix/crif-combine-sample.json`) rather than hitting the
   vendor.

Also note CRIF's array-vs-bare-object inconsistency: a sub-section (e.g. `ADDRESS-VARIATIONS.
VARIATION`, `RESPONSES.RESPONSE`) arrives as a bare JSON object instead of a one-element array
whenever there is exactly one entry — the parser tolerates both.

**Use for:** same risk inputs Experian used to supply — score, active/overdue accounts, total
exposure, recent enquiries — mapped into the same bureau-agnostic `BureauReportFacts` shape
Experian/Digitap fill, so A/B/C/D and the credit-brief PDF stay provider-agnostic.

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

> Items 1, 3, 4 describe the retired multi-API integration (see the status note above) and are
> stale. Item 2 is **resolved** — see §3.5.

1. ~~**UAN API**~~ — moot; Signzy/Digitap now cover employment/UAN (see `CLAUDE.md` §14,
   `verifyEmployment`).
2. ~~**CRIF endpoint path** + request schema (we have the response sample, need the call).~~
   **Resolved:** `POST /crif_combine`, body `{name, mobile, remark, consent}` — see §3.5.
3. ~~Penny-drop **name-match**~~ — moot; penny-drop is now Signzy-only (`CLAUDE.md` §14).
4. ~~Experian/CRIF: confirm production score range...~~ — moot; bureau routing is now
   Fintrix (`crif_combine`) → Digitap Credit Analytics, no Experian leg.
