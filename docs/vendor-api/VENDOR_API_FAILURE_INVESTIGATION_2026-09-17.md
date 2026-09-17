# Vendor API failure investigation — 2026-09-17

**Question asked:** why did the last 100–200 provider API calls fail, which vendor errors should and
should not trigger a fallback, and where is the current fallback logic spending money it should not.

**Answer in one paragraph:** of the 4,831 live provider calls recorded as `FAILED` in the 90-day audit
table, only **4.8 % are genuine transient vendor faults**. **43.6 % are definitive vendor *answers*** (no
record, invalid PAN, KBA question, too many matches) that our transport layer records as failures;
**16.7 % are our own bugs** — the largest being that **Aadhaar eSign has never worked in production**
(156/156 initiates rejected, 0 successes, because the callback URL is sent as an empty string);
**18.7 % are account-level blocks** (prepaid balances at zero, products never provisioned, a retired
preprod account with no credits) that fail every borrower until an operator acts; and **15.2 % are poll
amplification** — one DigiLocker outage produced 654 identical `409 Upstream Down` rows from seven
borrowers, and one borrower whose e-Aadhaar carries an invalid document signature generated **820
*successful* `geteAadhaar` fetches in 3½ hours** (58 % of all successful DigiLocker calls in the window)
because a permanent verdict is treated as "not ready yet". The fallback chain itself is mostly right —
it rescued 15/15 bureau pulls during the Fintrix balance outage and 14/18 during the Digitap Experian
outage — but it walks past three definitive answers it should stop on, still routes through two Digitap
products that have never once succeeded, and a Sep-11 change to the chain order silently inverted the
one place the code assumed Digitap was the last leg.

---

## 1. Scope, data and method

| | |
|---|---|
| Source | `provider_api_execution` via the ADMIN read-only history endpoint (`GET /api/admin/provider-apis/history`, `/history/{id}`). No database access; no writes; no AWS. |
| Window | 2026-08-09 17:39 → 2026-09-17 06:09 UTC. The table has 90-day retention but live-call capture (V57) only began 2026-08-20, so this is the complete live history. |
| Volume | 19,766 calls: 14,918 `SUCCESS`, **4,848 `FAILED`** (4,831 live + 17 ADMIN-workbench probes, excluded below). |
| Failures read with full request/response | **628** — the newest 200 (Sep 14 12:38 → Sep 17 06:09) plus a stratified sample of up to 15 per (provider, operation, HTTP status) class spread across each class's date range (428 rows, 42 classes), so every signature is grounded in real payloads rather than extrapolated. |
| Fallback tracing | full call history for **368 applications** (every application touched by a sampled failure); 617 failure-centred traces of every same-application call within ±180 s. |
| Denominators | `SUCCESS`/`FAILED` totals per vendor endpoint queried directly, so every failure count has a rate. |
| Agents | 10 Haiku collectors (one per failure class + one cross-cutting; ≤10 concurrent), 2 Sonnet analysts (fallback logic; root cause), 2 Sonnet adversarial verifiers. Every conclusion below was additionally checked first-hand against the raw rows and code; the cross-cutting collector's fallback claims were discarded as unreliable (it asserted UAN fell back to Signzy, which has no UAN product). |
| PII | none in this document; rows are cited by `id` / `applicationId` only. |

### 1.1 Per-endpoint health (live calls, whole window)

| Vendor / endpoint | SUCCESS | FAILED | Fail % | Note |
|---|---:|---:|---:|---|
| Signzy PAN `compliance-206-individual-search` | 3,548 | 125 | 3.4 % | healthy |
| Signzy EMAIL `verificationV2` | 3,623 | 39 | 1.1 % | healthy |
| Signzy PENNY_DROP | 135 | 1 | 0.7 % | healthy |
| Signzy LIVENESS | 991 | 0 | 0 % | but 184/221 sampled "successes" are tolerated `404 not completed` polls |
| Signzy ADDRESS | 93 | 0 | 0 % | |
| Signzy DIGILOCKER `geteAadhaar` | 1,509 | 737 | 32.8 % | one application = 881 of the 1,509 successes |
| **Signzy ESIGN `contract/initiate`** | **0** | **156** | **100 %** | never worked |
| Digitap UAN `uan_basic/sync` | 1,867 | 2,087 | 52.8 % | 1,337 of the failures are `result_code 103/104` answers |
| Digitap BUREAU `credit_analytics/request` (Experian) | 605 | 143 | 19.1 % | |
| **Digitap PAN `pan_details_plus`** | **0** | **96** | **100 %** | product not provisioned (412) |
| **Digitap EMAIL `email_verification/v1`** | **0** | **39** | **100 %** | product not provisioned (412) |
| Fintrix BUREAU `crif_combine` + `bureau_ch_user_auth` | 2,532 | 853 | 25.2 % | 601 of the failures are no-hit / KBA answers |
| Fintrix PAN `pan_comprehensive` | 4 | 93 | 95.9 % | third PAN leg; all 4 successes on Aug 31 |
| **Signzy EXPERIAN + CRIF (preprod)** | **0** | **462** | **100 %** | out of credits; retired from routing Aug 23 |

### 1.2 What the 4,831 live failures actually are

| Bucket | Rows | Share | What it is |
|---|---:|---:|---|
| **A. Vendor gave a definitive answer** | 2,108 | 43.6 % | UAN `103 No record(s) found` / `104 Too many responses` (1,337); Fintrix `No data found in CRIF` no-hit + KBA challenge (601); Experian `102` masked-mobile / no record (98); Signzy `404 Pan Number Not Found` (56); Experian `422 trade line count exceeds limit` (16) |
| **B. Our bug** (code / config / request) | 808 | 16.7 % | UAN re-run sends a `uan` field Digitap rejects (378); eSign `callbackUrl` empty (156); Fintrix KBA follow-up envelope mis-parsed (108); `application/yaml` Content-Type, fixed Sep 4 (72 + 36 cascade); octet-stream body not parsed, fixed (48); future DOB / email format / bureau key format (10) |
| **C. Account-level block** | 905 | 18.7 % | Signzy preprod bureau "No remaining API credits" (462, Aug 15–23); prepaid balance exhausted — Digitap Sep 8–9, Fintrix Aug 31 (308); Digitap PAN/EMAIL never provisioned (135) |
| **D. Poll amplification** | 736 | 15.2 % | DigiLocker `409 Upstream Down` polled every 5 s (654, 7 borrowers); `geteAadhaar` before consent completes (59); `400 User denied the consent` polled 23× (23, 1 borrower) |
| **E. Vendor transient** | 234 | 4.8 % | Digitap UAN `505 Source is busy` (153, ~6/day, ongoing); Experian `503 source error` (18, Sep 16); Fintrix `500 Source Down` / `502` (39); Signzy PAN `409 Error in getting data from Upstream` (21, Aug 31) |
| **F. Wasted cascade leg** | 40 | 0.8 % | Fintrix PAN called on a PAN Signzy had already declared not found |

**The newest 200 (Sep 14–17)** are: DigiLocker 409 polls 63 · UAN 103/104 answers 48 · vendor 5xx 20 ·
Fintrix no-hit/KBA 15 · UAN `uan`-field 400s 15 · eSign callback 13 · Experian 102 10 · everything else 16.
Every ongoing issue below is present in that window.

### 1.3 The chain order changed mid-window

Commit `4b3a0c2` (2026-09-11, "make Digitap the bureau primary ahead of Fintrix") flipped the global chain
from `signzy → fintrix → digitap` to `signzy → digitap → fintrix`. The traces show Fintrix called first
for bureau and PAN through Sep 9 and Digitap first from Sep 11. Several code comments and one piece of
logic (§2, I8) were written for the old order and are now wrong.

---

## 2. Issues, root causes, and what to change

Severity is by borrower/business impact. "Ongoing" = seen on/after 2026-09-14.

### I1 — Aadhaar eSign has never worked in production &nbsp;`CRITICAL · ours (config + code) · ongoing`

* **Rows:** 156 / 119 applications / Aug 21 → Sep 16. **0 successes in the entire window.**
* **Vendor message:** `400 VALIDATION_ERROR "callbackUrl is not allowed to be empty"` — every row.
* **Root cause:** `application.yml:82` binds `navix.esign.callback-url: ${NAVIX_ESIGN_CALLBACK_URL:}`, which
  is the empty string when the env var is unset. It is unset: `backend/.env.example:41` has it commented
  out and it appears in no task-definition or SSM inventory (`aws.md`). `SignzyEsignAdapter.initiate():75`
  passes `props.callbackUrl()` raw (the sibling `callbackSecret` one line below is `blankToNull`-wrapped).
  `EsignProperties` documents "blank disables the accelerator" — but Signzy's own spec
  (`docs/signzy/initiatecontact.md:193`) lists `callbackUrl` as **Mandatory**, so a blank cannot disable
  anything; it is rejected.
* **What happens today:** `ApplicationVerificationService.esignInit():2313` catches the exception and returns
  `fallback=true`, so every borrower is sent to the **drawn-signature** path. Every sanction letter in the
  window was "signed" by drawing, none by Aadhaar eSign. Rejected initiates are not billed.
* **Fix:** set `NAVIX_ESIGN_CALLBACK_URL=https://<public-backend>/api/webhooks/signzy/contract` and
  `NAVIX_ESIGN_CALLBACK_SECRET` (SSM, then a task-def revision); in `EsignConfig`, fail startup when
  `provider=signzy` and the callback URL is blank (the vendor requires it, so silence is wrong); add a
  100 %-failure alarm per capability (this ran for four weeks unnoticed because the fallback hid it).

### I2 — ~1,939 "failures" are correctly-handled answers recorded as `FAILED` &nbsp;`HIGH (observability) · ours (code) · ongoing`

* **Rows:** UAN `103`/`104` (1,337) + Fintrix `crif_combine` no-hit / KBA challenge (601). Verified: the
  UAN client returns a not-found answer and never throws (`DigitapUanClient:79-84`); the Fintrix client
  returns a no-hit or a `PendingChallenge` (`FintrixCrifClient:80-86,144-172`); the router does not fall
  through; the consumer records an honest `REVIEW`.
* **Root cause:** `ProviderJson.post():144-145` writes the audit row `FAILED` whenever
  `isProviderErrorEnvelope()` is true — `status ∈ {error,failed,failure}`, a non-null `error` key, **or
  `result_code != 101`** — *before and independently of* whether the caller throws
  (`throwOnErrorEnvelope` on line 146). The `result_code != 101` rule is UAN-specific and wrong for
  Experian, where `103` is "no record" and `102` an instruction.
* **Consequences:** the dashboard's 24.5 % failure rate is really ~10 %; every one of these logs
  `PROVIDER_CALL failed` at **ERROR** (`ProviderCallLog:68`) — ~2,000 spurious error lines that bury the
  real ones and would trip any error-rate alarm; `ProviderAttemptDirectory.succeeded=false` shows staff a
  red attempt on the customer page. **No money is lost** by this alone, but any operator who re-runs
  "failed" calls pays for answers we already have.
* **Fix:** the audit status must follow the caller's outcome. Add `ProviderCall.ANSWERED` (or keep
  `SUCCESS` with `outcome=NO_RECORD|KBA|…`) set by `postAllowingErrorEnvelope` and by an endpoint-specific
  "expected answer" predicate; drop the global `result_code != 101` rule; log answers at INFO.

### I3 — Employment re-run sends a `uan` field Digitap rejects &nbsp;`HIGH · ours (request + flow) · ongoing`

* **Rows:** 378 `400 "One or more parameters format is wrong or missing"` / 349 applications /
  Aug 22 → Sep 17 (~14/day).
* **Evidence:** for the same application the request that **succeeded** and the one that **failed a minute
  later** are identical except the failing one carries `uan` (12 digits) — e.g. app 9847 row 19744
  (SUCCESS, no `uan`) → row 19745 (400, with `uan`); apps 9816, 9647, 9769, 9750 likewise. 21 of 30
  sampled 400 payloads carry `uan`; the other 9 carry only `pan`+`mobile`+`dob` with no names (second
  sub-cause, needs vendor confirmation).
* **Root cause:** `signup/submitted/page.tsx:76-84` lets the borrower add an optional UAN, saves it and
  immediately calls `POST …/verifications/employment` again. `verifyEmployment():1728` short-circuits only
  on `PASS`, so a `REVIEW` outcome (no record / employer mismatch — the common case) re-runs, now with
  `uan` appended to `pan+mobile+dob+names`. Digitap's UAN-Basic-V3 spec (`docs/digitap/digitap-apis.json`,
  `docs/digitap/UAN_EMPLOYMENT.md:57-63`) defines `uan` as *Lookup Method 3 (direct)* and says a request
  must satisfy **one** lookup method; the combined payload is rejected.
* **Consequences:** the honest "No EPFO employment record" review is overwritten by
  `providerUnavailable` → "Employment check unavailable — pending manual review", which tells credit
  staff the vendor was down when it was not. Not billed (400). But the design would **double-bill** if
  it ever succeeded: a `101` result (the only billable outcome) already on file is not reused.
* **Fix:** when a UAN is on file send **only** `client_ref_num` + `uan` (Method 3); short-circuit on an
  existing `found=true` (101) row, not only on `PASS`; validate 12 digits client- and server-side; ask
  Digitap to confirm the Method-3 payload and what the 9 name-less 400s violated (send `request_id`s
  from rows 19629, 19745, 19682).

### I4 — Fintrix KBA follow-up envelope is mis-parsed; borrowers answer a stale question &nbsp;`HIGH · ours (parser) · ongoing · billable`

* **Rows:** 108 on `/bureau_ch_user_auth`.
* **Vendor shape:** the answer endpoint returns `error_message` as an **object**:
  `{status:"S11", question, optionsList, orderId, reportId}` (a *new* question) or
  `{status:"S02", statusDesc:"Authentication failed due to unsuccesfull all ans attempt failed"}`
  (attempts exhausted). `FintrixCrifClient.kbaChallenge():102-127` only recognises a *string*
  `error_message` containing "auth question" plus `data.question`; it does not match, and
  `rejectUnlessNoRecord():167` calls `text(error_message)` on an object → `null` → throws
  `"Fintrix crif_combine error: unspecified provider error"`.
* **What happens today:** `answerBureauChallenge():1114` catches it, increments `bureauChallengeAttempts`,
  and keeps the **old** question open. The borrower re-answers a question CRIF has already replaced, CRIF
  counts it wrong, and after three tries returns `S02`. App 9741: S11 07:55:05 → S11 07:55:11 → S02
  07:55:17; app 9474 identical. The 3-attempt cap then holds (no further calls) — but the report is
  lost and the borrower is blamed.
* **Billing:** "Answering is billable exactly like a pull" (`FintrixVerificationAdapter:52`) — each of the
  three answers is billed; ≈2 of every 3 were spent on a question the borrower could not see.
* **Fix:** parse the flat/object envelope: `S11` → replace the stored question/options/orderId/reportId
  and re-park (`PendingChallenge`); `S02` → terminal: `bureauChallengeExhausted=true`, stop, staff
  review, **no** re-mint prompt; get Fintrix's status-code list (§4).

### I5 — DigiLocker readiness gate treats permanent verdicts as "not ready" → poll storms &nbsp;`HIGH · ours (code) + vendor outage · ongoing · possibly billable`

`SignzyVerificationAdapter.digilockerAadhaar():159-176` maps **every** `VerificationException` and any
`validAadhaarDSC != yes` to `notReady`; `digilockerComplete():714` turns that into the retryable
`DIGILOCKER_NOT_READY`; the callback page (`kyc/digilocker/callback/page.tsx`) polls **45 × every 4 s**
per page load; `loan/digilocker/page.tsx` caps consent restarts at `MAX_RETRIES = 3` — in React state,
which resets on reload. Three different vendor answers get the same treatment:

| Sub-case | Rows | Vendor message | Reality | Observed |
|---|---:|---|---|---|
| (a) `409` | 654 / 7 apps / 7 days (Aug 25, 30; Sep 2, 7, 11, 12, 16) | `"reason":"Error From Upstream","message":"Upstream Down"` | DigiLocker/UIDAI outage — vendor-side, intermittent | app 8710: **183 calls in 20 min at 5 s**, 6 consent sessions, 0 successes; apps 7345 (157), 9045 (149) |
| (b) `400` | 23 / 1 app / Sep 2 | `"reason":"AUTH_FAIL","message":"User denied the consent. Please try again."` | **terminal** for that session | polled 23× in 6 minutes |
| (c) `200` with `validAadhaarDSC:"no"` | **820 SUCCESS rows** / 1 app / Aug 30 10:25–13:53 | full e-Aadhaar returned every time | the document's signature is invalid — a **permanent** verdict | app 2631: **62 consent sessions + 820 successful fetches every 6 s** = 58 % of all successful DigiLocker calls in 90 days. Every other application got `dsc=yes` on its first fetch and stopped. |

* **Billing:** the repo documents no rule for `geteAadhaar`. If Signzy bills per successful e-Aadhaar
  fetch (the usual model), app 2631 alone is ~820 billable pulls in an afternoon. This is potentially the
  largest single charge in the investigation and it is invisible to a failures-only view. **Ask Signzy
  (§4).**
* **Fix:** classify instead of collapsing: `401` → not ready (keep polling); `409 Upstream Down` →
  `DIGILOCKER_UPSTREAM_DOWN`, back off to 30 s, cap at ~6, then offer "try later / upload Aadhaar";
  `400 AUTH_FAIL` → `DIGILOCKER_CONSENT_DENIED`, terminal, offer restart or manual upload at once;
  `200` with `validDsc=false` → persist `AADHAAR` as `REVIEW` with `validDsc=false` and **never re-poll**.
  Server-side cap on `createUrl` sessions per application (e.g. 5 per 24 h) — the client counter cannot be
  trusted; persist the retry count in the `DIGILOCKER` row's `derived`. Same classification for liveness
  `getData`: 184 of 221 sampled "successes" are tolerated `404 not completed` polls (app 9473: 57 in 6 min).

### I6 — Two Digitap products that have never succeeded are still fallback legs &nbsp;`MEDIUM · ours (config) / vendor · ongoing`

* Digitap PAN `pan_details_plus`: **0 / 96**, and EMAIL `email_verification/v1`: **0 / 39** — all
  `412 Precondition Failed` (product not provisioned), Aug 22 → Sep 16.
* Every Signzy PAN or EMAIL failure therefore burns a guaranteed-412 Digitap call (56 after a Signzy PAN
  404 alone; 39 for email), adds latency, and for email ends the chain with a `REVIEW` that looks like a
  vendor outage. Almost certainly unbilled (precondition rejection), but it is a dead leg.
* **Fix:** in `DigitapVerificationAdapter.verifyPan/verifyEmail` throw `CapabilityNotSupportedException`
  behind feature flags (`digitap-pan`, `digitap-email`, default off) exactly as the retired Signzy
  bureau leg does; turn them on when Digitap provisions the products (§4).

### I7 — PAN cascade continues past a definitive "not found" &nbsp;`MEDIUM · ours (routing) · ongoing · billable`

* Signzy `404 {"reason":"NOT_FOUND","message":"Pan Number Not Found"}` — 58 rows; **none of the 18
  traced applications ever later got a PAN success**: the PAN is simply wrong.
* The router treats the 404 as "tried and failed" and walks on: Signzy 404 → Digitap 412 (dead) →
  Fintrix `pan_comprehensive` `{"status":"error","error_message":"Invalid PAN"}`. 41 Fintrix PAN calls
  followed a Signzy 404 within 90 s (37 applications); Fintrix calls are documented as always billable.
* Contrast: Signzy `409 "Error in getting data from Upstream"` (21 rows, Aug 31) **is** transient —
  falling through to Fintrix rescued 2 of them. The distinction is the vendor's own `reason` field.
* **Fix:** make `NOT_FOUND` a definitive negative — a `PanNotFoundException` (subclass of
  `VerificationException`) that `RoutingVerificationPort` treats as terminal (or a PAN `acceptable`
  predicate like bureau's), recorded as `FAIL`/`REVIEW` "PAN not found"; keep fall-through for
  5xx / 409-upstream / transport / 415.

### I8 — `MASKED_MOBILE_REQUIRED` now falls through to a billable CRIF pull because the chain flipped &nbsp;`MEDIUM · ours (stale assumption) · ongoing · billable`

* Experian `200 result_code 102 "please call the masked mobile report API with the following actual
  mob…"` — 25 rows. `DigitapCreditClient:79-97` throws `MASKED_MOBILE_REQUIRED` on the explicit
  assumption "Digitap is the last leg, so `RoutingVerificationPort` rethrows this and the bureau step
  records an honest REVIEW". Since `4b3a0c2` (Sep 11) Digitap is **first**, so the exception now falls
  through to Fintrix `crif_combine` — a billable CRIF pull. Traced: 9 → Fintrix no-hit/KBA, 3 → KBA then a
  real report, 2 → four KBA attempts.
* This is not obviously wrong — CRIF is a different bureau and 3 of 14 recovered a report — but it
  contradicts the code's documented intent, and the `bureauMaskedMobileRequired` hint only reaches
  staff if Fintrix *also* fails. `RoutingVerificationPort`'s class javadoc still says "Fintrix (primary)
  → … → Digitap (fallback)".
* **Decision:** keep the fall-through (it yields reports), but make it deliberate: return a
  `BureauCheck` with `noRecord=true, maskedMobileHint=true` (an *unacceptable answer* that walks on, like
  a no-hit) instead of throwing; when the chain ends in a no-hit and the hint was seen, set
  `bureauMaskedMobileRequired` on the REVIEW. Fix the javadoc and the `DigitapCreditClient` comment.

### I9 — Experian `422 trade line count exceeds limit` left rich-file borrowers unscored &nbsp;`MEDIUM · vendor limit + old chain order · historical`

* 16 rows / 9 applications, Aug 20 → Sep 1 — these are borrowers with *many* tradelines, i.e. the
  best credit histories. Under the old order Digitap was last, so the 422 was rethrown → `REVIEW
  HTTP_422`, no score, and apps 117 and 172 re-ran the whole chain repeatedly (13 failed bureau rows
  each). Under today's order a 422 falls through to Fintrix CRIF, which is correct.
* **Fix:** re-pull those 9 applications through Fintrix (ADMIN retry); add a 422-specific review message
  ("rich file — Experian cannot render; CRIF result used"); ask Digitap for the limit / a full-report
  product (§4).

### I10 — Prepaid balances ran to zero for 10 h and 27 h &nbsp;`MEDIUM · ours (ops) · recurring`

| Vendor | Window (UTC) | Failed calls | Applications | Fallback? |
|---|---|---:|---:|---|
| Fintrix `crif_combine` | Aug 31 18:10 → Sep 1 04:21 (10 h) | 106 | 106 | Digitap Experian rescued **15/15** traced |
| Digitap UAN (+3 bureau) | Sep 8 10:45 → Sep 9 13:50 (**27 h**) | 202 | 173 | none — UAN is Digitap-only; 173 employment checks parked as "unavailable" and **never re-tried** |

`ProviderJson:114-121` already detects `insufficient_balance` and logs `PROVIDER_BALANCE_EXHAUSTED` at
ERROR, referencing an earlier 11.5 h incident — it is a log line, not an alert. **Fix:** CloudWatch metric
filter → alarm on that line; ask both vendors for low-balance webhooks; a bounded scheduled re-run of
`EMPLOYMENT` rows parked with `providerErrorCode ∈ {HTTP_402, HTTP_505, HTTP_503, TRANSPORT_FAILURE}`
(3 attempts over 72 h — none of those outcomes is billed; the only billable UAN outcome is the one we
want).

### I11 — Signzy preproduction bureau account out of credits &nbsp;`historical · closed`

462 rows / 223 applications, Aug 15 → Aug 23 06:47, `403 "No remaining API credits. Contact support"`
on both `experian-lite` and `crif`, always as a pair, then a third call. Retired from routing by
`10392f4` (Aug 23). Nothing further to do; noted because 462 dead calls per ~220 borrowers is the
template for what I6 still does on a smaller scale.

### I12 — `application/yaml` Content-Type regression &nbsp;`historical · closed`

110 rows in one window, Sep 4 05:47 → 10:30: Signzy PAN/EMAIL `415 "Unsupported Media Type:
application/yaml"` (73) → Digitap 412 → Fintrix `pan_comprehensive 400` (36). Cause: springdoc pulled
`jackson-dataformat-yaml` in and the YAML converter was first in line; fixed by `6bb80a6` the same
day. Worth a regression test asserting the JSON converter's position — the code comment already warns
someone will "simplify" it back.

### I13 — `application/octet-stream` bodies not parsed &nbsp;`mostly fixed · residual`

48 rows `RestClientException: Error while extracting response…` (peak Aug 31: 26; last Sep 16), across
Signzy PAN, Fintrix PAN/bureau, Digitap UAN/bureau; the `lenientJson` converter fix covers JSON bodies
mislabelled as octet-stream, but a residual 1–2/day remain where the body is not JSON at all. The audit
row stores `response=null` for these, so they cannot be diagnosed after the fact. **Fix:** capture the raw
body (first 4 KB) into the audit row on parse failure.

### I14 — Input validation gaps that reach a vendor &nbsp;`LOW · ours (data)`

Future date of birth (`400 "date_of_birth is greater than current_date"`, row 19671, DOB 2026-10-19 on
2026-09-16 — the consent page's DOB input has no `max`); malformed emails (3 Signzy 400s); a null
beneficiary name on a penny drop (1 Signzy 409). Each spends a vendor call to learn what a form check
would have caught.

### I15 — Digitap UAN `505 "Source is busy or unavailable. Try again later"` &nbsp;`vendor · ongoing`

153 rows / 145 applications across 26 days (~6/day, still on Sep 17): the EPFO source behind Digitap.
Genuinely transient and unbilled, but there is no retry — 145 employment checks sit in `REVIEW
"unavailable"`. Covered by the I10 scheduled re-run; raise the frequency with Digitap (§4).

### I16 — Digitap Experian `503 "source error"` &nbsp;`vendor · transient`

18 rows, 16 of them Sep 16 08:10–11:56. Fell through to Fintrix, which answered 14 of 18. Working as
designed; report the incident to Digitap.

---

## 3. Fallback logic: what is right, what is wrong

**Working as designed (do not change):** `CapabilityNotSupportedException` = skip; `VerificationException`
= fall through; bureau no-hit walks on to the next bureau; `answerBureauChallenge` bypasses the chain
(an `order_id` belongs to one vendor); the 3-attempt KBA cap; the drawn-signature eSign fallback;
5xx/transport fall-through — it rescued 15/15 during the Fintrix outage and 14/18 during Digitap's.

**Incorrect fallback scenarios found in the data:**

| # | Scenario | Rows | Extra vendor calls | Billed? |
|---|---|---:|---|---|
| 1 | Signzy `404 Pan Number Not Found` (definitive) → Digitap 412 → Fintrix "Invalid PAN" | 58 | up to 2 per PAN; 41 Fintrix PAN calls observed | **Fintrix: yes** (documented always-billable) |
| 2 | Any Signzy PAN/EMAIL failure → Digitap PAN/EMAIL (never provisioned, 100 % 412) | 135 | 1 dead call each | no (precondition) |
| 3 | Experian `102` masked-mobile → Fintrix CRIF (since Sep 11; code assumed Digitap was last) | 25 | 1 CRIF pull each (+ KBA attempts) | **Fintrix: yes**; 3/14 recovered a report — keep, but make deliberate |
| 4 | Fintrix KBA `S11` (new question) parsed as failure → borrower answers stale question → `S02` | 108 | ~2 of 3 answers wasted per affected borrower | **yes** |
| 5 | DigiLocker `409 Upstream Down` → 45 polls per page load, unbounded sessions | 654 | 183 calls / 20 min for one borrower | unknown — ask Signzy |
| 6 | DigiLocker `400 User denied consent` (terminal) → polled 23× | 23 | 22 | unknown |
| 7 | DigiLocker `200` with `validAadhaarDSC=no` (permanent) → treated as not-ready → **820 successful fetches** | 820 (SUCCESS) | 819 | **potentially yes** — largest single item |
| 8 | Employment `REVIEW` re-run with `uan` appended → always 400; would double-bill on success | 378 | 1 each (400, unbilled) | no today; yes by design if it worked |
| 9 | Signzy preprod bureau 403 → 403 → third vendor (historical) | 462 | 2 dead calls per attempt | no |
| 10 | Experian `422` rich file → rethrown under the old order, app re-ran the chain 4× | 16 | repeated Experian pulls on apps 117/172 | Experian pull: not documented |

**Not a fallback problem but inflates every failure metric:** ~1,939 phantom `FAILED` rows (I2).

### 3.1 Recommended behaviour per error signature

| Vendor · endpoint | Signature | Class | Today | Should |
|---|---|---|---|---|
| Signzy PAN | `404 NOT_FOUND "Pan Number Not Found"` | definitive answer | fall through ×2 | **stop**, record "PAN not found" |
| Signzy PAN | `409 "Error in getting data from Upstream"` | transient | fall through | fall through (keep) |
| Signzy any | `415` / transport `null` | our client | fall through | fall through; fixed at source |
| Signzy DigiLocker | `401` (no body) | not ready | poll | poll (keep, 45 × 4 s) |
| Signzy DigiLocker | `409 "Upstream Down"` | vendor outage | poll 45× per load, unbounded sessions | back off 30 s, cap ~6, then manual path; server cap on sessions |
| Signzy DigiLocker | `400 AUTH_FAIL "User denied the consent"` | terminal | poll | stop; offer restart / manual upload |
| Signzy DigiLocker | `200`, `validAadhaarDSC:"no"` | permanent | poll (820 fetches) | stop; `REVIEW validDsc=false`; never re-fetch |
| Signzy Liveness | `404 not completed` | in progress | poll, recorded SUCCESS | poll; record as `POLL`, not SUCCESS |
| Signzy eSign | `400 "callbackUrl is not allowed to be empty"` | our config | drawn-signature fallback, silent | fix config; fail startup if blank; alarm on 100 % failure |
| Signzy Experian/CRIF | `403 "No remaining API credits"` | account | retired | keep retired |
| Digitap UAN | `200 result_code 103/104` | answer | correct (`REVIEW`), but audit `FAILED` | record as answer |
| Digitap UAN | `400 "parameters format is wrong or missing"` | our request | `REVIEW "unavailable"` | fix request (Method-3 only); don't re-run a found record |
| Digitap UAN | `402 Insufficient Balance` | account | `REVIEW`, no retry | alert; retry after top-up |
| Digitap UAN | `505 "Source is busy"` | vendor transient | `REVIEW`, no retry | bounded scheduled retry |
| Digitap UAN | `412` (Advanced variants) | not provisioned | n/a (never called) | keep on Basic V3 |
| Digitap PAN / EMAIL | `412 Precondition Failed` | not provisioned | called on every Signzy failure | `CapabilityNotSupported` until provisioned |
| Digitap Experian | `200 result_code 102 masked-mobile` | answer (records exist elsewhere) | throw → falls to Fintrix | walk on as an unacceptable answer, keep the hint |
| Digitap Experian | `200 result_code 103` | no record | no-hit → walk on | keep |
| Digitap Experian | `422 trade line count exceeds limit` | vendor limit | fall through (now) | keep; specific review message |
| Digitap Experian | `503 "source error"` | vendor transient | fall through | keep |
| Digitap Experian | `400 "date_of_birth is greater than current_date"` | our data | fall through | validate before any call |
| Fintrix `crif_combine` | `200 status:error "No data found in CRIF"` | no-hit | returned as no-hit; audit `FAILED` | record as answer |
| Fintrix `crif_combine` | `200 status:error "Solve the Auth Questions"` | KBA | parked; audit `FAILED` | record as answer |
| Fintrix `crif_combine` | `200 statusCode:400 "Missing required field name"` | our request | throws; guarded upstream since | keep the guard |
| Fintrix `bureau_ch_user_auth` | `200 error_message{status:S11}` | new question | thrown as failure | replace stored question, re-park |
| Fintrix `bureau_ch_user_auth` | `200 error_message{status:S02}` | exhausted | thrown as failure | terminal; stop; staff review |
| Fintrix any | `402 insufficient_balance` | account | fall through to Digitap (worked) | keep + alert |
| Fintrix any | `500 "Source Down"` / `502` | vendor transient | fall through | keep |
| Fintrix PAN | `200 "Invalid PAN"` | answer | thrown | never reached if I7 is fixed |

---

## 4. What to raise with each vendor

**Signzy**
1. **Billing of `geteAadhaar`:** are `409`/`401` responses billed, and are repeated *successful* fetches
   of the same `requestId` each billed? Application 2631, 2026-08-30 10:25–13:53 UTC: 62 `createUrl` +
   820 `geteAadhaar` 200s (request ids are in our audit rows). Request a credit review.
2. `409 "Error From Upstream / Upstream Down"` on `geteAadhaar`: Aug 25, Aug 30, Sep 2, Sep 7, Sep 11,
   Sep 12, Sep 16 — is there a status page / SLA for the DigiLocker dependency, and a documented retry
   guidance?
3. `validAadhaarDSC:"no"` for one borrower's document — document defect or a validation issue on the
   Signzy side?
4. PAN `409 "Error in getting data from Upstream"`, Aug 31 10:16–12:50 UTC — incident report.
5. (No ask on `callbackUrl` — their spec says mandatory; the defect is ours.)

**Digitap**
1. UAN-Basic V3 **Method 3**: confirm the exact payload when `uan` is supplied (alone? with `pan`?) —
   send 3–5 `request_id`s from the `400` rows (e.g. rows 19745, 19682, 19629); and what the name-less
   `pan+mobile+dob` requests violate.
2. Provision — or formally decline — **PAN Details Plus** and **Email Verification v1** (`412` on 100 %
   of calls since Aug 22) and UAN Advanced V4.
3. Low-balance webhook/e-mail (27 h at zero, Sep 8–9).
4. `505 "Source is busy or unavailable"` on UAN, ~6/day for 26 days — EPFO dependency SLA.
5. Experian `503 "source error"`, Sep 16 08:10–11:56 UTC — incident report.
6. `422 "trade line count exceeds limit"` — what is the limit, and is there a product that returns the
   full report for rich files?
7. Written `result_code` semantics per product (102/103 mean opposite things on CRIF vs Experian).

**Fintrix**
1. Official response schema and **status-code list for `/bureau_ch_user_auth`** (`S11`, `S02`, …) —
   the object-shaped `error_message` is undocumented on our side and is the cause of I4.
2. Low-balance webhook (10 h at zero, Aug 31 → Sep 1).
3. `500 "Source Down"` (26) and `502` (13) on `crif_combine`, clustered Aug 23–31 — incident report.
4. Confirm billing for: no-hit pulls, KBA-gated pulls, each `bureau_ch_user_auth` attempt, and
   `pan_comprehensive` "Invalid PAN" responses.

---

## 5. Potential unnecessary charges (whole window)

Billing rules documented in this repo: eSign initiate is billed on success only; every Fintrix call is
billable; UAN bills only on `result_code 101`. Everything else is inferred and marked.

| Item | Calls | Confidence | Note |
|---|---:|---|---|
| Fintrix `pan_comprehensive` on PANs Signzy had already declared not found | **41** | high (Fintrix always billable) | I7 |
| Fintrix KBA answers spent on a stale question | **~30** of 108 | medium | I4; ~2 of every 3 per affected borrower |
| Fintrix `crif_combine` after Experian `102` (since Sep 11) | **14+** | high that they are billed; whether they are *wasted* is a policy call (3 recovered a report) | I8 |
| Signzy `geteAadhaar` successful re-fetches, app 2631 | **819** | unknown — rule undocumented; potentially the largest item | I5(c) |
| Signzy `geteAadhaar` `409`/`401`/`400` polls | 736 | probably unbilled | I5(a,b) |
| Digitap PAN/EMAIL `412` dead legs | 135 | probably unbilled | I6 |
| Signzy preprod bureau `403` | 462 | unbilled (no credits) | I11, closed |
| Calls during `402` windows | 308 | unbilled | I10 |
| Experian pulls repeated on `422` apps under the old chain | ~20 | rule undocumented | I9 |
| Phantom `FAILED` rows | ~1,939 | **zero** — unless an operator re-runs "failed" calls (the employment backfill script re-runs `REVIEW` rows by design; each `101` re-resolution is billed) | I2 |

---

## 6. Concrete code and configuration changes

Ordered by impact; each preserves the behaviour named in "keeps".

1. **eSign config + guard** — SSM `/navix/<env>/navix/esign/callback-url` + `callback-secret`, task-def
   revision; `EsignConfig`: throw at startup when `provider=signzy` and `callbackUrl` is blank;
   `SignzyEsignAdapter.initiate`: `blankToNull` is *not* the fix (the vendor requires the field) — fail
   fast. *Keeps:* drawn-signature fallback for genuine provider outages.
2. **Audit status = caller outcome** (`ProviderJson`, `ProviderCall`) — add `ANSWERED` (or
   `SUCCESS` + `outcome`) written by `postAllowingErrorEnvelope` and by an endpoint-specific expected-answer
   predicate; delete the global `result_code != 101` rule; log answers at INFO; dashboard filter
   `status=FAILED` then means failed. *Keeps:* every call still recorded with full payloads.
3. **Fintrix KBA answer parser** (`FintrixCrifClient.kbaChallenge/rejectUnlessNoRecord`,
   `ApplicationVerificationService.answerBureauChallenge`) — recognise the object envelope; `S11` → new
   `PendingChallenge`, replace stored question; `S02` → `bureauChallengeExhausted=true`, terminal, no
   re-mint. *Keeps:* the 3-attempt cap, the 60 s cooldown, option-membership check.
4. **Employment re-run** (`verifyEmployment`, `DigitapUanClient`, `submitted/page.tsx`) — Method-3
   payload when `uan` is present; short-circuit on an existing `found=true`; validate 12 digits; the
   "add UAN" action should re-run only when the previous result was *not found*. *Keeps:* advisory,
   never-blocking employment check.
5. **DigiLocker classification** (`SignzyVerificationAdapter.digilockerAadhaar`, `digilockerComplete`,
   both DigiLocker pages) — `401` not-ready; `409 Upstream Down` → `DIGILOCKER_UPSTREAM_DOWN` with
   30 s backoff, cap, manual path; `400 AUTH_FAIL` → `DIGILOCKER_CONSENT_DENIED` terminal; `200`
   `validDsc=false` → `REVIEW`, terminal; server-side `createUrl` cap per application per 24 h; persist
   the retry counter in `derived`. Record liveness `404 not completed` as `POLL`. *Keeps:* redirect-driven
   completion, DB row as source of truth, 45 × 4 s poll for the genuine not-ready case.
6. **Dead legs off** (`DigitapVerificationAdapter.verifyPan/verifyEmail`) — `CapabilityNotSupportedException`
   behind `digitap-pan` / `digitap-email` flags (default off). *Keeps:* Signzy → Fintrix PAN fallback.
7. **PAN definitive negative** (`SignzyPanClient`, `RoutingVerificationPort.verifyPan`) — Signzy
   `404 NOT_FOUND` → terminal answer "PAN not found"; fall-through only on 5xx / `409` upstream / transport.
   *Keeps:* rescue on Signzy outages (2 recovered on Aug 31).
8. **Masked-mobile as an answer** (`DigitapCreditClient`) — return `noRecord=true, maskedMobileHint=true`
   instead of throwing; consumer sets `bureauMaskedMobileRequired` when the chain ends in a no-hit with
   the hint; fix `RoutingVerificationPort` and `DigitapCreditClient` comments to the Sep-11 order.
   *Keeps:* no-hit fall-through to CRIF.
9. **Balance and outage handling** — CloudWatch metric filter + alarm on `PROVIDER_BALANCE_EXHAUSTED`;
   a generic "capability at 100 % failure for 24 h" alarm (would have caught I1, I6 within a day);
   bounded scheduled re-run of `EMPLOYMENT` rows parked with `HTTP_402/HTTP_505/HTTP_503/TRANSPORT_FAILURE`.
10. **Input validation** — DOB `max=today` (consent page, profile edit, ADMIN correction) + server check;
    email format; penny-drop beneficiary name required.
11. **Diagnosability** — store the first 4 KB of an unparseable body in the audit row; regression test
    pinning the JSON converter's position.
12. **Data repair** — ADMIN bureau retry for the 9 `422` applications; re-run employment for the 173
    applications parked during the Sep 8–9 balance outage and the 145 parked on `505`.

---

## 7. Caveats

* Billing rules for most endpoints are not documented in the repository; §5 marks confidence per item.
  The single biggest potential charge (I5c) hinges on Signzy's `geteAadhaar` pricing — confirm before
  acting on the number.
* The 17 `MANUAL` (admin workbench) failures were excluded; they are probes, not traffic.
* `SUCCESS`-side analysis (poll storms, per-endpoint rates) used the 368 traced applications plus
  direct totals; a full pass over the 14,918 success rows was not performed.
* CloudWatch was not accessible from this session, so the `request_id` join to access logs was not
  used; every conclusion rests on the audit rows and the code.
* Reading the history required an ADMIN login (`force=true`), which ended whichever admin session was
  active at the time.

**Working files (session scratch, not committed):** `failures_all.json` (4,848 rows),
`failures_recent.json` (200 with payloads), `failures_supplement.json` (428 with payloads),
`chains/<applicationId>.json` (368), `fallback_trace.json` (617), `slices/*.json` (9 classes),
`success_totals.json`, and the workflow transcript `wf_03fc1ca3-cdc`.
