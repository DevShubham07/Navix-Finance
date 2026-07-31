# DhanBoost DLT template-creation — self-contained agent instructions

This file is the COMPLETE, self-contained instruction set for the Claude-in-Chrome agent creating
DhanBoost's DLT SMS content templates on the STPL portal. It embeds every template inline — the agent
needs nothing else. (Mirror of `dlt-templates.json`; if they ever differ, `dlt-templates.json` wins.)

> **Batch:** `DHANBOOST_*_V1` — a fresh registration. The NAVIX→DhanBoost rebrand changed the brand
> string and the URL in every body, which invalidates all 15 previously-assigned `_V2` ids. Nothing is
> being edited here; all 15 are created new.

---

## Preconditions (the operator ensures these before the agent runs)

- The STPL DLT portal is open and logged in as **NAVIX FINANCE PRIVATE LIMITED** (the legal entity is
  unchanged by the rebrand), on the **Template (SMS)** listing page (the one with a **New Template** button).
- **`DhanBoost` is registered as a brand under the entity.** DLT requires the registered brand name
  inside the body; if DhanBoost isn't registered, every submit is rejected with *"Entity brand name is
  not mentioned in the SMS content"*. **If unsure, STOP and ask before submitting anything.**
- The 6-char header **`DHANBT`** is registered and active under the entity (replaces the retired `NAVIXF`).
- **`dhanboost.com` is a live registered domain** and the URL **`https://www.dhanboost.com/login`** is
  **URL-whitelisted** under the entity. Every template except the OTP (#1) contains this link; an
  un-whitelisted URL → rejection.

## Entity / sender settings

- Header / Sender ID: **DHANBT**.
- Principal Entity ID (PE-ID): **`1701178039634361131`** (entity-level, unchanged by the rebrand). The
  account is logged in as the entity, so the form may auto-bind it. If the form REQUIRES a PE-ID and
  it's blank, STOP and ask the operator rather than assuming it wants this typed.
- Category for **every** template: **`Service Implicit`**.
  ⚠ NEVER "Transactional" — that's banks-only; DhanBoost is non-banking and it will be rejected.

---

## HARD RULES (apply to every template)

1. **Category = `Service Implicit`** for all 15. If the dropdown has no "Service Implicit" option,
   STOP and ask.
2. **Paste the CONTENT string EXACTLY** — character-for-character. It must: contain the brand name
   `DhanBoost`; end with ` - DhanBoost`; have NO double spaces and NO trailing space; keep the
   URL exactly `https://www.dhanboost.com/login`.
3. **Variables**: the token is exactly `{#var#}` (with hashes). Prefer pasting the whole content so the
   `{#var#}` tokens auto-register as variables. If the portal needs the "Add Variable" button instead,
   type the static text and insert a variable at each `{#var#}` position, left-to-right, in order.
   Never hand-edit a `{#var#}` after inserting it.
4. For each variable, set its **TAG** and **SAMPLE VALUE** (given below) in the matching input, in
   order. If the tag dropdown lacks a listed name (OTP / Number / Amount / Date), report the available
   options and map to the closest (e.g. Numeric / Currency / Alphanumeric) — don't guess silently.
   *(On the `_V2` run the portal lacked these and `Number` / `Alphanum` were used — that fallback is fine.)*
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
    Implicit, but on the `_V2` run both were ultimately registered as **Service Explicit** on
    reviewer-risk grounds. Expect the same here; do not force-submit under a disputed category.

---

## The 15 templates — create IN ORDER

Format: `[#] NAME — CATEGORY` / `CONTENT:` / `VARS: index. purpose | tag | sample` (or "none").

**[1] DHANBOOST_OTP_LOGIN_V1 — Service Implicit**
CONTENT: `Your OTP for DhanBoost login is {#var#}. It is valid for {#var#} minutes. Do not share this OTP with anyone. - DhanBoost`
VARS:
1. numeric OTP code | OTP | `123456`
2. validity in minutes | Number | `5`
NOTE: OTP is intentionally link-free (no URL). Register it FIRST — it is the only template that can
send before `dhanboost.com` exists, and it unblocks borrower login.

**[2] DHANBOOST_KYC_APPROVED_V1 — Service Implicit**
CONTENT: `Your KYC is verified with DhanBoost. Log in at https://www.dhanboost.com/login to choose your loan amount. - DhanBoost`
VARS: none

**[3] DHANBOOST_KYC_REJECTED_V1 — Service Implicit**
CONTENT: `We could not verify your KYC with DhanBoost. Log in at https://www.dhanboost.com/login to review and resubmit. - DhanBoost`
VARS: none

**[4] DHANBOOST_KYC_REMINDER_V1 — Service Implicit**
CONTENT: `Your verification with DhanBoost is incomplete. Log in at https://www.dhanboost.com/login to complete your pending steps. - DhanBoost`
VARS: none

**[5] DHANBOOST_LOAN_DISBURSED_V1 — Service Implicit**
CONTENT: `DhanBoost has disbursed {#var#} to your bank account. Repay {#var#} by {#var#} at https://www.dhanboost.com/login. - DhanBoost`
VARS:
1. net amount disbursed | Amount | `Rs. 8,820`
2. total repayable amount | Amount | `Rs. 12,700`
3. due date | Date | `30 Jun 2026`

**[6] DHANBOOST_REPAYMENT_VERIFIED_V1 — Service Implicit**
CONTENT: `Your payment of {#var#} to DhanBoost is confirmed. Outstanding balance is {#var#}. View details at https://www.dhanboost.com/login. - DhanBoost`
VARS:
1. payment amount | Amount | `Rs. 5,000`
2. remaining outstanding balance | Amount | `Rs. 7,700`

**[7] DHANBOOST_REPAYMENT_REJECTED_V1 — Service Implicit**
CONTENT: `Your payment of {#var#} could not be verified by DhanBoost. Log in at https://www.dhanboost.com/login to check the reference and record it again. - DhanBoost`
VARS:
1. payment amount | Amount | `Rs. 5,000`

**[8] DHANBOOST_PAYMENT_DUE_SOON_V1 — Service Implicit**
CONTENT: `Your DhanBoost payment of {#var#} is due in {#var#} day(s) by {#var#}. Pay at https://www.dhanboost.com/login on or after your salary day with no penalty. - DhanBoost`
VARS:
1. amount due | Amount | `Rs. 12,700`
2. days until due | Number | `3`
3. due date | Date | `30 Jun 2026`

**[9] DHANBOOST_PAYMENT_OVERDUE_V1 — Service Implicit**
CONTENT: `Your DhanBoost payment of {#var#} is overdue by {#var#} day(s). Pay now at https://www.dhanboost.com/login to stop the daily penalty and protect your credit score. - DhanBoost`
VARS:
1. overdue amount | Amount | `Rs. 12,700`
2. days overdue | Number | `5`

**[10] DHANBOOST_LOAN_CLOSED_V1 — Service Implicit**
CONTENT: `Your loan with DhanBoost is fully repaid and closed. Thank you. Visit https://www.dhanboost.com/login to borrow again. - DhanBoost`
VARS: none

**[11] DHANBOOST_APPLICATION_DECLINED_V1 — Service Implicit**
CONTENT: `DhanBoost is unable to approve your loan application at this time. Visit https://www.dhanboost.com/login for details. - DhanBoost`
VARS: none
NOTE: ONE template reused for BOTH the credit rejection and the reborrow rejection — register once;
its single DLT Template ID is bound to both notification types in the backend.

**[12] DHANBOOST_SETTLEMENT_APPROVED_V1 — Service Implicit**
CONTENT: `A full and final settlement of {#var#} is approved on your DhanBoost loan. Pay at https://www.dhanboost.com/login to close the loan. - DhanBoost`
VARS:
1. settlement amount | Amount | `Rs. 9,000`

**[13] DHANBOOST_REBORROW_APPROVED_V1 — Service Implicit**
CONTENT: `Your loan application with DhanBoost is approved. Log in at https://www.dhanboost.com/login to choose your amount. - DhanBoost`
VARS: none

**[14] DHANBOOST_REBORROW_PREAPPROVED_V1 — Service Implicit  ⚠ BORDERLINE — PAUSE & ASK BEFORE SUBMIT**
CONTENT: `Welcome back to DhanBoost. You can apply for another loan now. Log in at https://www.dhanboost.com/login to choose your amount. - DhanBoost`
VARS: none

**[15] DHANBOOST_REFERRAL_REWARD_CREDITED_V1 — Service Implicit  ⚠ BORDERLINE — PAUSE & ASK BEFORE SUBMIT**
CONTENT: `Your DhanBoost referral reward of {#var#} is credited with reference {#var#}. Log in at https://www.dhanboost.com/login to view it. - DhanBoost`
VARS:
1. referral reward amount | Amount | `Rs. 500`
2. payout transaction reference | Number | `TXN123456`

---

## At the end

Print a table of `{ template name -> DLT Template ID, registered category, status }` for all 15, so the
operator can paste the IDs into `docs/sms-dlt/SMSULTRON.md`, `DLT_SUBMISSION_TRACKER.md`,
`dlt-templates.json` (`dltTemplateId`), and the backend config `navix.sms.dlt-template-ids`.
Reminder: template **#11**'s single ID maps to BOTH `CREDIT_REJECTED` and `REBORROW_REVIEW_REJECTED`.
Flag any template the portal registered as **Service Explicit** rather than Implicit.
