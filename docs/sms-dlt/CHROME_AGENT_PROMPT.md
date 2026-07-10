# NAVIX DLT template-creation — self-contained agent instructions

This file is the COMPLETE, self-contained instruction set for the Claude-in-Chrome agent creating
NAVIX's DLT SMS content templates on the STPL portal. It embeds every template inline — the agent
needs nothing else. (Mirror of `dlt-templates.json`; if they ever differ, `dlt-templates.json` wins.)

---

## Preconditions (the operator ensures these before the agent runs)

- The STPL DLT portal is open and logged in as **NAVIX FINANCE PRIVATE LIMITED**, on the **Template
  (SMS)** listing page (the one with a **New Template** button).
- The URL **`https://www.navixfinance.com/login`** is already **URL-whitelisted** under the entity on
  the portal. Every teplate except the OTP (#1) contains this link; an un-whitelisted URL → rejection.

## Entity / sender settings

- Header / Sender ID: **NAVIXF** (existing templates use the `NAVIX` naming convention — consistent).
- Principal Entity ID (PE-ID): **`<FILL_IN_PEID>`** — the account is logged in as NAVIX FINANCE, so the
  form may auto-bind the entity. If the form REQUIRES a PE-ID and it's blank, STOP and ask the operator.
- Category for **every** template: **`Service Implicit`**.
  ⚠ NEVER "Transactional" — that's banks-only; NAVIX is non-banking and it will be rejected.

---

## HARD RULES (apply to every template)

1. **Category = `Service Implicit`** for all 15. If the dropdown has no "Service Implicit" option,
   STOP and ask.
2. **Paste the CONTENT string EXACTLY** — character-for-character. It must: contain the brand name
   `NAVIX Finance`; end with ` - NAVIX Finance`; have NO double spaces and NO trailing space; keep the
   URL exactly `https://www.navixfinance.com/login`.
3. **Variables**: the token is exactly `{#var#}` (with hashes). Prefer pasting the whole content so the
   `{#var#}` tokens auto-register as variables. If the portal needs the "Add Variable" button instead,
   type the static text and insert a variable at each `{#var#}` position, left-to-right, in order.
   Never hand-edit a `{#var#}` after inserting it.
4. For each variable, set its **TAG** and **SAMPLE VALUE** (given below) in the matching input, in
   order. If the tag dropdown lacks a listed name (OTP / Number / Amount / Date), report the available
   options and map to the closest (e.g. Numeric / Currency / Alphanumeric) — don't guess silently.
5. One variable ≤ **40 characters** (all samples below comply).
6. **Before submitting each one**, use `read_page` to confirm the message-box text equals the CONTENT
   string exactly. Fix if it differs; if it still won't match after two attempts, STOP and ask.
7. Click **Submit**. Capture the returned **DLT Template ID** + confirmation. Return to the create
   form for the next entry (navigate back if the portal doesn't auto-return).
8. **NEVER trigger a JavaScript alert/confirm/prompt dialog** (it freezes the extension). Avoid any
   Delete/Clear control that might confirm.
9. Record a GIF of the FIRST creation (`gif_creator`, name `dlt_first_template.gif`); then proceed
   without recording. Pause for operator approval after the first 2–3 submits, then continue.
10. ⚠ **PAUSE AND ASK before submitting #14 and #15** (BORDERLINE). They're worded for Service
    Implicit, but if the reviewer flags them promotional they must be registered as **Service
    Explicit** instead. Do not force-submit under a disputed category.

---

## The 15 templates — create IN ORDER

Format: `[#] NAME — CATEGORY` / `CONTENT:` / `VARS: index. purpose | tag | sample` (or "none").

**[1] NAVIX_OTP_LOGIN_V2 — Service Implicit**
CONTENT: `Your OTP for NAVIX Finance login is {#var#}. It is valid for {#var#} minutes. Do not share this OTP with anyone. - NAVIX Finance`
VARS:
1. numeric OTP code | OTP | `123456`
2. validity in minutes | Number | `5`
NOTE: OTP is intentionally link-free (no URL). Register it FIRST.

**[2] NAVIX_KYC_APPROVED_V2 — Service Implicit**
CONTENT: `Your KYC is verified with NAVIX Finance. Log in at https://www.navixfinance.com/login to choose your loan amount. - NAVIX Finance`
VARS: none

**[3] NAVIX_KYC_REJECTED_V2 — Service Implicit**
CONTENT: `We could not verify your KYC with NAVIX Finance. Log in at https://www.navixfinance.com/login to review and resubmit. - NAVIX Finance`
VARS: none

**[4] NAVIX_KYC_REMINDER_V2 — Service Implicit**
CONTENT: `Your verification with NAVIX Finance is incomplete. Log in at https://www.navixfinance.com/login to complete your pending steps. - NAVIX Finance`
VARS: none

**[5] NAVIX_LOAN_DISBURSED_V2 — Service Implicit**
CONTENT: `NAVIX Finance has disbursed {#var#} to your bank account. Repay {#var#} by {#var#} at https://www.navixfinance.com/login. - NAVIX Finance`
VARS:
1. net amount disbursed | Amount | `Rs. 8,820`
2. total repayable amount | Amount | `Rs. 12,700`
3. due date | Date | `30 Jun 2026`

**[6] NAVIX_REPAYMENT_VERIFIED_V2 — Service Implicit**
CONTENT: `Your payment of {#var#} to NAVIX Finance is confirmed. Outstanding balance is {#var#}. View details at https://www.navixfinance.com/login. - NAVIX Finance`
VARS:
1. payment amount | Amount | `Rs. 5,000`
2. remaining outstanding balance | Amount | `Rs. 7,700`

**[7] NAVIX_REPAYMENT_REJECTED_V2 — Service Implicit**
CONTENT: `Your payment of {#var#} could not be verified by NAVIX Finance. Log in at https://www.navixfinance.com/login to check the reference and record it again. - NAVIX Finance`
VARS:
1. payment amount | Amount | `Rs. 5,000`

**[8] NAVIX_PAYMENT_DUE_SOON_V2 — Service Implicit**
CONTENT: `Your NAVIX Finance payment of {#var#} is due in {#var#} day(s) by {#var#}. Pay at https://www.navixfinance.com/login on or after your salary day with no penalty. - NAVIX Finance`
VARS:
1. amount due | Amount | `Rs. 12,700`
2. days until due | Number | `3`
3. due date | Date | `30 Jun 2026`

**[9] NAVIX_PAYMENT_OVERDUE_V2 — Service Implicit**
CONTENT: `Your NAVIX Finance payment of {#var#} is overdue by {#var#} day(s). Pay now at https://www.navixfinance.com/login to stop the daily penalty and protect your credit score. - NAVIX Finance`
VARS:
1. overdue amount | Amount | `Rs. 12,700`
2. days overdue | Number | `5`

**[10] NAVIX_LOAN_CLOSED_V2 — Service Implicit**
CONTENT: `Your loan with NAVIX Finance is fully repaid and closed. Thank you. Visit https://www.navixfinance.com/login to borrow again. - NAVIX Finance`
VARS: none

**[11] NAVIX_APPLICATION_DECLINED_V2 — Service Implicit**
CONTENT: `NAVIX Finance is unable to approve your loan application at this time. Visit https://www.navixfinance.com/login for details. - NAVIX Finance`
VARS: none
NOTE: ONE template reused for BOTH the credit rejection and the reborrow rejection — register once;
its single DLT Template ID is bound to both notification types in the backend.

**[12] NAVIX_SETTLEMENT_APPROVED_V2 — Service Implicit**
CONTENT: `A full and final settlement of {#var#} is approved on your NAVIX Finance loan. Pay at https://www.navixfinance.com/login to close the loan. - NAVIX Finance`
VARS:
1. settlement amount | Amount | `Rs. 9,000`

**[13] NAVIX_REBORROW_APPROVED_V2 — Service Implicit**
CONTENT: `Your loan application with NAVIX Finance is approved. Log in at https://www.navixfinance.com/login to choose your amount. - NAVIX Finance`
VARS: none

**[14] NAVIX_REBORROW_PREAPPROVED_V2 — Service Implicit  ⚠ BORDERLINE — PAUSE & ASK BEFORE SUBMIT**
CONTENT: `Welcome back to NAVIX Finance. You can apply for another loan now. Log in at https://www.navixfinance.com/login to choose your amount. - NAVIX Finance`
VARS: none

**[15] NAVIX_REFERRAL_REWARD_CREDITED_V2 — Service Implicit  ⚠ BORDERLINE — PAUSE & ASK BEFORE SUBMIT**
CONTENT: `Your NAVIX Finance referral reward of {#var#} is credited with reference {#var#}. Log in at https://www.navixfinance.com/login to view it. - NAVIX Finance`
VARS:
1. referral reward amount | Amount | `Rs. 500`
2. payout transaction reference | Number | `TXN123456`

---

## At the end

Print a table of `{ template name -> DLT Template ID, status }` for all 15, so the operator can paste
the IDs into `docs/sms-dlt/SMSGuide.md` §6 and the backend config `navix.sms.dlt-template-ids`.
Reminder: template **#11**'s single ID maps to BOTH `CREDIT_REJECTED` and `REBORROW_REVIEW_REJECTED`.
