# 01 — Borrower sign-in, gates and Phase-1 intake

This file is the use-case catalog for everything a borrower does before the credit team first sees the file: signing in, the
gates that can stop them, the 12-screen `/signup/*` intake, the verification run at consent, the bureau security question,
submission (`DRAFT → KYC_PENDING`), and the fraud and duplicate rules that can end an application. It adds the screen-level
detail that `docs/PRD_REBUILD.md` leaves out. **The code is the source of truth.** Every message below is quoted from the
code. Where the code and a doc disagree, the code is described and the disagreement is listed in §Z.

## Contents

- §0 Conventions shared by every screen
- [UC-B1-01 Sign in with mobile OTP](#uc-b1-01-sign-in-with-mobile-otp)
- [UC-B1-02 Sign in with mobile + password](#uc-b1-02-sign-in-with-mobile--password)
- [UC-B1-03 Skip the sign-in form for a returning, verified borrower](#uc-b1-03-skip-the-sign-in-form-for-a-returning-verified-borrower)
- [UC-B1-04 Request a password-reset link](#uc-b1-04-request-a-password-reset-link)
- [UC-B1-05 Set a new password from the reset link](#uc-b1-05-set-a-new-password-from-the-reset-link)
- [UC-B1-06 Waitlist form while onboarding is paused](#uc-b1-06-waitlist-form-while-onboarding-is-paused)
- [UC-B1-07 Waitlist "under review" and the freeze redirect](#uc-b1-07-waitlist-under-review-and-the-freeze-redirect)
- [UC-B1-08 Applications-closed screen](#uc-b1-08-applications-closed-screen)
- [UC-B1-09 Intake screen 1 — PAN, mobile, T&C, PEP](#uc-b1-09-intake-screen-1--pan-mobile-tc-pep)
- [UC-B1-10 Intake screen 2 — verify mobile and create the draft](#uc-b1-10-intake-screen-2--verify-mobile-and-create-the-draft)
- [UC-B1-11 Intake screen 3 — set a password (optional)](#uc-b1-11-intake-screen-3--set-a-password-optional)
- [UC-B1-12 Intake screen 4 — employment status](#uc-b1-12-intake-screen-4--employment-status)
- [UC-B1-13 Self-employed branch (auto-reject, 90 days)](#uc-b1-13-self-employed-branch-auto-reject-90-days)
- [UC-B1-14 Intake screen 5 — employer and salary](#uc-b1-14-intake-screen-5--employer-and-salary)
- [UC-B1-15 Intake screen 6 — personal and official email, both OTP-confirmed](#uc-b1-15-intake-screen-6--personal-and-official-email-both-otp-confirmed)
- [UC-B1-16 Intake screen 7 — salary account and bank statements](#uc-b1-16-intake-screen-7--salary-account-and-bank-statements)
- [UC-B1-17 Intake screen 8 — three salary slips](#uc-b1-17-intake-screen-8--three-salary-slips)
- [UC-B1-18 Intake screen 9 — Aadhaar number and card](#uc-b1-18-intake-screen-9--aadhaar-number-and-card)
- [UC-B1-19 Intake screen 10 — PAN card](#uc-b1-19-intake-screen-10--pan-card)
- [UC-B1-20 Intake screen 11 — consent OTP, date of birth, bureau authorisation](#uc-b1-20-intake-screen-11--consent-otp-date-of-birth-bureau-authorisation)
- [UC-B1-21 The consent-time verification run](#uc-b1-21-the-consent-time-verification-run)
- [UC-B1-22 Answer the bureau security question (`/credit-question`)](#uc-b1-22-answer-the-bureau-security-question-credit-question)
- [UC-B1-23 Intake screen 12 — submit the application (submit-kyc gate)](#uc-b1-23-intake-screen-12--submit-the-application-submit-kyc-gate)
- [UC-B1-24 Optional accelerators after submission (address proof, UAN)](#uc-b1-24-optional-accelerators-after-submission-address-proof-uan)
- [UC-B1-25 Resume on any device (server-side journey)](#uc-b1-25-resume-on-any-device-server-side-journey)
- [UC-B1-26 Aadhaar-mismatch fraud rejection](#uc-b1-26-aadhaar-mismatch-fraud-rejection)
- [UC-B1-27 Duplicate Aadhaar: silent flag at save, permanent reject at submit](#uc-b1-27-duplicate-aadhaar-silent-flag-at-save-permanent-reject-at-submit)
- [UC-B1-28 Rejected screen and the cooling-off on the next attempt](#uc-b1-28-rejected-screen-and-the-cooling-off-on-the-next-attempt)
- [UC-B1-29 After submission — `KYC_PENDING`, notifications, tracking](#uc-b1-29-after-submission--kyc_pending-notifications-tracking)
- §Z Code/doc contradictions and known gaps

---

## §0 Conventions shared by every screen

**Error display.** Every API error shown to a borrower is rendered as `<message> (<CODE>)`, plus ` · ref <requestId>`
when the backend returned an `X-Request-Id`. For example: `Invalid or expired OTP (INVALID_OTP) · ref 3f9c…`. This
comes from `formatApiError` / `formatEnvelopeError` in `lib/api/errors.ts`. Below, "shows X" means "shows X in that
format". Pure client-side validation messages carry no code.

**Rate-limit messages.** The backend `AttemptLimiter` throws `TOO_MANY_ATTEMPTS`. The message is the lead sentence plus
` Please try again in <human wait>.`, so the borrower always sees the real remaining wait.

**OTP and code rules (backend).**

| Code | Purpose key | Length | TTL | Wrong guesses | Sends allowed | Channel |
|---|---|---|---|---|---|---|
| Mobile login / signup OTP | `LOGIN` | 6 (`NAVIX_SMS_OTP_LENGTH`) | 300 s (`NAVIX_SMS_OTP_TTL`) | 5, then the code is dropped | 5 per mobile per 15 min → `TOO_MANY_ATTEMPTS` "Too many OTP requests for this number." | SMS (UltronSMS, DLT template) **and** WhatsApp; `sent=true` if either succeeds |
| Bureau-consent OTP | `BUREAU_CONSENT` | 6 | 300 s | 5 | 5 per mobile per 15 min, with its **own** budget separate from login | SMS (own DLT key `BUREAU_CONSENT_OTP`, falls back to the login template id) + WhatsApp |
| Personal-email OTP | `PERSONAL_EMAIL` | 6 | 600 s | 5 | 5 per address per 15 min | Email; subject `"<code> is your DhanBoost verification code"` |
| Official-email OTP | `OFFICIAL_EMAIL` | 6 | 600 s | 5 | 5 per address per 15 min | Email (same template) |

- Codes are single-use and held in memory, keyed by purpose plus the normalised mobile or address.
- A code is never logged or returned, with two exceptions. **Mock mode** (`NAVIX_SMS_MOCK=true`) accepts the fixed
  code `123456` and reports `sent=true`. **Dev-echo** returns the code to the client, which shows
  `Dev code: <code>` instead of the "Code sent" line. `ProductionConfigGuard` refuses to boot either mode in
  production.
- An email OTP to an address on the SES suppression list is not sent and returns `sent=false`.

**Six-box code input (`OtpInput`).** Six numeric boxes. Typing auto-advances, Backspace on an empty box moves back,
and a paste fills all boxes with non-digits stripped. Where the screen wires `onComplete`, entering the 6th digit
submits automatically.

**Upload rules (every document picker).**
- Accepted: PDF, JPG/JPEG, PNG, HEIC/HEIF, WEBP. The check is on MIME type **or** file extension, so an empty or odd
  MIME type still passes on its extension.
- Maximum 10 MB per file.
- Errors: `Upload a PDF or a photo (JPG, PNG or HEIC).` and `File must be under 10 MB.`
- Images are re-encoded and compressed in the browser (`compressImage`).
- Every upload has three steps:
  1. **presign**: get a key under `applications/<appId>/…`.
  2. **PUT**: the browser sends the file straight to S3.
  3. **register**: the key is recorded as an `application_document` row.
- If storage is unreachable, the backend refuses with `UPLOAD_UNAVAILABLE` "We can't accept uploads just now. Please
  try again in a few minutes."
- A registered key must sit in this application's own folder and outside the system folders (`credit_brief`,
  `bureau_report`, `sanction_letter`, `signed_agreement`). Otherwise the backend refuses with `INVALID_DOCUMENT_KEY`
  "That upload does not belong to this application".
- The optional file password is trimmed, stored on each document row, and truncated to 128 characters.

**Wizard chrome (`/signup/*` layout).**
- Shown on every intake screen:
  - a header "Step N of 12"
  - the step label from `ONBOARDING_STEPS`: Your details · Verify your mobile · Set a password · Employment status ·
    Employer & salary · Email addresses · Salary account · Salary slips · Aadhaar card · PAN card · Consent &
    verification · With our credit team
  - "Secure & encrypted"
  - a **Save & exit** button that goes to `/dashboard`
  - a progress bar at `(N/12)·100 %`
- Most screens end with `WizardActions`, a Back link and a gold Continue button, and the reassurance strip
  "256-bit encrypted · Transparent terms · No advance fee".
- A screen that loads with no stored application id (`localStorage navix.live.applicationId`) redirects to
  `/signup/start`.
- `completeStep(step)` records the step server-side (`POST /api/applications/{id}/journey/{step}`) and then navigates.
  **A failure to record the step is swallowed**, so the borrower is never blocked by the pointer write.
- Each screen pre-fills from the saved server profile (`GET …/profile`), not from local storage, so a second device
  sees what was typed.

**Session.**
- Borrower JWT: audience `borrower`, subject = `customerId`, lifetime 7 days (`BORROWER_AUTH_TTL_SECONDS`).
- The BFF stores it in the httpOnly cookie `navix_borrower` as `{token,id,customerId,name,mobile}`.
- `customerId` is derived from the **last 7 digits of the mobile**. The first successful sign-in writes a
  `borrower_mobile` claim. A different mobile that derives the same id is refused with `CUSTOMER_ID_COLLISION`
  "This number can't be used for sign-in right now. Please contact support."

---

### UC-B1-01 Sign in with mobile OTP

- **Actor / entry point:** Borrower, new or returning.
  - Routes: `/login`, the "Sign in" link in the header, or a deep link `/login?next=<path>` (for example from the
    bureau-question email).
  - The **Mobile OTP** tab is the default.
- **Preconditions:** None. Not captcha-gated; SMS cost is throttled server-side.
- **Screen:**
  - Heading **"Welcome back"**, sub "Sign in to manage your advance or finish your application."
  - Two tabs: **Mobile OTP** | **Password**. Switching tabs clears the error.
  - Stage *enter*:
    - **Mobile number** (required, numeric keypad, placeholder `98765 43210`). The input is normalised as you type:
      non-digits stripped, a leading `91` dropped when there are more than 10 digits, leading zeros dropped, capped
      at 10 digits.
    - Button **Send code** ("Sending…" while busy), disabled until exactly 10 digits.
  - Stage *verify*:
    - "Enter the 6-digit code", then "Sent to **<mobile>** · Change". **Change** returns to stage *enter*; the resend
      counter is **not** reset.
    - The six-box input, which auto-submits on the 6th digit.
    - A status line, one of:
      - `Dev code: <code>` (mock/dev-echo only)
      - "Code sent. Resend (N left)"
      - once no resends remain: "No more resends — contact support if you didn't receive your code."
      - when delivery failed (`sent=false`): "We couldn't send an SMS. Try again (N left)", and once exhausted
        "Please contact support."
    - Button **Sign in**, disabled until 6 digits.
  - Footer: "New to DhanBoost? **Apply for an advance**" → `/signup/start`, then the reassurance strip.
- **Main flow:**
  1. Borrower types a mobile and taps **Send code**. The BFF re-normalises the number and the backend generates a
     code (purpose `LOGIN`) and sends it by SMS and WhatsApp.
  2. Borrower enters the code. The BFF calls the backend login; the backend verifies the code and claims the
     customer id.
  3. The backend resolves the display name from the newest stored profile for that mobile, or "Borrower" if none.
     It issues the JWT and the BFF sets the cookie.
  4. The client wipes every prior-user artefact on this browser: the onboarding draft, the app-id pointer, all
     `navix.*` keys, and the React Query cache.
  5. Borrower lands on `next` (same-site paths only, via `safeNextPath`), or `/dashboard` by default.
- **Business rules:**
  - Client: `MAX_RESENDS = 3`, and the counter includes the first send. So **3 sends in total** from this screen.
  - Server: 5 sends per 15 minutes per mobile; 5 wrong guesses per code; TTL 300 s; single use.
  - The login screen sends **no name**. The name always comes from server data, never the device.
- **Alternate & error flows:**
  - Fewer than 10 digits → "Enter a valid 10-digit mobile number". From the BFF the same check returns "Enter a
    valid 10-digit mobile number."
  - Resend cap reached on the client → "Maximum resend attempts reached. Please try again later or contact support."
  - Send cap reached on the server → `TOO_MANY_ATTEMPTS` "Too many OTP requests for this number. Please try again in …".
  - Code shorter than 6 digits → "Enter the 6-digit code."
  - Wrong, expired, or locked-out code → `INVALID_OTP` "Invalid or expired OTP". If the envelope cannot be read,
    the client falls back to "Incorrect code — please try again."
  - Derived-id collision → `CUSTOMER_ID_COLLISION` (see §0).
  - Network failure → "Something went wrong — please try again."
  - Backend unreachable → 502 "Could not reach the authentication service." / "Could not reach the OTP service."
- **Postconditions:**
  - `navix_borrower` cookie set (7 days).
  - `borrower_mobile` claim row written on the first successful login.
  - No application is created here. No notification is sent.
- **Edge cases / not allowed:**
  - `/dashboard` then applies the gates: UC-B1-07 waitlist freeze, and the resume CTA "Continue application" →
    `/signup/start` for a `DRAFT`.
  - The login mobile check is length-only. The signup screens additionally require a first digit of 6–9.

### UC-B1-02 Sign in with mobile + password

- **Actor / entry point:** A returning borrower who set a password (UC-B1-11 or Profile). Route: `/login` → **Password**
  tab.
- **Preconditions:** A password row exists for the derived customer id.
- **Screen:**
  - **Mobile number** (required, same normalisation as UC-B1-01).
  - **Password** (required, `type=password`, placeholder "Your password").
  - A Cloudflare Turnstile widget, action `borrower-password-login`.
  - Button **Sign in** ("Signing in…"). It is disabled until there are 10 digits and a password. When a site key is
    configured it is also disabled until a captcha token arrives, unless the widget failed to render, in which case
    the button unblocks and the backend decides.
  - Link **Forgot password?** → `/forgot-password`.
- **Main flow:**
  1. Borrower submits.
  2. The backend runs, in this order:
     1. captcha check
     2. normalise to the last 10 digits
     3. limiter
     4. collision check (read-only)
     5. credential lookup
     6. BCrypt compare
  3. On a match the limiter is cleared, the mobile claim is recorded, and the name is resolved as in UC-B1-01.
     The JWT and cookie are issued.
  4. The client wipes prior-user state and lands on `next` or `/dashboard`.
- **Business rules:**
  - 3 wrong attempts per mobile within a 15-minute memory window trigger a **30-second** cool-off. Successes don't
    count.
  - The captcha runs **before** the limiter, so a bot cannot burn a real borrower's three attempts.
  - Turnstile tokens are single-use, so the widget is reset after every failure.
  - The password policy is **not** applied at login, so a pre-policy password keeps working.
- **Alternate & error flows:**
  - Empty fields → "Enter a valid 10-digit mobile number" / "Enter your password."
  - Captcha failed → `CAPTCHA_FAILED` "Could not confirm you are human. Please try again."
  - Not 10 digits server-side → `INVALID_MOBILE` "Enter a 10-digit mobile number".
  - Limiter tripped → `TOO_MANY_ATTEMPTS` "Too many sign-in attempts. Please try again in …".
  - No password set → `NO_PASSWORD_SET` "No password is set for this number. Sign in with an OTP, then add a password."
  - Wrong password → `INVALID_CREDENTIALS` "Invalid mobile or password". The client fallback is the same text.
  - Collision → `CUSTOMER_ID_COLLISION`.
- **Postconditions:** As in UC-B1-01.
- **Edge cases / not allowed:**
  - The mobile→id claim is **never written before the password matches**. Writing it early would let an attacker
    squat another person's id.
  - `NO_PASSWORD_SET` tells the caller whether a number has a password; this is accepted behaviour.

### UC-B1-03 Skip the sign-in form for a returning, verified borrower

- **Actor / entry point:** A borrower who opens `/login` while still holding a valid `navix_borrower` cookie.
- **Preconditions:** A cookie session exists, **and** at least one of the borrower's applications is in a "KYC done"
  status:
  - `KYC_APPROVED`, `PRE_APPROVED`, `CREDIT_EXEC_PENDING`, `DISBURSEMENT_PENDING`, `DISBURSED`, `ACTIVE`, `OVERDUE`,
    `DEFAULTED`, `CLOSED`, `WRITTEN_OFF`
  - or one of the retired values `REVIEW_PENDING`, `CREDIT_EXEC_APPROVED`, `CREDIT_HEAD_PENDING`,
    `CREDIT_HEAD_APPROVED`, `ACCOUNTANT_PENDING`, `DISBURSEMENT_FAILED`.
- **Screen:** A centred spinner only, until the check resolves.
- **Main flow:** On mount the page reads `/api/auth/borrower/me` and then `/mine`. If the preconditions hold, it
  replaces the route with `next`, or `/dashboard`. Otherwise it shows the form.
- **Business rules:** The check runs once per mount. Any error falls through to the form.
- **Edge cases / not allowed:** `DRAFT`, `KYC_PENDING` and `SANCTIONED` are **not** in the set. A borrower in any of
  those states sees the full sign-in form even with a valid cookie.

### UC-B1-04 Request a password-reset link

- **Actor / entry point:** Borrower → **Forgot password?** on the Password tab → `/forgot-password`.
- **Preconditions:** None. The response never reveals whether the email matched.
- **Screen:**
  - Heading **"Reset your password"**, sub "Enter the email on your account and we'll email you a secure reset link."
  - **Email** (`type=email`, placeholder `you@example.com`).
  - Turnstile, action `borrower-forgot-password`.
  - Button **Email me a reset link** ("Sending…"), with the same captcha gating as UC-B1-02.
  - Link **Back to sign in**.
  - After success the form is replaced by a check icon, "If that email matches an account, we've emailed a reset
    link. It expires in 30 minutes.", and a **Back to sign in** button.
- **Main flow:**
  1. Borrower enters an email and submits. The backend runs, in order: captcha, the limiter, then a lookup.
  2. The lookup finds the newest profile whose `email` equals the input (case-insensitive) **and** has
     `personal_email_verified = true`, then resolves that profile's **application's** `customerId`.
  3. On a match the backend mints a 32-byte random token. It stores only the SHA-256 hash, with a 30-minute TTL and
     single use, and emails the link `<frontend-base-url>/reset-password?token=<raw>`.
  4. The generic acknowledgement is returned whether or not anything matched.
- **Business rules:**
  - 3 requests per email per 15 minutes; once blocked, the block also lasts 15 minutes. Every request counts, match
    or not.
  - Email: subject "Reset your DhanBoost password". The body says the link works "within 30 minutes" and "If you
    didn't request this, you can safely ignore this email".
  - The mail is sent **directly** through the email client, not through the notification dispatcher, so it never
    appears in the in-app inbox.
  - It is skipped silently when email is disabled or the address is suppressed.
  - With the `log` email provider the link is written to the backend log (dev only).
  - The token's subject comes from the application row. It is never derived from the profile's mobile, which the
    borrower can edit.
- **Alternate & error flows:**
  - Input without `@` → "Enter the email on your account."
  - `CAPTCHA_FAILED`.
  - `TOO_MANY_ATTEMPTS` "Too many reset requests. Please try again in …".
  - Network error → "Something went wrong — please try again."
  - A rejection never renders as the success acknowledgement, and the captcha is re-challenged.
- **Postconditions:** A `password_reset_token` row (subject type `BORROWER`) exists only on a match.
- **Edge cases / not allowed:**
  - Only an **OTP-verified personal** email qualifies. The official or work email never does.
  - A borrower who never verified their personal email cannot reset by email and must use OTP sign-in.
  - No mobile number is asked for.

### UC-B1-05 Set a new password from the reset link

- **Actor / entry point:** Borrower clicks the emailed link → `/reset-password?token=…`.
- **Preconditions:** An unused, unexpired borrower token.
- **Screen:**
  - Heading **"Set a new password"**, sub "Choose a password of at least 8 characters (up to 72) with a letter, a digit
    and a special character."
  - **New password**, with that same hint as helper text.
  - **Confirm new password** (placeholder "Re-enter your password").
  - Button **Update password** ("Updating…").
  - Link **Back to sign in**.
  - Success: "Your password has been updated. You can now sign in with it." and **Go to sign in**.
- **Main flow:** Client validation, then the backend validates the policy, looks up the token hash, checks the
  subject type, `used_at` and `expires_at`, sets the BCrypt hash and stamps `used_at`.
- **Business rules (password policy, shared by borrowers and staff):**
  - Server (`PasswordPolicy`): ≥ 8 characters, ≤ 72 **UTF-8 bytes**, at least one letter, one digit, and one character
    that is neither a letter, a digit nor whitespace. Otherwise `WEAK_PASSWORD` "Password must be at least 8 characters
    and at most 72 bytes, and include a letter, a digit and a special character."
  - Client (`passwordOk`): the same rule, but the letter check is ASCII-only `[A-Za-z]`.
- **Alternate & error flows:**
  - No token in the URL → "This reset link is invalid or has expired."
  - Policy fails → "Password must be at least 8 characters (up to 72) with a letter, a digit and a special character."
  - Passwords differ → "Passwords don't match."
  - Unknown token, or a staff token used here → `INVALID_TOKEN` "This reset link is invalid."
  - Already used → `INVALID_TOKEN` "This reset link has already been used."
  - Expired → `EXPIRED_TOKEN` "This reset link has expired. Please request a new one."
- **Postconditions:**
  - The `borrower_credential` hash is replaced or created; the token is marked used.
  - The borrower is **not** signed in automatically.
- **Edge cases / not allowed:**
  - Unlike a staff reset, a borrower reset does **not** revoke existing borrower sessions. A 7-day JWT issued before
    the reset stays valid.
  - Several unexpired tokens can coexist, each single-use.

### UC-B1-06 Waitlist form while onboarding is paused

- **Actor / entry point:** A signed-in borrower who has **never held a loan**, while the flag `onboarding-paused` is ON
  (V76 seeds it ON on a fresh database). Entry: `/apply`. Any other route redirects here (UC-B1-07).
- **Preconditions:** The gate is computed server-side (`GET /api/onboarding-waitlist`):
  - `OPEN` when the flag is off **or** the customer has any `loan` row
  - `SUBMITTED` when a waitlist row exists for the customer
  - `FORM` otherwise
- **Screen (gate `FORM`):**
  - Heading **"Tell us about yourself"**, lead "We're not opening new applications right now. Share your details and
    we'll get back to you."
  - Fields. All are required, and errors appear only after a submit attempt:
    - **Full name**, ≥ 2 characters after trimming → "Enter your full name"
    - **Email**, client regex `^[^\s@]+@[^\s@]+\.[^\s@]+$` → "Enter a valid email address"
    - **Mobile number**, pre-filled from the session and editable, `^[6-9]\d{9}$` → "Enter a valid 10-digit mobile
      number"
    - **PAN**, auto-uppercased, max 10, `^[A-Z]{5}[0-9]{4}[A-Z]$`, placeholder `ABCDE1234F` → "Enter a valid
      10-character PAN"
    - **Aadhaar number**, digits only, max 12, shown grouped as `1234 5678 9012` → "Enter a valid 12-digit Aadhaar
      number". The client checks length only.
  - Button **Submit** ("Submitting…").
  - While loading: "Loading…".
- **Main flow:**
  1. Borrower submits.
  2. The backend re-validates locally and calls **no vendor**: trimmed name non-empty; email `^[^@\s]+@[^@\s]+\.[^@\s]{2,}$`;
     mobile stripped of `91` when it has 12 digits; PAN regex; Aadhaar **Verhoeff**.
  3. The backend checks for duplicates: the PAN or Aadhaar must not belong to another customer's profile or another
     waitlist row.
  4. It saves one row per customer.
  5. The gate query is refreshed and the page shows UC-B1-07.
- **Business rules:**
  - One submission per customer. A repeat call just echoes the stored row.
  - Nothing is sent to Signzy, Digitap or Fintrix.
- **Alternate & error flows (copy mapped by the client):**
  - `DUPLICATE_PAN` → "This PAN is already registered with us — please sign in with the mobile number it was
    registered under."
  - `DUPLICATE_AADHAAR` → "This Aadhaar is already registered with us — please sign in with the mobile number it was
    registered under."
  - `INVALID_EMAIL` / `INVALID_MOBILE` / `INVALID_PAN` / `INVALID_AADHAAR` → the client strings above.
  - `INVALID_NAME` (server) → "Enter your full name".
  - `ONBOARDING_OPEN` (the flag was switched off meanwhile) → the gate is refetched, becomes `OPEN`, and the borrower
    goes to `/dashboard`.
  - Signed out → `/login`.
- **Postconditions:**
  - An `onboarding_waitlist` row is saved with the Aadhaar in full; only ADMIN sees it in full.
  - Notification `WAITLIST_RECEIVED` goes to the borrower by **IN_APP** "We have your details — Your application is
    under review. We'll get back to you soon." and **EMAIL** "We've received your DhanBoost application".
- **Edge cases / not allowed:**
  - The mobile field is shape-checked only. It is not tied to the login mobile.
  - The server email rule is stricter than the client's: it needs a TLD of at least 2 characters, so `a@b.c` passes
    the client and fails the server.

### UC-B1-07 Waitlist "under review" and the freeze redirect

- **Actor / entry point:** A waitlisted borrower (gate `SUBMITTED`), or any gated borrower navigating anywhere.
- **Preconditions:** Gate `FORM` or `SUBMITTED`.
- **Screen (`SUBMITTED`):**
  - Clock icon, **"Your application is under review"**, "Thanks — we have your details and will get back to you soon."
  - A summary: Name, Email, Mobile, PAN, and Aadhaar as `••••<last4>`.
  - Buttons **Contact support** → `/support` and **Sign out**. Sign out clears the cookie and all client state, then
    goes to `/login`.
- **Main flow:** `OnboardingGate` is mounted once in the borrower layout. While the gate is `FORM` or `SUBMITTED` it
  replaces any route outside `/apply`, `/login`, `/settings`, `/support` (and their sub-paths) with `/apply`. While
  loading or `OPEN` it does nothing.
- **Business rules:**
  - The gate query is cached for 30 s and runs only with a session.
  - Signup entry also checks the public flag: `/signup/start` sends a paused borrower to `/login`, and `createDraft` and
    `submit-kyc` refuse with `ONBOARDING_PAUSED` "We are not taking new applications right now". The blocked-start map
    sends `ONBOARDING_PAUSED` to `/apply`.
- **Postconditions:** None.
- **Edge cases / not allowed:**
  - The frozen set is everyone without a loan row. That includes a borrower with a `DRAFT` or a `KYC_PENDING` file.
    They are bounced off `/loan/status`, `/credit-question` and `/dashboard` to `/apply`, even though their file is
    in flight.
  - Reborrowers (customers with any loan) are unaffected.

### UC-B1-08 Applications-closed screen

- **Actor / entry point:** Any borrower whose new-application or reloan attempt hits `APPLICATIONS_CLOSED` (flag
  `applications-closed`, ADMIN Settings). Route `/applications-closed`.
- **Preconditions:** The flag is ON and the borrower has no live or in-flight application.
- **Screen:**
  - Heading **"We're not accepting new applications right now"**.
  - "This includes new loans and reloans. Please check back soon."
  - "If you already have a loan or an application in progress, nothing has changed. You can still track it and make
    repayments."
  - Buttons **Go to my account** → `/dashboard` and **Contact support** → `/support`.
- **Main flow:** `createDraft` or `reborrow` throws `APPLICATIONS_CLOSED` "We are not accepting new applications right
  now". `routeForBlockedStart` redirects here.
- **Business rules:**
  - The check runs **last** in `assertCanStartNewApplication`. A borrower who already has a file in flight gets
    `ACTIVE_APPLICATION` and carries on.
  - Nothing is cancelled and nobody is notified when the switch flips.
- **Postconditions:** None.

---

### UC-B1-09 Intake screen 1 — PAN, mobile, T&C, PEP

- **Actor / entry point:** A new borrower.
  - From **Apply for an advance** on `/login`, from the marketing site, or from `/signup` (which redirects to
    `/signup/start`).
  - From the dashboard **Continue application** button for a `DRAFT`.
- **Preconditions:** None to view the screen.
- **Screen:** Step 1 of 12 "Your details". Lead "Let's start with your PAN and mobile number. It takes about two minutes."
  - **PAN** (required, auto-uppercase, max 10, placeholder `ABCDE1234F`). Must match `^[A-Z]{5}[0-9]{4}[A-Z]$` →
    "Enter a valid 10-character PAN".
  - **Mobile number** (required, numeric, normalised as in UC-B1-01, placeholder `9876543210`, helper "We'll send a
    verification code to this number."). Must match `^[6-9]\d{9}$` → "Enter a valid 10-digit mobile number".
  - Checkbox "I agree to the **Terms & Conditions**".
    - Ticking it, or clicking the link, opens the T&C modal. Only **Agree** in the modal sets the tick. Unticking
      clears it.
  - Checkbox "I confirm that I am not a Politically Exposed Person, nor related to one."
  - When either box is unticked after a submit attempt: "Both confirmations are required to continue."
  - **Continue** is disabled until both boxes are ticked. PAN and mobile errors appear only after a submit attempt.
  - **T&C modal:**
    - Eyebrow "DhanBoost borrower agreement", title "Terms & Conditions", "Review the agreement before recording your
      consent."
    - The text is fetched from `/legal/terms-and-conditions.txt` and shown in a scrollable box. "Loading…" shows while
      it fetches.
    - Footer "Version: terms-and-conditions@1", with **Decline** (closes and leaves the box unticked) and **Agree**
      (disabled while loading).
- **Main flow:**
  1. On mount, the fields pre-fill from the device draft. The T&C box is pre-ticked only if the draft holds the
     **current** terms version.
  2. If the borrower is already signed in (another device, or a returning borrower):
     1. The page reads the public `onboarding-paused` flag. If it is ON → `/login` (no draft is ever created).
     2. Otherwise it resolves or creates the draft (as in UC-B1-10) and asks the server journey for the route.
     3. It replaces the route with that `/signup/*` step, or `/signup/set-password` if the server returned something
        outside `/signup/`.
  3. The borrower fills the form and taps Continue. PAN, mobile, `termsVersion = terms-and-conditions@1` and
     `pepDeclared = true` are written **only to the local draft** (`navix.onboarding.draft`). The page goes to
     `/signup/otp`.
- **Business rules:**
  - Nothing is persisted server-side on this screen; there is no application yet.
  - Bumping `TERMS_VERSION` re-prompts every borrower.
- **Alternate & error flows:**
  - Signed-in start blocked:
    - `ACTIVE_APPLICATION` → `/loan/status`
    - `ACTIVE_LOAN` → `/repay`
    - `RETURNING_BORROWER` → `/reloan`
    - `ONBOARDING_PAUSED` → `/apply`
    - `APPLICATIONS_CLOSED` → `/applications-closed`
    - any other code (including `NOT_ELIGIBLE`) → stays on the form silently
- **Postconditions:** The local draft holds `pan`, `mobile`, `termsVersion` and `pepDeclared`.
- **Edge cases / not allowed:**
  - If the T&C fetch fails, the modal shows "Terms & Conditions could not be loaded. Please try again." **Agree is
    then enabled**, because the error text counts as loaded text. A borrower can therefore agree to terms they never
    saw.
  - There is no PAN duplicate check here. It happens on screen 2.

### UC-B1-10 Intake screen 2 — verify mobile and create the draft

- **Actor / entry point:** Borrower arriving from screen 1 → `/signup/otp`.
- **Preconditions:** The draft holds a mobile; otherwise the page redirects to `/signup/start`.
- **Screen:** Step 2 of 12 "Verify your mobile".
  - "Enter the 6-digit code", "Sent to **<mobile>**", and the six-box input (auto-submits).
  - Status line, one of:
    - "Sending your code…" (first send in flight)
    - `Dev code: …`
    - "Code sent. Resend (N left)"
    - "We couldn't send an SMS. Resend (N left)"
    - once no resends remain: "Please contact support."
  - Back → `/signup/start`. **Verify** is disabled until 6 digits.
- **Main flow:**
  1. On mount the page sends one OTP (purpose `LOGIN`, the same code family as sign-in).
  2. The borrower enters the code. The client logs in (UC-B1-01) unless a session for the **same** mobile already
     exists. It then clears the query cache.
  3. **Resolve or create the draft:**
     1. Reuse the stored app id if that application is still `DRAFT`.
     2. Otherwise adopt any `DRAFT` from the ownership-scoped `/mine` list (this is how cross-device resume works).
     3. Otherwise `POST /api/applications` → `createDraft`.
  4. Store the app id. Save the first profile slice: `mobile`, `pan`, `termsVersion`, `pepDeclared`. The server stamps
     `terms_accepted_at` and `pep_declared_at` itself; the client sends only the version and the tick.
  5. Advance to `OTP` and go to `/signup/set-password`.
- **Business rules — `createDraft` guards, in order:**
  1. The caller must be the BORROWER whose id is in the request (ADMIN may open for anyone) → `FORBIDDEN_ROLE` "You can
     only start an application for yourself".
  2. A per-customer advisory lock is taken and held to commit, so two parallel creates queue.
  3. **Blocks:**
     - An active staff block (`BLOCKED_BY_STAFF`) → `NOT_ELIGIBLE` "Your account is not eligible for new loans. Please
       contact support."
     - An unexpired cooling-off row matched by customer id **or** by the mobile on the latest profile → `NOT_ELIGIBLE`
       "You are not eligible at the moment. You can apply again on or after <dd MMM yyyy, IST>." An indefinite block
       uses the "contact support" text.
  4. Any application in `ACTIVE`, `OVERDUE` or `DEFAULTED` → `ACTIVE_LOAN` "Repay your current advance before borrowing
     again".
  5. Any other non-terminal application → `ACTIVE_APPLICATION` "Finish your in-progress application before starting
     a new one".
  6. `applications-closed` ON → `APPLICATIONS_CLOSED`.
  7. Customer has a `CLOSED` loan → `RETURNING_BORROWER` "You already have an account with us — use Borrow again to
     start a new advance".
  8. `onboarding-paused` ON → `ONBOARDING_PAUSED`.
  9. Insert the `DRAFT` and append an `application_event` row (`CREATE`, from null to DRAFT, with actor and
     acting_role).
- **Business rules — profile save (`saveProfile`, every slice on every screen):**
  - Only the fields present are merged; a later slice never wipes an earlier one.
  - Each changed value is written to the staff activity log. PAN, mobile, account number and Aadhaar are masked there.
  - PAN on another customer's profile → `DUPLICATE_PAN` "This PAN is already registered with another customer."
  - Mobile on another customer's profile → `DUPLICATE_MOBILE` "This mobile number is already registered with another
    customer."
  - A borrower's profile mobile must equal their OTP-proven login mobile → `MOBILE_MISMATCH` "The mobile number must be
    the one you signed in with."
  - A mobile that is not 10 digits after stripping → `INVALID_MOBILE` "Mobile must be a 10-digit number".
- **Alternate & error flows:**
  - Wrong code → "Incorrect code — please try again. (INVALID_OTP)". The backend's own message is replaced by this
    client string.
  - Draft creation refused → mapped as in UC-B1-09. **Any unmapped code, including `NOT_ELIGIBLE`, sends the borrower
    to `/dashboard` with no message.**
  - Profile slice refused (for example `DUPLICATE_PAN`) → the error shows on this screen. The draft already exists by
    then. The borrower must go Back and correct the PAN.
  - Resend cap reached → "Maximum resend attempts reached. Please contact support."
- **Postconditions:**
  - Cookie session set.
  - `loan_application` in `DRAFT` with `journey_step = OTP`.
  - `customer_profile` holds mobile, PAN, terms version and timestamp, and PEP timestamp.
  - Audit event `CREATE`. No notification (`CREATE` maps to none).
- **Edge cases / not allowed:**
  - Resends: initial send + 3 resends = **4** sends from this screen. The server cap is 5 per 15 minutes, shared with
    sign-in.
  - If a session for this mobile already exists, the typed code is **not** checked. Any 6 digits proceed.

### UC-B1-11 Intake screen 3 — set a password (optional)

- **Actor / entry point:** Borrower after screen 2 → `/signup/set-password`.
- **Preconditions:** A session and an app id.
- **Screen:** Step 3 of 12 "Set a password".
  - Copy: "Add a password so you can sign in without an OTP next time. This is optional — you can skip it now and add
    one later from your profile."
  - A **Back** text link → `/signup/otp`.
  - **Password** (placeholder and helper = the policy hint).
  - **Confirm password**.
  - **Set password & continue** ("Saving…").
  - **Skip for now**.
- **Main flow:**
  - Set: client policy check → authenticated `POST /api/auth/borrower/set-password` → advance `SET_PASSWORD` →
    `/signup/employment`.
  - Skip: advance `SET_PASSWORD` → `/signup/employment`.
- **Business rules:**
  - The policy is as in UC-B1-05.
  - The endpoint refuses a non-borrower → `UNAUTHORIZED` "Sign in as a borrower to set a password".
  - It creates or replaces the BCrypt credential for the session's customer id.
- **Alternate & error flows:**
  - Client policy fails → "Password must be …".
  - Mismatch → "Passwords don't match."
  - Server → `WEAK_PASSWORD` (fallback "Could not set your password.").
  - Network → "Something went wrong — please try again."
- **Postconditions:** Optional `borrower_credential` row; journey pointer `SET_PASSWORD`. Success message
  server-side: "Password set. You can now sign in with it."
- **Edge cases / not allowed:**
  - The screen leaves no data trace when skipped. Only the pointer can hold a borrower here (see UC-B1-25).
  - **Back** re-opens the OTP screen, which **sends a new OTP on mount** and spends SMS budget.

### UC-B1-12 Intake screen 4 — employment status

- **Actor / entry point:** `/signup/employment`.
- **Screen:** Step 4 of 12. Lead "What is your employment status?"
  - Select **Employment status** (required): `Select…` / **Salaried** (`SALARIED`) / **Self-employed**
    (`SELF_EMPLOYED`). Error: "Select your employment status".
  - Pre-filled from the saved profile. Back → `/signup/set-password`.
- **Main flow:** Save `employmentStatus` → advance `EMPLOYMENT`. Then **Salaried** → `/signup/employer`;
  **Self-employed** → `/signup/self-employed`.
- **Alternate & error flows:** Save error → "Could not save — please try again."
- **Postconditions:** `customer_profile.employment_status` set.

### UC-B1-13 Self-employed branch (auto-reject, 90 days)

- **Actor / entry point:** A borrower who chose Self-employed → `/signup/self-employed`.
- **Preconditions:** A `DRAFT` application.
- **Screen:** Lead "A few more details and we'll review your application."
  - **Full name**: more than 2 characters after trimming → "Enter your full name".
  - **Date of birth**: `type=date`, max today, must be before today → "Enter your date of birth".
  - **Annual income**: digits only, > 0, placeholder `600000` → "Enter your annual income".
  - Back → `/signup/set-password`; Continue labelled **Submit**.
- **Main flow:**
  1. Save `fullName`, `dob` and `annualSalaryPaise` (rupees × 100).
  2. `POST …/self-employed` → `autoReject(SELF_EMPLOYED, "Declared self-employed at intake", 90 days)`.
  3. Clear client state.
  4. Show the outcome card: **"You are not eligible at the moment"**, "Thank you for your interest in DhanBoost. We're
     unable to take your application forward right now. Please try again later.", and **Back to home** → `/`.
- **Business rules:**
  - The server DOB rule (`INVALID_DOB`): it must be in the past ("Date of birth must be in the past") and not more than
    100 years ago ("Please check your date of birth").
  - The rejection writes an `application_rejection` row (`reason_code SELF_EMPLOYED`, `auto=true`,
    `blocked_until = now + 90 days`, keyed on customer id and mobile).
  - It moves the application `DRAFT → REJECTED` with action `AUTO_REJECT_SELF_EMPLOYED`.
  - The borrower is never told which rule fired.
- **Alternate & error flows:** Any error → "Something went wrong — please try again." plus the code.
- **Postconditions:**
  - Status `REJECTED`; cooling-off for 90 days (UC-B1-28).
  - Notification `CREDIT_REJECTED` to the borrower by IN_APP, SMS, EMAIL and WhatsApp:
    - in-app: "Application #<id> was declined at credit review. You can apply again on or after <date>."
    - SMS (no date, because the text is DLT-fixed): "Dear <name>, your DhanBoost loan application <id> could not be
      approved at this time. Details at https://dhanboost.com/login. - DhanBoost"
- **Edge cases / not allowed:**
  - This page is not in `ONBOARDING_STEPS`, so the header reads "Step 1 of 12 — Your details".
  - The wizard's mount-only journey guard derives `EMPLOYER` for this profile. A **reload** of `/signup/self-employed`
    therefore lands the borrower on the employer screen.

### UC-B1-14 Intake screen 5 — employer and salary

- **Actor / entry point:** `/signup/employer`, for Salaried borrowers.
- **Screen:** Step 5 of 12. Lead "Tell us where you work and when you get paid."
  - **Company name**: required, more than 1 character, placeholder "Infosys Limited" → "Enter your employer's name".
  - **Previous salary date**: `type=date`, max today, helper "The date you were last paid. We use it to set your
    repayment date." → "Pick the date you were last paid".
  - **Monthly salary (in-hand)**: digits only, > 0, placeholder `45000`, helper "The amount credited to your bank each
    month." → "Enter your monthly in-hand salary".
  - Pre-filled from the profile. Salary is shown in rupees and stored in paise.
- **Main flow:** Save `employer`, `previousSalaryDate`, `monthlySalaryPaise` → advance `EMPLOYER` → `/signup/email`.
- **Business rules:**
  - The server derives `loan_application.salary_credit_day` = the **day of month** of the previous salary date. This
    day drives the salary-linked due date later.
  - After `DRAFT` a borrower can no longer change the salary date. Raising the salary is refused with
    `SALARY_INCREASE_NEEDS_REVIEW` "To update your salary, please contact support. We need to verify the new amount
    first."
- **Alternate & error flows:** "Could not save — please try again."
- **Postconditions:** Profile employer, previous salary date and monthly salary saved; `salary_credit_day` set.
- **Edge cases / not allowed:** **Back** on this screen goes to `/signup/set-password`, not to the employment screen.

### UC-B1-15 Intake screen 6 — personal and official email, both OTP-confirmed

- **Actor / entry point:** `/signup/email`.
- **Screen:** Step 6 of 12.
  - Lead: "We send your sanction letter and statements to your personal email, and confirm your employer from your
    official one. We'll email a code to each to check it reaches you."
  - **Personal email address** (required, `type=email`, regex `^[^\s@]+@[^\s@]+\.[^\s@]+$`) → "Enter a valid email
    address".
  - **Official work email address** (required, same regex, placeholder `you@company.com`, helper "Used to confirm your
    employer. We send a 6-digit code to this address.") → "Enter your valid work email".
  - Under each address there is a verify block:
    - Unsent: **Send code to verify** ("Sending…"), disabled until the address is valid.
    - Sent: "Enter the 6-digit code we sent to this address", the six-box input (auto-confirms), and **Resend code**.
    - Verified: "✓ Email verified".
  - Editing an address resets **only** that block, and clears its verified state.
  - **Continue** is disabled until the personal address is verified **and** the official address is either verified
    or undeliverable.
- **Main flow:**
  1. **Send code** first saves the address to the profile; the server resolves the OTP target from the saved profile,
     never from the request. It then requests the email OTP.
  2. The borrower enters the code and the server verifies it. On success it sets
     `personal_email_verified = true` or `official_email_otp_verified = true` and writes a PASS row: `EMAIL_OTP`
     ("Personal email verified (OTP)") or `OFFICIAL_EMAIL_OTP` ("Official email verified (OTP)").
  3. Continue saves both addresses → advance `EMAIL` → `/signup/bank`.
- **Business rules:**
  - The rules are deliberately **asymmetric**:
    - If the official-email send fails (corporate filters drop outside mail, or the address is suppressed), the server
      writes an `OFFICIAL_EMAIL_OTP` row in **REVIEW** ("Verification code could not be delivered to the work email",
      `delivered=false`). The client shows "We couldn't deliver a code to this address — some workplaces block outside
      email. You can carry on; our team will confirm it with you." and stops gating Continue on it.
    - The personal email has **no** such escape. It is the channel for the sanction letter and statements.
  - Server gate: advancing the journey to `EMAIL` or beyond requires the personal address OTP-verified **and** the
    official address either OTP-verified or holding any `OFFICIAL_EMAIL_OTP` row. Otherwise `EMAIL_NOT_VERIFIED`
    "Verify both your personal and work email addresses to continue". The derivation in UC-B1-25 holds the borrower on
    this screen with the same rule.
  - Saving a different address (case-insensitive) clears that address's verified flag. Inbox control was proven for
    the old address only.
  - Codes: 6 digits, 10 minutes, 5 tries, 5 sends per 15 minutes per address.
- **Alternate & error flows:**
  - No saved address → `EMAIL_MISSING` "No personal email on file — save your email first" / "No official email on
    file — save your work email first".
  - Wrong code → `INVALID_OTP` "Invalid or expired code".
  - Send cap → `TOO_MANY_ATTEMPTS`.
  - Fallbacks: "Could not send the code — please try again." / "Incorrect code — please try again." / "Could not save —
    please try again."
- **Postconditions:** Profile `email` and `official_email` saved, with verification flags; `EMAIL_OTP` and
  `OFFICIAL_EMAIL_OTP` rows. Neither row is part of the submit gate.
- **Edge cases / not allowed:**
  - The "undeliverable" state lives in browser memory only. After a reload, Continue stays disabled until the borrower
    presses Send again.
  - The server already accepts the REVIEW row, so the journey would let them past.
  - If the **personal** send reports `sent=false`, the block shows the same "You can carry on" copy, but Continue stays
    disabled. The copy is misleading for that address.
  - `completeStep` swallows `EMAIL_NOT_VERIFIED`, so a stale client navigates on. The server pointer does not move, and
    a resume brings the borrower back here.

### UC-B1-16 Intake screen 7 — salary account and bank statements

- **Actor / entry point:** `/signup/bank`.
- **Screen:** Step 7 of 12.
  - Lead: "Add the salary account your employer credits each month, and upload your bank statements (ideally the last
    6 months)."
  - **Salary bank**: searchable Combobox over the Indian-bank list, more than 1 character → "Enter your salary bank".
  - **Account number**: digits only, 9–18 → "Enter a valid account number".
  - **IFSC code**: uppercased, spaces stripped, max 11, `^[A-Z]{4}0[A-Z0-9]{6}$` → "Enter a valid 11-character IFSC".
  - **Mobile number linked to this account**: `^[6-9]\d{9}$` → "Enter a valid 10-digit mobile number".
  - A multi-file drop zone, "Bank statements (last 6 months)" / "PDF or photo (JPG, PNG, HEIC) up to 10 MB each — upload
    one or several".
    - Once files are added: "N bank statement(s) added · Tap to add more", with a per-file list and a remove (✕)
      button.
    - Error: "Upload at least one bank statement to continue."
  - **PDF password (optional)**, shown once at least one file is added. Helper: "If your statement is
    password-protected, enter the password so our team can open it."
  - **Continue** is disabled until at least one statement is attached.
- **Main flow:**
  1. Save `salaryBank`, `salaryAccountNumber`, `salaryIfsc`, `salaryAccountMobile`.
  2. Upload each statement (presign → PUT).
  3. Register all the keys in one call as `BANK_STATEMENT`, with the password.
  4. Advance `BANK` → `/signup/payslips`.
- **Business rules:**
  - Any number of statements from 1 upward.
  - There is no penny-drop here. The account is verified only if it changes at the disbursal-account step of the
    offer journey.
- **Alternate & error flows:** File-type and size errors (§0); "Could not save — please try again."; `UPLOAD_UNAVAILABLE`.
- **Postconditions:** Salary account on the profile; `application_document` rows `BANK_STATEMENT` (`bank-statement-1…n`).
- **Edge cases / not allowed:**
  - The statement picker is not resume-aware. On a revisit the borrower must re-attach at least one file, and every
    submit adds more rows.
  - The server never requires a statement. The derivation advances once account number and IFSC are saved, and
    submit-kyc does not check for statements.

### UC-B1-17 Intake screen 8 — three salary slips

- **Actor / entry point:** `/signup/payslips`.
- **Screen:** Step 8 of 12. Lead "Upload your last 3 months' salary slips so we can verify your income."
  - Three single-file tiles: **Most recent month**, **Previous month**, **Month before that**. Each shows "PDF or
    photo (JPG, PNG, HEIC) up to 10 MB", or "<name> · tap to replace" once picked.
  - Error: "All 3 payslips are required to continue."
  - **PDF password (optional)**, shown once any slip is picked.
  - A result banner (PASS green / REVIEW amber / FAIL red).
  - **Continue** is disabled until all three are picked. Its label would read **Try again** after a FAIL.
- **Main flow:**
  1. Upload each slip (presign → PUT).
  2. Call `verifySalary(monthlySalaryPaise from the saved profile, keys, password)`.
  3. Advance `PAYSLIPS` → `/signup/aadhaar`.
- **Business rules (backend `verifySalary`):**
  - Refuses `monthlySalaryPaise ≤ 0` → `INVALID_SALARY` "Monthly salary must be positive".
  - Refuses a borrower once the file is past `DRAFT` → `SALARY_INCREASE_NEEDS_REVIEW`.
  - Stores the slips as `SALARY_SLIP` documents (`salary-slip-1..3`).
  - Sets `eligible_limit` = the ADMIN per-customer override if one exists (V69), otherwise 25 % of monthly salary
    floored to ₹100.
  - Writes the `SALARY` verification row as **PASS** "Declared salary recorded". This is self-declared; the
    independent check is employment (UC-B1-21).
  - The slips are registered by `verifySalary` itself. `SALARY_SLIP` is not on the generic upload allow-list.
- **Alternate & error flows:** "Could not upload your payslips — please try again."
- **Postconditions:** `SALARY` row (one of the four submit-gate checks); eligible limit set.
- **Edge cases / not allowed:**
  - If the profile has no monthly salary (employer screen skipped), Continue does nothing at all.
  - The FAIL branch cannot be reached today, because `verifySalary` always writes PASS.

### UC-B1-18 Intake screen 9 — Aadhaar number and card

- **Actor / entry point:** `/signup/aadhaar`.
- **Screen:** Step 9 of 12.
  - Lead: "Enter your Aadhaar number exactly as printed on the card, then upload a clear photo or PDF of both sides."
  - **Aadhaar number** (required, digits, shown grouped `1234 5678 9012`, max 14 characters, helper "12 digits. We
    compare it with the Aadhaar linked to your PAN and DigiLocker."). Errors:
    - fewer than 12 digits → "Enter your 12-digit Aadhaar number."
    - 12 digits that fail the checksum → "That doesn't look like a valid Aadhaar number — please check it against your
      card."
  - Two card tiles, **Front of Aadhaar card** (`AADHAAR_CARD_FRONT`) and **Back of Aadhaar card** (`AADHAAR_CARD_BACK`):
    - Empty: **Choose file**, plus on phones **Take photo**, which opens the rear camera; images only.
    - Picked: a preview (or a PDF icon), the file name and **Replace**.
    - Already on file: "Already uploaded" and **Upload a different file**. The ✕ is hidden for existing files.
    - Note: "Photo (JPG, PNG, HEIC) or PDF, up to 10 MB each. Make sure every corner of the Aadhaar card is visible and
      the text is readable."
    - Error: "Upload both the front and the back of your Aadhaar card to continue."
  - **PDF password (optional)**, shown only when a picked file is a PDF.
  - **Continue** is disabled while existing documents are loading.
- **Main flow:**
  1. On mount the page lists existing documents to mark sides as done, and pre-fills the number from the profile.
  2. On Continue it saves `aadhaar` only if the number changed.
  3. It uploads each **newly picked** side: presign → PUT → register, one key per side. A side already uploaded is
     never re-uploaded, and a failure on one side does not redo the other.
  4. Advance `AADHAAR` → `/signup/pan-card`.
- **Business rules:**
  - **Validation:** 12 digits, first digit 2–9, and a valid UIDAI **Verhoeff** check digit. The same rule is enforced
    client-side and server-side. Server error: `INVALID_AADHAAR` "Enter a valid 12-digit Aadhaar number — please check
    it against your card."
  - Stored in full (V75; a product-approved exception to masking).
  - **Lock:** once a PAN or AADHAAR verification row exists, the file has left `DRAFT`, or `aadhaar_verified` is true,
    any change is refused with `AADHAAR_LOCKED` "Your Aadhaar number has already been verified and can no longer be
    changed."
  - **Duplicate flag:** see UC-B1-27.
  - The server will not let the journey skip this screen. See UC-B1-25.
- **Alternate & error flows:** "Could not save your Aadhaar details — please try again."; upload errors.
- **Postconditions:** `customer_profile.aadhaar`; documents `AADHAAR_CARD_FRONT` and `AADHAAR_CARD_BACK`; possibly an
  `AADHAAR_DUPLICATE` REVIEW row.
- **Edge cases / not allowed:** These card types are distinct from the DigiLocker-fallback `AADHAAR_FRONT/BACK` used in
  the offer journey.

### UC-B1-19 Intake screen 10 — PAN card

- **Actor / entry point:** `/signup/pan-card`.
- **Screen:** Step 10 of 12.
  - Lead: "Upload a clear photo or PDF of both sides of your PAN card."
  - Then "The card should match the PAN you entered: **<PAN>**", or "…at the start." if the PAN is not loaded.
  - The same two-tile component as UC-B1-18, labelled **Front of PAN card** (`PAN_CARD_FRONT`) and **Back of PAN
    card** (`PAN_CARD_BACK`). Error: "Upload both the front and the back of your PAN card to continue."
- **Main flow:** Upload the new sides → advance `PAN_CARD` → `/signup/consent`.
- **Business rules:** Mandatory, back side included. Submit-kyc refuses without both.
- **Alternate & error flows:** "Could not upload your PAN card — please try again."
- **Postconditions:** Two PAN card documents.

### UC-B1-20 Intake screen 11 — consent OTP, date of birth, bureau authorisation

- **Actor / entry point:** `/signup/consent`. This is the only intake screen where external, billable APIs fire.
- **Preconditions:** A `DRAFT` with a mobile on the profile.
- **Screen:** Step 11 of 12.
  - Title "✓ One last confirmation", then "Enter the code we sent to **<mobile>** and authorise the credit check." The
    mobile shown is from the live session, falling back to the profile.
  - The six-box input. It does **not** auto-submit here.
  - **Date of birth**: `type=date`, required, min today − 100 years, max today, pre-filled from the profile. Error:
    "Your date of birth is required for the credit check."
  - Status: `Dev code: …`, or "Resend code (N left)", or "No more resends — please contact support if you didn't
    receive your code."
  - Checkbox: **"I authorize DhanBoost to retrieve my credit report from a credit bureau for verification and
    assessment purposes."** Error: "Your consent is required to continue."
  - Back → `/signup/pan-card`. Continue is labelled **Confirm & submit** and is disabled until 6 digits, ticked
    consent and a DOB.
  - While the checks run, the form is replaced by a spinner, "Verifying your details…", and "This takes a few seconds.
    Please don't close this page."
- **Main flow:**
  1. On mount the page sends a **bureau-consent OTP** (purpose `BUREAU_CONSENT`). The server resolves the mobile from
     the profile, never from the request.
  2. Borrower submits.
  3. Save `dob` (server rule `INVALID_DOB` as in UC-B1-13).
  4. Record consent: the server verifies the OTP **inside the consent write** and stores a `BUREAU_CONSENT` PASS row.
     That row holds the **exact consent text that was displayed** and channel `OTP`, with the message "Bureau consent
     given (OTP verified)".
  5. Run the verification sequence (UC-B1-21).
  6. Advance `CONSENT` and go to `/credit-question` if the bureau returned a challenge, otherwise to
     `/signup/submitted`.
- **Business rules:**
  - The consent text is one constant. It is both displayed and sent, so the screen and the audit row cannot drift.
  - `BUREAU_CONSENT` is not on the submit gate, and staff cannot set it manually.
  - The consent OTP has its own budget, separate from login.
- **Alternate & error flows:**
  - No mobile → "We couldn't find your mobile number. Please sign in again.", or server `MOBILE_MISSING` "No mobile on
    file for this application — complete the mobile step first".
  - Wrong code → `INVALID_OTP` "Invalid or expired OTP" (fallback "Incorrect or expired code — please try again."). The
    spinner is dismissed and the form returns.
  - Send failure → "Could not send the code — please try again."
- **Postconditions:** DOB on the profile; `BUREAU_CONSENT` row; the verification rows from UC-B1-21; pointer
  `CONSENT`.

### UC-B1-21 The consent-time verification run

- **Actor / entry point:** The system, triggered by UC-B1-20 immediately after consent. The calls run **in this order**
  in the browser.
- **Preconditions:** A `BUREAU_CONSENT` PASS row.
- **Screen:** "Verifying your details…" (UC-B1-20). Individual results are never shown to the borrower. Each call's
  failure is caught and ignored. **A FAIL never stops the borrower**; it goes to the credit team flagged.
- **Main flow and rules per check:**
  1. **PAN** (`verifyPan`; chain Signzy → Fintrix → Digitap). Runs only if the profile has a PAN.
     - Already PASS → returned as is.
     - Outcomes:
       - valid → **PASS** "PAN valid"
       - invalid → **FAIL** "PAN not valid"
       - provider says the PAN does not exist → **FAIL** "PAN not found — please check the number and try again"
         (`panNotFound`, not a provider error)
       - provider unreachable → **REVIEW** "PAN check unavailable — pending manual review"
     - Side effects:
       - sets `pan_verified` and `aadhaar_linked`
       - writes **full name** and **DOB** from the PAN record when the profile has none. The intake never asks for a
         name; this is where it comes from.
       - recomputes the name match
     - **Fraud rule:** if the PAN record carries a masked Aadhaar that disagrees with the typed number, see UC-B1-26.
       The client sees `derived.applicationRejected` and **stops the sequence** → `/application-rejected`.
  2. **Official email** (`verifyEmail`; Signzy → Digitap) on the saved official address.
     - Outcomes:
       - verified, employer matched and not generic → **PASS** "Email + employer matched"
       - generic domain → **REVIEW** "Not an official email"
       - otherwise → **REVIEW** "Employer not matched — manual review"
       - provider error → **REVIEW** "We couldn't verify your email right now — you can continue; our team will
         review it."
     - Also saves this address as the contact email if the profile has none.
  3. **Bureau** (`pullBureau`, with the consent OTP). The guards run in this order:
     1. Already PASS → reused.
     2. No `BUREAU_CONSENT` → REVIEW "Bureau consent not yet given — pull deferred."
     3. No DOB → REVIEW "Your date of birth is required before we can run the credit check."
     4. A sibling application of the same customer PASSed within **24 hours** → reused for free, with " (reused within
        24h)" appended.
     5. No name on the profile (PAN gave none) → REVIEW "We need the name from your PAN record before we can run the
        credit check." This is parked rather than making a billable call that cannot succeed.
     6. Provider failure → REVIEW "We couldn't run the credit check right now — you can continue; our team will review
        it."
     7. CRIF knowledge-based question → REVIEW "Bureau needs the borrower to answer a security question before the
        report can be released". The question, options, order id, report id and issuer are stored; see UC-B1-22.
     8. Success → **PASS** "Bureau pulled", or "Thin-file (no bureau record)". If the report's own PAN or DOB disagree
        with the profile → **REVIEW** with the mismatch text.
     - On success the backend also stores the score, source and risk category, ingests the vendor PDF, and builds the
       staff credit brief.
     - **Score floor:** below `MIN_BUREAU_SCORE = 550` auto-rejects `LOW_BUREAU_SCORE` (90 days) **only if**
       `bureau-auto-reject` is ON. It is OFF (V64), so today every score goes to a human. It never fires on a
       mismatch, a thin file or a challenge.
  4. **Employment / EPFO** (`verifyEmployment`; Digitap UAN). Advisory only and **not** on the submit gate.
     - Outcomes:
       - **PASS** "Employment confirmed with EPFO" / "Employment confirmed — <employer>"
       - **REVIEW** for: "Multiple UANs matched — manual review"; "No EPFO employment record found — manual review"; a
         date-of-exit message; "Employer does not match the declared employer — manual review"; a name mismatch; "EPFO
         record found but employment could not be confirmed — manual review"; "Employment not checked — no PAN, mobile
         or UAN on file"
       - provider outage → **REVIEW** "Employment check unavailable — pending manual review"
     - A resolved REVIEW is not re-billed unless forced.
     - The hourly scheduler (:30) re-runs outage-parked rows up to **5** attempts, on vendor codes
       `HTTP_402/500/502/503/505`, `TRANSPORT_FAILURE`, `EMPTY_RESPONSE` and unparseable responses. It covers files in
       `DRAFT`, `KYC_PENDING` or `REVIEW_PENDING`, while the flag `employment-auto-retry` is on.
- **Postconditions:**
  - Rows `PAN`, `EMAIL`, `BUREAU` and `EMPLOYMENT`. `SALARY` was written on screen 8.
  - Every provider call is logged to `provider_api_execution`.
  - Profile enriched with name, DOB and score.
- **Edge cases / not allowed:**
  - If the official email is missing, `EMAIL` never runs and submit-kyc later fails `KYC_INCOMPLETE`. The screen makes
    the official email mandatory, so this should not happen.
  - **Interrupted run.** If the tab closes after PAN but before the bureau call, the pointer has not moved but a PAN
    row exists. The derivation then sends the borrower to `/signup/submitted`, where submit-kyc refuses
    `KYC_INCOMPLETE` because `BUREAU` was never attempted. Nothing on that screen re-runs the bureau, so this is
    likely a stuck state that needs staff.

### UC-B1-22 Answer the bureau security question (`/credit-question`)

- **Actor / entry point:**
  1. Directly from UC-B1-20 when the bureau returned a challenge. This is the freshest question, minted seconds
     earlier.
  2. From the emailed or in-app nudge `BUREAU_QUESTION_PENDING`. ADMIN-triggered outreach sends it to `KYC_PENDING`
     files only, through `/login?next=/credit-question?appId=<id>`.
  - The page sits outside `/signup/*` on purpose, so there is no journey guard and no step counter.
- **Preconditions:** A signed-in borrower. The app id comes from `?appId=` or the stored pointer.
- **Screen.** The page reads **only the stored BUREAU row**; mounting never calls the provider. The phase is chosen
  from that row:
  - **done** (row is PASS): "All done — thank you" / "That's the last thing we needed. Our team is reviewing your
    application now." → **Continue** → `/loan/status`.
  - **skipped**: "Thanks — we'll take it from here" / "No problem. Our credit team will complete this check manually;
    you don't need to do anything else." → **Continue**.
  - **closed** (CRIF exhausted the question, or 3 or more attempts): "We'll take this one from here" / "The credit
    bureau has closed this security question. Our credit team will complete the check for you — there's nothing left
    for you to answer." → **Continue**. This records a skip. No new question is offered, because the attempt count
    survives re-mints.
  - **none** (no challenge): "Nothing to answer" / "There's no security question waiting on your application right
    now."
  - **needsMint** (a legacy row with no report id, or no options): heading "One quick security question", sub "The
    credit bureau needs to confirm it's really you before releasing your report. Only you know the answer.", then
    "Your question has expired. Get a fresh one — it only takes a moment." and button **Get my question** ("Getting
    your question…").
  - **question**: the same heading and sub, then the question text and one radio per option. The label shows the
    trimmed option, or "None of these" if it is blank. Buttons **Confirm answer** ("Checking…", disabled until an
    option is chosen) and **I don't recognise any of these**.
  - An amber notice line and a red error line.
- **Main flow:**
  - **Answer:**
    1. The backend runs three free guards before any billable call:
       - an open challenge with both an order id and a report id; otherwise `BUREAU_CHALLENGE_STALE` "This question
         has expired - please get a new one."
       - the answer is **verbatim** one of the stored options, with no trimming, because CRIF pads its options and
         compares literally; otherwise `BUREAU_CHALLENGE_ANSWER_INVALID` "Please pick one of the listed options."
       - fewer than 3 attempts
    2. It calls Fintrix directly, bypassing the verification chain because an order id belongs to one vendor.
    3. Outcomes:
       - success → the normal bureau finish (score, report, brief) → PASS → phase **done**
       - another question → re-parked → phase **question**
       - wrong answer → attempts + 1, REVIEW "That answer wasn't accepted - you can try again or get a new question."
         (shown as a notice)
       - CRIF exhausted → `bureauChallengeExhausted`, attempts set to 3, "The bureau has closed this question — our
         team will complete this check." → **closed**
  - **Get my question:** a forced re-pull. If the row is already PASS, nothing happens. Within **60 s** of the row's
    last update the stored row is returned unchanged. The client detects the unchanged order id and shows "Please wait
    a minute before asking for a new question."
  - **Skip:** no provider call. Sets `bureauChallengeSkipped`, REVIEW "Borrower could not answer the bureau security
    question - credit team to review."
- **Business rules:**
  - Max **3** answer attempts per challenge, and the count carries across re-mints.
  - 60 s mint cooldown, plus a client latch against double-clicks.
  - The answer text is never stored. Every mint is a live, billable Fintrix call with no sandbox.
  - A REVIEW bureau row still counts as "attempted" for submit-kyc.
- **Alternate & error flows:**
  - Load failure → "We couldn't load your question — please refresh." → phase none.
  - Mint failure → "We couldn't fetch a question just now — please try again in a minute."
  - Answer failure → "That didn't go through — please try again."
  - Skip failure → "Please try again."
  - `BUREAU_CHALLENGE_NONE` "There is no bureau question to answer." / "…to skip." / "…to refresh."
- **Postconditions:** The BUREAU row is updated (attempts, skipped or exhausted flags, or a PASS with score).
- **Edge cases / not allowed:**
  - **This page never calls submit-kyc.** On the consent→question path, every **Continue** goes to `/loan/status`,
    which does not submit either, and the application stays `DRAFT`. The file reaches the credit team only if the
    borrower later re-enters the wizard (dashboard **Continue application** → `/signup/start` → server route
    `/signup/submitted`), which submits it.
  - The waitlist freeze (UC-B1-07) redirects this page to `/apply` for a borrower with no loan.

### UC-B1-23 Intake screen 12 — submit the application (submit-kyc gate)

- **Actor / entry point:** `/signup/submitted`, reached from UC-B1-20 with no challenge, or by resume.
- **Preconditions:** A `DRAFT` application. The screen calls submit **once per mount**, automatically. There is no
  button.
- **Screen:** Step 12 of 12 "With our credit team".
  - Check icon, **"Your application is with our credit team"**.
  - The sub-line reads "Finishing up…" until the call returns, then "We'll be in touch as soon as it's reviewed. You can
    close this page — we'll notify you."
  - Below: the optional accelerator card (UC-B1-24).
  - "Questions? Call us at <BRAND.phone>" (a tel link).
  - Button **Track my application** → `/loan/status`.
- **Main flow (`POST /api/applications/{id}/submit-kyc`):**
  1. Ownership check: the borrower owns the file, or the caller is staff.
  2. **Completeness gate 1:** `PAN`, `EMAIL`, `BUREAU` and `SALARY` each hold a terminal status (PASS, REVIEW or FAIL)
     **and** `terms_accepted_at` is set. Otherwise `KYC_INCOMPLETE` "Complete every step and accept the Terms &
     Conditions before submitting".
  3. **Completeness gate 2:** the typed Aadhaar plus all four card images (`AADHAAR_CARD_FRONT/BACK`,
     `PAN_CARD_FRONT/BACK`). Otherwise `KYC_INCOMPLETE` "Upload both sides of your Aadhaar card and PAN card before
     submitting".
  4. **Duplicate-Aadhaar rule** (UC-B1-27). If it fires, the response is the `REJECTED` application and the client
     redirects to `/application-rejected`.
  5. **PAN–Aadhaar link check** (Signzy `kycIdLinkStatus`). Best-effort: linked → PASS "PAN linked to Aadhaar"; not
     linked, missing data or an outage → REVIEW. Exceptions are swallowed.
  6. `submitKyc`: borrower role required, and `onboarding-paused` must be off (`ONBOARDING_PAUSED`). Transitions
     `DRAFT → KYC_PENDING` with action `SUBMIT_KYC`.
  7. The client advances the pointer to `SUBMITTED` (best effort).
- **Business rules:**
  - The gate is **completeness, not outcome**. A failed PAN or bureau pull still reaches credit, flagged.
  - `EMPLOYMENT`, `PAN_AADHAAR_LINK`, `EMAIL_OTP`, `OFFICIAL_EMAIL_OTP` and `BUREAU_CONSENT` never gate.
  - The transition is validated by `ApplicationStatus.canTransitionTo`; skipping stages is impossible.
- **Alternate & error flows:**
  - Any refusal → the error line, with fallback "We couldn't submit your application." The header text stays on
    "Finishing up…".
- **Postconditions:** Status `KYC_PENDING`; audit event `SUBMIT_KYC`; notification `KYC_SUBMITTED` (UC-B1-29).
- **Edge cases / not allowed:** Calling submit on a file already past `DRAFT` fails `ILLEGAL_TRANSITION`, and the
  duplicate rule only fires on a `DRAFT`.

### UC-B1-24 Optional accelerators after submission (address proof, UAN)

- **Actor / entry point:** The same `/signup/submitted` screen, in the second card.
- **Screen:**
  - **"Help us process your loan faster"**, "Optional — adding an address proof can speed up the review. Sit back
    comfortably; we'll do the rest."
  - Three single-file tiles: **Electricity bill**, **Gas bill**, **Rent agreement**. A tile shows the file name once
    uploaded.
  - **EPFO UAN (optional)**: digits only, max 12, placeholder "12-digit number, printed on your payslip".
    - Helper: "Sharing your Universal Account Number helps us confirm your employment faster. Skipping it changes
      nothing."
    - Error when the input is non-empty and not 12 digits: "That's not 12 digits — or just leave it blank."
    - Button **Save**, then "Saved ✓".
- **Main flow:**
  - Bill tile: presign → PUT, and the tile is marked done.
  - UAN: a borrower self-edit (`editProfile`) of `uan` (server regex `\d{12}`, logged on the timeline). Then a silent
    re-run of the employment check.
- **Business rules:** Skipping either changes nothing about the outcome.
- **Alternate & error flows:** "Upload failed — please try again."; "Couldn't save that — please try again."
- **Edge cases / not allowed:** The bill uploads are **never registered**. There is no register call, and these document
  types are not on the backend upload allow-list. The S3 object exists, but no `application_document` row is written,
  so staff never see these files.

### UC-B1-25 Resume on any device (server-side journey)

- **Actor / entry point:** Any borrower re-entering intake:
  - the dashboard **Continue application** → `/signup/start`
  - the next-milestone card "Complete your application — Finish the remaining details so we can review your
    application." → `/signup/start`
  - opening any `/signup/*` URL
- **Preconditions:** A `DRAFT` application.
- **Main flow:**
  1. `/signup/start`, when signed in, resolves the draft and asks `GET …/journey` for the route.
  2. The `/signup` layout runs a **mount-only** guard: if the server route differs from the current path, it replaces
     it. It never fights a Back button later in the session.
- **Business rules (`JourneyService`):**
  - Status not `DRAFT` → step `DONE` → route `/loan/status`.
  - **Derived step** = the first screen whose data is missing:

    | Missing | Step |
    |---|---|
    | no profile, or no `terms_accepted_at` | START |
    | no mobile | OTP |
    | no employment status | SET_PASSWORD, or EMPLOYMENT if the pointer is already at or past SET_PASSWORD |
    | employer, previous salary date or monthly salary missing | EMPLOYER |
    | official email missing, or emails not settled (UC-B1-15 rule) | EMAIL |
    | account number or IFSC missing | BANK |
    | no `SALARY` row | PAYSLIPS |
    | Aadhaar number, or either Aadhaar card side, missing | AADHAAR |
    | either PAN card side missing | PAN_CARD |
    | no `PAN` row | CONSENT |
    | otherwise | SUBMITTED |

  - **Resolved step** = `max(derived, stored pointer + 1)`. The pointer covers screens that leave no trace, such as a
    skipped password.
    - **Exception:** if the derived step is AADHAAR or PAN_CARD, it wins outright. A pointer written before V75 cannot
      carry anyone past the card screens into a submit that would fail.
  - The pointer **never moves backwards**. Unknown step names are ignored, because a stale client must not hard-fail.
    A known step whose precondition is unmet throws (the email rule).
  - The step counter on the server reports `index/total` over the 12 steps.
- **Edge cases / not allowed:**
  - `/signup/start` falls back to `/signup/set-password` when the server route is not a `/signup/*` path.
  - Only a `DRAFT` is resumed. A further-along application makes `createDraft` throw `ACTIVE_APPLICATION`, which routes
    to `/loan/status`.

### UC-B1-26 Aadhaar-mismatch fraud rejection

- **Actor / entry point:** The system, inside the PAN check of UC-B1-21. DigiLocker completion in the offer journey
  runs the same rule.
- **Preconditions:** The typed Aadhaar is on file and the provider returned a masked Aadhaar.
- **Main flow:**
  1. Compare by position (`Aadhaar.matchesMasked`):
     - a 12-character mask (for example `XXXXXXXX1234` or `65XXXXXXXX90`) is compared digit by digit wherever it shows
       a digit
     - any other mask length is compared on its trailing four digits
     - if either side is missing, or the mask shows no digits, the result is UNKNOWN and the rule does **not** fire
  2. On MISMATCH, the PAN row's `derived` gains `aadhaarMismatch` and `applicationRejected`, and the message is suffixed
     " — Aadhaar mismatch: entered XXXX XXXX 1234, provider record …".
  3. If the status can still transition to `REJECTED`, the system auto-rejects with `FRAUD_REJECTED`. The detail
     "Aadhaar number entered does not match the PAN record (…)" is staff-only. The block lasts **365 days**, and the
     action is `AUTO_REJECT_FRAUD_REJECTED`.
  4. The client goes to `/application-rejected`.
- **Business rules:**
  - Signzy's PAN search returns no masked Aadhaar. In that case nothing is compared here and DigiLocker checks later.
  - The **DOB rule** (entered DOB versus the DigiLocker DOB) fires at DigiLocker in the offer journey, not at intake. A
    DigiLocker DOB of 1 January is compared on the year only.
  - On a file that can no longer be rejected (for example a staff retry on a disbursed loan), only the flag is recorded
    and a human decides.
- **Postconditions:**
  - `REJECTED`; a rejection-register row with a 365-day block on customer id and mobile.
  - `CREDIT_REJECTED` sent to the borrower (IN_APP, SMS, EMAIL, WhatsApp) with the retry date.

### UC-B1-27 Duplicate Aadhaar: silent flag at save, permanent reject at submit

- **Actor / entry point:** The system, at two moments.
- **Main flow:**
  1. **At save (UC-B1-18).**
     - When the typed number is on **any other customer's profile**, the system writes or updates an
       `AADHAAR_DUPLICATE` row: status REVIEW, provider SYSTEM, the other customer ids in `derived`, message "Aadhaar
       number is also on file for customer #… — confirm it is the same person before sanction".
     - When the number no longer collides, the row is deleted.
     - The borrower is told **nothing**, so the profile write cannot be used to test whether someone holds a number.
     - The row is staff-only and blocks `sanction` until a human resolves it (`AADHAAR_DUPLICATE_UNRESOLVED`).
  2. **At submit (UC-B1-23).** When the number is on another customer's **submitted** application (one that reached
     `KYC_PENDING`) and this file is still `DRAFT`, the system auto-rejects:
     - reason `AADHAAR_DUPLICATE`
     - block **INDEFINITE** (31 Dec 9999)
     - event action `AUTO_REJECT_IDENTITY`, notes "Identity check failed", deliberately generic
     - the register detail names the earlier customer and application
     - the client redirects to `/application-rejected`
- **Business rules:**
  - Only the full 12-digit number is compared. Four masked digits are too few to call two people the same.
  - The waitlist form (UC-B1-06) refuses a duplicate Aadhaar outright with `DUPLICATE_AADHAAR`.
- **Postconditions:**
  - `REJECTED` with a permanent block. The next start returns `NOT_ELIGIBLE` "Your account is not eligible for new
    loans. Please contact support."
  - `CREDIT_REJECTED` notification.
- **Edge cases / not allowed:** The notification's retry line is built from the block end and only skips a missing date.
  It most likely reads "You can apply again on or after 31 Dec 9999" on the in-app and email messages (see §Z).

### UC-B1-28 Rejected screen and the cooling-off on the next attempt

- **Actor / entry point:** `/application-rejected`, reached after UC-B1-26 or UC-B1-27. The self-employed screen
  (UC-B1-13) shows its own inline card instead.
- **Screen:**
  - Red ✕, **"Your application has been rejected"**.
  - "Thank you for your interest in DhanBoost. We're unable to take your application forward. If you believe this is a
    mistake, please contact our support team."
  - **Contact support** → `/support` and **Back to home** → `/`.
  - The page sits outside the signup layout, so there is no progress bar.
- **Main flow:** On mount it clears all client onboarding state, so a reload cannot drop the borrower back into a dead
  application.
- **Business rules — cooling-off on the next `createDraft`:**
  - The block is looked up by customer id **and** by the mobile on the latest profile. Editing the mobile does not
    escape it.
  - The message gives the date only, never the rule or the window length.

  | Reason code | Block |
  |---|---|
  | `SELF_EMPLOYED` | 90 days |
  | `LOW_BUREAU_SCORE` | 90 days, only when the auto-reject flag is on |
  | `FRAUD_REJECTED` | 365 days |
  | `AADHAAR_DUPLICATE` | permanent |
  | manual `REJECT_LEAD` | 30 days |
  | `BLOCKED_BY_STAFF` | until lifted |

- **Edge cases / not allowed:**
  - No borrower screen maps `NOT_ELIGIBLE`:
    - `/signup/start` swallows it.
    - `/signup/otp` sends the borrower to `/dashboard`.
    - So the "apply again on or after <date>" text from `createDraft` is effectively never displayed. The borrower
      learns the date only from the `CREDIT_REJECTED` notification.
  - On `/loan/status` the audit trail shows the raw event **action**. The event notes are stripped for the borrower, but
    the action is not, so a rejected borrower can read `AUTO_REJECT_SELF_EMPLOYED`, `AUTO_REJECT_FRAUD_REJECTED`, and so
    on. Only the duplicate rule uses the generic `AUTO_REJECT_IDENTITY`.

### UC-B1-29 After submission — `KYC_PENDING`, notifications, tracking

- **Actor / entry point:** The system and staff after UC-B1-23; the borrower via **Track my application**.
- **Postconditions and what happens next:**
  - `loan_application.status = KYC_PENDING`, with event `SUBMIT_KYC` (actor BORROWER, acting role recorded).
  - Notification **`KYC_SUBMITTED`** is published after commit and delivered asynchronously. Its audience is **Credit
    Heads and Admins only**, by IN_APP ("New KYC to review — A borrower submitted KYC for application #<id>. Review it
    in your queue.") and EMAIL ("New DhanBoost KYC to review — application #<id>").
  - **No borrower notification is sent on submission.**
  - The file appears in the Credit Head queue. The Head assigns it to self or an ACTIVE Credit Executive, which moves it
    to `CREDIT_EXEC_PENDING`, and the Executive decides (`sanction` / `reject-lead` / `mark-pending`). This is
    covered in the credit catalog file.
  - `/loan/status` polls every 4 s while the status can still move. It shows "Application #<id> · <status label>" (the status name in title
    case, for example "Kyc Pending"), the status tracker (stage `UNDER_REVIEW` for `KYC_PENDING`), a **Refresh**
    button, and a collapsible audit trail.
  - Staff can send a `KYC_REMINDER` (IN_APP, SMS, EMAIL, WhatsApp) listing pending steps. ADMIN can send
    `BUREAU_QUESTION_PENDING` (IN_APP and EMAIL only; there is no SMS because the DLT templates cannot carry a link) to
    `KYC_PENDING` files with an open question.
  - After submit, the borrower can no longer change their name, PAN or DOB: `PROFILE_LOCKED` "Your name, PAN and date
    of birth can't be changed after you submit. Contact support." Editing a verified field re-opens that check.

---

## §Z Code/doc contradictions and known gaps

1. **When the salary check runs.**
   - CLAUDE.md §6 says salary fires at `consent`, alongside PAN, email, bureau and employment.
   - In code, `SALARY` is written on the **payslips** screen. It is always PASS ("Declared salary recorded", the
     self-declaration). Consent fires PAN, official-email provider check, bureau and employment.
2. **Password rule.**
   - CLAUDE.md §7 says "Passwords ≥ 8 chars".
   - Code (`PasswordPolicy`) also requires a letter, a digit and a special character, with a maximum of 72 UTF-8 bytes.
     The client letter check is ASCII-only, while the server uses Unicode letters.
3. **Password-reset match.** The `PasswordResetService` class doc says the request is gated by "a matching email +
   mobile". The code matches **email only**, and only an **OTP-verified personal** email.
4. **Login resend count.**
   - The `BorrowerOtpService` comment says the login screen allows "1 initial + 3 resends".
   - `/login` counts the first send in its cap of 3, so it allows 3 sends in total. `/signup/otp` and `/signup/consent`
     do allow 1 + 3.
5. **"We'll notify you" on the submitted screen.** No borrower notification fires on `SUBMIT_KYC`. `KYC_SUBMITTED` goes
   to Credit Heads and Admins only.
6. **Address-proof uploads are orphaned.** The Electricity, Gas and Rent tiles on `/signup/submitted` presign and PUT,
   but never register a document row, and these types are not on the backend allow-list. Staff never see them.
7. **The bureau-question path never submits.**
   - `/signup/consent` routes a challenged borrower to `/credit-question`, whose every exit goes to `/loan/status`.
     Neither page calls submit-kyc, so the file stays `DRAFT` until the borrower re-enters the wizard.
   - The ADMIN outreach only targets `KYC_PENDING`, so those `DRAFT` files are not chased.
8. **Permanent-block notification date.** The Aadhaar-duplicate reject passes the INDEFINITE block (31 Dec 9999) as the
   event's `retryFrom`. `retryLine` only skips a missing date, so the in-app and email `CREDIT_REJECTED` messages most
   likely promise a year-9999 retry date. `assertNotBlocked` special-cases indefinite blocks; the notification path
   does not.
9. **"Never shows the reason."**
   - CLAUDE.md §6 and the code comments say the borrower never sees which rule fired. The rejected screen honours this,
     and `EventView.forBorrower` strips event notes.
   - But `/loan/status` renders the raw event **action** (`AUTO_REJECT_SELF_EMPLOYED`, `AUTO_REJECT_FRAUD_REJECTED`,
     `AUTO_REJECT_LOW_BUREAU_SCORE`).
10. **Cooling-off message never shown.** `NOT_ELIGIBLE` "…apply again on or after <date>" is not in the client's
    blocked-start map. `/signup/start` swallows it and `/signup/otp` silently goes to `/dashboard`.
11. **Waitlist email rule.** The client accepts any `x@y.z`; the server requires a TLD of at least 2 characters.
12. **Stale step count in a comment.** `/credit-question` says an unregistered segment would render "Step 1 of 10". The
    wizard has **12** steps, and that is exactly what the self-employed page shows ("Step 1 of 12 — Your details").
13. **Smaller UX gaps in code.**
    - The T&C **Agree** button enables after a failed text fetch.
    - Employer **Back** goes to set-password.
    - Set-password **Back** re-sends an OTP.
    - `/signup/otp` skips code verification when a same-mobile session already exists.
    - The bank-statement picker is not resume-aware, and the server never requires a statement.
    - The payslip FAIL branch cannot be reached.
    - The official-email "undeliverable" escape is browser-memory only.
    - The personal-email block shows "You can carry on" copy while Continue stays disabled.
    - A borrower password reset does not revoke live 7-day sessions; a staff reset does.
    - An interrupted consent run (PAN written, bureau not) likely strands the borrower at `/signup/submitted` with
      `KYC_INCOMPLETE`.
    - The waitlist freeze redirects in-flight no-loan borrowers away from `/loan/status` and `/credit-question`.
