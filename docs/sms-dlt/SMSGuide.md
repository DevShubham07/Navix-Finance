# SMS / DLT Template Guide — NAVIX Finance

Knowledge base for registering NAVIX's SMS content templates on the DLT portal (via our
aggregator **STPL**) and wiring the approved template IDs back into the backend.

**Files in this folder:**
- [`DLT_template_guidelines.pdf`](./DLT_template_guidelines.pdf) — the operator's official
  template-category + formatting rules (the source these instructions derive from).
- [`dlt-templates.json`](./dlt-templates.json) — the templates as machine-readable data (name,
  category, content, variables + sample values). Single source of truth.
- [`CHROME_FILL_RUNBOOK.md`](./CHROME_FILL_RUNBOOK.md) — a Claude-in-Chrome driver prompt that
  creates every template on the portal UI (there is no bulk import), reading the JSON.

> **Scope (current):** we register **every SMS-enabled notification** — the login OTP, the
> loan-lifecycle status & payment messages, plus the returning-borrower re-onboarding nudge and the
> referral-reward confirmation. The last two are borderline promotional; we register them as **Service
> Implicit** with transactional wording, and note the fallback to **Service Explicit** if STPL flags
> them — see [§8](#8-category-caveat-two-borderline-templates).

---

## 1. The two rejection reasons STPL gave us — and the fix

| Rejection | Cause | Fix (applied to every template) |
|---|---|---|
| **"Entity brand name is not mentioned in the SMS content"** | DLT **mandates** the complete business/brand name *inside the message body*, not just the 6-char header. Our old strings used a bare `NAVIX:` prefix. | Spell out **`NAVIX Finance`** and end every template with ` - NAVIX Finance`. |
| **"Purpose of first variable is not clear"** | The text around a `{#var#}` must make its purpose obvious; leading a message with a bare variable (`{amount} is due…`) reads as ambiguous. | Put descriptive text **before** each variable (`Your OTP for NAVIX Finance login is {#var#}`); reword any message that started with a variable. |

---

## 2. DLT rules that govern our templates (from the guidelines PDF)

**Category — the big one for us:**

- ⚠️ **NAVIX must NOT use the "Transactional" category.** The PDF's *Don'ts* explicitly list
  *"Selecting 'Transactional' category by non-banking entities."* **Transactional is banks only**
  (OTP to complete a card / net-banking transaction). NAVIX is a **non-banking** lender, so:
- ✅ **Every template — including the login OTP — registers under `Service Implicit`.** Service
  Implicit = "any message arising out of the customer's actions or existing relationship that is not
  promotional": OTP to access a service, transaction/status confirmations, **due-date reminders**,
  settlement alerts. That's exactly our set.
- **Promotional** = sells/promotes; needs numeric sender + consent scrubbing.
- **Service Explicit** = promotional-style content sent only with recorded consent.

**Formatting rules:**

- Variable placeholder is exactly **`{#var#}`** (with hashes).
- **One variable = up to 40 characters.** Provide a sample value per variable.
- **Brand name mandatory in the content** — complete business name (`NAVIX Finance`).
- **No extra spaces** (single spaces only; no trailing space).
- Don't reuse one content template across multiple headers.
- Once a `{#var#}` is inserted, don't hand-edit it in the box or it stops being a variable.
- ⚠️ **URLs must be whitelisted.** Every non-OTP template below carries
  `https://www.navixfinance.com/login`. That **exact** URL **must be whitelisted under the entity on
  the SmartPing/DLT portal (URL/APK whitelisting) before submission** — and it must match the URL in
  the registered content char-for-character — or any template containing the link is rejected. (OTP is
  intentionally link-free — links in OTP messages are discouraged and often rejected.)
- The **sent SMS must match the registered template character-for-character**, only `{#var#}` slots
  substituted, or the gateway returns *"Invalid template text"*.

**Header / sender ID / PE-ID:** a registered 6-char header (e.g. `NAVIXF`) tied to our
Principal-Entity ID (PE-ID) is required alongside each content template. In code these map to
`navix.sms.senderId`, `navix.sms.peid`, and the per-template `navix.sms.dltTemplateId` ([§7](#7-backend-wiring)).

---

## 3. Improvements applied in this revision

Beyond the two rejection fixes, this pass tightened the templates:

1. **`KYC_REMINDER` — dropped the `{pendingSteps}` variable.** The joined step list (e.g.
   "PAN, Selfie, Bank penny-drop, Agreement") can exceed the **40-char/variable** limit. The SMS is
   now generic ("your verification is incomplete, please log in"); the **detailed list stays in the
   in-app + email versions**. *(Needs the SMS body in `NotificationTemplates.java` updated to the
   variable-free text so the sent SMS matches the registered template.)*
2. **Amounts must render `Rs.`, not `₹`, for SMS.** `NotificationFormat.inr()` currently produces
   `₹8,820`. The `₹` glyph is **not in the GSM-7 charset**, so every amount-bearing SMS silently
   becomes **UCS-2/Unicode** — segment size collapses 160 → 70 chars and cost ~doubles. The `₹` sits
   *inside* the `{#var#}` value so it doesn't block DLT approval, but it's a real cost/length bug.
   **Recommendation:** render `Rs. 8,820` for the SMS channel (an SMS-specific formatter, or switch
   `NotificationFormat.inr` and keep `₹` only for in-app/email). Sample values in the JSON use `Rs.`.
3. **Minor wording polish** for clarity ("record it again", "Please pay it to close the loan").

---

## 4. OTP template (login) — Category: **Service Implicit**

Source: `BorrowerOtpService.buildMessage` (`backend/navix-app/.../auth/BorrowerOtpService.java`).

```
Your OTP for NAVIX Finance login is {#var#}. It is valid for {#var#} minutes. Do not share this OTP with anyone. - NAVIX Finance
```

| Variable | Meaning | Tag | Sample |
|---|---|---|---|
| `{#var#}` #1 | numeric OTP code | OTP | `123456` |
| `{#var#}` #2 | validity in minutes | Number | `5` |

> Set `navix.sms.otpTemplate` to the human form `Your OTP for NAVIX Finance login is {otp}. It is
> valid for {ttl} minutes. Do not share this OTP with anyone. - NAVIX Finance` so the on-wire text
> matches the registered template exactly.

---

## 5. Loan-lifecycle templates — Category: **Service Implicit**

Source: `NotificationTemplates.java`. Register each as its own content template (one DLT Template ID
each). Full data incl. sample values is in [`dlt-templates.json`](./dlt-templates.json).

| # | Template name | DLT content to register | Vars (in order) |
|---|---|---|---|
| 1 | `NAVIX_KYC_APPROVED` | `Your KYC is verified with NAVIX Finance. Log in at https://www.navixfinance.com/login to choose your loan amount. - NAVIX Finance` | — |
| 2 | `NAVIX_KYC_REJECTED` | `We could not verify your KYC with NAVIX Finance. Log in at https://www.navixfinance.com/login to review and resubmit. - NAVIX Finance` | — |
| 3 | `NAVIX_KYC_REMINDER` | `Your verification with NAVIX Finance is incomplete. Log in at https://www.navixfinance.com/login to complete your pending steps. - NAVIX Finance` | — |
| 4 | `NAVIX_LOAN_DISBURSED` | `NAVIX Finance has disbursed {#var#} to your bank account. Repay {#var#} by {#var#} at https://www.navixfinance.com/login. - NAVIX Finance` | net disbursed, total repayable, due date |
| 5 | `NAVIX_REPAYMENT_VERIFIED` | `Your payment of {#var#} to NAVIX Finance is confirmed. Outstanding balance is {#var#}. View details at https://www.navixfinance.com/login. - NAVIX Finance` | amount, outstanding |
| 6 | `NAVIX_REPAYMENT_REJECTED` | `Your payment of {#var#} could not be verified by NAVIX Finance. Log in at https://www.navixfinance.com/login to check the reference and record it again. - NAVIX Finance` | amount |
| 7 | `NAVIX_PAYMENT_DUE_SOON` | `Your NAVIX Finance payment of {#var#} is due in {#var#} day(s) by {#var#}. Pay at https://www.navixfinance.com/login on or after your salary day with no penalty. - NAVIX Finance` | amount, days to due, due date |
| 8 | `NAVIX_PAYMENT_OVERDUE` | `Your NAVIX Finance payment of {#var#} is overdue by {#var#} day(s). Pay now at https://www.navixfinance.com/login to stop the daily penalty and protect your credit score. - NAVIX Finance` | amount, days overdue |
| 9 | `NAVIX_LOAN_CLOSED` | `Your loan with NAVIX Finance is fully repaid and closed. Thank you. Visit https://www.navixfinance.com/login to borrow again. - NAVIX Finance` | — |
| 10 | `NAVIX_APPLICATION_DECLINED` | `NAVIX Finance is unable to approve your loan application at this time. Visit https://www.navixfinance.com/login for details. - NAVIX Finance` | — |
| 11 | `NAVIX_SETTLEMENT_APPROVED` | `A full and final settlement of {#var#} is approved on your NAVIX Finance loan. Pay at https://www.navixfinance.com/login to close the loan. - NAVIX Finance` | settlement amount |
| 12 | `NAVIX_REBORROW_APPROVED` | `Your loan application with NAVIX Finance is approved. Log in at https://www.navixfinance.com/login to choose your amount. - NAVIX Finance` | — |
| 13 | `NAVIX_REBORROW_PREAPPROVED` | `Welcome back to NAVIX Finance. You can apply for another loan now. Log in at https://www.navixfinance.com/login to choose your amount. - NAVIX Finance` | — |
| 14 | `NAVIX_REFERRAL_REWARD_CREDITED` | `Your NAVIX Finance referral reward of {#var#} is credited with reference {#var#}. Log in at https://www.navixfinance.com/login to view it. - NAVIX Finance` | reward amount, txn reference |

Row 10 (`NAVIX_APPLICATION_DECLINED`) is a **single** template reused for both `CREDIT_REJECTED` and
`REBORROW_REVIEW_REJECTED` (identical content → one DLT Template ID bound to both types). Rows 13–14
are borderline promotional — see [§8](#8-category-caveat-two-borderline-templates). All are triggered
by the customer's own action / existing loan relationship → **Service Implicit**.

---

## 6. Registration + approved-ID mapping

Per template: category **Service Implicit**; content pasted **exactly** (brand name present, ends
` - NAVIX Finance`); each `{#var#}` ≤ 40 chars with a sample value; no double/trailing spaces;
relevant template name; bound to header `NAVIXF` / PE-ID. Record the returned ID below on approval.

| Template name | Source (OTP / NotificationType) | `navix.sms.*` key | DLT Template ID |
|---|---|---|---|
| `NAVIX_OTP_LOGIN` | OTP login | `dlt-template-id` (global/OTP) | `__________` |
| `NAVIX_KYC_APPROVED` | `KYC_APPROVED` | `dlt-template-ids.KYC_APPROVED` | `__________` |
| `NAVIX_KYC_REJECTED` | `KYC_REJECTED` | `dlt-template-ids.KYC_REJECTED` | `__________` |
| `NAVIX_KYC_REMINDER` | `KYC_REMINDER` | `dlt-template-ids.KYC_REMINDER` | `__________` |
| `NAVIX_LOAN_DISBURSED` | `LOAN_DISBURSED` | `dlt-template-ids.LOAN_DISBURSED` | `__________` |
| `NAVIX_REPAYMENT_VERIFIED` | `REPAYMENT_VERIFIED` | `dlt-template-ids.REPAYMENT_VERIFIED` | `__________` |
| `NAVIX_REPAYMENT_REJECTED` | `REPAYMENT_REJECTED` | `dlt-template-ids.REPAYMENT_REJECTED` | `__________` |
| `NAVIX_PAYMENT_DUE_SOON` | `PAYMENT_DUE_SOON` | `dlt-template-ids.PAYMENT_DUE_SOON` | `__________` |
| `NAVIX_PAYMENT_OVERDUE` | `PAYMENT_OVERDUE` | `dlt-template-ids.PAYMENT_OVERDUE` | `__________` |
| `NAVIX_LOAN_CLOSED` | `LOAN_CLOSED` | `dlt-template-ids.LOAN_CLOSED` | `__________` |
| `NAVIX_APPLICATION_DECLINED` | `CREDIT_REJECTED` **and** `REBORROW_REVIEW_REJECTED` | `dlt-template-ids.CREDIT_REJECTED` **+** `.REBORROW_REVIEW_REJECTED` (same ID) | `__________` |
| `NAVIX_SETTLEMENT_APPROVED` | `SETTLEMENT_APPROVED` | `dlt-template-ids.SETTLEMENT_APPROVED` | `__________` |
| `NAVIX_REBORROW_APPROVED` | `REBORROW_REVIEW_APPROVED` | `dlt-template-ids.REBORROW_REVIEW_APPROVED` | `__________` |
| `NAVIX_REBORROW_PREAPPROVED` | `REBORROW_PREAPPROVED` | `dlt-template-ids.REBORROW_PREAPPROVED` | `__________` |
| `NAVIX_REFERRAL_REWARD_CREDITED` | `REFERRAL_REWARD_CREDITED` | `dlt-template-ids.REFERRAL_REWARD_CREDITED` | `__________` |

> The one approved `NAVIX_APPLICATION_DECLINED` ID goes into **both** `CREDIT_REJECTED` and
> `REBORROW_REVIEW_REJECTED` keys.
>
> **Creating them on the portal:** the STPL portal has no bulk import — use
> [`CHROME_FILL_RUNBOOK.md`](./CHROME_FILL_RUNBOOK.md) to drive the UI from `dlt-templates.json`.

---

## 7. Backend wiring

- **OTP** flows through `UltronSmsClient`, which passes `senderid`, `peid`, and a `DLTTemplateId`
  (`SmsProperties`, bound from `navix.sms.*`). Set `navix.sms.otp-template` to the approved OTP text
  (human form with `{otp}`/`{ttl}`) and `navix.sms.dlt-template-id` to the OTP ID (also the fallback).
- ✅ **Per-`NotificationType` DLT ID (resolved).** `SmsProperties` now carries a
  `dlt-template-ids` **map** (key = `NotificationType.name()`) alongside the single fallback
  `dlt-template-id`. `TemplateRenderer` stamps the type name onto the SMS `RenderedMessage`,
  `SmsSender` forwards it, and `UltronSmsClient` resolves `dltTemplateIds.getOrDefault(key,
  dltTemplateId)`. Fill each approved ID into `navix.sms.dlt-template-ids.<TYPE>` (§6). Until an ID is
  filled for a type, that type falls back to the global ID.
- ✅ **SMS bodies match the registered text.** `NotificationTemplates.java` SMS bodies were rewritten
  to equal each `content` in `dlt-templates.json` char-for-char; `KYC_REMINDER` dropped `{pendingSteps}`
  (§3.1); the SMS channel renders `Rs.` instead of `₹` (§3.2, done in `TemplateRenderer` for
  `channel == SMS` only — in-app/email keep `₹`).
- **Testing without DLT:** `NAVIX_SMS_MOCK=true` short-circuits sends (fixed OTP `123456`);
  `navix.sms.dev-echo=true` returns the OTP in the response for local testing.

---

## 8. Category caveat: two borderline templates

We now register **all** SMS-enabled types, including the two that used to be deferred as
promotional. Both are worded to stay **Service Implicit** (they arise from the customer's existing
relationship / their own action), but the portal reviewer may disagree:

- `NAVIX_REBORROW_PREAPPROVED` (`REBORROW_PREAPPROVED`) — the returning-borrower re-onboarding nudge.
  Reworded to drop offer/"pre-approved" language.
- `NAVIX_REFERRAL_REWARD_CREDITED` (`REFERRAL_REWARD_CREDITED`) — confirmation that the customer's own
  referral reward was credited (gated by the `referral` feature flag).

**If STPL rejects either as promotional**, it moves to **Service Explicit** (requires recorded
consent + a numeric/other sender per portal rules) — flag this to the operator running the runbook,
who should pause on those two rather than force-submit.

---

## 9. References

- [`DLT_template_guidelines.pdf`](./DLT_template_guidelines.pdf) · [`dlt-templates.json`](./dlt-templates.json) · [`CHROME_FILL_RUNBOOK.md`](./CHROME_FILL_RUNBOOK.md) (this folder).
- `CLAUDE.md` §13/§14 — SMS is real via UltronSMS but delivery is **blocked on DLT-registered
  templates**; `NAVIX_SMS_MOCK=true` is the demo/testing path.
- Code: `backend/navix-app/.../auth/BorrowerOtpService.java`,
  `backend/navix-app/.../sms/{UltronSmsClient,SmsProperties}.java`,
  `backend/navix-notification/.../template/NotificationTemplates.java`,
  `backend/navix-notification/.../template/NotificationFormat.java`.
