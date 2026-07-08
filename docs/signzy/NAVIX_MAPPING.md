# Signzy → NAVIX integration map

*How each Signzy API (see [`SignzyGuide.md`](./SignzyGuide.md)) would plug into NAVIX's existing verification
code. The design goal: **Signzy slots in behind the same provider-neutral seam NAVIX already uses for Fintrix**,
so business logic and onboarding services don't change.*

## The seam Signzy would plug into

NAVIX's bank/identity verification flows through three layers today (all provider-neutral above the client):

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

Key files:
- `backend/navix-common/src/main/java/com/navix/common/verification/VerificationPort.java` — the interface + neutral records (`PennyDropCheck`, …). *No provider DTO ever crosses onto the loan classpath.*
- `backend/navix-verification/src/main/java/com/navix/verification/service/VerificationAdapter.java` — maps provider DTOs → neutral records.
- `backend/navix-verification/src/main/java/com/navix/verification/client/PennyDropClient.java` (+ `ExperianClient`, `CrifClient`, `PanComprehensiveClient`, `AddressVerificationClient`, `EmailVerificationClient`, `FaceLivenessClient`, `DigiLockerClient`).
- `backend/navix-verification/src/main/java/com/navix/verification/config/FintrixClientConfig.java` — factory for the named `RestClient` beans (`fintrixRestClient` with `Authorization: Basic base64(id:secret)`; `digiLockerRestClient` with `X-Client-ID`/`X-Client-Secret`).
- `backend/navix-verification/src/main/java/com/navix/verification/support/FintrixJson.java` — shared POST/parse helper (fails closed to `VerificationException`, PII-safe, null-tolerant extractors).
- `backend/navix-verification/src/main/java/com/navix/verification/dto/FintrixDtos.java` — all Fintrix request/response records.
- `backend/navix-loan/src/main/java/com/navix/loan/service/ApplicationVerificationService.java` — the 9 onboarding steps (`PENNY_DROP`, `PAN`, `DigiLocker`, `BUREAU`, `SALARY`, `SELFIE`, …).

### What a Signzy integration adds (and what it does NOT touch)

**Add (navix-verification only):**
1. `SignzyProperties` — `@ConfigurationProperties(prefix = "navix.signzy")` with `baseUrl`, `token` (Signzy uses a **single opaque `Authorization` token**, unlike Fintrix's `clientId`+`clientSecret` Basic auth).
2. A `signzyRestClient` bean (mirror `FintrixClientConfig`): `baseUrl(props.baseUrl())` + a default header `Authorization: <token>` (raw, **not** `Bearer`). Same 5s/30s timeouts.
3. Per-API clients (`@Component`, `@Qualifier("signzyRestClient")`): e.g. `SignzyBankVerificationClient`, `SignzyPanClient`, `SignzyDigiLockerClient`, `SignzyBureauClient`, `SignzyUanPassbookClient`, `SignzyLivenessClient`. Reuse a `SignzyJson` helper cloned from `FintrixJson`.
4. `SignzyDtos.java` — request/response records with `@JsonProperty` wire names (see per-API mapping below).
5. In `VerificationAdapter`, choose the provider (config flag or a `@Primary`/`@Qualifier` swap) and map Signzy DTOs → the **existing** neutral records.

**Do NOT touch:** `VerificationPort` (neutral records already fit), `ApplicationVerificationService`, controllers, DTOs in `navix-loan`, or any business logic. A provider swap stays localized to navix-verification, exactly like the Fintrix wiring.

> A clean way to make it switchable: add `navix.verification.provider = fintrix|signzy` and have `VerificationAdapter` (or a small factory) pick the client set. Keeps Fintrix as the default and Signzy behind a flag.

---

## Per-API mapping

| Signzy API | Endpoint | NAVIX seam / neutral record | Notes |
|---|---|---|---|
| **Hybrid Bank Account Verification** | `POST /api/v3/bankaccountverification/bankaccountverifications` | `VerificationPort.pennyDrop(account, ifsc, ref)` → `PennyDropCheck(txnId, accountExists, fullName, bank, ifsc)` | Direct 1:1 replacement for `PennyDropClient`. Map `active=="yes"`→`accountExists`; `bankTransfer.beneName`→`fullName`; `beneIFSC`→`ifsc`; `signzyReferenceId` (or `auditTrail.value` RRN)→`txnId`. Signzy can also compute name-match server-side (`nameMatchScore`/`nameFuzzy`) — NAVIX currently does its own `nameSimilarity`, so either keep NAVIX's or trust Signzy's. |
| **PAN 206AB Compliance** | `POST /api/v3/pan/compliance-206-individual-search` | PAN verification step (`PanComprehensiveClient` slot) | Lighter than Fintrix PAN-comprehensive: gives masked name + `panStatus`(operative) + `panAadhaarLinkStatus`(linked) + `isSpecified`. Good for a pass/fail PAN gate; `entityName` is masked so can't populate the profile name. |
| **DigiLocker Create URL** | `POST /api/v3/digilocker/createUrl` | `ApplicationVerificationService.digilockerInit` / `digilockerStatus` / `digilockerComplete` | Same init→iframe→callback pattern NAVIX already runs (see `CLAUDE.md` §14 DigiLocker gotchas). `requestId` is the correlation key; **`callbackUrl` completion is the source of truth** (mirrors NAVIX making its own DB authoritative). Make `redirect_url`/callback unique per attempt (NAVIX already appends `?app=<id>&sid=<nonce>`). |
| **DigiLocker Get e-Aadhaar** | `POST /api/v3/digilocker/geteaadhaar` | DigiLocker fetch → populates `CustomerProfile` name/dob/gender/address (+ `aadhaar_verified`, V29) | `uid` first-8 masked; `validAadhaarDSC=="yes"` is the signature-valid gate; `splitAddress` maps to NAVIX address fields; `photo` can seed the selfie face-match reference. |
| **DigiLocker Get e-Aadhaar with XML** | `POST /api/v3/digilocker/geteaadhaarwithxml` | Same as above + store `xmlFileLink` (offline Aadhaar XML) to S3 | Use when you want the signed XML for verifiable KYC evidence, not just parsed fields. |
| **Liveness Secure** | `POST /api/v3/liveness-secure/createUrl` + `/getData` | SELFIE step (`FaceLivenessClient` slot) | Passive liveness + optional face-match against the DigiLocker `photo` (pass as `matchImage`). `result.status` = overall pass; `passiveLiveliness.score` + `faceMatch.matchPercentage` for detail. Two-step async — reuse the DigiLocker callback/poll plumbing. |
| **Aadhaar to UAN** ⛔ | `POST /api/v3/employment-verification/aadhaar-to-uan` | New employment lookup (feeds SALARY step) | Not enabled on the account yet. Chain: Aadhaar → UAN → UAN Passbook. |
| **UAN Passbook without OTP** | `POST /api/v3/underwriting/uan-passbook-without-otp` | SALARY / income-risk (`navix-income-risk`) | The strongest salary signal — EPF contributions (`employeeShare`, min ₹1,800) → back out basic → salary → drives `eligibleLimitPaise` (25% of salary). Current employer = the record with no `dateOfExit`. |
| **eSign Using Doc Signer** ⛔ | `POST /api/v3/esign/docSigner` | Loan AGREEMENT / sanction-letter e-sign (🔴 go-live item, `CLAUDE.md` §13) | Not enabled yet. Class 2/3 doc-signer + optional eStamp/eChallan; returns signed PDF → store to S3 (`navix-storage`). |
| **Experian Lite Bureau Report** | `POST /api/v3/bureau/experian-lite` | `VerificationPort` bureau path → `ExperianClient` / `BureauReportFacts` / `CreditRatingCalculator` | **Highest reuse:** `data.jsonExperianReport` is the **same shape** NAVIX already parses from Fintrix `individual_experian` (`samplepan.json`). `SCORE.FCIREXScore`→`bureau_score`; `Current_Application`/`CAIS_*`/`CAPS`→`BureauReportFacts`→credit brief (§14). A Signzy Experian client could largely reuse the existing Experian parser (just unwrap `data.jsonExperianReport`). |
| **CRIF Bureau Report** | `POST /api/v3/bureau/crif` | Bureau fallback → `CrifClient` (Experian→CRIF fallback in `BureauService`) | Different response shape (`crifReport.INDV-REPORT-FILE`); `SCORES[].SCORE-VALUE`→score. Keep a separate parser, as NAVIX already separates `ExperianClient`/`CrifClient`. |

---

## Auth difference to plan for

| | Fintrix (today) | Signzy |
|---|---|---|
| Verification auth | `Authorization: Basic base64(clientId:clientSecret)` | `Authorization: <opaque token>` (raw, not Bearer) |
| DigiLocker auth | `X-Client-ID` / `X-Client-Secret` headers | same `Authorization: <token>` (unified) |
| Config props | `navix.fintrix.{base-url,client-id,client-secret}`, `navix.digilocker.{…}` | proposed `navix.signzy.{base-url,token}` (single token covers all Signzy APIs) |
| Base URL | `https://admin.fintrix.tech/__api/api/v1/` | prod `https://api.signzy.app` · UAT `https://api-preproduction.signzy.app` |
| Secrets | env / SSM SecureString `/navix/<env>/…` | same — add `NAVIX_SIGNZY_TOKEN` (never commit) |

## Suggested rollout order (by reuse / value)

1. **Experian Lite** — biggest win, response shape already parsed by NAVIX.
2. **Hybrid Bank Account Verification** — clean 1:1 for the `pennyDrop` seam.
3. **DigiLocker + Liveness** — NAVIX already has the iframe/callback pattern.
4. **UAN Passbook** — unlocks real salary verification for income-risk.
5. **PAN 206AB / CRIF** — secondary gates / bureau fallback.
6. **Aadhaar-to-UAN, eSign** — require enabling on the Signzy account first.

*See [`signzy-apis.json`](./signzy-apis.json) for the exact fields/samples to build the DTOs from.*
