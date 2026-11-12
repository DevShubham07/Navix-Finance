# 02 — Borrower: offer journey, active loan, repay, reborrow, account

This file covers everything the **borrower** does after a Credit Executive sanctions the file. That is the `/loan/*` offer journey, the hand-off to disbursement, live tracking, the active-loan dashboard, repayment (manual and PayU), loan history, reborrow and the account menu.
Every rule below comes from the code (frontend `app/(borrower)/**`, `lib/offer.ts`, `lib/api/live-journey.ts`, backend `OfferService`, `JourneyService`, `ApplicationVerificationService`, `MandateService`, `RepaymentService`, `PayuService`, `ApplicationFlowService`). Where docs disagree, the code wins and the disagreement is listed in §Z.
Money is integer paise; screens show whole rupees (`formatINR0` = `Math.round(paise/100)`, `paiseToINR` keeps paise when present). "IST" = Asia/Kolkata.
Use-case ids are `UC-B2-nn`. Intake (`/signup/*`) is file 01; staff-side decisions (credit, disbursement, accountant verify, collections) are files 03/04.
Endpoints are named only where they clarify; payload shapes are deliberately omitted.

## Table of contents

- [A. Journey frame](#a-journey-frame)
  - UC-B2-01 Enter / resume the offer journey (server-driven step list)
  - UC-B2-02 Retired and legacy routes
- [B. Offer journey screens](#b-offer-journey-screens)
  - UC-B2-03 Choose the amount
  - UC-B2-04 Acknowledge the locked repayment date
  - UC-B2-05 Connect DigiLocker
  - UC-B2-06 DigiLocker return (`/kyc/digilocker/callback`)
  - UC-B2-07 Manual Aadhaar-card upload fallback
  - UC-B2-08 Name two references
  - UC-B2-09 Reference OTP confirmation (`reference-otp` flag)
  - UC-B2-10 Loan summary
  - UC-B2-11 Selfie — camera capture + Digitap liveness (primary)
  - UC-B2-12 Selfie — Signzy video liveness (secondary)
  - UC-B2-13 Address — geolocation
  - UC-B2-14 Address — typed fallback
  - UC-B2-15 Read the Key Fact Statement / sanction letter
  - UC-B2-16 Sign with Aadhaar eSign
  - UC-B2-17 eSign return (`/kyc/esign/callback`)
  - UC-B2-18 Sign by drawing (fallback)
  - UC-B2-19 Sanctioned celebration
  - UC-B2-20 Confirm the salary account (₹1 check)
  - UC-B2-21 Send to a different account (penny drop, 3 strikes, 12 h lock)
  - UC-B2-22 Cancelled cheque / passbook fallback
  - UC-B2-23 Hand-off to disbursement (system)
  - UC-B2-24 Set up auto-repayment (Digio eMandate) inside the journey
  - UC-B2-25 Set up auto-repayment from a staff invite link
- [C. Tracking and the active loan](#c-tracking-and-the-active-loan)
  - UC-B2-26 Track the application (`/loan/status`)
  - UC-B2-27 Dashboard
  - UC-B2-28 Loan details dialog / inline loan detail
- [D. Repayment](#d-repayment)
  - UC-B2-29 See what is owed (repay quotes)
  - UC-B2-30 Pay manually (UPI / bank transfer + proof)
  - UC-B2-31 Pay online with PayU checkout
  - UC-B2-32 Return from PayU (`/repay?order=`)
  - UC-B2-33 Payment verified / rejected → loan closes
- [E. History](#e-history)
  - UC-B2-34 Past loans (`/loans`)
  - UC-B2-35 Past transactions (`/transactions`)
- [F. Reborrow](#f-reborrow)
  - UC-B2-36 Borrow again (`/reloan`)
  - UC-B2-37 Reborrow decision engine (system)
- [G. Account](#g-account)
  - UC-B2-38 Profile self-edit
  - UC-B2-39 Notification preferences (`/settings`)
  - UC-B2-40 Support, grievance and FAQ (`/support`)
  - UC-B2-41 Refer & earn card
  - UC-B2-42 Notification bell
  - UC-B2-43 Header, account menu and sign out
- [Z. Code ↔ doc contradictions and sharp edges](#z-code--doc-contradictions-and-sharp-edges)

---

## A. Journey frame

### UC-B2-01 Enter / resume the offer journey (server-driven step list)
- **Actor / entry point:** a signed-in borrower whose application is `SANCTIONED`. They arrive from one of these:
  - "Continue your loan" on `/loan/status`
  - the dashboard InfoCard ("You're approved" → `/loan/amount`)
  - the Next-milestone card ("Complete your offer steps")
  - a `LOAN_SANCTIONED` notification
  - a deep link to any `/loan/<step>`
- **Preconditions:**
  - A valid `navix_borrower` session.
  - The application id comes from the localStorage pointer `navix.live.applicationId`. If the pointer is missing, it is recovered from `GET /mine`, which picks the newest application that is not `KYC_REJECTED/REJECTED/CANCELLED/DISBURSEMENT_FAILED`.
  - If the application status is anything other than `SANCTIONED`, every offer step except `mandate` does `router.replace("/loan/status")` (`useOffer`).
  - **Onboarding-paused gate:** while `onboarding-paused` is on, only `/apply`, `/login`, `/settings` and `/support` are allowed. The gate is `OPEN` only for a customer with at least one `loan` row (`OnboardingWaitlistService.gateFor`). A first-time `SANCTIONED` borrower (no loan row yet) is therefore redirected to `/apply` by `OnboardingGate` while the flag is on (§Z16).
- **Screen (wizard chrome, `loan/layout.tsx`):** only the eleven offer segments get the chrome. `/loan/status`, `/loan/apply`, `/loan/salary`, `/loan/esign` render bare.
  - **Top bar, left:** "Step N of M". It is blank until the server's step list has loaded, so a short reborrow journey never flashes "Step 10 of 11".
  - **Top bar, right:** "Secure & encrypted" with a shield icon.
  - **Heading:** the step label:
    | # | Label |
    |---|---|
    | 1 | Choose your amount |
    | 2 | Repayment date |
    | 3 | Verify with DigiLocker |
    | 4 | Your references |
    | 5 | Loan summary |
    | 6 | Take a selfie |
    | 7 | Confirm your address |
    | 8 | Agreement & signature |
    | 9 | Sanctioned |
    | 10 | Where to send the money |
    | 11 | Auto-repayment setup |
  - **Progress bar:** width = round((index+1)/M × 100)%.
  - **Unknown segment:** if the segment is not in this borrower's list (mid-navigation), the chrome renders without a count.
  - **Footer of every step:** the Reassurance strip: "256-bit encrypted · Transparent terms · No advance fee".
- **Main flow:**
  1. The layout mounts → reads the stored app id → `GET /api/applications/{id}/journey`.
  2. The server returns `{step, route, index, total, completed, steps}` → the client stores `steps` (module-scoped; falls back to the full list minus `mandate`) → if `route` starts with `/loan/` and differs from the current path, `router.replace(route)`. This runs once per mount, so it never fights the Back button. It does not run on `/loan/mandate`.
  3. Each "Continue" calls `POST …/journey/advance {step}` (best-effort; failure is swallowed) and then navigates to the next segment in the server list. If there is no next segment, it goes to `/loan/status`.
- **Business rules — where the borrower resumes (`JourneyService.currentOfferStep`):**
  - The resume point is `max(derived step, stored pointer)`. The pointer is never allowed past `OFFER_SANCTION_LETTER` unless an `ESIGN` row is `PASS`.
  - A historical `OFFER_ESIGN` pointer normalises to `OFFER_SANCTION_LETTER`.
  - **Derivation, first unmet wins:**
    | Condition | Step |
    |---|---|
    | no `amount_requested` | `OFFER_AMOUNT` |
    | no `AADHAAR` verification row (any status, excluding staff-reopened rows) | `OFFER_DIGILOCKER` |
    | fewer than 2 references, or any reference OTP-pending | `OFFER_REFERENCES` |
    | no `SELFIE` row | `OFFER_SELFIE` |
    | no `ADDRESS` row | `OFFER_ADDRESS` |
    | `ESIGN` not `PASS` | `OFFER_SANCTION_LETTER` |
    | `disbursal_confirmed_at` null | `OFFER_DISBURSAL_ACCOUNT` |
    | `mandateRequired` | `OFFER_MANDATE` |
    | otherwise | `OFFER_DONE` (route `/loan/status`) |
  - Repayment-date, summary and sanctioned leave no trace of their own, so only the stored pointer can hold a borrower on them.
  - **Applicable steps (`applicableOfferSteps`):**
    - **First-time borrower:** all eleven minus `OFFER_ESIGN`/`OFFER_DONE`. `OFFER_MANDATE` is dropped when `mandateRequired` is false (flag `digio-mandate` off, or the customer already holds an ACTIVE mandate with a UMRN on this account covering the ceiling).
    - **Reborrow (`reappliedFrom` set):** the following are also dropped when carried over:
      - `OFFER_DIGILOCKER` if an `AADHAAR` row exists
      - `OFFER_REFERENCES` if ≥2 references exist
      - `OFFER_SELFIE` if a `SELFIE` row exists
      - `OFFER_ADDRESS` if an `ADDRESS` row exists
    - The list is keyed on `reappliedFrom`, so it is stable for the whole journey.
  - The pointer only moves forward (`advance`). `rewind` exists for staff "resume link" reopen and for a changed reference number.
- **Alternate & error flows:**
  - Journey fetch fails → silently ignored (the page renders where it is).
  - Advance fails → ignored; the server re-derives from saved data.
- **Postconditions:** `loan_application.journey_step` updated (shared column with intake; only `OfferStep` names are honoured while `SANCTIONED`).
- **Edge cases:**
  - A sanction never expires.
  - The tenure is recomputed daily from today to `approved_repayment_date`, never below 1 day, even when that date is in the past.

### UC-B2-02 Retired and legacy routes
- **Actor / entry point:** old bookmarks or links.
- **Preconditions:** None.
- **Behaviour:**
  | Route | Behaviour |
  |---|---|
  | `/loan/apply` | client redirect → `/loan/status` ("Retired in Phase 1"; the borrower no longer picks against 25 % of salary) |
  | `/loan/salary` | client redirect → `/loan/status` (this is where `/reloan` sends a successful reborrow — see UC-B2-36) |
  | `/loan/esign` | server `redirect("/loan/sanction-letter")` |
  | `/kyc` | spinner "Loading your application status…" → `/loan/status` |
- **Postconditions:** none.
- **Screen:** No screen of its own; only a redirect (or a spinner on `/kyc`).
- **Main flow:** The borrower opens the old URL and is redirected as shown in the table.
- **Business rules:** These routes are never steps and render without wizard chrome.
- **Alternate & error flows:** None.
- **Edge cases / not allowed:** `/loan/salary` is still the target of `/reloan` (see §Z4).

---

## B. Offer journey screens

### UC-B2-03 Choose the amount
- **Actor / entry point:** borrower, step 1, `/loan/amount`.
- **Preconditions:**
  - Status `SANCTIONED`; `sanctioned_amount_paise` set by credit (or carried over on reborrow).
  - Not yet signing: no `ESIGN` row in `PASS` or `PENDING`.
- **Screen:**
  - **Loading:** "Loading your offer…" spinner.
  - **Intro:** "You are eligible for up to **₹{ceiling}**, approved by our credit team." Sub-copy: "Take all of it or less — you only pay interest on what you draw."
  - **Card "Loan amount":**
    - Big figure showing the current value.
    - Range slider: `min = min(₹1,000, ceiling)`, `max = ceiling`, `step = ₹500`.
    - Under the slider: "₹{min}" on the left, "Approved ₹{ceiling}" on the right.
    - Preset buttons 25 % / 50 % / 75 % / 100 %, each `max(min, round(ceiling × f / 500) × 500)`. The pressed preset is navy.
  - **Default value:** the previously chosen `amountRequested`, otherwise the full ceiling.
  - **Cost breakdown card** (`LoanCostBreakdown`, client-side preview):
    | Row | Value |
    |---|---|
    | Loan amount | P |
    | Processing fee ("10% of loan amount") | `− max(0, round(P×0.10) − round(P×discount%/100))` |
    | GST on fee ("18% of processing fee") | `− round(fee×0.18)` |
    | **Net amount to your bank** | P − fee − GST |
    | Interest ("1%/day × N days") | `+ round(P×0.01×N)` |
    | **Total repayable** | P + interest |
    - N = `max(1, round((repaymentDate − now)/1 day))`, or 30 if no date.
    - The discount % comes from the server summary (`feeDiscountPercent`, pinned at reborrow). If the summary is not readable yet it is 0 (preview only).
  - **Footer line:** "Repayable in one instalment on **{date}** — the date our credit team set for you."
  - **Buttons:** Continue (disabled while busy or value < min). No Back (first step).
- **Main flow:**
  1. The borrower drags or taps a preset.
  2. Continue → `POST /offer/amount {amountPaise}`.
  3. The server sets `amount_requested` and advances the pointer to `OFFER_REPAYMENT_DATE`.
  4. The client records `OFFER_AMOUNT` and goes to the next step.
- **Business rules:**
  - Server: `AMOUNT_TOO_LOW` "Requested amount is below the minimum of ₹1,000" if < 100 000 paise.
  - Server: `LIMIT_EXCEEDED` "Requested amount exceeds the sanctioned amount" if > ceiling.
  - Server: `TERMS_LOCKED` "You have started signing your agreement, so the loan amount can no longer be changed." if the ESIGN row is PASS **or PENDING**.
  - The application stays `SANCTIONED`.
- **Alternate & error flows:**
  - `TERMS_LOCKED` → the client re-reads the journey and `replace`s to the server route (no message).
  - Any other error → inline red text (server message, else "Could not save your amount — please try again.").
- **Postconditions:** `amount_requested` saved. No event, no notification.
- **Edge cases / not allowed:**
  - Presets round to ₹500, so 100 % can exceed a non-multiple ceiling (e.g. ceiling ₹10,300 → preset ₹10,500 → `LIMIT_EXCEEDED`).
  - A ceiling below ₹1,000 makes `min = ceiling`, but the server refuses anything below ₹1,000.
  - The amount freezes from the first successful Aadhaar eSign initiate (ESIGN row PENDING), even though nothing has been signed yet.
  - Only a provider-reported "completed, not signed" (REVIEW, seen through the callback poll) unlocks it. A lapsed contract is re-minted as a new PENDING row, so it stays locked.
  - A borrower who never returns to the callback stays locked until they sign.

### UC-B2-04 Acknowledge the locked repayment date
- **Actor / entry point:** step 2, `/loan/repayment-date`.
- **Preconditions:** `SANCTIONED`; `approved_repayment_date` set by credit (or recomputed at reborrow).
- **Screen:**
  - **Copy:** "Your repayment date is set by our credit team." and "Repay in one instalment on this date. You can always pay earlier — interest is charged only to the day you pay."
  - **Month grid:** the due date's month. Weekday initials S M T W T F S. Every day is inert except the due day, which is highlighted navy (`aria-current="date"`).
  - **Box under the grid:** the date in large type, plus "🔒 Fixed by our credit team — this date cannot be changed."
  - **No date set:** "Your repayment date will appear here once our credit team sets it." Continue is disabled.
  - **Buttons:** Back (previous step). The main button is labelled **"Lock this date"**; after the first press it becomes **"Request for loan"**.
- **Main flow:**
  1. First press → local `locked = true` only.
  2. Second press → advance `OFFER_REPAYMENT_DATE` → next step.
- **Business rules:** the borrower can never change the date. Only credit roles set it at `sanction` (salary day → latest salary date ≤ 40 days).
- **Postconditions:** pointer only.
- **Alternate & error flows:** No server call can fail here; a failed pointer advance is ignored.
- **Edge cases / not allowed:** The date can be in the past (a sanction never expires). It is still shown and locked; the tenure is clamped to at least 1 day.

### UC-B2-05 Connect DigiLocker
- **Actor / entry point:** step 3, `/loan/digilocker`. It is dropped from a reborrow that carried an `AADHAAR` row.
- **Preconditions:** `SANCTIONED`.
- **Screen:**
  - **Header:** building icon. Heading "Connect DigiLocker". Copy: "You'll head to DigiLocker to share your Aadhaar securely. We only receive the details you approve — never your password."
  - **Checklist:** "Government of India digital identity · Consent-based, read-only access · Bank-grade encryption".
  - **Phases:**
    | Phase | What shows |
    |---|---|
    | idle | gold button "Continue with DigiLocker →", plus link "Can't connect DigiLocker? Upload your Aadhaar card instead" |
    | connecting | spinner "Opening DigiLocker…" |
    | polling | spinner "Waiting for you to finish in the DigiLocker tab…" |
    | done | "Continuing… →" plus the result banner |
    | failed, retries < 3 | button "Try DigiLocker again", "{n} attempt(s) remaining", and the upload link |
    | failed, retries ≥ 3 | grey box "DigiLocker didn't connect after a few tries. No problem — upload your Aadhaar card instead and our team will verify it during review." with button "Upload Aadhaar card instead" |
    | manual | see UC-B2-07 |
  - **Result banner (`StepResultBanner`):**
    - PASS green, "Verified."
    - REVIEW amber, "Flagged for manual review — you can still continue."
    - FAIL red, "We couldn't verify this — please try again."
    - PENDING neutral, "Verifying…"
    - The server message is shown instead when present.
  - **Back:** link to the previous step.
- **Main flow:**
  1. The borrower presses Continue. Client cap: `MAX_RETRIES = 3` per page life. The counter resets on reload.
  2. The client builds `redirectUrl = {origin}/kyc/digilocker/callback?app={id}&sid={nonce}`. The nonce is per attempt, because the provider caches sessions per redirect URL.
  3. `POST verify/digilocker/init`. The server counts consent sessions in a rolling 24 h window per application (`MAX_DIGILOCKER_SESSIONS_PER_DAY = 5`).
  4. **Under the cap:** the provider mints a session (20-minute expiry parameter). The server writes a `DIGILOCKER` row `PENDING` with the URL.
  5. The client calls `window.location.assign(url)` (same tab). A fallback poll every 4 s, up to 45 polls (~3 min), calls `verify/digilocker/status`.
  6. Consent finishes → DigiLocker redirects to the callback (UC-B2-06).
- **Business rules (status poll):**
  - Our own row is authoritative:
    - `AADHAAR` PASS → PASS (`completed:true`).
    - Signature-invalid REVIEW → REVIEW (terminal).
    - No session → PENDING "not_started".
    - Provider blip → PENDING.
  - Poll results on this page:
    | Result | Action |
    |---|---|
    | PASS | advance after 600 ms |
    | `derived.completed` but not fetched | call `complete` from this page, up to 10 attempts × 4 s on `DIGILOCKER_NOT_READY` |
    | failed / FAIL | phase "failed" |
    | 45 polls reached | drop to manual upload (never advance without proof) |
- **Alternate & error flows:**
  - **Session cap reached:** the server returns REVIEW "DigiLocker could not be completed after several attempts — please upload your Aadhaar card instead." with `sessionsExhausted:true` → straight to the manual upload. "Try DigiLocker again instead" is hidden.
  - **Provider cannot start a session:** REVIEW "DigiLocker is temporarily unavailable — you can continue; our team will verify your Aadhaar during review." with no URL → banner shown → manual upload after 900 ms.
  - **Finalise errors on this page:**
    | Code | What the borrower sees |
    |---|---|
    | `DIGILOCKER_UPSTREAM_DOWN` / `DIGILOCKER_PROVIDER_ERROR` | manual upload |
    | `DIGILOCKER_CONSENT_DENIED` | "You declined the DigiLocker consent, so we couldn't read your Aadhaar." (failed) |
    | not ready after 10 attempts | "DigiLocker is taking longer than usual." |
    | other | "Could not finalise DigiLocker." / "Could not start DigiLocker." / "Lost connection to DigiLocker." |
  - `?manual=1` in the URL opens the manual phase directly (used by the callback page).
- **Postconditions:** `DIGILOCKER` row PENDING; `customer_profile.digilocker_client_id` set. Session counter carried in the row's `derived`.
- **Edge cases:** there is no silent skip — every path that used to bypass DigiLocker now leads to the mandatory card upload.

### UC-B2-06 DigiLocker return (`/kyc/digilocker/callback`)
- **Actor / entry point:** DigiLocker redirects the browser here after consent. The `app`/`sid` params are ignored; the app id is read from localStorage.
- **Preconditions:** a stored app id. If none → "Couldn't confirm DigiLocker".
- **Screen (centred card):**
  - **Working:** spinner. "Confirming your consent" / "Fetching your Aadhaar details from DigiLocker. This takes a moment…"
  - **Done:**
    - Normal: "Aadhaar verified" / "Your identity is confirmed via DigiLocker. You can carry on with your loan."
    - Signature-invalid variant: "Aadhaar received" / "We have your Aadhaar from DigiLocker and our team will verify it during review. You can carry on with your loan."
    - Button "Continue →" → `/loan/references` (hard-coded).
  - **Unavailable:** "DigiLocker is temporarily unavailable" / "DigiLocker isn't responding right now. Upload your Aadhaar card instead and we'll verify it during review, or come back to this step a little later." Buttons: "Upload my Aadhaar instead" → `/loan/digilocker?manual=1`; "Try again later" → `/loan/status`.
  - **Denied:** "You declined the DigiLocker consent" / "We can't read your Aadhaar without your approval. Start DigiLocker again and accept the consent screen to carry on." Button "Start DigiLocker again" → `/loan/digilocker`.
  - **Rejected:** "Your application has been rejected" / "We're unable to take your application forward. If you believe this is a mistake, please contact our support team." Buttons "Contact support" (`/support`) and "Back to home" (`/`). All borrower client state is cleared.
  - **Failed:** "Couldn't confirm DigiLocker" / "Please return to your application and try the DigiLocker step again." Button "Back to DigiLocker".
- **Main flow:** `POST verify/digilocker/complete` → the server fetches the parsed e-Aadhaar.
  1. **Readiness:** a blank name and blank masked number → `DIGILOCKER_NOT_READY`. Retried every 4 s, up to 45 attempts.
  2. **Signature (DSC) not valid, or absent:**
     - `AADHAAR` REVIEW "Aadhaar signature could not be validated — manual review" (demographics kept).
     - `DIGILOCKER` REVIEW `validDsc:false`. The client id is cleared, so the document is never fetched again.
     - The page shows "Aadhaar received".
  3. **DOB:** the Aadhaar DOB overwrites the profile DOB unless it mismatches the DOB entered at intake (1 Jan ⇒ year-only compare). A blank profile address is filled from the Aadhaar address.
  4. **Fraud rule:** the typed Aadhaar is compared with the masked last four (`aadhaarMismatch`), and the DOB is compared too.
     - Any mismatch → `AADHAAR` row FAIL "Aadhaar mismatch: …", `aadhaarVerified=false`, `derived.applicationRejected=true`.
     - Then `autoRejectSystem(FRAUD_REJECTED, 365 days)` if the status allows REJECTED → the page shows "rejected". The reason is never shown.
  5. **Otherwise:**
     - `AADHAAR` PASS "Aadhaar fetched from DigiLocker".
     - The e-Aadhaar PDF is ingested to S3 (`AADHAAR` doc), plus the optional JPEG (`AADHAAR_JPEG`) and the face photo (`AADHAAR_PHOTO`, used by the Signzy selfie face-match). Each ingest is best-effort.
     - If the name match vs PAN is > 0 and < 0.60 → the row becomes REVIEW "Name mismatch vs PAN — manual review".
     - `DIGILOCKER` row PASS. The single-use client id is cleared.
- **Business rules — two independent retry budgets:**
  - not-ready: 4 s × 45 attempts
  - upstream-down / provider-error: 30 s × 6 attempts, then "unavailable"
  - Consent denied is terminal for that session.
- **Postconditions:**
  - On PASS: the `aadhaar_verified` profile flag is set.
  - On rejection: application → `REJECTED`, an `application_rejection` row (`FRAUD_REJECTED`, blocked 365 days), and a `CREDIT_REJECTED` notification with no reason.
- **Edge cases:**
  - The "Continue" target is hard-coded to `/loan/references`. The layout guard then corrects it to the server route if references are already done.
  - A repeat `complete` after PASS returns the stored row (idempotent).
- **Alternate & error flows:** See the per-phase cards above. No stored app id leads to the failed card.

### UC-B2-07 Manual Aadhaar-card upload fallback
- **Actor / entry point:** inside `/loan/digilocker`. Reached by:
  - the "Can't connect…" link
  - three failed tries
  - the 24 h session cap
  - a provider outage
  - the 3-minute poll timeout
  - the callback's "Upload my Aadhaar instead"
- **Preconditions:** Signed-in borrower; application `SANCTIONED`; the earlier offer steps are satisfied (UC-B2-01 guard).
- **Screen:**
  - **Heading:** "Upload your Aadhaar card instead". Copy: "DigiLocker didn't connect. Upload clear photos or scans of both sides of your Aadhaar card and we'll verify it manually before your advance is sent."
  - **Two file inputs:** "Aadhaar front" and "Aadhaar back". Each shows "Selected: {name}" once chosen.
  - **Buttons:**
    - "Upload & continue" — disabled until both files are chosen; "Uploading…" while busy.
    - "Try DigiLocker again instead" — shown only while retries < 3 and the session cap is not exhausted.
- **Field validation (`checkDocumentFile`):**
  - Accepted when the MIME type **or** the extension is one of pdf / jpeg / jpg / png / heic / heif / webp. Otherwise: "Upload a PDF or a photo (JPG, PNG or HEIC)."
  - Max 10 MB: "File must be under 10 MB."
  - Images are re-encoded by `compressImage` before upload.
- **Main flow:**
  1. For each side: presign (`docType AADHAAR_FRONT` / `AADHAAR_BACK`) → browser PUT to S3 → register the uploaded document.
  2. `POST verify/aadhaar/manual` → `recordAadhaarProofPending`.
  3. Advance `OFFER_DIGILOCKER` → next step.
- **Business rules:**
  - Server: `AADHAAR_PROOF_REQUIRED` "Upload both sides of your Aadhaar card first" if either doc is missing.
  - Writes the `AADHAAR` row as **REVIEW** (provider `MANUAL_PROOF`, "Aadhaar card uploaded — awaiting manual verification"). It must never be PASS.
  - The Disbursement Head or a reviewer judges it before release.
  - These doc types are different from intake's `AADHAAR_CARD_FRONT/BACK`.
- **Alternate & error flows:**
  - "Choose both the front and back of your Aadhaar card first."
  - Upload failure: "Could not upload your Aadhaar card — please try again."
  - Storage down: `UPLOAD_UNAVAILABLE` "We can't accept uploads just now. Please try again in a few minutes."
- **Postconditions:** two `application_document` rows; the `AADHAAR` row is REVIEW, which satisfies the journey step.
- **Edge cases / not allowed:** A REVIEW AADHAAR row satisfies the journey but never counts as identity proof; it must not be written as PASS.

### UC-B2-08 Name two references
- **Actor / entry point:** step 4, `/loan/references`. Dropped on a reborrow that carried ≥2 references.
- **Preconditions:** Signed-in borrower; application `SANCTIONED`; the earlier offer steps are satisfied (UC-B2-01 guard).
- **Screen:**
  - **Banner:** gift icon, "Refer & Earn Gifts and Vouchers". Copy: "Add two people who know you. We may contact them only if we can't reach you." This is not connected to the referral program.
  - **Two fieldsets ("Reference 1", "Reference 2"), each with:**
    | Field | Type | Placeholder | Error |
    |---|---|---|---|
    | Full name | text, required | "Ravi Kumar" | "Enter their full name" (needs > 1 char after trim) |
    | Mobile number | numeric, required, normalised to digits | "9876543210" | "Enter a valid 10-digit mobile number" (`^[6-9]\d{9}$`); on reference 2, "Your two references must have different mobile numbers" |
    | Relation | select, required | — | "Choose a relation" |
    - Relation options: Select…, Parent, Spouse, Sibling, Relative, Friend, Colleague, Manager, Neighbour.
  - Saved references are pre-filled from `GET /offer/references`.
  - **Buttons:** Back and Continue (submit). Errors appear only after the first submit attempt.
- **Main flow:**
  1. Submit → `POST /offer/references` (exactly two).
  2. If no OTP is owed → advance `OFFER_REFERENCES` → summary.
- **Business rules (server `saveReferences`):**
  | Rule | Code | Message |
  |---|---|---|
  | exactly two | `REFERENCES_REQUIRED` | "Two references are required" |
  | each needs a name and a 10-digit mobile | `REFERENCE_INVALID` | "Each reference needs a name and a 10-digit mobile number" |
  | relation must be in the list | `REFERENCE_INVALID` | "Choose a relation from the list" |
  | mobile must differ from the borrower's own | `REFERENCE_IS_SELF` | "A reference cannot be your own mobile number" |
  | the two mobiles must differ | `REFERENCE_DUPLICATE` | "Your two references must have different mobile numbers" |
  | with `reference-otp` on, name must be letters/space/`.'-` only, 2–60 chars | `REFERENCE_INVALID` | "Enter the reference's real name, using letters only" |
  - Rows are upserted per slot 1/2.
  - Changing a slot's mobile clears that slot's OTP state. With the flag on, only a **new** number gets `otp_required = true`.
  - If any OTP is pending, the pointer is rewound to `OFFER_REFERENCES`; otherwise it advances to `OFFER_SUMMARY`.
  - An ADMIN edit (staff side) bypasses SANCTIONED, never moves the pointer, and logs changes.
- **Alternate & error flows:** server message, else "Could not save your references — please try again."
- **Postconditions:** `application_reference` rows. No notification unless OTP (UC-B2-09).
- **Edge cases / not allowed:** Back-and-resubmit with unchanged numbers holds nobody for an OTP. Only a changed number re-asks.

### UC-B2-09 Reference OTP confirmation (`reference-otp` flag)
- **Actor / entry point:** the same screen. It appears after saving when any reference has `otpRequired && !verified`, or on return to the screen with codes still owed (no new send in that case).
- **Preconditions:** flag `reference-otp` on (default off); status `SANCTIONED`. Otherwise `NOT_APPLICABLE` "Reference confirmation is not switched on" / "This offer isn't ready".
- **Screen:**
  - **Heading:** "Ask your references for their codes". Copy: "We sent each person a 6-digit code. They should share it with you only if they agree to be your reference."
  - **Per pending reference:**
    - Input "Code from {name} (••••••{last4})", numeric, max 6, digits only.
    - "Confirm" button — enabled only at exactly 6 digits.
    - "Send again" button.
  - **Link:** "Change my references" → back to the form.
- **Main flow:**
  1. On save, the client calls `POST /offer/references/{slot}/otp` for each pending slot.
  2. **The server:**
     - generates a 6-digit code and stores a SHA-256 hash of it (10-minute TTL)
     - sends an SMS (DLT key `REFERENCE_OTP`): "DhanBoost customer {borrower name ≤30 chars} named you as a loan reference. If you agree, share OTP {code} with them. - DhanBoost"
     - on the **first** send to that number only, sends the WhatsApp notice template `db_reference_added_v1_164` (reference name, borrower name)
     - sends the WhatsApp OTP
     - The send succeeds if SMS **or** WhatsApp went out.
  3. Confirm → `POST …/otp/verify`. A match marks it verified. When none are pending, the pointer advances to `OFFER_SUMMARY` and the client moves on.
- **Business rules:**
  - 60 s resend wait.
  - Max 6 sends per reference.
  - Max 5 verify attempts per code.
  - TTL 10 minutes.
  - Constant-time hash compare.
- **Alternate & error flows:**
  | Code | Message | Notes |
  |---|---|---|
  | `REFERENCE_OTP_COOLDOWN` | "Please wait a minute before asking for another code" | silently ignored on auto-send |
  | `REFERENCE_OTP_LIMIT` | "Too many codes sent to this reference" | |
  | `REFERENCE_OTP_NOT_SENT` | "We could not send the code. Please try again." | "…Please contact support." if the borrower has no name |
  | `REFERENCE_OTP_EXPIRED` | "This code has expired. Please ask for a new one." | also returned once attempts are exhausted |
  | `REFERENCE_OTP_INVALID` | "That code is not correct" | |
  | `NOT_APPLICABLE` | "This reference does not need a code" | |
- **Postconditions:** `verified_at` set. `acceptOffer` later refuses `REFERENCE_OTP_PENDING` "Both references must confirm their code first" while any are pending.
- **Edge cases:** turning the flag off releases a held borrower on their next save (`otp_required` cleared).

### UC-B2-10 Loan summary
- **Actor / entry point:** step 5, `/loan/summary`.
- **Preconditions:** Signed-in borrower; application `SANCTIONED`; the earlier offer steps are satisfied (UC-B2-01 guard).
- **Screen:**
  - **Loading:** "Preparing your summary…".
  - **Lead:** "Here's everything about your loan, before you confirm."
  - **Rows (all from `GET /offer/summary`, computed by backend `LoanMath`):**
    | Row | Value |
    |---|---|
    | Loan Amount | drawn principal (default = ceiling) |
    | Interest Rate | "1% per day" |
    | Tenure | "{N} days", where N = max(1, days from today IST to the repayment date) |
    | Processing Fee (hint "excluding GST") | fee after discount |
    | Repayment discount (only if > 0) | hint "{pct}% of your loan, already taken off the fee above" |
    | Repayment (hint "on {date}") | total repayable = P + round(P × 0.01 × N) |
  - **Highlighted:** "You will receive **₹{net}**", where net = P − fee − GST.
  - **Expected disbursal date:** today if now < 18:00 IST, else tomorrow. Calendar days; no weekend or holiday roll.
  - **Footnote:** "The processing fee and GST are deducted upfront, which is why you receive ₹{net} of the ₹{P} you are borrowing. Pay early and you pay less interest — it is charged only to the day you repay."
  - **Buttons:** Back, and "Looks right, continue".
- **Business rules:**
  - fee = round(P × 10 %) − round(P × discount% / 100), floored at 0
  - GST = round(fee × 18 %)
  - HALF_UP
  - The summary, KFS and disbursal all read the same columns.
- **Postconditions:** pointer only.
- **Edge cases:** GST has no row of its own here (folded into "You will receive"). It is itemised on the KFS.
- **Main flow:** Read `GET /offer/summary` → "Looks right, continue" → advance `OFFER_SUMMARY`.
- **Alternate & error flows:** The summary call fails (for example `NOT_APPLICABLE` "This offer isn't ready") → the spinner stays; the status guard redirects any non-SANCTIONED file.

### UC-B2-11 Selfie — camera capture + Digitap liveness (primary)
- **Actor / entry point:** step 6, `/loan/selfie`. It is dropped on a reborrow that carried a `SELFIE` row.
- **Preconditions:** Signed-in borrower; application `SANCTIONED`; the earlier offer steps are satisfied (UC-B2-01 guard).
- **Screen:**
  - **Heading:** "Verify it's really you". Copy: "Take a quick selfie so we can confirm you're a real person. Look straight at the camera in good lighting."
  - **Camera area:** a dashed circle with a camera icon. When live, it shows the front-camera preview with an oval guide.
  - **Buttons by phase:**
    - "Start camera" (navy)
    - "Capture selfie", which becomes "Retake selfie" after a FAIL
    - "Verifying your selfie…" spinner while uploading
    - "Verified — continuing… →" when done
  - A Back link stops the camera.
- **Main flow:**
  1. Start camera → `getUserMedia({video:{facingMode:"user"}, audio:false})`.
  2. Capture → a canvas frame from the video (defaults 480×480 if the size is unknown) → JPEG at quality 0.9.
  3. Presign `SELFIE` → PUT to S3 → `POST verify/selfie {objectKey}`.
  4. **Server:**
     - checks the key starts with this application's own `SELFIE` folder and has no `..`
     - always saves a `SELFIE` document
     - calls Digitap Face Liveness v4 (liveness only; no face match)
     - `live && !multipleFaces` → PASS "Liveness check passed"; otherwise REVIEW "Liveness check low — manual review"
     - confidence × 100 is stored as the score
  5. PASS or REVIEW → advance after 700 ms.
- **Business rules:**
  - Never blocks.
  - **Provider failure:** REVIEW "We couldn't run the face check right now — you can continue; our team will review your selfie." with `derived.fallback:true`. This starts the secondary path (UC-B2-12).
  - **Client retry rule:** after a FAIL the borrower may retake up to 2 more times (3 total), then it advances. The hint "2 attempts used — 1 retake remaining before we proceed to manual review." shows after the first FAIL.
- **Alternate & error flows:**
  - No camera API: "Your device or browser doesn't support camera capture."
  - Permission denied: "We couldn't access your camera. Please allow camera access and try again."
  - No canvas or blob: "Could not capture the image — please try again."
  - Server error: "Could not verify your selfie — please try again." The camera stays live.
  - `INVALID_SELFIE_KEY` "That selfie upload does not belong to this application"
  - `SELFIE_REQUIRED` "selfieObjectKey is required"
- **Postconditions:** `SELFIE` verification row (PASS/REVIEW) and a `SELFIE` document.
- **Edge cases:**
  - The server never returns FAIL on this path, so the retake loop and its hint are unreachable in practice (see §Z).
  - "Verified — continuing…" also shows on REVIEW.

### UC-B2-12 Selfie — Signzy video liveness (secondary)
- **Actor / entry point:** automatic, when the primary call returns `derived.fallback:true`.
- **Preconditions:** UC-B2-11 returned `fallback:true`.
- **Screen:**
  - **Copy changes to:** "We couldn't check your selfie automatically — complete this quick video check instead. It takes a few seconds."
  - "Starting…" with a face-scan icon.
  - **Embedded journey:** an iframe (`allow="camera; microphone; fullscreen"`, 3:4) with "Follow the prompts above — this finishes automatically."
- **Main flow:**
  1. `POST verify/selfie/liveness/init`.
  2. **The server:**
     - uses the DigiLocker `AADHAAR_PHOTO` (if any) as the match image
     - starts Signzy liveness
     - writes the `SELFIE` row PENDING with the token and `videoUrl`
  3. The client polls `…/liveness/status` every 4 s, up to 45 polls (~3 min).
  4. **Server on completion:**
     - ingests the captured frame to S3 as a `SELFIE` doc
     - **PASS** when live and (no match attempted, or the face matched): "Live selfie matched to Aadhaar photo" or "Liveness check passed"
     - otherwise **REVIEW**: "Liveness/face-match low — manual review"
  5. PASS or REVIEW → advance.
- **Business rules:**
  - Signzy unavailable → `fallback:true` with nothing written. The REVIEW row from UC-B2-11 stands, and the borrower advances.
  - Poll limit reached → advance (manual review).
  - Init throws → advance.
- **Alternate & error flows:**
  - Poll error → "Lost connection to the liveness check." (the poll stops)
  - `LIVENESS_NOT_STARTED` "No liveness session for this application"
- **Postconditions:** `SELFIE` row PASS/REVIEW; the frame is stored.
- **Edge cases / not allowed:** The face match runs only when DigiLocker supplied an `AADHAAR_PHOTO`; a manual card upload gives a liveness-only check.

### UC-B2-13 Address — geolocation
- **Actor / entry point:** step 7, `/loan/address`. It is dropped on a reborrow that carried an `ADDRESS` row.
- **Preconditions:** Signed-in borrower; application `SANCTIONED`; the earlier offer steps are satisfied (UC-B2-01 guard).
- **Screen (geo mode):**
  - **Lead:** "Confirm where you currently live. Sharing your location is the fastest way — we only use it to verify your address, never to track you."
  - **Card:** "Verify your current address" / "We'll match your live location to your address records."
  - **Button:** "Use my current location" ("Getting your location…" while busy).
  - Result banner and Back link.
- **Main flow:**
  1. `navigator.geolocation.getCurrentPosition` (high accuracy, 10 s timeout, maximumAge 0).
  2. `POST verify/address {latitude, longitude}` → Signzy reverse-geocode, then Digitap (chain).
  3. **The server:**
     - address resolved (non-blank) → PASS "Address resolved"; `profile.address` is overwritten and the change is logged
     - otherwise → REVIEW "Address could not be resolved — review"
     - the within-India flag is recorded but does **not** gate the result
  4. **Client:**
     - result not PASS, or no address string → switch to typed mode with "We couldn't resolve your address from this location. Please enter it below."
     - otherwise save the resolved address to the profile and advance
- **Business rules:**
  - A repeat call after PASS returns the stored row.
  - Provider failure → REVIEW "We couldn't verify your address right now — you can continue; our team will review it." The client treats it as non-PASS and drops to the typed form.
- **Alternate & error flows:**
  | Condition | Message (and switch to typed mode) |
  |---|---|
  | no geolocation API | "Location isn't available on this device — please enter your address." |
  | permission denied | "Location permission is blocked — allow it in your browser's site settings, or enter your address below." |
  | timeout | "Getting your location took too long — please enter your address instead." |
  | other | "We couldn't access your location — please enter your address instead." |
  | API error | "Could not verify your location." |
- **Postconditions:** `ADDRESS` row; `profile.address_verified` true only on a resolved geo PASS.
- **Edge cases / not allowed:** A geo PASS overwrites the typed or intake address on the profile (logged in `profile_change_log`).

### UC-B2-14 Address — typed fallback
- **Actor / entry point:** typed mode of `/loan/address`, after a geo failure.
- **Preconditions:** Geo mode failed or was declined (UC-B2-13).
- **Screen:**
  - **Lead:** "Enter your current residential address."
  - **Field:** "Full address", required, `autocomplete=street-address`, placeholder "Flat / house, street, area, city, PIN".
    - Validation: trimmed length ≥ 8, else "Enter your complete address".
    - Pre-filled from the saved profile address.
  - **Link:** "Use my location instead" (back to geo).
  - **Submit:** "Continue" (or "Try again" if the last result was FAIL).
- **Main flow:**
  1. Save the profile address slice.
  2. `POST verify/address {manualAddress}` → `recordManualAddress`: `ADDRESS` row **REVIEW** "Manual address — pending review", `address_verified=false`.
  3. REVIEW → advance.
- **Alternate & error flows:**
  - `ADDRESS_REQUIRED` "Provide coordinates or a manual address"
  - Network error: "Could not verify your address."
- **Postconditions:** `ADDRESS` REVIEW; the address appears on the staff verification board.
- **Business rules:** Never blocks: a typed address is always REVIEW and always advances.
- **Edge cases / not allowed:** A typed address is never auto-verified, so `address_verified` stays false.

### UC-B2-15 Read the Key Fact Statement / sanction letter
- **Actor / entry point:** step 8, `/loan/sanction-letter` ("Agreement & signature").
- **Preconditions:** `SANCTIONED`. All previous evidence exists; the journey guard enforces this.
- **Screen:**
  - **Lead:** "Your loan agreement". Copy: "Read the Key Fact Statement and sign it directly below. The final saved PDF includes your signature, name, and the server-recorded date and time."
  - **Loading:** "Preparing your agreement…".
  - **Error box:** server message, or "Could not prepare your agreement."
  - **PDF:** an iframe of the presigned PDF URL. Inner fallback text: "Your browser can't display the PDF here — open it using the link below."
  - **Button:** "Open agreement ↗" (new tab).
  - Below the PDF: the "Sign this agreement" section (UC-B2-16 / 18).
- **Main flow:**
  1. `POST /offer/sanction-letter` (never cached on the client).
  2. **If already signed (ESIGN PASS):** return the letter on file, never re-rendered.
  3. **Otherwise:**
     - render the PDF at the **current** drawdown and today's tenure
     - store it at `applications/{id}/sanction_letter/sanction-letter.pdf`
     - upsert the `SANCTION_LETTER` document
     - advance the pointer to `OFFER_SANCTION_LETTER`
- **KFS PDF content (`SanctionLetterPdfRenderer`):**
  - **Header:** brand wordmark; borrower name; "Application #{id} · PAN {pan} · {mobile}"; "Sanctioned on {date}".
  - **Hero:** "You will receive ₹net" and "You will repay on {date} ₹total".
  - **Key Facts table:**
    - Lender: MLB Securities Private Limited
    - Loan amount (principal)
    - Repayment discount applied (only if > 0)
    - Processing fee (excluding GST)
    - GST on processing fee (18%)
    - Amount credited to your account
    - Interest rate "1% per day on the principal"
    - Annual Percentage Rate (APR) = (total − (net + GST)) × 36 500 / ((net + GST) × tenure), 1 dp HALF_UP, shown as "% per year, including fee, excluding GST"
    - Tenure "{N} days"
    - Interest payable over the tenure
    - Repayment date
    - Total repayable on the repayment date
    - Number of instalments "1 (single repayment)"
    - Credit account: masked "•••• last4" of the disbursal or salary account, else "As confirmed by you"
  - **Charges and terms (bullets):**
    - upfront deduction
    - 1 %/day for actual days
    - free prepayment
    - "late penalty of 2% per day on the principal applies from the day after the date shown above, capped at 30 days"
    - credit-bureau reporting
    - "Repayment is a single instalment. There is no auto-debit mandate on this advance."
    - the sanction is subject to verification and release
  - **Grievance redressal:** the officer's name, phone and email come from `navix.grievance.*` config, plus RBI Ombudsman escalation after 30 days.
  - **Signed copy:** adds a "Borrower signature" block (image, "Signed by …", "Signed at …" IST).
  - **Footer:** lender CIN/RBI registration, and "DhanBoost is operated by NAVIX Finance Private Limited (CIN U64990HR2026PTC144926) as technology service provider."
- **Alternate & error flows:** `SANCTION_LETTER_UNAVAILABLE` "We couldn't prepare your sanction letter just now. Please try again in a moment." (S3 failure; deliberately not silent).
- **Postconditions:** `SANCTION_LETTER` document.
- **Business rules:** The letter is regenerated on every visit until signed, so it always reflects the current amount and tenure. After signing it is frozen.
- **Edge cases / not allowed:** The tenure (and so the interest and APR) changes day by day until signing, because it is counted from today.

### UC-B2-16 Sign with Aadhaar eSign
- **Actor / entry point:** the sign section of `/loan/sanction-letter`, default mode "aadhaar".
- **Preconditions:** As UC-B2-15; the `SANCTION_LETTER` document exists.
- **Screen:**
  - **Copy:** "Sign with Aadhaar e-sign. You'll confirm with a one-time password sent to the mobile number registered with your Aadhaar, then come straight back here."
  - **Ticks:** "Legally valid Aadhaar e-signature", "A signed copy is saved to your documents".
  - **Buttons:**
    - "Sign with Aadhaar" ("Opening Aadhaar e-sign…" while busy)
    - "Can't use Aadhaar? Sign by drawing"
- **Main flow:**
  1. The client builds `{origin}/kyc/esign/callback?app={id}&sid={nonce}`, with `&outcome=success` / `&outcome=failure` variants.
  2. `POST /offer/esign/init` → the server re-renders the letter at the current amount, then `verification.esignInit`.
  3. **Server:**
     - already PASS → returns it
     - needs the `SANCTION_LETTER` doc, else `SANCTION_LETTER_MISSING` "Open your sanction letter before signing it"
     - signer = profile name and mobile, plus gender, year of birth and UID last-4 from the DigiLocker `AADHAAR` row. Match mode is `STRICT` when gender and YOB are known, else `NAME_ONLY`.
     - `EsignPort.initiate` (Signzy) → the `ESIGN` row becomes **PENDING** with the session id and sign URL
  4. The client calls `window.location.assign(signUrl)`. The borrower completes an Aadhaar OTP at the provider and is returned to the callback (UC-B2-17).
- **Business rules:**
  - Every initiate is a **real, billable, legally binding** contract.
  - `esignInit` short-circuits only on PASS, so each press of "Sign with Aadhaar" while the row is PENDING mints another contract.
  - A PENDING row locks the amount (`TERMS_LOCKED`).
- **Alternate & error flows:**
  - Provider unavailable → no row is written; `derived.fallback:true` "Aadhaar e-sign is unavailable — sign the agreement here instead" → the page switches to draw mode.
  - No URL → draw mode.
  - Network error → "Could not start Aadhaar e-sign — you can sign below instead." and draw mode.
- **Postconditions:** `ESIGN` PENDING row.
- **Edge cases / not allowed:** The STRICT match fails when the Aadhaar gender/YOB disagree with the provider. That ends as REVIEW, and the drawn path remains.

### UC-B2-17 eSign return (`/kyc/esign/callback`)
- **Actor / entry point:** the provider redirects here. Query params are advisory; the app id comes from localStorage.
- **Preconditions:** An `ESIGN` row with a provider session id exists (UC-B2-16); otherwise `ESIGN_NOT_STARTED`.
- **Screen:**
  - **Working:** "Confirming your signature" / "This takes a few seconds — please don't close this page."
  - **Done:** "Agreement signed" / "Taking you to the next step…"
  - **Failed:** "We couldn't confirm your signature" / "Nothing is lost. Go back to your agreement to try again, or sign by drawing instead." Button "Back to your agreement →".
- **Main flow:**
  1. Poll `GET /offer/esign/status` every 4 s, up to 45 attempts.
  2. **Server `esignStatus`:**
     - PASS row → return it.
     - Fetch from the provider:
       | Provider state | Result |
       |---|---|
       | not completed, contract expired | delete the row and mint a fresh contract with the same redirects (only case of a silent re-mint) |
       | not completed | PENDING "Waiting for your signature" |
       | completed, not signed (name/YOB/gender mismatch) | row **REVIEW** with the provider reason, else "Signature not completed" |
       | completed and signed | `finalizeSignature` |
     - **`finalizeSignature`:**
       - stores the provider's signed PDF at `…/signed_agreement/sanction-letter-signed.pdf` (or points at the letter)
       - upserts `SIGNED_AGREEMENT`
       - sets `agreement_accepted=true`
       - publishes `SanctionLetterSignedEvent`
       - sets `ESIGN` → **PASS** "Sanction letter signed"
  3. PASS → the pointer advances to `OFFER_SANCTIONED` → the client advances `OFFER_SANCTION_LETTER` → next step.
- **Alternate & error flows:**
  - REVIEW, FAIL, attempts exhausted, or an exception → the failed card.
  - `ESIGN_NOT_STARTED` "Start signing your agreement first".
- **Postconditions:**
  - Signed agreement stored.
  - Notification `SANCTION_LETTER_SIGNED` (IN_APP, EMAIL with the attachment, WHATSAPP; no SMS).
- **Edge cases:**
  - A REVIEW row unlocks the amount again (it is neither PASS nor PENDING). It is only written when this callback polls.
  - A contract-expired re-mint keeps the amount locked.
- **Business rules:** Our row is authoritative; the redirect only says the journey ended. Polling is 4 s × 45. Only PASS advances.

### UC-B2-18 Sign by drawing (fallback)
- **Actor / entry point:** "Can't use Aadhaar? Sign by drawing", or automatic after an eSign init fallback or error.
- **Preconditions:** As UC-B2-15.
- **Screen:**
  - **Copy:** "Use your pointer, mouse, or touchscreen to sign inside the box."
  - **Ticks:** "Your drawn signature is saved", "A signed copy is saved to your documents".
  - **Canvas:** 720×220 (dashed box, navy 3 px stroke).
  - **Buttons:**
    - "Clear signature"
    - "Sign and continue" ("Saving signature…" while busy)
    - "Use Aadhaar e-sign instead"
  - Result banner.
- **Main flow:**
  1. The client checks the canvas has at least one non-transparent pixel, else "Please draw your signature inside the box first".
  2. The client asks for geolocation (7 s timeout; optional — null on denial).
  3. `POST /offer/esign {signatureDataUrl (PNG), latitude, longitude, accuracyMeters}`.
  4. **Server `recordManualEsign`:**
     - validates the location ranges: lat −90..90, lon −180..180, accuracy 0..100 000 m. Otherwise `SIGNATURE_LOCATION_INVALID` "The signing location is invalid".
     - validates the image: must be a `data:image/png;base64,` URL, else `SIGNATURE_INVALID` "Draw your signature in the signing area"; must decode, else "Draw your signature again"; must be 1 B–1 MB, else "The signature image is invalid".
     - an existing PASS from a provider (not MANUAL) → `ESIGN_ALREADY_COMPLETED` "This agreement is already signed"; a repeat drawn signature is idempotent
     - stores the PNG at `…/signed_agreement/signature.png`, keeps any abandoned Aadhaar-attempt data for staff, finalises (ESIGN PASS, provider `MANUAL`)
     - `OfferService` re-renders the KFS with the stamped signature, name and server time into `sanction-letter-signed.pdf` (`SIGNED_AGREEMENT`), and advances `OFFER_SANCTIONED`
  5. PASS → advance → next step.
- **Business rules:**
  - Never calls `EsignPort` (no billable contract).
  - A storage failure gives `SIGNED_AGREEMENT_UNAVAILABLE` "We couldn't save your signed agreement just now. Please try again."
- **Alternate & error flows:** server message, else "Could not sign your agreement — please try again."
- **Postconditions:** same as UC-B2-17 (`SANCTION_LETTER_SIGNED` notification).
- **Edge cases / not allowed:** Location is optional. A denied permission still signs, with null coordinates.

### UC-B2-19 Sanctioned celebration
- **Actor / entry point:** step 9, `/loan/sanctioned`.
- **Preconditions:** `ESIGN` row PASS (the guard holds the borrower on the sanction letter otherwise).
- **Screen:**
  - Confetti and a party-popper icon.
  - **Heading:** "Your loan has been SANCTIONED!" with "One more step to go."
  - **Big figure:** the drawn amount (else the ceiling).
  - **Two-node indicator:** "Sanctioned ✓ —— Disbursal".
  - **Button:** "Continue →". There is **no Back**, because the signature is a legal act.
- **Main flow:** advance `OFFER_SANCTIONED` → disbursal-account.
- **Postconditions:** pointer only.
- **Business rules:** No server state changes here.
- **Alternate & error flows:** None.
- **Edge cases / not allowed:** Back is deliberately absent; the browser Back button simply returns to an already-signed letter.

### UC-B2-20 Confirm the salary account (₹1 check)
- **Actor / entry point:** step 10, `/loan/disbursal-account` ("Where to send the money"), default view.
- **Preconditions:** `SANCTIONED`; `amount_requested` set (else `AMOUNT_NOT_CHOSEN` "Choose your loan amount first").
- **Screen:**
  - **Loading:** "Loading your bank details…".
  - **Lead:** "Where should we send the money?" / "This is the salary account you gave us. Confirm it, or use a different one."
  - **Read-only table (`GET /offer/disbursal-account`):**
    - Bank
    - Account number, masked "•••• last4"
    - IFSC
    - Account holder
    - Each value prefers the application's disbursal fields, else the profile's salary fields and full name.
  - **Status line:**
    - If the saved account is already verified for exactly this account+IFSC: "✓ We'll reuse the successful verification for this account."
    - Else if not locked and attempts remain: "Confirming will perform a ₹1 account check. {n} attempt(s) remaining."
  - **Link:** "Send it to a different account instead" (UC-B2-21).
  - **Lock banner (when locked):** "Too many failed account checks. The ₹1 check can run again after {date time} — or skip the wait and upload a cancelled cheque or passbook below, which we'll verify by hand."
  - **Bank-proof card:** always shown (UC-B2-22).
  - **Main button:** "Confirm this account". Disabled while busy, or when locked and a penny drop is required.
- **Main flow:**
  1. Confirm → `POST /offer/disbursal-account` with the saved number, IFSC, holder and bank.
  2. **Server validation:** digits only; account < 6 digits or IFSC ≠ 11 chars → `ACCOUNT_INVALID` "Invalid account details, please enter the bank details again."
  3. **"Changed" flag:** `changed = not equal to the salary account AND not equal to the carried disbursal account`. A blank reference never matches. This is recorded only.
  4. **Verified?** `verified = disbursal_account_verified && same account+IFSC`. On a reborrow this is inherited from the source application.
  5. **Not verified:**
     - lock present → `PENNY_DROP_LOCKED` "Too many failed attempts. Please try again after 12 hours."
     - otherwise run the penny drop (`verifyPennyDrop(force=true)`, own transaction): Signzy; PASS only if the account exists **and** the name similarity with the profile name is ≥ 0.60
     - record the attempt in `PennyDropGuard` (own transaction)
     - not PASS → `ACCOUNT_INVALID`
  6. **Success:**
     - save the disbursal number, IFSC, holder (else the profile name) and bank (else the salary bank)
     - set `disbursal_account_changed` and `disbursal_account_verified`
     - set `disbursal_confirmed_at = now`
     - → UC-B2-23
  7. The client navigates to `nextOfferRoute("disbursal-account")`: `/loan/mandate` if that step is in the list, else `/loan/status`.
- **Business rules (`PennyDropGuard`):**
  - Per customer: 3 failures since the last success (or since the previous lock) → a 12 h lock.
  - A success deletes the lock.
  - `remainingAttempts` = 0 while locked.
  - Staff can clear the lock with a manual PENNY_DROP override.
- **Alternate & error flows:**
  - Any failure → "Invalid account details, please enter the bank details again.", then the page refetches attempts and lock.
  - A provider outage or name mismatch is recorded as a REVIEW row but still counts as a strike and an `ACCOUNT_INVALID` here (see §Z).
- **Postconditions:**
  - `PENNY_DROP` verification row (PASS/REVIEW, `REQUIRES_NEW`, so it survives the rejection).
  - `penny_drop_attempt` row; possibly a `customer_penny_drop_lock`.
  - If the provider returns a bank name and the profile has none, `salary_bank` is filled from it (else the IFSC's first 4 characters).
- **Edge cases / not allowed:** Holder name is not editable in this view; it comes from the disbursal record or the profile full name.

### UC-B2-21 Send to a different account (penny drop, 3 strikes, 12 h lock)
- **Actor / entry point:** "Send it to a different account instead" on `/loan/disbursal-account`.
- **Preconditions:** As UC-B2-20; not locked (the main button is disabled while locked).
- **Screen (form):**
  | Field | Input | Placeholder | Rule | Error |
  |---|---|---|---|---|
  | Account number | numeric, required, digits only, max 18 | "00123456789012" | 9–18 digits | "Enter a valid account number" |
  | IFSC code | required, upper-cased, spaces stripped, max 11 | "HDFC0001234" | `^[A-Z]{4}0[A-Z0-9]{6}$` | "Enter a valid 11-character IFSC" |
  | Account holder name | required | "As it appears at your bank" | > 1 char | "Enter the name on the account" |
  - **Note:** "We'll send ₹1 to check this account is real and belongs to you. {n} attempt(s) remaining."
  - **Link:** "Use my salary account instead".
  - **Main button:** "Verify & confirm". Disabled while busy, while locked, or while the form is invalid (an accidental submit costs a strike).
- **Main flow:** same as UC-B2-20, with the typed values. A matching verified account is reused without a drop.
- **Business rules:** as UC-B2-20. A strike is committed even though the request is rejected.
- **Alternate & error flows:** `PENNY_DROP_LOCKED`; `ACCOUNT_INVALID`.
- **Postconditions:** as UC-B2-20, with `disbursal_account_changed=true` when it differs from both salary and carried accounts.
- **Edge cases / not allowed:** The strike count is per customer, not per application, so it carries across applications until a success or lock expiry.

### UC-B2-22 Cancelled cheque / passbook fallback
- **Actor / entry point:** the always-visible card "Not able to verify your account?" on `/loan/disbursal-account`, including while locked.
- **Preconditions:** As UC-B2-20. Works while locked.
- **Screen:**
  - **Copy:** "Upload a cancelled cheque or your bank passbook instead — we'll verify it manually before your advance is sent."
  - File input (same accepted types, 10 MB max, same messages as UC-B2-07); "Selected: {name}".
  - **Button:** "Upload proof & continue" ("Uploading…" while busy).
    - Navy while locked, outline otherwise.
    - Disabled without a file, or when the different-account form is open and invalid.
- **Main flow:**
  1. Compress, presign `BANK_PROOF`, PUT, register the document.
  2. `POST /offer/disbursal-account {…, useBankProof:true}` with the typed account (or the saved one).
  3. **Server:**
     - no `BANK_PROOF` doc → `BANK_PROOF_REQUIRED` "Upload a cancelled cheque or passbook first"
     - saves the account fields, `changed` (real value), `verified=false`, `confirmed_at`
     - writes the `PENNY_DROP` row **REVIEW** (provider `MANUAL_PROOF`, "Bank proof uploaded — awaiting manual verification")
     - never calls the penny drop or the guard, so it bypasses the lock deliberately
  4. → UC-B2-23.
- **Alternate & error flows:**
  - "Choose a cancelled cheque or passbook photo first."
  - Upload failure: "Could not upload your proof — please try again."
- **Postconditions:** `BANK_PROOF` document; the Disbursement Head judges it before release.
- **Business rules:** No attempt counter and no lock apply. The account fields are still required (proof shows ownership, not the destination).
- **Edge cases / not allowed:** `disbursal_account_verified` stays false; release is gated on a staff judgement of the proof.

### UC-B2-23 Hand-off to disbursement (system)
- **Actor / entry point:** system, from both exits of `confirmDisbursalAccount`, or from the mandate status poll (UC-B2-24).
- **Preconditions:** `disbursal_confirmed_at` just set (or a UMRN just landed).
- **Main flow (`afterAccountConfirmed`):**
  - `mandateRequired` (flag `digio-mandate` on and no usable mandate) → the pointer goes to `OFFER_MANDATE` and the application stays `SANCTIONED`.
  - Otherwise → the pointer goes to `OFFER_DONE` and `acceptOffer` runs.
- **`acceptOffer` gates, in order:**
  | Gate | Code | Message |
  |---|---|---|
  | role BORROWER | — | — |
  | status SANCTIONED | `NOT_APPLICABLE` | "This offer isn't ready to accept" |
  | `ESIGN` row **PASS** | `ESIGN_REQUIRED` | "Sign your sanction letter before continuing" |
  | no reference OTP pending | `REFERENCE_OTP_PENDING` | — |
  | no mandate required | `MANDATE_REQUIRED` | "Register your eMandate before continuing" |
  | drawn ≥ ₹1,000 | `AMOUNT_TOO_LOW` | — |
  | drawn ≤ ceiling | `LIMIT_EXCEEDED` | — |
  - The **stored** amount is used; a caller-supplied amount is ignored.
- **Postconditions:**
  - `SANCTIONED → DISBURSEMENT_PENDING`, event action `ACCEPT_OFFER` (notes `amountPaise=…`).
  - Notification `LOAN_APPLIED_FAST_TRACK` (IN_APP + EMAIL) to Disbursement Heads, ADMINs and the borrower.
  - Later (staff, file 04): DISBURSED → ACTIVE with `LOAN_DISBURSED` (IN_APP, SMS, EMAIL, WhatsApp). The pinned discount offer is consumed (`DISCOUNT_OFFER_USED` if it was already used), and a referral payout is created if this is the referred borrower's first loan.
- **Screen:** None (system). The borrower lands on `/loan/mandate` or `/loan/status`.
- **Business rules:** See the gate table. With the flag off, `mandateRequired` is false for every application.
- **Alternate & error flows:** A gate failure surfaces as the error on the screen that triggered it (disbursal-account or mandate).
- **Edge cases / not allowed:** `mandateStatus` only hands off when `disbursal_confirmed_at` is set. A poll on an unconfirmed file never queues it for disbursement.

### UC-B2-24 Set up auto-repayment (Digio eMandate) inside the journey
- **Actor / entry point:** step 11, `/loan/mandate`. The step exists only while `digio-mandate` is on and the customer has no ACTIVE mandate (UMRN) on this account with `maximum ≥ ceiling`.
- **Preconditions:**
  - Signed in. If not, the page redirects to `/login?next=/loan/mandate`.
  - Account confirmed (`MANDATE_ACCOUNT_UNCONFIRMED` "Confirm your repayment bank account before setting up auto-repayment").
  - Status in the allowed set: PRE_APPROVED, SANCTIONED, DISBURSEMENT_PENDING, DISBURSEMENT_FAILED, DISBURSED, ACTIVE, OVERDUE, DEFAULTED, WRITTEN_OFF, CLOSED. Otherwise `NOT_APPLICABLE`.
- **Screen states:**
  - **Checking:** "Loading…" / "This takes a few seconds — please don't close this page."
  - **Ready:** "Set up auto-repayment".
    - Copy (in journey): "To receive your advance, authorise an eMandate with your bank so your repayment can be collected automatically on the due date. The mandate allows debits up to a maximum amount (ceiling) that is higher than your advance. You can cancel it with your bank at any time."
    - Button "Continue to your bank".
  - **Waiting:** "Confirming with your bank" / "Your bank's page opened in a new tab. Authorise the mandate there, then come back to this tab — we'll continue automatically. Please keep this tab open."
    - Button "Reopen your bank's page", or "Open your bank's page" if the popup was blocked.
  - **Stalled:** "Still waiting".
    - After 45 polls: "We haven't seen your mandate complete yet. If you've authorised it with your bank, check again in a moment."
    - After 3 consecutive poll errors: "We couldn't check your mandate right now. If you've authorised it with your bank, try checking again."
    - Buttons "Check again" and "Reopen your bank's page".
  - **Failed:** red "The setup did not complete. Nothing was charged — please try again." (or the specific error). Button "Try again".
  - **Unsupported bank:** "We'll be in touch" / "Your bank can't set up automatic repayment online. Our team will be in touch with you to finish this step, so you don't need to do anything or try again. Your advance will move forward once it is done."
  - **Done:** "Auto-repayment set up" / "Taking you to the next step…"
- **Main flow:**
  1. Press → a new tab opens synchronously (popup-safe; `opener = null`).
  2. `POST /offer/mandate/init`. **Server `MandateService.init`:**
     - per-customer advisory lock
     - flag check, else `MANDATE_NOT_ENABLED`
     - account-confirmed check
     - attempts on this application must be < 10, else `MANDATE_ATTEMPTS_EXHAUSTED` "Too many mandate attempts. Our team will get in touch."
     - account 8–18 digits (`INVALID_ACCOUNT_NUMBER`); IFSC 11 characters (`INVALID_IFSC`)
     - ceiling = 2 × max(25 %-of-salary limit, sanctioned amount)
     - reuse a usable ACTIVE mandate if there is one
     - settle earlier attempts with Digio; a live PENDING attempt returns its own URL
     - otherwise mint CRN `C{customerId}A{attemptNo}`
  3. **Bank without API mandate:** the row is `BANK_UNSUPPORTED` → event `MANDATE_BANK_UNSUPPORTED` (IN_APP to Disbursement Heads) → unsupported screen.
  4. **Otherwise:**
     - file at the registration ceiling (an ADMIN override may raise it)
     - first collection today, final collection today + 5 years
     - status `PENDING_AUTHORIZATION`
     - the authorisation URL is stored and opened. Only `https` on `digio.in` / `*.digio.in` is accepted; anything else → "We could not open your bank's page. Please try again."
  5. The page polls `GET /offer/mandate/status` every 4 s. A UMRN → PASS → `mandateStatus` sees `disbursal_confirmed_at` and no longer requires a mandate → the pointer goes to `OFFER_DONE` and `acceptOffer` runs (UC-B2-23) → the client advances to `/loan/status`.
- **Business rules:**
  - The step is blocking, with no skip.
  - Every init is a real NPCI filing.
  - A double tap is guarded client-side (an in-flight ref) and server-side (the advisory lock).
  - The authorisation URL lives for 7 days (`navix.digio.expire-in-days`).
  - Staff may override **only** a `BANK_UNSUPPORTED` latest attempt.
  - If the flag is switched off after the account was confirmed, the next status poll hands off as "eMandate not required".
- **Postconditions:** `mandate` row(s); on success the file moves to `DISBURSEMENT_PENDING`.
- **Alternate & error flows:** See the Failed / Stalled / Unsupported states; server codes `MANDATE_NOT_ENABLED`, `MANDATE_ACCOUNT_UNCONFIRMED`, `MANDATE_ATTEMPTS_EXHAUSTED`, `INVALID_ACCOUNT_NUMBER`, `INVALID_IFSC` show as the error text.
- **Edge cases / not allowed:** A refresh or second tab resumes from the live status. Without the URL in memory, "Reopen" mints a fresh attempt instead.

### UC-B2-25 Set up auto-repayment from a staff invite link
- **Actor / entry point:** the borrower opens the `MANDATE_SETUP_INVITE` link (IN_APP, SMS, EMAIL, WhatsApp) → `/loan/mandate`, often on a loan that has left the journey (ACTIVE/OVERDUE/CLOSED…).
- **Preconditions:** As UC-B2-24, except the status may be any in the allowed set (the borrower usually already has a loan).
- **Differences from UC-B2-24:**
  - It uses `useLiveApplication` (no SANCTIONED bounce), and the layout guard is disabled on this route.
  - Ready-screen copy: "Authorise an eMandate with your bank so your repayment is collected automatically on the due date — nothing to remember, and no late fee for forgetting." plus the same ceiling sentence.
  - Done: "Your repayment will now be collected automatically on the due date. There's nothing else to do." with "Back to my dashboard".
  - Status on a non-SANCTIONED application just reports the stored row (no hand-off).
  - No application resolvable: "No advance found" / "We couldn't find an advance on this account to set up auto-repayment for. If you signed in with a different mobile number, sign out and use the one your advance is on." with "Go to my dashboard".
- **Postconditions:** an ACTIVE mandate. The dashboard then shows the "Auto-repayment is on" card (UC-B2-27).
- **Screen:** As UC-B2-24 except the copy noted above.
- **Main flow:** As UC-B2-24, minus the hand-off.
- **Business rules:** As UC-B2-24. The invite link carries no token; a signed-out borrower is sent to `/login?next=/loan/mandate`.
- **Alternate & error flows:** As UC-B2-24.
- **Edge cases / not allowed:** A staff override can move a `BANK_UNSUPPORTED` file on without a mandate; the page then reports the stored row.

---

## C. Tracking and the active loan

### UC-B2-26 Track the application (`/loan/status`)
- **Actor / entry point:** the borrower, via any of:
  - the dashboard "View full status & timeline →"
  - the quick link "Application status"
  - the end of the offer journey
  - the retired routes
  - a notification with an `applicationId`
- **Preconditions:** a session. The app id comes from the pointer or `/mine`. The loan is not fetched.
- **Screen:**
  - **Heading:** "Application status", with a "Refresh" button that refetches.
  - **Sub-line:** "Application #{id} · **{status label}**", or "Loading your application…".
  - **No app id:** "No application yet" / "Start your application to track it here in real time." Button "Start application" → `/signup/start`.
  - **Left — vertical tracker (7 stages):**
    | # | Stage | Sub-text |
    |---|---|---|
    | 1 | Applied | Application submitted |
    | 2 | Under review | Credit team is reviewing |
    | 3 | Approved | Loan offer ready |
    | 4 | Documents signed | Agreement, sanction letter & KFS e-signed |
    | 5 | Money on the way | Transferring funds to your bank account |
    | 6 | Active | Due on your salary day |
    | 7 | Repaid & closed | Loan fully settled |
    - **Status mapping:**
      | Status | Tracker stage |
      |---|---|
      | DRAFT | NEW |
      | KYC_PENDING | Under review |
      | KYC_APPROVED, PRE_APPROVED, SANCTIONED, CREDIT_EXEC_PENDING | Approved |
      | DISBURSEMENT_PENDING, DISBURSED | Money on the way |
      | ACTIVE | Active |
      | OVERDUE | Active (shown as overdue) |
      | CLOSED | Repaid |
      | REJECTED, KYC_REJECTED, CANCELLED, DISBURSEMENT_FAILED | Declined |
    - "Documents signed" is never the current stage.
  - **Right — cards by state:**
    - **SANCTIONED:** "You're approved" / "Our credit team has approved up to ₹{ceiling}. A few steps left to receive it." Button "Continue your loan" → `/loan/amount` (the guard resumes).
    - **Processing** (not active, declined or offer): "Under review" / "Your application will be reviewed in 2–3 hours. This page updates automatically — currently **{status}**."
    - **ACTIVE:** "Your advance is active" / "Manage everything from your dashboard." Button "Go to dashboard".
    - **Declined:** "Not approved this time" / "Your application did not meet current credit policy." plus "Questions? {support phone}".
  - **Collapsible "Audit trail (n)":** fetched only when opened, or eagerly on decline. Each row: "{action} {from} → {to}" and the IST date-time. "Loading…" or "Nothing recorded yet." when empty.
- **Business rules:**
  - The application polls every 4 s until a terminal status (ACTIVE, CLOSED, WRITTEN_OFF, DEFAULTED, OVERDUE, REJECTED, CANCELLED, KYC_REJECTED, DISBURSEMENT_FAILED).
  - `/mine` re-polls every 15 s while the pointer may be stale (none, unresolved, CLOSED or a bad terminal) and moves the pointer **forward only** to a newer application.
  - The borrower's event view is stripped server-side to id / from / to / action / at. Actor, role, acting role and notes are removed. So the decline card **always** shows the generic sentence, and the rejection reason never reaches the borrower.
- **Postconditions:** none (read-only).
- **Main flow:** Open the page → poll the application → optionally open the audit trail → follow a card CTA.
- **Alternate & error flows:** Loading shows a skeleton. An event fetch failure shows "Nothing recorded yet.".
- **Edge cases / not allowed:** The pointer never moves back to an older application.

### UC-B2-27 Dashboard
- **Actor / entry point:** `/dashboard` (header nav "Dashboard", brand logo, post-login landing).
- **Preconditions:** a session. A paused-onboarding borrower with no loan is redirected to `/apply` by `OnboardingGate`.
- **Screen:**
  - **Greeting:** "Hi, {first name}".
    - The name source: the session name if it is not "Borrower", else the profile/draft name, else "there".
    - The session name self-heals from the profile once.
    - Sub-copy: "Here's where your advance stands."
    - A status chip: Not started / Applied / Under review / Approved / Docs signed / Processing / Active loan / Repaid / Declined / Overdue.
  - **Main column by state:**
    - **ACTIVE/OVERDUE with a loan — LoanCard:**
      - Header: "Active advance · Due in {d} days", or red "Repayment overdue · {d} days past due" (overdue when status OVERDUE or days < 0).
      - "Total outstanding ₹{outstanding}".
      - If outstanding > total repayable: "Includes ₹{outstanding − total} late penalty (2%/day, cap 30d)".
      - Progress bar: repaid / total, where repaid = Σ VERIFIED payments.
      - "₹x repaid · ₹y total".
      - "Due {date}".
      - Button "Repay / prepay", or "Pay now" when overdue → `/repay`.
      - "View full breakdown" opens the details dialog.
      - Inline "Payment issue? Call … · Email …".
    - **CLOSED — Pre-approved banner:**
      - Badge "Up to ₹{sanctioned} pre-approved", or "Pre-approved".
      - Title "Pre-Approved Personal Loan".
      - "No paperwork | One repayment on salary day | Instant disbursal".
      - Note "Your previous advance is fully repaid — you're in good standing."
      - "Check Now" → `/reloan`.
      - "*T&C Apply".
    - **Declined:** "Application not approved" / "Your application didn't meet current credit policy. See your status page for details." → "View status".
    - **Other in-flight:**
      - Title "You're approved" (SANCTIONED) or "Application in progress".
      - Body "Our credit team has approved your loan. A few steps left to receive your money." or "Pick up where you left off — track your application or choose your amount."
      - A status box with a stage description, for example "Your application will be reviewed in 2–3 hours." or "Approved — your disbursal is being arranged."
      - CTA "Continue your loan" / "Continue": DRAFT → `/signup/start`, SANCTIONED → `/loan/amount`, else `/loan/status`.
    - **No application:** the pre-approved banner → `/signup/start`.
    - **Auto-repayment card:** shown when active and the mandate is `ACTIVE` with a UMRN. "Auto-repayment is on" / "Your repayment will be collected automatically from your {bank} account ending {last4} on the due date. You can cancel this mandate with your bank at any time." plus the UMRN and the "Authorised" time.
  - **Aside:**
    - **Next milestone card** (title / detail / action):
      | State | Title | Action |
      |---|---|---|
      | DRAFT | "Complete your application" | Continue application |
      | SANCTIONED | "Complete your offer steps" | Continue loan |
      | DISBURSEMENT_PENDING / DISBURSED / DISBURSEMENT_FAILED | "Disbursal in progress" | Track status |
      | ACTIVE, due in N days | "Repayment due in N days" | Repay / prepay |
      | due today | "Repayment due today" | Pay today |
      | inside a grace day (legacy loans only) | "Grace day — pay today" | Pay today |
      | OVERDUE | "Payment overdue by N days" | Pay now |
      | CLOSED | "You can borrow again" | Borrow again |
      | rejected / cancelled / defaulted / written-off | "Application closed" | View status |
      | otherwise | "Credit decision" | Track status |
    - **Quick links:** Application status, Repay / prepay, Profile & KYC.
    - **Refer & earn card** (UC-B2-41).
  - **"Your applications" roll-up (full width):** newest first, from `/mine`. Each row: "Application #{id} — Requested ₹{amount} · {purpose}" with a coloured status badge.
    - A row with a loan opens the details dialog; otherwise it links to `/loan/status`.
    - "View all →" → `/loans`.
    - Empty: "No applications yet."
    - Error: "Could not load your applications. Please refresh and try again."
- **Postconditions:** none.
- **Edge cases:**
  - "Due in N days" uses the browser date.
  - The PRD wording "never in N days" is not honoured here (see §Z).
- **Main flow:** Open the page → read the state cards → follow a CTA (repay, continue, borrow again, details).
- **Business rules:** "Repaid" = Σ VERIFIED payments (not total − outstanding). The penalty shown = max(0, outstanding − total repayable).
- **Alternate & error flows:** Loading shows a skeleton card. A mandate 404 just hides the auto-repayment card.

### UC-B2-28 Loan details dialog / inline loan detail
- **Actor / entry point:** "View full breakdown" (dashboard), a roll-up row, "View full details" (`/loans`), any `/transactions` row, or inline on a past-loan card.
- **Preconditions:** The application has a `loanId` (disbursed).
- **Screen (`LoanDetailBody`):**
  - **Disbursal:** Principal (sanctioned); Processing fee (10%); GST (18% on fee); **Amount disbursed to you**.
  - **Cost:**
    - Interest (1%/day over tenure) = total repayable − principal
    - "Late penalty (2%/day, cap 30d)", shown only when overdue (owed > total repayable)
    - Total repayable (on salary day)
  - **Repayment:**
    - Disbursed on; Due date.
    - Paid so far = server verified sum.
    - Then one of:
      - "✓ Fully repaid — loan closed" (CLOSED/REPAID or owed ≤ 0)
      - "Settlement — full & final ₹x" with "Our collections team approved a full & final settlement of ₹x."
      - "Pay now (incl. penalty) ₹x" with "A late penalty is accruing — clear it soon to stop the clock."
      - "Outstanding (pay today) ₹x" with "Pay early & save ₹y — interest is charged only to the day you pay."
  - **Payments list:** amount; method (UPI / PayU / Bank); txn ref; date; proof link; pill Pending verification / Verified / Rejected.
  - **CTA:** "Repay / prepay" when ACTIVE/OVERDUE.
  - **Load error:** "Could not load this loan. Please close and try again."
- **Business rules:** figures come from `GET loan/{id}` plus `GET loan/{id}/outstanding?asOf=today`. See UC-B2-29 for the formula.
- **Main flow:** Open from any loan surface → read → optionally "Repay / prepay" (closes the dialog first).
- **Alternate & error flows:** The load error message above.
- **Postconditions:** None (read-only).
- **Edge cases / not allowed:** Uses the UTC date for `asOf` (§Z15).

---

## D. Repayment

### UC-B2-29 See what is owed (repay quotes)
- **Actor / entry point:** `/repay` (header nav "Repay", the dashboard CTA, a `REPAYMENT`-category notification).
- **Preconditions:** the current application is ACTIVE, OVERDUE or CLOSED with a `loanId`.
  - Otherwise: "No active loan" / "You don't have anything to repay right now." with "Go to dashboard".
  - CLOSED, or outstanding ≤ 0: "Loan fully repaid" / "Thank you — your advance is closed and in good standing." with "Back to dashboard".
- **Screen:**
  - **Heading:** "Repay your advance". Copy: "Single repayment on your salary day — or prepay any amount, any day. **No pre-closure or prepayment charges, ever** — you only pay interest up to the day you repay."
  - **"When will you pay?" card:** hidden when overdue or under settlement.
    - Copy: "Paying by your salary day is penalty-free — and prepaying any day costs you less interest. Pick whatever suits you; we'll remind you before it's due." Legacy grace loans read: "Paying on your salary day or the day after is penalty-free".
    - Tiles:
      | Tile | Figure | Note |
      |---|---|---|
      | **Prepay today** (highlighted) | today's figure | "Save ₹x interest", or "Lowest — least interest" |
      | **On salary day** | the due-date figure | "Penalty-free" |
      | **Day after salary** (only if `grace_days > 0`) | the grace-date figure | "One extra interest day · no late penalty" |
  - **"What you owe":**
    - Principal
    - Total repayable (on salary day)
    - Due date
    - Days since disbursement
    - "Interest so far (principal × 1%/day × {min(days since, tenure)} days)"
    - Highlighted payable: "Pay today", or "Pay now (incl. penalty)", or "Settlement — full & final".
    - Hints:
      - "Pay earlier → pay less interest. Every day saved = ₹{round(P×1%)} less."
      - "Pay early & save ₹x — interest is charged only to the day you pay."
      - Overdue: "A late penalty (2%/day, capped 30 days) is accruing. Clear it soon to stop the clock."
      - Settlement: "Our collections team approved a settlement of ₹x as **full & final**. Pay this to close your loan — no further amount will be due."
    - The payments list, with rejection banners (UC-B2-33).
  - A "Payment issue?" help card.
- **Business rules — one outstanding formula everywhere (`RepaymentService.breakdown`):**
  - `at` = asOf, frozen at `closed_on` for a closed loan.
  - tenure = days(disbursed → due).
  - interestDays = min(days(disbursed → at), tenure + grace).
  - penaltyDays = max(0, dpd − grace), and the penalty is capped at 30 days.
  - owed = P + P×1%×interestDays + P×2%×min(penaltyDays, 30) − Σ VERIFIED.
  - With an approved settlement: owed = min(owed, max(0, settlement − verified)).
  - The client asks for today in IST (`Intl` en-CA, Asia/Kolkata), so a 00:00–05:30 visit is not a day short.
  - One request (`outstandingSchedule`) returns the asOf, due and grace balances.
- **Postconditions:** none.
- **Main flow:** Open `/repay` → read the quotes → choose a pay method (UC-B2-30 / 31).
- **Alternate & error flows:** Schedule loading falls back to the cached `loan.outstanding`.
- **Edge cases / not allowed:** Under an approved settlement the "When will you pay?" card and the prepay hints are hidden.

### UC-B2-30 Pay manually (UPI / bank transfer + proof)
- **Actor / entry point:** the "How much to pay" card on `/repay`.
- **Preconditions:**
  - Loan not CLOSED/REPAID.
  - The manual section is shown only when PayU is off, **or** `manualAllowed`. With PayU on, that means the latest PayU attempt on this loan is FAILED, EXPIRED, or INITIATED and older than 30 minutes, and no other attempt is live. A borrower with PayU on and no attempts at all sees **no** manual form.
- **Screen:**
  - **Amount mode:** "Pay in full" / "Part payment". Part payment shows an "Amount" field (₹, digits only, placeholder = today's figure, helper "Up to ₹{today}"). The amount is clamped to today's figure.
  - **When PayU is on:** heading "Or pay manually".
  - **Payment method:** UPI / Bank transfer.
    - **UPI:** the QR (tap to enlarge, "Scan to pay with any UPI app") and "Scan the QR or pay to **{upiId}**, then paste the UPI reference below." with "Tap the QR to enlarge it for scanning."
    - **Bank:** "NEFT/IMPS to: {account name} · A/C {no} · IFSC {ifsc} · {bank}", "Download account details (PDF)", "Then paste the UTR below."
    - The payee comes from admin `payment_settings` (UPI id, QR, account, IFSC, bank, PDF). Bundled static fallbacks exist when unset; do not treat them as real.
  - **Reference field:** "UPI reference / txn ID" (placeholder "e.g. 4287…") or "UTR number" ("e.g. HDFCN…"). Helper: "We verify your payment against this reference before it reduces your balance".
  - **"Payment screenshot \*":**
    - Image only, else "Attach an image (screenshot) of your payment confirmation."
    - Max 8 MB, else "That screenshot is too large — please attach one under 8 MB."
    - Shows a thumbnail and a remove button.
    - Hint: "A screenshot of the payment confirmation is required — this is what our accounts team checks against your reference before verifying."
  - **Button:** "Pay ₹{amount}", or "Submit payment details" when PayU is on. "Uploading & recording…" while busy.
    - Enabled only when amount > 0, ref ≥ 4 characters, and a proof is attached.
    - Hints under the button: "Paste your UPI/UTR reference (min 4 characters) to enable payment" / "Attach a screenshot of your payment to enable payment".
  - **Footer:** "Payments are confirmed manually by our accounts team — your balance updates once verified."
- **Main flow:**
  1. Compress the image → presign (`category REPAYMENT_PROOF`) → PUT.
  2. `POST loan/{id}/repayments {amountPaise, method, txnRef, proofUrl(key), paidOn=today IST}`.
  3. **Server `recordPayment`:**
     - checks ownership
     - BORROWER without proof → `PAYMENT_PROOF_REQUIRED` "Attach a screenshot of your payment before submitting."
     - loan closed → `LOAN_SETTLED`
     - amount ≤ 0 → `INVALID_AMOUNT`
     - **idempotent on (loan, txnRef):** a duplicate ref returns the existing payment
     - paidOn must be ≤ today + 1 day (a future date is clamped to today)
     - paidOn must be ≥ the disbursal date
     - BORROWER paidOn must be ≥ today − 3 days, else "Payment date is too far in the past — contact support to record an older payment."
     - saves `PENDING_VERIFICATION`; `partial = amount < outstanding as of paidOn`
  4. **Success banner:** "Payment recorded — **pending verification** by our accounts team. Your balance updates once the transfer is confirmed." The form is cleared.
- **Alternate & error flows:** red banner with the server message, else "Could not record your payment. Please try again."
- **Postconditions:**
  - A `payment` row, PENDING_VERIFICATION.
  - Notification `REPAYMENT_RECORDED` (IN_APP + WhatsApp to the borrower; IN_APP to Accountants).
  - Accountant verify/reject → UC-B2-33.
- **Edge cases:** a pending manual payment blocks PayU (`MANUAL_PAYMENT_PENDING`).
- **Business rules:** Proof is mandatory for borrowers only (staff may record without). The reference is ≥ 4 characters client-side and idempotent per loan server-side. Backdating is ≤ 3 days for borrowers.

### UC-B2-31 Pay online with PayU checkout
- **Actor / entry point:** `/repay` when `GET payu/state` says `enabled`. That requires the `payu-repayment` flag, the credentials, and allowed hosts. Fail-safe: while loading, on error, or when off, the page is the manual page.
- **Preconditions:** A signed-in borrower who owns the loan; the loan is ACTIVE/OVERDUE with owed > 0.
- **Screen:**
  - Gold button "Pay ₹{amount}" ("Taking you to secure checkout…" while busy). Disabled at amount ≤ 0.
  - Note: "Credit cards carry an additional fee of up to ₹{fee}, shown at checkout." (or "Credit cards may carry an additional fee, shown at checkout."), then "UPI, net banking and debit cards have no added fee."
  - The fee is a per-mode bps config (236 = 2.36 %); the maximum across modes is displayed.
- **Main flow:**
  1. `POST payu/orders {amountPaise}`. **Server `createOrder`:**
     | Check | Code | Message |
     |---|---|---|
     | PayU enabled | `PAYU_DISABLED` | — |
     | loan not settled | `LOAN_SETTLED` | — |
     | amount ≥ 100 paise | `INVALID_AMOUNT` | "The minimum online payment is Rs. 1" |
     | no PENDING_VERIFICATION manual payment | `MANUAL_PAYMENT_PENDING` | — |
     | each open order re-verified with PayU: SUCCESS | `PAYU_PAYMENT_RECEIVED` | — |
     | each open order re-verified with PayU: PENDING < 15 min old | `PAYU_IN_PROGRESS` | — |
     | owed today > 0 | `LOAN_SETTLED` | — |
     | amount ≤ owed | `AMOUNT_EXCEEDS_OUTSTANDING` | — |
     - The order row is created INITIATED (txnid `DB` + 20 hex characters).
     - Email: the profile email, else `pay{loanId}@dhanboost.com`. Name: sanitised to letters only, ≤60 characters.
     - The hash and `additional_charges` are added.
  2. The browser auto-POSTs a hidden form to PayU hosted checkout. surl, furl and curl all point to `/api/payu/return`.
- **Alternate & error flows (banner):**
  | Code | Message |
  |---|---|
  | `PAYU_IN_PROGRESS` | "A payment is already being processed. Please wait a few minutes before paying again." |
  | `PAYU_PAYMENT_RECEIVED` | "We have just received your payment. Your balance is being updated." |
  | `MANUAL_PAYMENT_PENDING` | "You have a manual payment awaiting verification. Please wait until it is confirmed before paying online." |
  | `AMOUNT_EXCEEDS_OUTSTANDING` | "That amount is more than you currently owe. Please refresh and try again." |
  | other | "Could not start your payment. Please try again." |
  - For the first three codes, the page also refreshes its queries.
- **Postconditions:** a `payu_order` INITIATED. The reconcile scheduler runs every 5 min. The first re-check is at 5 min, then 15 min, then hourly. An order still open after 24 h → EXPIRED.
- **Business rules:** See the server check table. The fee is PayU's surcharge on top; it is never credited as repayment.
- **Edge cases / not allowed:** The amount is clamped to today's figure client-side; the server re-checks against the owed amount today.

### UC-B2-32 Return from PayU (`/repay?order=`)
- **Actor / entry point:**
  1. PayU POSTs cross-site to the BFF `/api/payu/return`. No session is read there.
  2. The BFF forwards the raw body to the backend, which checks only the reverse hash.
  3. The BFF 303s to `/repay?order={txnid}` (txnid `^[A-Za-z0-9]{1,25}$`), or plain `/repay` on any failure.
  - A server webhook arrives in parallel.
- **Preconditions:** A PayU order exists for this loan; the txnid is in the URL.
- **Screen (banner, polling `GET payu/orders/{txnid}` every 3 s for up to 2 min):**
  - SUCCESS, not credited: "**We've received your payment.** Our team is confirming it and your balance will update shortly."
  - SUCCESS, credited: "**Payment received** — ₹{amount}. Your balance has been updated."
  - FAILED / EXPIRED: "Your payment didn't go through. Try again, or pay manually below."
  - Otherwise: "We're confirming your payment. Don't pay again yet."
- **Business rules (reconcile):**
  - Every signal is re-checked with PayU `verify_payment`; the body's status and amount are never trusted.
  - **Success** needs `status=success` and `unmappedstatus=captured`.
  - **Credit amount** = `transaction_amount`. If it differs from the order amount → review `AMOUNT_MISMATCH`, not credited.
  - No `mihpayid` → review `NO_PROVIDER_ID`.
  - A fee not as quoted → review `FEE_MISMATCH`, still credited.
  - **Credit** = a VERIFIED payment (method PAYU, txnRef = `mihpayid`, idempotent). Overpay → review `OVERPAID`. The loan closed meanwhile → `LOAN_SETTLED` overpayment parked for refund.
  - SUCCESS is never downgraded; a FAILED/EXPIRED order is not reopened by a later pending.
  - Any order checked more than 10 s ago is re-read on the borrower's poll.
- **Postconditions:** VERIFIED payment → outstanding recomputed → possibly closed (UC-B2-33). Review rows go to the Accountant's PayU queue (file 04).
- **Main flow:** The return lands on `/repay?order=…` → the page polls the order → when the order is final, the payments/schedule/loan queries are refreshed.
- **Alternate & error flows:** An invalid hash or a backend failure → plain `/repay` with no banner. `ORDER_NOT_FOUND` "Order not found" for a foreign txnid.
- **Edge cases / not allowed:** Polling stops after 2 minutes; the scheduler keeps reconciling in the background.

### UC-B2-33 Payment verified / rejected → loan closes
- **Actor / entry point:** system or staff (Accountant verify/reject; PayU credit; NACH debit). The borrower sees the outcome on `/repay`, the dialog, `/transactions` and notifications.
- **Preconditions:** A payment exists in PENDING_VERIFICATION (manual), or a PayU/NACH credit lands.
- **Main flow:**
  - **Verify:**
    - The status becomes VERIFIED → `recomputeOutstanding`: owed as of the latest verified `paidOn`.
    - owed > 0 → the cache `loan.outstanding` = owed today.
    - owed = 0 → loan CLOSED with `closed_on` = that date → application `ACTIVE/OVERDUE → CLOSED` (action `REPAID`; the settlement note is added if one applies) → DSA commission matured.
  - **Reject:**
    - Reason ∈ {WRONG_REFERENCE, AMOUNT_MISMATCH, NOT_RECEIVED, UNREADABLE_PROOF, OTHER} plus an optional note. A VERIFIED payment cannot be rejected (`PAYMENT_ALREADY_VERIFIED`).
    - The borrower sees "Payment rejected — {label}. {note}" with the labels Wrong payment reference / Amount doesn't match / Payment not received / Proof unreadable / Other, plus "Payment issue? Call … · Email …".
- **Postconditions / notifications:**
  - `REPAYMENT_VERIFIED` (IN_APP, SMS, WhatsApp)
  - `REPAYMENT_REJECTED` (IN_APP, SMS, EMAIL with the reason, WhatsApp)
  - `LOAN_CLOSED` (IN_APP, SMS, EMAIL, WhatsApp)
- **Edge cases:**
  - Closure is judged on the payment date, so a late verification does not add penalty.
  - A closed loan's balance is frozen at `closed_on`.
- **Screen:** The status pills and rejection banners on `/repay`, the details dialog and `/transactions`.
- **Business rules:** Only VERIFIED payments reduce the balance. Rejection is terminal. A loan closes only when the penalty-aware outstanding reaches 0.
- **Alternate & error flows:** `REJECTION_REASON_REQUIRED`, `INVALID_REJECTION_REASON`, `PAYMENT_ALREADY_VERIFIED` (staff side).

---

## E. History

### UC-B2-34 Past loans (`/loans`)
- **Actor / entry point:** account menu "Past loans", or "View all →".
- **Preconditions:** Signed-in borrower.
- **Screen:**
  - **Heading:** "Your loans" / "Every advance you've applied for, with its current status."
  - **Sections:**
    - "Active advances (n)" — ACTIVE/OVERDUE; empty hint "No active advance right now."
    - "In progress (n)" — everything that is neither active nor past; hidden when empty.
    - "Past loans (n)" — CLOSED, DEFAULTED, WRITTEN_OFF, REJECTED, KYC_REJECTED, CANCELLED. Rendered expanded with the full `LoanDetailBody` inline.
  - **Card:** "Application #{id}", "Requested ₹{amount} · {purpose}", and a status badge (green ACTIVE; grey CLOSED/CANCELLED; red OVERDUE/DEFAULTED/WRITTEN_OFF/REJECTED/KYC_REJECTED/DISBURSEMENT_FAILED; blue otherwise).
    - With a loan: "View full details", plus a stats grid: Principal, Net disbursed, Total repayable, Outstanding, Due date ("due on your salary day"), Disbursed on, Loan #, Loan status.
  - **Loading:** skeletons.
  - **Error:** "Could not load your loans. Please refresh and try again."
  - **Empty:** "No loans yet" / "When you apply for an advance it'll show up here with live status." with "Apply now" → `/signup/start`.
- **Edge cases:** `DISBURSEMENT_FAILED` falls in "In progress".
- **Main flow:** Open → browse the sections → open details.
- **Business rules:** Sections are by application status; the loan stats come from `GET loan/{id}`.
- **Alternate & error flows:** The load error message above; a per-loan fetch error just hides that loan's stats.
- **Postconditions:** None (read-only).

### UC-B2-35 Past transactions (`/transactions`)
- **Actor / entry point:** account menu "Past transactions".
- **Preconditions:** Signed-in borrower.
- **Screen:**
  - **Heading:** "Transactions" / "Money disbursed to you and the repayments you've made."
  - **Table:** Type | Loan | Date | Amount | Status.
    - One **Disbursal** row per loan: "+ ₹net disbursed", dated `disbursedOn`, pill "Completed".
    - One **Repayment** row per payment: "− ₹amount", dated `paidOn`, pill Pending verification / Verified / Rejected.
    - A rejected row gets a sub-row with the reason and the support strip.
    - Sorted by date, newest first. Clicking a row opens the loan details dialog.
  - **Loading:** "Loading transactions…".
  - **Error:** "Could not load your transactions. Please refresh and try again."
  - **Empty** (no disbursed loan): "No transactions yet" / "Once an advance is disbursed, your disbursal and repayments appear here." with "Go to dashboard".
- **Edge cases:** the client builds the list itself (one loan query and one payments query per loan). There is no server ledger for borrowers.
- **Main flow:** Open → read the table → click a row for details.
- **Business rules:** Disbursal amount = net disbursed. Repayments include every status. Sort is by date, newest first.
- **Alternate & error flows:** The load error message above.
- **Postconditions:** None (read-only).

---

## F. Reborrow

### UC-B2-36 Borrow again (`/reloan`)
- **Actor / entry point:** any of:
  - the header "New loan" button
  - the account menu "Borrow again"
  - the dashboard "Check Now" or "Borrow again"
  - the Next-milestone card
  - `routeForBlockedStart("RETURNING_BORROWER")` from intake
  - `canStartNewLoan` = every application in CLOSED/REJECTED/CANCELLED/KYC_REJECTED/DISBURSEMENT_FAILED/WRITTEN_OFF and at least one exists
- **Preconditions:** Signed-in borrower; ideally every application is in a done status (the CTA is hidden otherwise).
- **Screen:**
  - **Live loan** (pointer app ACTIVE/OVERDUE/DEFAULTED): the `ActiveLoanNotice` (repay first).
  - **Default:**
    - Eyebrow "⚡ Pre-approved".
    - Heading "Borrow again, instantly". Copy: "Your KYC carries over from before — just re-confirm your current salary, pick an amount, and the money's on its way. If you've ever missed a repayment, a quick approver review runs first."
    - Card "Available to borrow now ₹{availableLimitPaise, else sanctioned}", "Instant loan up to ₹10,00,000".
    - Button "Borrow again" ("Checking…" while busy).
    - Feature tiles: "No re-KYC — Your verified details carry over"; "Clean record = instant — Pre-approved → straight to disbursal"; "Same fast flow — Amount → money in your account".
  - **No prior loan:** "Welcome back" / "Start a fresh application to borrow with DhanBoost." with "Apply now" → `/signup/start`.
- **Main flow:**
  1. `POST borrower/applications/reborrow` (UC-B2-37).
  2. Store the new app id; optimistically put it first in `["my-apps"]`; invalidate.
  3. Push to `/loan/salary`. That is retired and redirects to `/loan/status`, which shows "You're approved" (SANCTIONED) → "Continue your loan" → `/loan/amount` → the short journey: amount → date → summary → sanction letter → 🎉 → account (→ mandate).
- **Alternate & error flows:**
  | Code | Result |
  |---|---|
  | `NO_PRIOR_LOAN` | Welcome-back card |
  | `ACTIVE_APPLICATION` | `/loan/status` |
  | `ACTIVE_LOAN` | `/repay` |
  | `RETURNING_BORROWER` | `/reloan` |
  | `ONBOARDING_PAUSED` | `/apply` |
  | `APPLICATIONS_CLOSED` | `/applications-closed` |
  | other (e.g. `NOT_ELIGIBLE` "Your account is not eligible for new loans. Please contact support." or a dated block) | inline red text, else "Something went wrong — please try again." |
- **Postconditions:** see UC-B2-37.
- **Business rules:** All routing is server-decided (UC-B2-37). The "Available" figure prefers the live `availableLimitPaise`.
- **Edge cases / not allowed:** The copy promises a salary step and an approver review that do not exist (§Z4).

### UC-B2-37 Reborrow decision engine (system)
- **Actor / entry point:** `ApplicationFlowService.reborrow()` (BORROWER only; customer = JWT subject).
- **Preconditions:** A BORROWER JWT.
- **Gates, in order:**
  1. **Customer advisory lock**, then `assertNotBlocked`:
     - a staff block → `NOT_ELIGIBLE`
     - any active rejection block on the customer id **or** the latest mobile → `NOT_ELIGIBLE` (indefinite) or the dated message
  2. **Any application ACTIVE/OVERDUE/DEFAULTED** → `ACTIVE_LOAN` "Repay your current advance before borrowing again".
  3. **Any non-terminal, non-live application** → `ACTIVE_APPLICATION` "Finish your in-progress application before starting a new one".
  4. **Flag `applications-closed`** → `APPLICATIONS_CLOSED` "We are not accepting new applications right now".
  5. **No prior profile** → `NO_PRIOR_LOAN` "No previous application found to borrow against".
  6. **No CLOSED loan and not disqualified** → `NO_PRIOR_LOAN` "No repaid advance found to borrow against".
- **Standing (`isDisqualifiedByHistory`) — any of these disqualifies:**
  - any application DEFAULTED or WRITTEN_OFF
  - any loan whose effective status today is OVERDUE or IN_COLLECTIONS
  - any loan with **any** VERIFIED payment whose `paid_on` > due date + 5 days (`LATE_REPAYMENT_TOLERANCE_DAYS`, counted from the due date, not the grace)
  - The credit score does not gate reborrow.
- **New draft:**
  - The eligible limit is the ADMIN override (V69), else 25 % of the prior salary.
  - The salary credit day is reused.
  - The profile is cloned onto the new application (identity, employment, salary account, terms acceptance, PEP declaration, bureau, both email-verified flags, address/penny-drop flags).
  - The credit-brief PDF row is carried (same S3 object).
- **Forks:**
  - **Disqualified, with an unused ADMIN reapply allowance:**
    - consume it (one-use)
    - pin the best discount
    - copy AADHAAR/SELFIE/ADDRESS/EMPLOYMENT checks and references from the latest sanctioned application
    - → `KYC_PENDING` (action `REBORROW_ALLOWED`); the normal credit queue decides
    - No borrower notification for this action.
  - **Disqualified, no allowance:**
    - rejection `PAST_DELINQUENCY` (no `blocked_until`)
    - → `REJECTED` (action `REBORROW`, "Past delinquency")
    - notification `CREDIT_REJECTED` (no reason)
  - **Clean:**
    - pin the best unlocked **repay-now discount**: unused, its loan CLOSED, latest VERIFIED payment on or before `repay_by`; highest percent, then newest
    - → `PRE_APPROVED` (action `REBORROW`, notification `REBORROW_PREAPPROVED`: IN_APP, SMS, WhatsApp)
    - If a prior sanctioned application exists, `carryOverForReapply`:
      - `reappliedFrom` set
      - ceiling = ADMIN override, else the prior sanctioned amount
      - `sanctioned_at = now`, remarks "Carried over from application {id}"
      - repayment date **recomputed** = `dueDateFromSalary(today, salaryDay)`; tenure = days to it
      - disbursal account fields carried and `verified` carried, but `confirmed_at` **not** carried and `changed=false`
      - AADHAAR, SELFIE, ADDRESS and EMPLOYMENT checks copied with "Carried over from application {id} — …"
      - references copied (no OTP state)
      - → `SANCTIONED` (action `REAPPLY_SANCTION`; no notification mapped)
    - If no prior sanction exists (legacy) → it stays `PRE_APPROVED`.
- **Never carried:** the eSign (each advance signs its own KFS), the drawn amount, and the disbursal confirmation.
- **Postconditions:**
  - A new `loan_application`.
  - The discount is pinned in `fee_discount_percent/offer_id`. It reduces the fee on the summary, KFS and disbursal, and is consumed at disbursal.
  - Events appended.
- **Screen:** None (system). Outcomes surface on `/loan/status` and the dashboard.
- **Main flow:** Gates → standing → new draft → fork (allowance / reject / clean) → events and notifications.
- **Business rules:** See the gates, standing rules and forks above.
- **Alternate & error flows:** Codes `NOT_ELIGIBLE`, `ACTIVE_LOAN`, `ACTIVE_APPLICATION`, `APPLICATIONS_CLOSED`, `NO_PRIOR_LOAN`.
- **Edge cases / not allowed:** `PAST_DELINQUENCY` has no `blocked_until`, so it re-fires on every attempt until an ADMIN allowance is granted.

---

## G. Account

### UC-B2-38 Profile self-edit
- **Actor / entry point:** account menu "Profile details", or the dashboard quick link "Profile & KYC" → `/profile`.
- **Preconditions:** a resolvable application.
  - None: "Your profile" / "Complete your application to set up your profile." with "Sign out".
  - The read falls back to the customer's latest profile when the application has none.
- **Screen:**
  - **Heading:** the full name (or "Your profile"); sub-line: the email (or "Verified borrower").
  - **Identity (read-only):**
    - Full name
    - PAN (lock icon)
    - Aadhaar: "DigiLocker verified" with a shield, or "Not verified"
    - Mobile (shield)
    - Note: "Identity details are locked. Contact support to change your PAN or mobile."
  - **Contact & address:** Email (type email), Address.
  - **Employment & income:**
    - Employer
    - Employment status (free text)
    - Monthly salary (₹, digits only)
    - Salary bank (combobox of Indian banks)
    - Warning: "Changing your address, salary or bank will require those checks to be re-verified before your next loan."
  - **Emergency contact:** Name, Phone (tel), Relationship.
  - **Buttons:** "Save changes" → "Profile updated." or the server error ("Could not save your changes."), and "Sign out".
  - **KYC status widget:** PAN / Aadhaar / Selfie / Address / Bank, all derived from the **application status**, not from the checks:
    - KYC_PENDING → in progress
    - KYC_REJECTED → failed
    - any post-KYC status → verified
    - otherwise → pending
- **Main flow:** `PUT …/profile/self` → `CustomerReviewService.editOwnProfile` (BORROWER; ADMIN bypass). Blank text fields are sent as null and mean "unchanged" for the verified fields.
- **Business rules — what each edit invalidates (`VerificationInvalidationService`; the row is reset to PENDING "Reset for re-verification after a profile edit", the profile flag cleared, and a `REVERIFY` event appended):**
  | Changed field | Checks reset | Profile flag cleared / side effect |
  |---|---|---|
  | address | ADDRESS | `address_verified=false` |
  | salary bank | PENNY_DROP | `penny_drop_verified=false` |
  | monthly salary | SALARY | eligible limit recomputed |
  | employer / employment status | EMAIL and EMPLOYMENT | `email_verified=false` |
  | email | EMAIL | `personal_email_verified` set to null |
  - **Salary raise after DRAFT:** any value higher than stored (or when none is stored) → `SALARY_INCREASE_NEEDS_REVIEW` "To update your salary, please contact support. We need to verify the new amount first." A decrease is allowed.
  - **Emergency-contact fields are overwritten as sent.** Clearing one nulls it; this is not a partial update.
  - **UAN** (not on this screen) must be 12 digits (`INVALID_UAN`).
  - Every change is written to `profile_change_log` (the activity timeline).
  - Identity fields (name, PAN, Aadhaar, mobile, DOB) are not editable.
- **Postconditions:** the profile of the **pointed-at application** is updated. A later reborrow clones it forward.
- **Edge cases:** resetting ADDRESS to PENDING on a SANCTIONED file does not rewind the journey, because the row still exists and derivation counts any row.
- **Alternate & error flows:** Codes `SALARY_INCREASE_NEEDS_REVIEW`, `INVALID_UAN`; otherwise "Could not save your changes.".

### UC-B2-39 Notification preferences (`/settings`)
- **Actor / entry point:** account menu "Account settings". This page stays reachable while onboarding is paused.
- **Preconditions:** Signed-in borrower (BORROWER role, else `FORBIDDEN_ROLE` "Preferences are borrower-scoped").
- **Screen:**
  - **Heading:** "Account settings" / "Manage your preferences for this app." "Saving…" shows while busy.
  - **Notifications (server-persisted; all on by default):**
    - "Email notifications" — Payment reminders and status updates by email.
    - "SMS & WhatsApp notifications" — Due-date and repayment alerts by text message and WhatsApp.
    - "Product offers" — Occasional pre-approved offers and product news.
  - **Device preferences** ("These stay on this device only."): "Biometric unlock" — stored in localStorage `navix.settings.security.biometric`. It has no effect.
  - **Security:** "Reset your password — we'll email you a secure reset link." with "Reset password" → `/forgot-password`.
  - **Profile:** "Edit profile" → `/profile`.
- **Main flow:** a toggle → an optimistic update → `PUT` preferences (all three flags) → refetch. Failure: "Couldn't save your preferences. Please try again."
- **Business rules (`NotificationDispatcher`):**
  - Email off → the EMAIL channel is skipped for **every** borrower notification type, including due/overdue reminders. A `SKIPPED` delivery is recorded.
  - SMS off → SMS **and** WhatsApp are skipped.
  - IN_APP is never suppressed.
  - "Product offers" only gates ADMIN customer messages in the MARKETING category (`OFFERS_OPTED_OUT`).
  - No saved row = everything on.
- **Postconditions:** a `borrower_preferences` row (keyed on the customer id).
- **Alternate & error flows:** "Couldn't save your preferences. Please try again." The optimistic value is reconciled by refetch.
- **Edge cases / not allowed:** Opting out of SMS also silences due-date reminders and verified/rejected payment SMS (only in-app remains).

### UC-B2-40 Support, grievance and FAQ (`/support`)
- **Actor / entry point:** account menu "Support" / "Help & FAQ" (`/support#faq`), the decline card, "Contact support" on the rejected callback. Reachable while paused.
- **Preconditions:** None (works signed in, including while paused).
- **Screen:**
  - **Heading:** "Support" / "We're here to help. Reach us directly, raise a grievance, or browse the FAQ."
  - **Contact tiles** (both values from `BRAND`):
    - "Email us" — the support email, "We reply within one business day." (mailto)
    - "Call us" — the support phone, "Mon–Sat, 9am–7pm IST." (tel)
  - Communication-consent and privacy text.
  - The payment-safety ticker.
  - **"Raise a grievance":** "Not satisfied with a resolution? Escalate to our Grievance Redressal Officer, {name}, and we'll respond within the regulatory timeline." plus the email and phone (from `BRAND.grievanceOfficer`). Button "File a grievance" (mailto, subject "Grievance — DhanBoost").
  - **"Help & FAQ"** (accordion of 7):
    1. How much can I borrow? (up to ₹10,00,000, minimum ₹1,000)
    2. What does it cost? (10 % fee + 18 % GST, deducted upfront)
    3. How is interest charged? (1 %/day for actual days)
    4. When do I repay? (single repayment on the next salary credit, within 40 days, no EMIs)
    5. Can I pay early? (yes, no penalty)
    6. What happens if I'm late? (2 %/day, capped at 30 days)
    7. How do I make a payment? (UPI/bank transfer plus reference, verified by accounts)
- **Postconditions:** none (static).
- **Main flow:** Read → tap email/phone/grievance mailto or expand an FAQ.
- **Business rules:** Contacts and the officer come from `lib/brand.ts`; nothing is fetched.
- **Alternate & error flows:** None.
- **Edge cases / not allowed:** The FAQ omits PayU (§Z9).

### UC-B2-41 Refer & earn card
- **Actor / entry point:** dashboard aside. It renders nothing while loading, or when `referralApi.me().enabled` is false (flag/config `referral`).
- **Preconditions:** Signed-in borrower; the referral program is enabled.
- **Screen:**
  - **Title:** "🎁 Refer & earn ₹{reward}". Copy: "You and your friend each get ₹{reward} once their first loan is disbursed."
  - **Code box:** the code (lazily minted server-side) with "Copy" → "✓ Copied" (1.5 s).
  - **Button:** "Share your code" (Web Share API with the server `shareMessage`; falls back to copy).
  - **Stats:** "Earned ₹{total}" and "Friends joined {qualified count}". If pending > 0: "₹{pending} pending payout".
- **Business rules:**
  - The reward is per person, `navix.referral.reward-paise` (default 20 000 = ₹200).
  - A code is redeemed at signup only (file 01). Codes: `INVALID_REFERRAL_CODE`, `SELF_REFERRAL`, `ALREADY_REFERRED`, `NOT_NEW_BORROWER`, `REFERRAL_DISABLED`.
  - At the referred borrower's first disbursal, two PENDING payouts are created (referrer + referred). The Disbursement Head marks them paid with a txn id → `REFERRAL_REWARD_CREDITED` (IN_APP, SMS, WhatsApp).
  - The references screen's "Refer & Earn" banner is unrelated.
- **Main flow:** Copy or share the code → a friend redeems it at signup → payouts after the friend's first disbursal.
- **Alternate & error flows:** A clipboard failure is silent (the code stays visible).
- **Postconditions:** The code is minted on first read.
- **Edge cases / not allowed:** The card hides entirely when disabled; there is no "program off" message.

### UC-B2-42 Notification bell
- **Actor / entry point:** the header bell (signed-in borrower).
- **Preconditions:** Signed-in borrower (the bell is only mounted in authenticated shells).
- **Screen:**
  - Bell with an unread badge (red, "99+" cap).
  - Dropdown "Notifications" with "Mark all read" (when unread > 0).
  - Each item: an unread dot, title, a 2-line body, and relative time (just now / {m}m ago / {h}h ago / {d}d ago, else "d Mon").
  - States: "Loading…", "Couldn't load notifications.", "You're all caught up."
  - Closes on outside click or Esc.
- **Main flow:** the unread count and the list each poll every 20 s (the list only while open). Clicking an item marks it read and deep-links:
  - category REPAYMENT with a loan → `/repay`
  - any loan → `/dashboard`
  - application → `/loan/status`
  - otherwise no navigation
- **Postconditions:** notification marked read.
- **Business rules:** In-app rows are always written (never suppressed by preferences). The badge counts unread.
- **Alternate & error flows:** "Couldn't load notifications."
- **Edge cases / not allowed:** A notification with no loan or application id does not navigate.

### UC-B2-43 Header, account menu and sign out
- **Actor / entry point:** Any signed-in borrower page (header) and the avatar menu.
- **Preconditions:** A session for the authed header; otherwise the signed-out header.
- **Header** (signed in):
  - Brand → `/dashboard`.
  - Nav "Dashboard", "Repay".
  - Gold "New loan" (only when `canStartNewLoan`).
  - Bell.
  - Avatar menu with the first name.
  - Signed out: "Sign in" / "Apply Now".
- **Account menu:**
  - A header with the name and mobile.
  - Groups:
    1. Dashboard, Profile details
    2. Past loans, Past transactions
    3. Repay / prepay, Borrow again (hidden unless `canStartNewLoan`)
    4. Support, Help & FAQ, Account settings
  - "Sign out" (red).
- **Sign out flow (`useBorrowerLogout`):**
  1. `POST /api/auth/borrower/logout` clears the httpOnly `navix_borrower` cookie.
  2. `clearBorrowerClientState`: the app-id pointer, the onboarding draft (memory and storage), and every `navix.*` localStorage key.
  3. `queryClient.clear()`.
  4. `router.replace("/login")`.
- **Footer** (every borrower page):
  - "NBFC Partner : {lender}." plus the disclaimer.
  - "© 2026 {legal name}".
  - Links: Privacy, Terms, Grievance, Main site.
- **Screen:** As listed above.
- **Main flow:** Pick a menu item, or Sign out → the four steps above.
- **Business rules:** "New loan"/"Borrow again" show only when every application is in a done status.
- **Alternate & error flows:** The logout call is best-effort; client state is cleared regardless.
- **Postconditions:** Cookie cleared; localStorage `navix.*` and the query cache emptied.
- **Edge cases / not allowed:** A different user signing in on the same browser without signing out is also handled: login clears the client state.

---

## Z. Code ↔ doc contradictions and sharp edges

Code wins in every case below; each one is a candidate fix.

1. **A penny drop runs on an unchanged salary account.**
   - The `OfferService.confirmDisbursalAccount` javadoc and CLAUDE.md §6 ("confirm salary account or penny-drop a new one") say confirming the salary account never fires a drop.
   - The code drops whenever the application is not already `disbursal_account_verified` for that exact account. The `changed` flag is only recorded.
   - So a first-time borrower confirming their salary account pays the ₹1 check and can be locked out. The UI copy ("Confirming will perform a ₹1 account check") matches the code.
   - PRD_REBUILD §7.3 step 10 makes the same "confirm the salary account" claim.
2. **A provider outage or name mismatch costs a strike.**
   - `verifyPennyDrop` turns a provider failure into REVIEW "you can continue…". `confirmDisbursalAccount` treats any non-PASS as a failure: it records a strike and throws `ACCOUNT_INVALID`.
   - Three outages in a row lock an innocent borrower for 12 h. The bank-proof card is the way out.
3. **The KFS says "There is no auto-debit mandate on this advance."** This is false whenever `digio-mandate` is on, and it is on in production per the Digio runbook.
4. **`/reloan` copy and routing are stale.**
   - The copy promises "re-confirm your current salary" and "a quick approver review" for past misses.
   - In code there is no salary step, and a disqualified borrower is auto-rejected `PAST_DELINQUENCY` unless an ADMIN allowance exists (→ `KYC_PENDING`).
   - Success pushes to the retired `/loan/salary`, which bounces via `/loan/status`.
   - A legacy reborrower left `PRE_APPROVED` (no prior sanction) sees "Under review" with no borrower action.
5. **The selfie FAIL/retake path is unreachable.**
   - `verifySelfie` and `selfieLivenessResult` return only PASS / REVIEW / PENDING.
   - The 3-attempt logic and the "1 retake remaining" hint are dead code.
   - "Verified — continuing…" is also shown for REVIEW.
6. **Amount presets can exceed the ceiling.** They round to ₹500, so 100 % of ₹10,300 becomes ₹10,500 → `LIMIT_EXCEEDED`. A ceiling below ₹1,000 renders a slider the server will always refuse (`AMOUNT_TOO_LOW`).
7. **An abandoned Aadhaar eSign freezes the amount, and repeat presses mint repeat contracts.**
   - The first successful initiate writes a PENDING `ESIGN` row → `TERMS_LOCKED`, with nothing signed.
   - Only a REVIEW written by the callback poll unlocks it. A lapsed contract is re-minted as PENDING.
   - Each "Sign with Aadhaar" press while PENDING mints another billable contract, because `esignInit` short-circuits only on PASS.
8. **`PAYU_EMAIL_REQUIRED` is handled on the frontend but never thrown.** The backend substitutes `pay{loanId}@dhanboost.com`.
9. **With PayU on, a first-time payer gets no manual option.** `manualAllowed` needs a failed, expired or stale (≥30 min) PayU attempt first. The FAQ still says payment is manual UPI/bank only.
10. **The account-number rule differs at each layer.** The client requires 9–18 digits, the offer server accepts ≥6, and the mandate requires 8–18. A 6–8-digit account passes the confirm step and then fails the mandate init (`INVALID_ACCOUNT_NUMBER`).
11. **The references banner "Refer & Earn Gifts and Vouchers"** suggests a reward. References are not connected to the referral program.
12. **The DigiLocker callback "Continue" is hard-coded to `/loan/references`.** The server step list (for example a reborrow without references) is not consulted until the layout guard corrects it.
13. **PRD_REBUILD §7.3 differs from the code in three places:**
    - It names `BELOW_DRAWN_AMOUNT` for the amount screen. That is a staff-side code; the borrower sees `AMOUNT_TOO_LOW` / `LIMIT_EXCEEDED`.
    - It lists "all-in APR" on the summary screen. APR is only on the KFS PDF.
    - It says the date is shown with "due on your salary day — never 'in N days'". The repayment-date screen says "Fixed by our credit team…", the amount screen derives "×N days", and the dashboard LoanCard shows "Due in N days".
14. **The profile KYC widget does not show real check states.** It is derived from the application status, so a reset ADDRESS/PENNY_DROP after a profile edit still reads "verified".
15. **`LoanDetailBody` asks for the outstanding "as of" the UTC date** (`toISOString`). `/repay` uses the IST date, so between 00:00 and 05:30 IST the dialog can quote one interest day less than `/repay`.
16. **The onboarding-paused gate keys on `loan` rows.** A first-time borrower who is `SANCTIONED` (no loan row yet) while `onboarding-paused` is on would be redirected to `/apply` from the offer journey.
17. **`REAPPLY_SANCTION` and `REBORROW_ALLOWED` send no notification.** Only the `REBORROW` → PRE_APPROVED fork notifies (`REBORROW_PREAPPROVED`).
18. **The tracker says "Approved" while credit is still deciding.**
    - `appStatusToStage` maps `CREDIT_EXEC_PENDING` (and `KYC_APPROVED`) to "Approved — Loan offer ready".
    - That label shows on the `/loan/status` tracker, the dashboard chip, and `stageDescription` ("Approved by our credit team — a few steps left to receive your money.").
    - Meanwhile the card on the same page reads "Under review".
    - `DRAFT` maps to `NEW`, which is not one of the tracker's stages, so no stage is highlighted.
