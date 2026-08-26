# DhanBoost — external integrations

> Extracted from `CLAUDE.md` (2026-08-24) to keep the onboarding doc small. Reference material:
> read it when you need it, not on every session. Rules and invariants stay in `CLAUDE.md`.

## 14. External integrations (when un-mocked)

### Verification providers — Signzy (primary) + Digitap (fallback)

NAVIX's identity/bureau/penny-drop/DigiLocker verification runs behind the provider-neutral
`VerificationPort` seam via `RoutingVerificationPort` (`@Primary`, `navix-verification`), which routes **per
capability: Signzy first, Digitap as fallback; where Signzy lacks a capability, Digitap directly** — **except
`pullBureau`, which is Fintrix first, Digitap as fallback (Signzy no longer does bureau at all)**. The old
Fintrix + Fintrix-DigiLocker integration was **removed** (`git` history has it) — **Fintrix later came back
as the bureau primary only**, via a single new endpoint unrelated to the old multi-API integration (see
`NAVIX_Fintrix_Integration_Flow.md` §3.5 for the full history + the live contract). Three per-provider
adapters (`SignzyVerificationAdapter`, `DigitapVerificationAdapter`, `FintrixVerificationAdapter`) map
provider clients → the neutral records; `FintrixVerificationAdapter` offers **two** capabilities — bureau
(primary) and PAN (fallback behind Signzy) — and every other method throws
`CapabilityNotSupportedException` so the router falls straight through to Signzy/Digitap for everything
else. A `CapabilityNotSupportedException` tells the router "skip to the next
provider" vs a `VerificationException` "tried and failed, fall through".

⚠️ **The chain order is GLOBAL, not per-capability** — `RoutingVerificationPort.route()` uses the
capability string for logging only. A provider opts out of a capability by throwing, not by ordering. That
is why the chain leads with **`signzy`**: it keeps Signzy the PAN primary now that Fintrix also serves PAN,
while bureau is unaffected because Signzy's bureau leg is retired and skips itself, leaving Fintrix primary
there exactly as before. Adding a capability to an adapter therefore silently changes who serves it —
check the chain position before you do. Full API catalogs + field/sample
specs: **`docs/signzy/`** (11 APIs) and **`docs/digitap/`** (43 APIs).

| Capability (`VerificationPort`) | Provider used | Endpoint |
|---|---|---|
| `verifyPan` | **Signzy** → Fintrix → Digitap | Signzy `/api/v3/pan/compliance-206-individual-search` → Fintrix `POST /pan_comprehensive` → Digitap `/validation/kyc/v1/pan_details_plus`. Fintrix returns DOB/gender/masked-Aadhaar/address (which Signzy's 206AB search does not) but carries **no 206AB flags**, so `compliant`/`isSpecified` are null on the Fintrix leg — both are display-only and gate nothing. Not behind the `fintrix-bureau` flag; drop `fintrix` from `NAVIX_VERIFICATION_CHAIN` to revert |
| `pullBureau` | **Fintrix** → Digitap | Fintrix `POST /crif_combine` (CRIF Highmark; PRIMARY) → Digitap `/credit_analytics/request`. Signzy's `experian-lite`/`crif` legs are **retired from routing** (`SignzyVerificationAdapter.pullBureau` now throws `CapabilityNotSupportedException`) — `SignzyExperianClient`/`SignzyCrifClient` are kept only for the ADMIN provider workbench. Gated by the `fintrix-bureau` feature flag (on by default; off falls through to Digitap). See `NAVIX_Fintrix_Integration_Flow.md` §3.5 |
| `answerBureauChallenge` | **Fintrix only** | Fintrix `POST /bureau_ch_user_auth` — answers a CRIF KBA question and releases the withheld report. Verified live 2026-08-24. **Does NOT walk the provider chain** (`RoutingVerificationPort` delegates straight to Fintrix): an `order_id` is meaningless to another bureau and falling through would burn a billable call. See the KBA note below |
| `livenessInit` / `livenessResult` (selfie) | **Signzy** | Signzy `/api/v3/liveness-secure/createUrl` + `/getData` (prod acct) — **interactive video journey**: passive liveness + 1:1 face-match vs the DigiLocker Aadhaar photo, embedded in an iframe (`allow="camera"`), polled to completion (our DB authoritative). Two-step async, mirrors DigiLocker |
| `faceLiveness` (selfie fallback) | **Digitap** | Digitap `/fmfl/v2/face-match` — synchronous 1:1 face-match of an uploaded selfie vs the Aadhaar photo (no live camera). **Fallback** used only when Signzy liveness init is unavailable (`selfieLivenessInit` → `derived.fallback=true`) |
| `pennyDrop` | **Signzy only** | Signzy `/api/v3/bankaccountverification/bankaccountverifications` (Digitap has no penny-drop) |
| `digilocker*` | **Signzy only** (prod acct) | Signzy **v2** `/api/v3/digilocker-v2/createUrl` + `/geteAadhaar` (Digitap has no consent flow). Migrated 2026-07-29: v2 is entitled on the **production** account only — the preprod account is out of API credits and prod is not entitled for the retired v1 pair |
| `verifyEmail` | **Signzy** → Digitap | Signzy `/api/v3/email/verificationV2` (prod acct; deliverability + person/company enrichment) → Digitap `/cv/email_verification/v1` |
| `verifyAddress` | **Digitap only** | Digitap `/ent/v1/address-verification` (Signzy has no address API) |
| `verifyEmployment` | **Digitap only** | Digitap `/cv/v3/uan_basic/sync` — EPFO/UAN employment (Signzy has no UAN API). **Basic V3, not Advanced V4**: every Advanced variant answers `412` (product not provisioned), so the PF-filing cross-check (`is_recent`, `has_pf_filings_details`) and `employer_confidence_score` are permanently null. Advisory — absent from `REQUIRED`, never blocks KYC, never returns FAIL. Full probe table + wiring in `docs/digitap/UAN_EMPLOYMENT.md` |

**Aadhaar eSign of the sanction letter** sits on its own seam — `EsignPort` (navix-common), *not*
`VerificationPort`/the router, because it is one provider and a legal act rather than a check.
`SignzyEsignAdapter` (`navix-verification`) drives Signzy's Contract API via `SignzyContractClient`:
`POST /api/v3/contract/initiate` mints a contract + a hosted eMudhra `esignUrl`, `POST
/api/v3/contract/pullData` resolves it. **Production account only** — preproduction answers `403 "You
cannot consume this service"`. Selected by `navix.esign.provider` (`signzy` default; `mock` signs inline
and is set only by the demo seed script and tests). Specs + verified corrections in
`docs/signzy/initiatecontact.md` and `docs/signzy/pullcontact.md`; live-test with
`docs/signzy/test-contract-esign.sh`.
- **Flow** (offer-journey screen 8, `/loan/sanction-letter`): `esignInit` presigns the stored
  `SANCTION_LETTER` and returns `derived.url` → the borrower is **redirected** (eMudhra refuses framing,
  so this follows DigiLocker, not the liveness iframe) → returns to `/kyc/esign/callback`, which polls
  `esignStatus` until terminal. **Our row is the source of truth**; the mandatory `callbackUrl`
  (`POST /api/webhooks/signzy/contract`, shared-secret) only accelerates the closed-tab case.
- **Identity match:** `nameMatchThreshold` + YOB + gender, taken from the `AADHAAR` verification row's
  `derived` (gender and the Aadhaar last-4 live nowhere else). DigiLocker is non-blocking, so when that
  data is absent we send the name threshold alone — `derived.matchMode` records which was used.
- **Fallback:** if init fails, `derived.fallback=true` and the borrower draws a signature instead
  (`recordManualEsign`, unchanged). That path deliberately never calls `EsignPort` — it must work when
  the provider does not, and each call would otherwise mint a billable contract.
- ⚠️ **Every initiate is a real, billable, legally binding contract; there is no sandbox.** The re-mint
  on expiry is deliberately narrow (provider 404 **and** nothing signed) so a poll loop cannot cycle.
  `contractTtl` is echoed but appears unhonoured (~7 days regardless).

- **Auth & hosts (env-driven, PREPRODUCTION by default).** Signzy = raw opaque token in `Authorization`
  **plus** the account id in the `x-client-unique-id` header (`SIGNZY_TOKEN` + `SIGNZY_CLIENT_UNIQUE_ID`,
  base `SIGNZY_BASE_URL` default `https://api-preproduction.signzy.app`). Digitap = HTTP
  Basic `base64(client_id:client_secret)` (`DIGITAP_CLIENT_ID`/`DIGITAP_CLIENT_SECRET`) over **two** hosts —
  `DIGITAP_SVC_BASE_URL` (default `https://svcdemo.digitap.work`, KYC/Email) + `DIGITAP_API_BASE_URL`
  (default `https://apidemo.digitap.work`, Credit/Address/Face-Match). Routing order via
  `NAVIX_VERIFICATION_CHAIN` (default **`signzy,fintrix,digitap`**). Switch to prod by overriding the `*_BASE_URL` vars
  (`api.signzy.app`, `svc.digitap.ai`, `api.digitap.ai`). **Keys load from `backend/.env`** (auto-loaded by
  `spring-dotenv` — see `.env.example`) or SSM; never committed.
- **Bureau consent gotcha:** Signzy's `experian-lite`/`crif` require `consent.consentTimestamp` as a JSON
  **number** (epoch millis) — a string returns `400 "must be a number"`. `SignzyDtos.Consent.consentTimestamp`
  is a `long` for this reason.
- **DigiLocker (Signzy)** — consent flow unchanged in shape (init consent URL → user authorizes →
  **redirect-driven** completion, DB `AADHAAR` row is the source of truth); PASS gates on
  `x509Data.validAadhaarDSC == "yes"`. On completion NAVIX also ingests the Aadhaar **face photo** to S3 as an
  `AADHAAR_PHOTO` document, which the **selfie step face-matches against** (`ApplicationVerificationService`).
  The gotchas below still apply.
- **Selfie = Signzy liveness (primary), Digitap face-match (fallback).** Primary path is an **interactive
  video journey**: `selfieLivenessInit` (presigns the stored `AADHAAR_PHOTO` as Signzy's `matchImage` for a
  1:1 face-match, mints the token + `videoUrl`) → the borrower completes the passive-liveness video in an
  **iframe** (`allow="camera"`, `signup/selfie/page.tsx`) → `selfieLivenessResult` polls Signzy `getData`
  (404 "not completed" = keep polling; on completion it ingests the captured frame to S3 as `SELFIE` and
  maps **PASS** when live + face-matched, else **REVIEW**). Signzy Liveness runs on the **prod** account
  (`SignzyLivenessClient` → `SIGNZY_PROD_CLIENT`). If Signzy liveness init is unavailable
  (`derived.fallback=true`), the page degrades to the legacy **camera-capture** selfie → `verifySelfie`
  presigns the selfie **and** the `AADHAAR_PHOTO` and calls `faceLiveness(selfieUrl, referenceUrl, ref)` →
  Digitap Face Match (`is_same_face` + confidence ≥ 0.60; no Aadhaar photo → single-image quality check).
  Neither path ever hard-blocks — a KYC approver makes the final call.
- **Bureau fixture** — `NAVIX_BUREAU_FIXTURE` (any non-blank value) still yields a rich local credit brief
  offline. Since Fintrix is now the bureau primary, the fixture is read by `FintrixCrifClient`, which
  ignores the property's value and always serves its own bundled `docs/fintrix/crif-combine-sample.json`
  (CRIF-shaped); `SignzyExperianClient`/`DigitapCreditClient` still honour the same property for their own
  Experian-shaped `classpath:samplepan.json` when reached (workbench / fallback paths).
- **Live-test status (verified 2026-07-14, preproduction/production).** ✅ Signzy PAN, **penny-drop**, CRIF
  (score 799), DigiLocker init; ✅ Digitap **Address** (200, prod host). ⚠️ **Account-side blockers, not code:**
  Digitap **Email** → `412` (product not provisioned), Digitap **Face Match** → `402` (needs account balance),
  Digitap **PAN/Credit fallback** → `403` IP-not-allowed (whitelist the caller IP; not critical since Signzy is
  primary). Signzy **Experian** may `409` on a thin/no-match identity → CRIF fallback covers it.
  **Config caveat:** the current Digitap keys are **production** but the app defaults to Digitap **preprod**
  hosts (which `401` prod keys) — set the `DIGITAP_*_BASE_URL` vars to the prod hosts to use them, or get a
  Digitap UAT key pair. Live-test scripts: `docs/signzy/test-all-signzy.sh`, `docs/digitap/test-all-digitap.sh`;
  ready-to-run curls in `docs/signzy/SIGNZY_LIVE_CURLS.md` + `docs/signzy/SIGNZY_CURLS_DIRECT.md`.
- **UltronSMS** (borrower OTP + lifecycle SMS) — `GET https://ultronsms.com/api/mt/SendSMS`, params
  `user/password/senderid/channel/DCS/flashsms/number/text/route/peid/DLTTemplateId`; success envelope
  `{ErrorCode:"0"|"000", JobId}`. Sent by `UltronSmsClient` (`navix-app`), bound from `navix.sms.*`.
  The **PEID is entity-level and constant** across all templates (verified working value
  `1701178039634361131`, sender `NAVIXF`, route `02`). Live-test **without** the app:
  `docs/sms-dlt/test-send-sms.sh <number> [text] [dltTemplateId]` (one send) and
  `docs/sms-dlt/test-all-templates.sh [number]` (sweeps every `_V2` template → a pass/fail tracker at
  `docs/sms-dlt/TEMPLATE_TEST_RESULTS.md`). The 15 template ids + exact content are in
  `docs/sms-dlt/SMSULTRON.md`; the sent text must match the registered template **char-for-char** (only
  variable slots filled), use `Rs.` not `₹` (₹ forces costly UCS-2), and any URL must be portal-
  whitelisted. **Status (2026-07-10):** `NAVIX_OTP_LOGIN_V2` approved & live; the other 14 pending
  (return `006 Invalid template text`).

### Bureau KBA (knowledge-based-authentication) challenges

`crif_combine` is keyed on **name + mobile only** — there is no PAN or DOB input, so CRIF decides who
you meant. When that match is not confident enough it withholds the report behind a question about the
borrower's own credit history, returned as an HTTP 200 *error* envelope carrying `data.question`,
`data.options`, `data.order_id` and `data.report_id`.

Four properties of this flow that are easy to get wrong:

1. **`bureau_ch_user_auth` authenticates differently from `crif_combine`** — `X-Client-ID` /
   `X-Client-Secret` headers, not `Authorization: Basic`. The single `fintrixRestClient` bean sends
   the superset of both. (Unverified whether either endpoint accepts the other's scheme.)
2. **Its response envelope is flatter.** `{timestamp, transaction_id, status, data:{HEADER,…},
   request_id}` — `data` **is** the `credit_report` node, with no `canonical` wrapper and no
   `credit_report_link`. `ApplicationVerificationService.creditReport(...)` resolves both shapes; do
   not re-hard-code either path.
3. **`auth_answers` must be the option string VERBATIM**, including CRIF's leading/trailing spaces.
   A trimmed answer is a wrong answer. Nothing between the radio button and the provider may trim it.
4. **Every pull mints a NEW question and invalidates the old one.** Stored questions go stale, so the
   borrower-facing screen offers a re-mint — the only billable call a borrower can trigger, guarded by
   a 60s server cooldown, a 3-attempt cap, and an option-membership check that refuses to spend on an
   answer that cannot be right.

The borrower answers at `/credit-question` (reached inline from the signup consent step, or by the
`BUREAU_QUESTION_PENDING` email). It never blocks: "I don't recognise any of these" writes
`bureauChallengeSkipped` and the file goes to the credit team without a score, exactly as before.
ADMIN outreach to already-parked borrowers is `GET|POST /api/admin/bureau-challenge/{preview,notify}`,
which sends notifications only and never calls Fintrix.

**Do not auto-answer by elimination.** The distractor options recur across unrelated borrowers, so
picking the odd one out would often work — and would be defeating an identity control on someone
else's credit file.


**Bureau report → credit brief:** the PRIMARY path is now Fintrix — `FintrixCrifClient` (`POST
/crif_combine`) unwraps the envelope to `canonical.data.credit_report` and hands it to
`support/CrifHighmarkFactsParser`, which parses the **full** CRIF Highmark report (accounts summary,
tradelines, inquiry history, score trend) into `BureauReportFacts` — the **same shape**
`support/ExperianFactsParser` produces for the Digitap fallback (`DigitapCreditClient`,
`result.result_json.INProfileResponse`), so the credit-brief PDF/rating stay bureau-agnostic regardless of
which provider answered (`SignzyExperianClient`/`SignzyCrifClient` are retained only for the ADMIN provider
workbench, no longer in the routed path). A **thin-file** response (no tradeline/summary detail → `facts ==
null`) is score-only, no brief; a rich
response yields the brief. For local end-to-end demos set **`NAVIX_BUREAU_FIXTURE`** (any non-blank value —
`FintrixCrifClient` treats it as an on/off toggle, not a path, and always serves its own bundled
`docs/fintrix/crif-combine-sample.json`, a redacted real capture; the Digitap/Signzy clients still honour
the same property name for their own `classpath:samplepan.json` Experian-shaped fixture) — every pull then
returns a real report, yielding a brief + PDF without a live (billable) call. The rating math + field map
live in `CreditRatingCalculator`
(see §2); the PDF needs **OpenPDF** (`com.github.librepdf:openpdf`, in the parent BOM + `navix-loan`).
The bureau facts drive the **rating + credit-health + exposure** numbers, but the brief's **displayed
identity** (name/PAN/mobile/DOB) is overridden from the borrower's `ApplicantProfile`
(`CreditBriefService.displayFacts`) — never the report's copy — so it can't show the fixture person;
the on-screen brief is always recomputed from the profile.

**DigiLocker live-flow gotchas** (touch points
`ApplicationVerificationService.{digilockerStatus,digilockerComplete}`, `signup/digilocker/page.tsx`,
`kyc/digilocker/callback/page.tsx`):
- **`digilocker_initialize` caches the consent session by `redirect_url`** and re-serves a stale,
  expired token on reuse (→ SDK "Access Denied"). **Fix:** make `redirect_url` unique per attempt
  (append `?app=<id>&sid=<nonce>`; the callback resolves the app from `localStorage`).
- **Completion is redirect-driven, not poll-driven.** The `digilocker_status` poll routinely stalls at
  `client_initiated`, so the **redirect to `/kyc/digilocker/callback`** is the completion signal and our
  **own DB is the source of truth**: the callback tab finalises via `digilockerComplete` (bounded retry
  on `DIGILOCKER_NOT_READY`, which the backend now throws instead of persisting a bogus PASS), and
  `digilockerStatus` short-circuits to PASS once the `AADHAAR` row exists. The signup tab polls until
  PASS with a ~3-min fallback to staff manual review.

**AWS SES — email delivery + bounce/complaint suppression (live, 2026-07-01):** the email channel's
`EmailClient` port (`navix-notification`) has four impls selected by `navix.email.provider`:
`log` (default, masked-log no-send), `smtp` (Boot `JavaMailSender`), **`ses`** (`SesEmailClient` over
the SES v2 SDK, reusing the **same region + default credential chain as S3** — no separate SMTP creds),
and `resend` (`ResendEmailClient` over the Resend HTTP API — interim while SES is sandbox-limited). Each
message carries a plain-text body + an optional branded **HTML** alternative (`EmailMessage.html`,
built by `EmailHtmlRenderer`).
When `navix.email.configuration-set` is set, sends are tagged with a SES **configuration set**
(`navix-notifications`) so deliverability events fire.

- **Bounce/complaint loop:** SES config-set → **SNS topic `navix-ses-events`** → **SQS queue
  `navix-ses-events`** (raw delivery, + DLQ) → `SesEventSqsListener` (`@SqsListener`, gated by
  `navix.ses.events.enabled`). A **permanent** bounce or any complaint adds the address to the
  `email_suppression` table (`EmailSuppressionService`, idempotent) and flips the originating
  `notification_delivery` row to `BOUNCED`/`COMPLAINED` (matched by the SES messageId in `provider_ref`).
  `EmailSender` then **skips** suppressed addresses (`SKIPPED("SUPPRESSED")`). Transient bounces are
  logged, not suppressed. SES account-level suppression is also on, so this is belt-and-suspenders +
  app-side visibility.
- **Run it (sandbox):** `AWS_PROFILE=navix-dev NAVIX_EMAIL_PROVIDER=ses
  NAVIX_EMAIL_FROM="NAVIX Finance <noreply@navixfinance.com>" NAVIX_SES_CONFIG_SET=navix-notifications
  NAVIX_SES_EVENTS_ENABLED=true`. The verified identity is the **domain** `navixfinance.com`, so any
  alias on it (e.g. `noreply@`) is a valid `From`. Test bounce/complaint/success without verifying
  recipients via the SES mailbox simulator (`{bounce,complaint,success}@simulator.amazonses.com`).
- **Caveats (both environmental, not code):** (1) with `NAVIX_SES_EVENTS_ENABLED=true` the app needs
  working AWS credentials **at startup** — the SQS listener resolves the queue URL eagerly and the boot
  **fails** without them (in prod that's the task role; locally pass `AWS_PROFILE=navix-dev`).
  (2) With those creds the SSM import succeeds and points the app at **RDS**, not local Docker — know
  which DB you're hitting when you test. The account is still in the **SES sandbox** (production access
  pending); real recipients need that approval.
