# DhanBoost DLT template submission tracker (`DHANBOOST_*_V1` batch)

To be submitted to the STPL DLT portal as **NAVIX FINANCE PRIVATE LIMITED** (legal entity unchanged),
brand **DhanBoost**, sender **DHANBT**, PE-ID `1701178039634361131`.

**Status: all 15 SUBMITTED 2026-07-31 21:00–21:17 IST via the SmartPing PE portal.**
1 Active · 14 "Work In Progress" (awaiting operator approval). **No DLT Template IDs assigned yet** —
the listing shows them only once a template is approved; re-check and fill the table below.

> Reminder: **#11 is one template bound to TWO NotificationTypes** — map its single ID to both
> `CREDIT_REJECTED` and `REBORROW_REVIEW_REJECTED`.

## Blockers to clear before submitting

| # | Blocker | Status |
|---|---|---|
| 1 | Register the domain **dhanboost.com** (every non-OTP template links to it) | ✅ live — apex + `www` both serve 200 (verified 2026-07-31) |
| 2 | Register **DhanBoost** as a brand under the entity on the DLT portal | ✅ effectively — every submit was accepted with `DhanBoost` in the body |
| 3 | Register/activate the 6-char header **DHANBT** | ✅ Active, Permanent, registered 31/07/2026 17:34 (`NAVIXF` also still Active) |
| 4 | URL-whitelist the login URL char-for-char | ✅ CTA `dhanboost login` = `https://dhanboost.com/login`, Static URL, Active 31/07/2026 20:28 |

> ⚠ **The whitelisted CTA has no `www.`** The templates originally said `https://www.dhanboost.com/login`,
> which would have failed the char-for-char URL check on all 14 non-OTP templates. Every source
> (`NotificationTemplates.java`, `dlt-templates.json`, both agent prompts) was changed to the apex
> `https://dhanboost.com/login` to match the CTA exactly. **Do not reintroduce `www.`**

`DHANBOOST_OTP_LOGIN_V1` is link-free and only needs blockers 2 + 3 — register it first to unblock
borrower login.

## The batch

| # | Template name | Target category | NotificationType → config key | DLT Template ID | Send test |
|---|---|---|---|---|---|
| 1 | DHANBOOST_OTP_LOGIN_V1 | Service Implicit | `OTP_LOGIN` (BorrowerOtpService) | `__________` | — |
| 2 | DHANBOOST_KYC_APPROVED_V1 | Service Implicit | `KYC_APPROVED` | `__________` | — |
| 3 | DHANBOOST_KYC_REJECTED_V1 | Service Implicit | `KYC_REJECTED` | `__________` | — |
| 4 | DHANBOOST_KYC_REMINDER_V1 | Service Implicit | `KYC_REMINDER` | `__________` | — |
| 5 | DHANBOOST_LOAN_DISBURSED_V1 | Service Implicit | `LOAN_DISBURSED` | `__________` | — |
| 6 | DHANBOOST_REPAYMENT_VERIFIED_V1 | Service Implicit | `REPAYMENT_VERIFIED` | `__________` | — |
| 7 | DHANBOOST_REPAYMENT_REJECTED_V1 | Service Implicit | `REPAYMENT_REJECTED` | `__________` | — |
| 8 | DHANBOOST_PAYMENT_DUE_SOON_V1 | Service Implicit | `PAYMENT_DUE_SOON` | `__________` | — |
| 9 | DHANBOOST_PAYMENT_OVERDUE_V1 | Service Implicit | `PAYMENT_OVERDUE` | `__________` | — |
| 10 | DHANBOOST_LOAN_CLOSED_V1 | Service Implicit | `LOAN_CLOSED` | `__________` | — |
| 11 | DHANBOOST_APPLICATION_DECLINED_V1 | Service Implicit | `CREDIT_REJECTED` **and** `REBORROW_REVIEW_REJECTED` | `__________` | — (one ID → both keys) |
| 12 | DHANBOOST_SETTLEMENT_APPROVED_V1 | Service Implicit | `SETTLEMENT_APPROVED` | `__________` | — |
| 13 | DHANBOOST_REBORROW_APPROVED_V1 | Service Implicit | `REBORROW_REVIEW_APPROVED` | `__________` | — |
| 14 | DHANBOOST_REBORROW_PREAPPROVED_V1 | **Promotional** (filed) — ✅ **Active** 31/07 21:16 | `REBORROW_PREAPPROVED` | `__________` | — |
| 15 | DHANBOOST_REFERRAL_REWARD_CREDITED_V1 | **Promotional** (filed) | `REFERRAL_REWARD_CREDITED` | `__________` | — |

## What was chosen at submission (2026-07-31)

- **Template type:** 13 as **Service Implicit**; **#14 and #15 as Promotional**. The portal now shows
  *"Service Explicit (SE) template type is no longer available. Such templates to be created under
  template type Promotional (P)"* — SE is gone from the dropdown entirely, so the `_V2` precedent of
  registering those two as SE is no longer possible. Chosen by the operator.
  ⚠ **Consequence:** Promotional messages are **not delivered to DND-registered numbers** and need a
  promotional route, not route `02`. Pre-approved-reborrow and referral-reward SMS may silently not
  arrive for DND subscribers; in-app + email are unaffected.
- **Header Type** (a field that only appears for Promotional): **Alphabetic/Alphanumeric (Others)**,
  which is what makes `DHANBT` selectable. "Numeric (Promotional)" would require a numeric sender ID
  that does not exist.
- **Category:** `Banking/Insurance/Financial products/ credit cards` for all 15.
- **Variable tags** — the dropdown offers only Alphanumeric / Email / Phone / URL×2 / NUMBER:
  - **NUMBER** only for pure-digit values (OTP `123456`, TTL `5`, day counts `3`/`5`).
  - **Alphanumeric (Name, Date, Address)** for every **amount** and **date**. Amounts are NOT numeric
    at send time — `TemplateRenderer` rewrites `₹` to `Rs. ` for SMS (₹ is outside GSM-7), so the
    substituted value is literally `Rs. 12,700`. Tagging that NUMBER would risk failing DLT scrubbing.
  - `TXN123456` (referral reference) is Alphanumeric for the same reason.
- Each template's char count was verified against the portal's own counter before submitting
  (120/114/118/129/122/139/139/151/154/126/125/140/122/135/139 — all single-segment).

## Carried forward from the `_V2` submission

- **Tag mappings deviated from the spec's OTP/Amount/Date tags** — the portal dropdown lacked those,
  so `Number` / `Alphanum` were used (the runbook's allowed fallback). Functionally safe; expect the same.
- **#14 and #15 registered as Service Explicit** (not Implicit) on reviewer-risk grounds — these send
  only to consented recipients, which fits reborrow-preapproved and referral-reward. Plan for the same
  outcome rather than fighting it.
- Only **1 of 15** `_V2` templates ever reached full DLT approval (the OTP). The other 14 stayed at
  `006 Invalid template text` at the gateway for three weeks — budget for a slow approval cycle.

## Wiring the IDs into the backend

`navix.sms.dlt-template-ids` (application.yml) is keyed by `NotificationType`. Set these env vars
(or the SSM equivalents) as each ID comes back — **#11's single ID goes to both declined keys**:

```
NAVIX_SMS_DLT_KYC_APPROVED=
NAVIX_SMS_DLT_KYC_REJECTED=
NAVIX_SMS_DLT_KYC_REMINDER=
NAVIX_SMS_DLT_LOAN_DISBURSED=
NAVIX_SMS_DLT_REPAYMENT_VERIFIED=
NAVIX_SMS_DLT_REPAYMENT_REJECTED=
NAVIX_SMS_DLT_PAYMENT_DUE_SOON=
NAVIX_SMS_DLT_PAYMENT_OVERDUE=
NAVIX_SMS_DLT_APPLICATION_DECLINED=      # → CREDIT_REJECTED + REBORROW_REVIEW_REJECTED
NAVIX_SMS_DLT_SETTLEMENT_APPROVED=
NAVIX_SMS_DLT_REBORROW_APPROVED=
NAVIX_SMS_DLT_LOAN_CLOSED=
NAVIX_SMS_DLT_REBORROW_PREAPPROVED=
NAVIX_SMS_DLT_REFERRAL_REWARD_CREDITED=
NAVIX_SMS_DLT_TEMPLATE_ID=               # OTP / global fallback
NAVIX_SMS_SENDER_ID=DHANBT
```

> The env-var and config-key names keep the internal `NAVIX_*` / `navix.*` namespace on purpose — only
> the customer-visible brand was rebranded (see `CLAUDE.md`).

The SMS bodies in `NotificationTemplates.java` and the `otp-template` in `application.yml` are **already**
DhanBoost-worded and match the content in `dlt-templates.json` char-for-char — do not edit them when
wiring the IDs, or they will drift out of match again.

---

## SUPERSEDED — the `NAVIX_*_V2` batch (audit record only)

Retired by the DhanBoost rebrand. Sender `NAVIXF`, brand "NAVIX Finance", URL
`https://www.navixfinance.com/login`. These IDs are recorded for audit and **must not be sent against** —
the app no longer produces text that matches them, so every send returns `006 Invalid template text`.

| # | Template name | Registered category | DLT Template ID | `_V2` send test (2026-07-11 JobId) |
|---|---|---|---|---|
| 1 | NAVIX_OTP_LOGIN_V2 | Service Implicit | 1707178366195230667 | ✅ 202881808 — *the only one that reached DLT approval + live send* |
| 2 | NAVIX_KYC_APPROVED_V2 | Service Implicit | 1707178366932300977 | ✅ 202881818 |
| 3 | NAVIX_KYC_REJECTED_V2 | Service Implicit | 1707178366348720389 | ✅ 202881824 |
| 4 | NAVIX_KYC_REMINDER_V2 | Service Implicit | 1707178366418625468 | ✅ 202881831 |
| 5 | NAVIX_LOAN_DISBURSED_V2 | Service Implicit | 1707178366447724079 | ✅ 202881840 |
| 6 | NAVIX_REPAYMENT_VERIFIED_V2 | Service Implicit | 1707178366455225240 | ✅ 202881850 |
| 7 | NAVIX_REPAYMENT_REJECTED_V2 | Service Implicit | 1707178366462035431 | ✅ 202881859 |
| 8 | NAVIX_PAYMENT_DUE_SOON_V2 | Service Implicit | 1707178366469516547 | ✅ 202881865 |
| 9 | NAVIX_PAYMENT_OVERDUE_V2 | Service Implicit | 1707178366491271206 | ✅ 202881875 |
| 10 | NAVIX_APPLICATION_DECLINED_V2 | Service Implicit | 1707178366517137780 | ✅ 202881881 |
| 11 | NAVIX_SETTLEMENT_APPROVED_V2 | Service Implicit | 1707178366525825089 | ✅ 202881894 |
| 12 | NAVIX_REBORROW_APPROVED_V2 | Service Implicit | 1707178367131025190 | ✅ 202881899 |
| 13 | NAVIX_LOAN_CLOSED_V2 | Service Implicit | 1707178367132471619 | ✅ 202881907 |
| 14 | NAVIX_REBORROW_PREAPPROVED_V2 | **Service Explicit** | 1707178366559342535 | ✅ 202881909 |
| 15 | NAVIX_REFERRAL_REWARD_CREDITED_V2 | **Service Explicit** | 1707178366569621398 | ✅ 202881915 |

> "Gateway-accepted (JobId assigned)" on that 2026-07-11 run meant the send was *queued*, not that the
> template was DLT-approved — the 2026-07-10 run in `TEMPLATE_TEST_RESULTS.md` shows 14 of 15 still
> returning `006 Invalid template text`. Test recipient: `917417682036`.
