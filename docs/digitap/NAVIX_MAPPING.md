# Digitap → NAVIX integration map

*How each Digitap API (see [`DigitapGuide.md`](./DigitapGuide.md)) would plug into NAVIX's existing verification
code. The design goal is identical to the Signzy analysis: **Digitap slots in behind the same provider-neutral
seam NAVIX already uses for Fintrix**, so business logic and onboarding services don't change. Where the two
providers differ, this doc calls it out (Digitap = HTTP Basic + two hosts + the standard `result_code` envelope;
no bank penny-drop and no direct-salary API in the package).*

## The seam Digitap would plug into

NAVIX's verification flows through three provider-neutral layers above the client (unchanged from the Signzy write-up):

```
ApplicationVerificationService (navix-loan)        ← onboarding steps; unchanged
        │  calls
VerificationPort (navix-common)                    ← provider-neutral interface + neutral result records
        │  implemented by
VerificationAdapter (navix-verification)           ← the ONLY place provider DTOs → neutral records
        │  calls
PennyDropClient / ExperianClient / CrifClient …    ← per-provider HTTP clients (Fintrix today)
        │  configured by
FintrixClientConfig + FintrixProperties            ← named RestClient beans + @ConfigurationProperties
```

Key files (same as `docs/signzy/NAVIX_MAPPING.md`):
- `backend/navix-common/src/main/java/com/navix/common/verification/VerificationPort.java` — the interface + neutral records (`PennyDropCheck`, …). *No provider DTO ever crosses onto the loan classpath.*
- `backend/navix-verification/src/main/java/com/navix/verification/service/VerificationAdapter.java` — maps provider DTOs → neutral records.
- `backend/navix-verification/src/main/java/com/navix/verification/client/` — `PennyDropClient`, `ExperianClient`, `CrifClient`, `PanComprehensiveClient`, `AddressVerificationClient`, `EmailVerificationClient`, `FaceLivenessClient`, `DigiLockerClient`.
- `backend/navix-verification/src/main/java/com/navix/verification/config/FintrixClientConfig.java` — factory for the named `RestClient` beans.
- `backend/navix-verification/src/main/java/com/navix/verification/support/FintrixJson.java` — shared POST/parse helper (fails closed, PII-safe).
- `backend/navix-verification/src/main/java/com/navix/verification/dto/FintrixDtos.java` — Fintrix request/response records.
- `backend/navix-loan/src/main/java/com/navix/loan/service/ApplicationVerificationService.java` — the 9 onboarding steps.

### What a Digitap integration adds (and what it does NOT touch)

**Add (navix-verification only):**
1. `DigitapProperties` — `@ConfigurationProperties(prefix = "navix.digitap")` with **two base URLs** (`svcBaseUrl` = `https://svc.digitap.ai`, `apiBaseUrl` = `https://api.digitap.ai`) plus `clientId` + `clientSecret`. Digitap uses **HTTP Basic** (`client_id:client_secret`), like Fintrix — *not* Signzy's single opaque token.
2. **Two** `RestClient` beans (mirror `FintrixClientConfig`): `digitapSvcRestClient` (base `svcBaseUrl`) and `digitapApiRestClient` (base `apiBaseUrl`), both with a default header `Authorization: Basic base64(clientId:clientSecret)`. Same 5s/30s timeouts. (Fintrix already builds a Basic header the same way — reuse that helper.)
3. Per-API clients (`@Component`, `@Qualifier`): e.g. `DigitapPanClient`, `DigitapForm206AbClient`, `DigitapEmploymentClient`, `DigitapPassbookClient`, `DigitapCreditClient`, `DigitapEmailClient`, `DigitapFaceMatchClient`, `DigitapOcrClient`. Reuse a `DigitapJson` helper cloned from `FintrixJson`; unwrap the standard `{ http_response_code, result_code, result }` envelope and treat `result_code == 101` as success.
4. `DigitapDtos.java` — request/response records with `@JsonProperty` wire names (snake_case: `client_ref_num`, `pan_operative_status`, `specified_person`, `cr_ee_share`, `BureauScore`, …).
5. In `VerificationAdapter`, choose the provider (config flag or `@Qualifier` swap) and map Digitap DTOs → the **existing** neutral records.

**Do NOT touch:** `VerificationPort`, `ApplicationVerificationService`, controllers, DTOs in `navix-loan`, or any business logic. A provider swap stays localized to navix-verification, exactly like Fintrix/Signzy.

> Switchability: extend `navix.verification.provider = fintrix|signzy|digitap` and have `VerificationAdapter` (or a factory) pick the client set. Keep Fintrix default; put Digitap behind the flag. Because it's per-capability, you can even **mix** — e.g. Digitap for PAN + bureau, Fintrix for penny-drop.

---

## Per-API mapping

| Digitap API | Endpoint (host) | NAVIX seam / neutral record | Notes |
|---|---|---|---|
| **PAN Details Plus** | `POST svc /validation/kyc/v1/pan_details_plus` | PAN step (`PanComprehensiveClient` slot) → can populate `CustomerProfile` name/dob/gender/address | Richer than Signzy's masked-name 206AB: **unmasked `fullname`** + address + `aadhaar_linked` + `is_salaried`. Best default PAN client. |
| **PAN 206AB Compliance Status** | `POST svc /validation/kyc/v1/form206ab_compliance_status` | PAN compliance gate | Analogue of Signzy 206AB: `specified_person` ↔ `isSpecified`, `pan_operative_status` ↔ `panStatus`. Pass/fail risk flag. |
| PAN Basic V1/V2, PAN Details/BC, PAN to Name/F'Name, PAN Profile, ITR | `POST svc /validation/kyc/v1/*` | PAN step (lighter variants) / income cross-check (`itr_basic`) | Choose one PAN client; the rest are narrower. `itr_basic` cross-checks declared income. |
| **PAN Aadhaar Link** | `POST svc /validation/kyc/v1/pan_aadhaar_link` | PAN↔Aadhaar link gate | `code` `LINK-001`=linked. Cheap gate (also covered by Details Plus `aadhaar_linked`). |
| PAN↔Aadhaar mapping (`pan_to_masked_aadhaar`, `aadhaar_to_masked_pan`, `aadhaar_to_unmasked_pan`) | `POST svc /validation/kyc/v1/*` | Aadhaar KYC assist | Bridge PAN↔Aadhaar when only one is known. |
| **Driving Licence [Plus]** | `POST svc /validation/kyc/v1/dl` · `/dl_plus` | Identity / address fallback; `dl_plus.user_image` → SELFIE face-match reference | Use when Aadhaar/DigiLocker unavailable. `dl_plus` gives address + photo. |
| VoterID, Passport, UDID | `POST svc /validation/kyc/v1/{voter,passport,kyc_udid_verification}` | Ancillary identity | Secondary ID checks; not on the core NAVIX path. |
| **UAN Employment (Basic/Advanced V2–V5)** | `POST svc /cv/vN/uan_{basic,advanced}/sync` | **New** employment-verification step (feeds income-risk context) | V4 Advanced = best coverage (lookup by PAN/mobile/UAN/name; `employment_history[]` + `epfo_details`). Current employer = entry with empty `date_of_exit`. V1 variants deprecated. |
| EPFO Employee Search | `POST svc /cv/v1/employee_name_search/{request,status}` | UAN discovery by name (async) | Two-step init→poll; feeds the UAN into the employment/passbook calls. |
| **TDS Quarterly** | `POST svc /cv/v1/tds_quarterly` | SALARY signal (salaried vs not) | `return_type: Salary/Non-Salary` + aggregate establishment PF-filing figures (company-wide). |
| **EPFO Passbook Extraction** | `POST svc /cv/uan_passbook/get_otp` + `/get_passbook` | **SALARY / income-risk** (`navix-income-risk`) | OTP-gated. Monthly `cr_ee_share` EPF contribution (min ₹1,800) → back out basic → salary → `eligibleLimitPaise` (25% of salary). Digitap's OTP counterpart to Signzy's *UAN Passbook without OTP*. |
| **Credit Analytics Request** | `POST api /credit_analytics/request` | `VerificationPort` bureau path → `ExperianClient` / `BureauReportFacts` / `CreditRatingCalculator` | **Highest reuse:** `result.result_json.INProfileResponse` is Experian's shape NAVIX already parses. `SCORE.BureauScore`→`bureau_score`; `Current_Application`/`CAIS_*`/`CAPS`→`BureauReportFacts`→credit brief (§14). OTP-based consent. |
| **Masked Mobile Report** | `POST api /credit_analytics/masked_mobile_report` | Bureau retry path | Call with the same `request_id` when the primary returns `result_code 102` (masked candidate mobiles). |
| **Face Match** | `POST api /fmfl/v2/face-match` | SELFIE step (`FaceLivenessClient` slot) | `same_face_confidence` (0–1) + `is_same_face`. Feed DigiLocker/DL/OCR photo as `card`, SDK selfie as `person`. |
| **FL Web SDK** | client-side JS SDK | SELFIE capture front-end (produces the `person` image) | Digitap's counterpart to Signzy *Liveness Secure*. Pair with Face Match for liveness + match. |
| **Email Verification** | `POST svc /cv/email_verification/v1` | EMAIL onboarding step (`EmailVerificationClient` slot) | Format + domain + SMTP/MX + WHOIS + name-match. Upgrades NAVIX's cosmetic email check. |
| **Location Services** (address↔lat/long, distance) | `POST api /ent/v1/{address-to-lat-long,address-verification,get-distance}` | ADDRESS step / geocoding | Geocode the declared address; distance-from-branch serviceability check. |
| **KYC-OCR** (aadhaar/voter/passport) | `POST api /ocr/v1/{aadhaar,voter,passport}` | Document OCR to pre-fill KYC fields | Base64/URL image → structured fields with Aadhaar masking + `fraudCheck`. |

---

## Auth & host differences to plan for

| | Fintrix (today) | Signzy | **Digitap** |
|---|---|---|---|
| Verification auth | `Authorization: Basic base64(clientId:clientSecret)` | `Authorization: <opaque token>` | **`Authorization: Basic base64(client_id:client_secret)`** (same style as Fintrix) |
| Hosts | one (`admin.fintrix.tech/__api/api/v1/`) | one (`api.signzy.app`) | **two** — `svc.digitap.ai` (KYC/Employment/Email) + `api.digitap.ai` (Credit/Location/Face-Match/OCR) |
| Envelope | provider-specific | `result:{}` / `{data:{}}` | `{ http_response_code, result_code(101 ok), request_id, client_ref_num, result:{} }` (OCR/Face-Match: `{status,statusCode,result}`) |
| Config props | `navix.fintrix.{base-url,client-id,client-secret}` | `navix.signzy.{base-url,token}` | `navix.digitap.{svc-base-url,api-base-url,client-id,client-secret}` |
| UAT | — | `api-preproduction.signzy.app` | `svcdemo.digitap.work` / `apidemo.digitap.work` (**separate client_id/secret**) |
| Secrets | SSM SecureString `/navix/<env>/…` | `NAVIX_SIGNZY_TOKEN` | `NAVIX_DIGITAP_CLIENT_ID` / `NAVIX_DIGITAP_CLIENT_SECRET` (never commit) |

## What Digitap covers vs the gaps

**Strong fits (map cleanly onto existing NAVIX seams):**
1. **Credit Analytics → BUREAU** — Experian `INProfileResponse` shape already parsed by NAVIX (biggest reuse, like Signzy Experian Lite).
2. **PAN Details Plus / 206AB → PAN** — richer than Signzy (unmasked name + employment flags).
3. **Face Match + FL Web SDK → SELFIE** — the capture-SDK + match-API pair NAVIX's liveness step needs.
4. **Email Verification → EMAIL**, **Location → ADDRESS** — direct upgrades to two cosmetic onboarding steps.
5. **EPFO Passbook / TDS Quarterly / UAN Advanced → SALARY & employment** — real EPF-based salary proxy + employment verification.

**Gaps in this package (need a different Digitap suite or stay on Fintrix):**
- **Bank penny-drop** — not in the hand-off. Keep Fintrix `PennyDropClient` (or request Digitap's bank-verification suite).
- **Direct salary/CTC** — no such API here; salary is inferred from EPF contributions (Passbook) + TDS `return_type`, not read directly.
- **DigiLocker Aadhaar consent flow** — this package has PAN↔Aadhaar *mapping/masked* endpoints and OCR, but not a DigiLocker consent-URL flow like Signzy's `createUrl`; NAVIX's existing DigiLocker journey stays.

## Suggested rollout order (by reuse / value)

1. **Credit Analytics Request** — biggest win; Experian shape already parsed by NAVIX.
2. **PAN Details Plus + 206AB** — clean PAN gate with richer profile fields.
3. **Face Match + FL Web SDK** — completes the SELFIE/liveness step.
4. **EPFO Passbook + TDS Quarterly + UAN Advanced** — real salary/employment signal for income-risk.
5. **Email Verification + Location** — upgrade the EMAIL/ADDRESS onboarding steps.
6. **KYC-OCR / secondary ID (Voter/Passport/DL)** — document pre-fill + fallback identity.

*See [`digitap-apis.json`](./digitap-apis.json) for the exact fields/samples to build the DTOs from (43 APIs, each with its own `host`).*
