# SMS / DLT Template Guide — DhanBoost

Knowledge base for registering DhanBoost's SMS content templates on the DLT portal (via our
aggregator **STPL**) and wiring the approved template IDs back into the backend.

> **Brand vs entity.** The consumer-facing brand is **DhanBoost**; the DLT-registered legal entity is
> still **NAVIX FINANCE PRIVATE LIMITED** (CIN `U64990HR2026PTC144926`), and the backend's internal
> namespace stays `navix` (`navix.sms.*`, `NAVIX_SMS_*`). Only the message body, the URL and the sender
> header changed.

**Files in this folder:**
- [`DLT_template_guidelines.pdf`](./DLT_template_guidelines.pdf) — the operator's official
  template-category + formatting rules (the source these instructions derive from).
- [`dlt-templates.json`](./dlt-templates.json) — the templates as machine-readable data (name,
  category, content, variables + sample values). Single source of truth.
- [`SMSULTRON.md`](./SMSULTRON.md) — the registered-template reference (content + DLT IDs).
- [`DLT_SUBMISSION_TRACKER.md`](./DLT_SUBMISSION_TRACKER.md) — submission status + backend wiring.
- [`CHROME_FILL_RUNBOOK.md`](./CHROME_FILL_RUNBOOK.md) / [`CHROME_AGENT_PROMPT.md`](./CHROME_AGENT_PROMPT.md)
  — Claude-in-Chrome driver prompts that create every template on the portal UI (there is no bulk import).
- [`CHROME_AGENT_PROMPT_V3.md`](./CHROME_AGENT_PROMPT_V3.md) — softer `_V1_ALT` rewrites of four
  templates, to use only if the primary wording is rejected.
- [`test-send-sms.sh`](./test-send-sms.sh) / [`test-all-templates.sh`](./test-all-templates.sh) — live
  gateway send tests; the latter regenerates [`TEMPLATE_TEST_RESULTS.md`](./TEMPLATE_TEST_RESULTS.md).

> **Scope (current):** we register **every SMS-enabled notification** — the login OTP, the
> loan-lifecycle status & payment messages, plus the returning-borrower re-onboarding nudge and the
> referral-reward confirmation. The last two are borderline promotional; we submit them as **Service
> Implicit** with transactional wording, and expect a fallback to **Service Explicit** — see
> [§8](#8-category-caveat-two-borderline-templates).

---

## 0. Rebrand status — read this first

The NAVIX → DhanBoost rebrand **invalidated the entire registered template set.** A DLT Template ID is
bound to its exact registered content; the brand string and the URL both changed in every body, so all
15 `NAVIX_*_V2` IDs are dead — **including `NAVIX_OTP_LOGIN_V2` (`1707178366195230667`), the one
template that had reached full DLT approval and was sending live.**

The **backend is already rebranded** and shipping DhanBoost wording:
- `application.yml` → `navix.sms.otp-template` = *"Your OTP for DhanBoost login is {otp}…"*
- `NotificationTemplates.java` → every `sms(...)` body says `DhanBoost` and links `dhanboost.com`

So the code and the live registrations **no longer agree**: any send today returns
`006 Invalid template text`. **SMS is fully down until the `DHANBOOST_*_V1` batch is registered.**
`NAVIX_SMS_MOCK=true` (fixed OTP `123456`) remains the demo/testing path.

**Four blockers, in order** (tracked in [`DLT_SUBMISSION_TRACKER.md`](./DLT_SUBMISSION_TRACKER.md)):

| # | Blocker | Why it's first |
|---|---|---|
| 1 | Register the domain **dhanboost.com** | It does not exist as of 2026-07-31; 14 of 15 templates link to it |
| 2 | Register **DhanBoost** as a brand under the entity on DLT | Otherwise every submit fails "Entity brand name is not mentioned" (§1) |
| 3 | Register/activate the 6-char header **DHANBT** | Replaces the retired `NAVIXF` |
| 4 | URL-whitelist **`https://dhanboost.com/login`** char-for-char (apex — **no `www.`**) | Un-whitelisted URL → rejection (§2) |

`DHANBOOST_OTP_LOGIN_V1` is link-free — it only needs #2 and #3, so register it first to unblock
borrower login while the domain work proceeds.

---

## 1. The two rejection reasons STPL gave us — and the fix

| Rejection | Cause | Fix (applied to every template) |
|---|---|---|
| **"Entity brand name is not mentioned in the SMS content"** | DLT **mandates** the registered business/brand name *inside the message body*, not just the 6-char header. Our old strings used a bare `NAVIX:` prefix. | Spell out **`DhanBoost`** and end every template with ` - DhanBoost`. ⚠ This only works once **DhanBoost is registered as a brand** under the entity — see §0 blocker #2. |
| **"Purpose of first variable is not clear"** | The text around a `{#var#}` must make its purpose obvious; leading a message with a bare variable (`{amount} is due…`) reads as ambiguous. | Put descriptive text **before** each variable (`Your OTP for DhanBoost login is {#var#}`); reword any message that started with a variable. |

---

## 2. DLT rules that govern our templates (from the guidelines PDF)

**Category — the big one for us:**

- ⚠️ **DhanBoost must NOT use the "Transactional" category.** The PDF's *Don'ts* explicitly list
  *"Selecting 'Transactional' category by non-banking entities."* **Transactional is banks only**
  (OTP to complete a card / net-banking transaction). DhanBoost is a **non-banking** lender, so:
- ✅ **Every template — including the login OTP — registers under `Service Implicit`.** Service
  Implicit = "any message arising out of the customer's actions or existing relationship that is not
  promotional": OTP to access a service, transaction/status confirmations, **due-date reminders**,
  settlement alerts. That's exactly our set.
- **Promotional** = sells/promotes; needs numeric sender + consent scrubbing.
- **Service Explicit** = promotional-style content sent only with recorded consent.

**Formatting rules:**

- Variable placeholder is exactly **`{#var#}`** (with hashes).
- **One variable = up to 40 characters.** Provide a sample value per variable.
- **Brand name mandatory in the content** — the registered brand name (`DhanBoost`).
- **No extra spaces** (single spaces only; no trailing space).
- Don't reuse one content template across multiple headers.
- Once a `{#var#}` is inserted, don't hand-edit it in the box or it stops being a variable.
- ⚠️ **URLs must be whitelisted.** Every non-OTP template below carries
  `https://dhanboost.com/login` (apex — **no `www.`**). That **exact** URL **must be whitelisted under the entity on
  the SmartPing/DLT portal (URL/APK whitelisting) before submission** — and it must match the URL in
  the registered content char-for-char — or any template containing the link is rejected. The domain
  must also actually resolve (§0 blocker #1). (OTP is intentionally link-free — links in OTP messages
  are discouraged and often rejected.)
- The **sent SMS must match the registered template character-for-character**, only `{#var#}` slots
  substituted, or the gateway returns *"Invalid template text"*.

**Header / sender ID / PE-ID:** a registered 6-char header (**`DHANBT`**) tied to our
Principal-Entity ID (PE-ID `1701178039634361131`, entity-level and unchanged by the rebrand) is
required alongside each content template. In code these map to `navix.sms.senderId`, `navix.sms.peid`,
and the per-template `navix.sms.dltTemplateId` ([§7](#7-backend-wiring)).

---

## 3. Content decisions carried into this batch

Beyond the two rejection fixes, these hold for the `DHANBOOST_*_V1` set:

1. **`KYC_REMINDER` has no `{pendingSteps}` variable.** The joined step list (e.g.
   "PAN, Selfie, Bank penny-drop, Agreement") can exceed the **40-char/variable** limit. The SMS is
   generic ("your verification is incomplete, please log in"); the **detailed list stays in the
   in-app + email versions**. Already reflected in `NotificationTemplates.java`.
2. **Amounts render `Rs.`, not `₹`, on the SMS channel.** The `₹` glyph is **not in the GSM-7 charset**,
   so an amount-bearing SMS would silently become **UCS-2/Unicode** — segment size collapses 160 → 70
   chars and cost ~doubles. Handled in `TemplateRenderer` for `channel == SMS` only; in-app/email keep `₹`.
   Sample values in the JSON use `Rs.`.
3. **One template serves both decline paths** — `CREDIT_REJECTED` and `REBORROW_REVIEW_REJECTED` have
   identical content, so a single registered ID is bound to both types.

---

## 4. OTP template (login) — Category: **Service Implicit**

Source: `navix.sms.otp-template` in `application.yml`, sent by `BorrowerOtpService.buildMessage`
(`backend/navix-app/.../auth/BorrowerOtpService.java`).

```
Your OTP for DhanBoost login is {#var#}. It is valid for {#var#} minutes. Do not share this OTP with anyone. - DhanBoost
```

| Variable | Meaning | Tag | Sample |
|---|---|---|---|
| `{#var#}` #1 | numeric OTP code | OTP | `123456` |
| `{#var#}` #2 | validity in minutes | Number | `5` |

> `navix.sms.otp-template` already holds the matching human form (`Your OTP for DhanBoost login is
> {otp}. It is valid for {ttl} minutes. Do not share this OTP with anyone. - DhanBoost`) — **don't edit
> it** when wiring IDs, or the on-wire text drifts out of match with the registered template.
>
> Register this one **first**: it is the only link-free template, so it clears DLT without waiting on
> the `dhanboost.com` domain, and it unblocks borrower login.

---

## 5. Loan-lifecycle templates — Category: **Service Implicit**

Source: `NotificationTemplates.java`. Register each as its own content template (one DLT Template ID
each). Full data incl. sample values is in [`dlt-templates.json`](./dlt-templates.json).

> ⚠ **The URL is the apex `https://dhanboost.com/login` — no `www.`** (that is what is whitelisted as
> a CTA, and it is checked char-for-char). Rows marked **WIP** were rejected 2026-07-31 as
> "promotional" and rewritten + re-submitted 2026-08-01; rows marked **Active** are approved and
> **frozen — do not edit their wording**. See [`DLT_SUBMISSION_TRACKER.md`](./DLT_SUBMISSION_TRACKER.md).

| # | Template name | Status | DLT content to register | Vars (in order) |
|---|---|---|---|---|
| 1 | `DHANBOOST_KYC_APPROVED_V1` | WIP | `Dear {#var#}, your KYC for DhanBoost application {#var#} is verified. Check status at https://dhanboost.com/login. - DhanBoost` | name, application id |
| 2 | `DHANBOOST_KYC_REJECTED_V1` | WIP | `Dear {#var#}, your KYC for DhanBoost application {#var#} could not be verified. Re-submit documents at https://dhanboost.com/login. - DhanBoost` | name, application id |
| 3 | `DHANBOOST_KYC_REMINDER_V1` | WIP | `Dear {#var#}, verification steps on your DhanBoost application {#var#} are pending. Complete them at https://dhanboost.com/login. - DhanBoost` | name, application id |
| 4 | `DHANBOOST_LOAN_DISBURSED_V1` | WIP | `Dear {#var#}, DhanBoost has credited {#var#} to your bank a/c. Repay {#var#} by {#var#} at https://dhanboost.com/login. - DhanBoost` | name, net disbursed, total repayable, due date |
| 5 | `DHANBOOST_REPAYMENT_VERIFIED_V1` | ✅ Active | `Your payment of {#var#} to DhanBoost is confirmed. Outstanding balance is {#var#}. View details at https://dhanboost.com/login. - DhanBoost` | amount, outstanding |
| 6 | `DHANBOOST_REPAYMENT_REJECTED_V1` | ✅ Active | `Your payment of {#var#} could not be verified by DhanBoost. Log in at https://dhanboost.com/login to check and record it again. - DhanBoost` | amount |
| 7 | `DHANBOOST_PAYMENT_DUE_SOON_V1` | WIP | `Dear {#var#}, repayment of {#var#} on your DhanBoost loan {#var#} is due on {#var#}. Pay at https://dhanboost.com/login. - DhanBoost` | name, amount, loan id, due date |
| 8 | `DHANBOOST_PAYMENT_OVERDUE_V1` | WIP | `Dear {#var#}, repayment of {#var#} on your DhanBoost loan {#var#} is overdue by {#var#} day(s). Pay at https://dhanboost.com/login. - DhanBoost` | name, amount, loan id, days overdue |
| 9 | `DHANBOOST_LOAN_CLOSED_V1` | ✅ Active | `Your loan with DhanBoost is fully repaid and closed. Thank you. Visit https://dhanboost.com/login to borrow again. - DhanBoost` | — |
| 10 | `DHANBOOST_APPLICATION_DECLINED_V1` | WIP | `Dear {#var#}, your DhanBoost loan application {#var#} could not be approved at this time. Details at https://dhanboost.com/login. - DhanBoost` | name, application id |
| 11 | `DHANBOOST_SETTLEMENT_APPROVED_V1` | WIP | `Dear {#var#}, a full and final settlement of {#var#} is approved on DhanBoost loan {#var#}. Pay at https://dhanboost.com/login. - DhanBoost` | name, settlement amount, loan id |
| 12 | `DHANBOOST_REBORROW_APPROVED_V1` | WIP | `Dear {#var#}, your DhanBoost application {#var#} is approved. Complete the remaining steps at https://dhanboost.com/login. - DhanBoost` | name, application id |
| 13 | `DHANBOOST_REBORROW_PREAPPROVED_V1` | ✅ Active (Promotional) | `Welcome back to DhanBoost. You can apply for another loan now. Log in at https://dhanboost.com/login to choose your amount. - DhanBoost` | — |
| 14 | `DHANBOOST_REFERRAL_REWARD_CREDITED_V1` | ✅ Active (Promotional) | `Your DhanBoost referral reward of {#var#} is credited with reference {#var#}. Log in at https://dhanboost.com/login to view it. - DhanBoost` | reward amount, txn reference |

Row 10 (`DHANBOOST_APPLICATION_DECLINED_V1`) is a **single** template reused for both `CREDIT_REJECTED`
and `REBORROW_REVIEW_REJECTED` (identical content → one DLT Template ID bound to both types). Rows 13–14
are borderline promotional — see [§8](#8-category-caveat-two-borderline-templates). All are triggered
by the customer's own action / existing loan relationship → **Service Implicit**.

---

## 6. Registration + approved-ID mapping

Per template: category **Service Implicit**; content pasted **exactly** (brand name present, ends
` - DhanBoost`); each `{#var#}` ≤ 40 chars with a sample value; no double/trailing spaces;
relevant template name; bound to header `DHANBT` / PE-ID `1701178039634361131`. Record the returned ID
below on approval, and mirror it into `SMSULTRON.md`, `DLT_SUBMISSION_TRACKER.md` and the
`dltTemplateId` fields in `dlt-templates.json`.

| Template name | Source (OTP / NotificationType) | `navix.sms.*` key | DLT Template ID |
|---|---|---|---|
| `DHANBOOST_OTP_LOGIN_V1` | OTP login | `dlt-template-id` (global/OTP) | `__________` |
| `DHANBOOST_KYC_APPROVED_V1` | `KYC_APPROVED` | `dlt-template-ids.KYC_APPROVED` | `__________` |
| `DHANBOOST_KYC_REJECTED_V1` | `KYC_REJECTED` | `dlt-template-ids.KYC_REJECTED` | `__________` |
| `DHANBOOST_KYC_REMINDER_V1` | `KYC_REMINDER` | `dlt-template-ids.KYC_REMINDER` | `__________` |
| `DHANBOOST_LOAN_DISBURSED_V1` | `LOAN_DISBURSED` | `dlt-template-ids.LOAN_DISBURSED` | `__________` |
| `DHANBOOST_REPAYMENT_VERIFIED_V1` | `REPAYMENT_VERIFIED` | `dlt-template-ids.REPAYMENT_VERIFIED` | `__________` |
| `DHANBOOST_REPAYMENT_REJECTED_V1` | `REPAYMENT_REJECTED` | `dlt-template-ids.REPAYMENT_REJECTED` | `__________` |
| `DHANBOOST_PAYMENT_DUE_SOON_V1` | `PAYMENT_DUE_SOON` | `dlt-template-ids.PAYMENT_DUE_SOON` | `__________` |
| `DHANBOOST_PAYMENT_OVERDUE_V1` | `PAYMENT_OVERDUE` | `dlt-template-ids.PAYMENT_OVERDUE` | `__________` |
| `DHANBOOST_LOAN_CLOSED_V1` | `LOAN_CLOSED` | `dlt-template-ids.LOAN_CLOSED` | `__________` |
| `DHANBOOST_APPLICATION_DECLINED_V1` | `CREDIT_REJECTED` **and** `REBORROW_REVIEW_REJECTED` | `dlt-template-ids.CREDIT_REJECTED` **+** `.REBORROW_REVIEW_REJECTED` (same ID) | `__________` |
| `DHANBOOST_SETTLEMENT_APPROVED_V1` | `SETTLEMENT_APPROVED` | `dlt-template-ids.SETTLEMENT_APPROVED` | `__________` |
| `DHANBOOST_REBORROW_APPROVED_V1` | `REBORROW_REVIEW_APPROVED` | `dlt-template-ids.REBORROW_REVIEW_APPROVED` | `__________` |
| `DHANBOOST_REBORROW_PREAPPROVED_V1` | `REBORROW_PREAPPROVED` | `dlt-template-ids.REBORROW_PREAPPROVED` | `__________` |
| `DHANBOOST_REFERRAL_REWARD_CREDITED_V1` | `REFERRAL_REWARD_CREDITED` | `dlt-template-ids.REFERRAL_REWARD_CREDITED` | `__________` |

> The one approved `DHANBOOST_APPLICATION_DECLINED_V1` ID goes into **both** `CREDIT_REJECTED` and
> `REBORROW_REVIEW_REJECTED` keys.
>
> **Creating them on the portal:** the STPL portal has no bulk import — use
> [`CHROME_FILL_RUNBOOK.md`](./CHROME_FILL_RUNBOOK.md) (or the fully-inline
> [`CHROME_AGENT_PROMPT.md`](./CHROME_AGENT_PROMPT.md)) to drive the UI from `dlt-templates.json`.

---

## 7. Backend wiring

- **OTP** flows through `UltronSmsClient`, which passes `senderid`, `peid`, and a `DLTTemplateId`
  (`SmsProperties`, bound from `navix.sms.*`). Set `navix.sms.dlt-template-id` to the approved
  `DHANBOOST_OTP_LOGIN_V1` ID (also the fallback) and `NAVIX_SMS_SENDER_ID=DHANBT`.
  `navix.sms.otp-template` is **already** the DhanBoost text — leave it alone.
- ✅ **Per-`NotificationType` DLT ID.** `SmsProperties` carries a `dlt-template-ids` **map**
  (key = `NotificationType.name()`) alongside the single fallback `dlt-template-id`.
  `TemplateRenderer` stamps the type name onto the SMS `RenderedMessage`, `SmsSender` forwards it, and
  `UltronSmsClient` resolves `dltTemplateIds.getOrDefault(key, dltTemplateId)`. Fill each approved ID
  into `navix.sms.dlt-template-ids.<TYPE>` (§6). Until an ID is filled for a type, that type falls back
  to the global ID — **which, until the OTP ID is refreshed, is also invalid.**
- ✅ **SMS bodies already match the DhanBoost text.** `NotificationTemplates.java` SMS bodies equal each
  `content` in `dlt-templates.json` char-for-char. **Do not edit them while wiring IDs** — that is
  exactly how the current mismatch arose (code rebranded ahead of the registrations).
- ⚠️ **Env/config names stay `NAVIX_*` / `navix.*`** — the internal namespace was deliberately not
  rebranded (see `CLAUDE.md`). Only `NAVIX_SMS_SENDER_ID`'s *value* changes, to `DHANBT`.
- **Testing without DLT:** `NAVIX_SMS_MOCK=true` short-circuits sends (fixed OTP `123456`);
  `navix.sms.dev-echo=true` returns the OTP in the response for local testing. **This is the only
  working path until the batch is registered.**

---

## 8. Category caveat: two borderline templates

We register **all** SMS-enabled types, including the two that read as promotional. Both are worded to
stay **Service Implicit** (they arise from the customer's existing relationship / their own action),
but on the previous batch the reviewer disagreed and **both were ultimately registered as Service
Explicit**:

- `DHANBOOST_REBORROW_PREAPPROVED_V1` (`REBORROW_PREAPPROVED`) — the returning-borrower re-onboarding
  nudge. Worded to drop offer/"pre-approved" language.
- `DHANBOOST_REFERRAL_REWARD_CREDITED_V1` (`REFERRAL_REWARD_CREDITED`) — confirmation that the
  customer's own referral reward was credited (gated by the `referral` feature flag).

**Expect Service Explicit for these two** (requires recorded consent + a numeric/other sender per portal
rules). The runbook tells the agent to pause on both rather than force-submit under a disputed category.

---

## 9. References

- [`DLT_template_guidelines.pdf`](./DLT_template_guidelines.pdf) · [`dlt-templates.json`](./dlt-templates.json) · [`CHROME_FILL_RUNBOOK.md`](./CHROME_FILL_RUNBOOK.md) (this folder).
- `CLAUDE.md` §13/§14 — SMS is real via UltronSMS but delivery is **blocked on DLT-registered
  templates**; `NAVIX_SMS_MOCK=true` is the demo/testing path. Note §14's live-status lines still
  describe the pre-rebrand `NAVIXF` / `_V2` state.
- Code: `backend/navix-app/.../auth/BorrowerOtpService.java`,
  `backend/navix-app/.../sms/{UltronSmsClient,SmsProperties}.java`,
  `backend/navix-app/src/main/resources/application.yml` (`navix.sms.*`),
  `backend/navix-notification/.../template/NotificationTemplates.java`,
  `backend/navix-notification/.../template/NotificationFormat.java`.
