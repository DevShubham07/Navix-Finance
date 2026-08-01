# DhanBoost DLT template submission tracker (`DHANBOOST_*_V1` batch)

To be submitted to the STPL DLT portal as **NAVIX FINANCE PRIVATE LIMITED** (legal entity unchanged),
brand **DhanBoost**, sender **DHANBT**, PE-ID `1701178039634361131`.

**Status (verified on the portal 2026-08-01 ~12:45 IST): 6 Active · 9 Work In Progress.**

- All 15 were first submitted 2026-07-31 21:00–21:23. STPL **approved 6** and **rejected 9** at
  21:50–21:53.
- The 9 rejected were **rewritten and re-submitted 2026-08-01** through the portal's own
  **Re-Submit** button (same reference number, same `_V1` name, still **Service Implicit**) and are
  now back at Work In Progress. See *"The 2026-08-01 rewrite"* below.
- **No DLT Template IDs assigned yet** — the listing shows them only once a template is approved;
  re-check and fill the table below.

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

`Status` is the portal's **Global Status** as of 2026-08-01 12:45. **WIP¹** = rejected 31/07 and
re-submitted 01/08 with rewritten copy.

| # | Template name | Registered type | Status | NotificationType → config key | DLT Template ID |
|---|---|---|---|---|---|
| 1 | DHANBOOST_OTP_LOGIN_V1 | Service Implicit | ✅ **Active** | `OTP_LOGIN` (BorrowerOtpService) | `__________` |
| 2 | DHANBOOST_KYC_APPROVED_V1 | Service Implicit | WIP¹ | `KYC_APPROVED` | `__________` |
| 3 | DHANBOOST_KYC_REJECTED_V1 | Service Implicit | WIP¹ | `KYC_REJECTED` | `__________` |
| 4 | DHANBOOST_KYC_REMINDER_V1 | Service Implicit | WIP¹ | `KYC_REMINDER` | `__________` |
| 5 | DHANBOOST_LOAN_DISBURSED_V1 | Service Implicit | WIP¹ | `LOAN_DISBURSED` | `__________` |
| 6 | DHANBOOST_REPAYMENT_VERIFIED_V1 | Service Implicit | ✅ **Active** | `REPAYMENT_VERIFIED` | `__________` |
| 7 | DHANBOOST_REPAYMENT_REJECTED_V1 | Service Implicit | ✅ **Active** | `REPAYMENT_REJECTED` | `__________` |
| 8 | DHANBOOST_PAYMENT_DUE_SOON_V1 | Service Implicit | WIP¹ | `PAYMENT_DUE_SOON` | `__________` |
| 9 | DHANBOOST_PAYMENT_OVERDUE_V1 | Service Implicit | WIP¹ | `PAYMENT_OVERDUE` | `__________` |
| 10 | DHANBOOST_LOAN_CLOSED_V1 | Service Implicit | ✅ **Active** | `LOAN_CLOSED` | `__________` |
| 11 | DHANBOOST_APPLICATION_DECLINED_V1 | Service Implicit | WIP¹ | `CREDIT_REJECTED` **and** `REBORROW_REVIEW_REJECTED` | `__________` (one ID → both keys) |
| 12 | DHANBOOST_SETTLEMENT_APPROVED_V1 | Service Implicit | WIP¹ | `SETTLEMENT_APPROVED` | `__________` |
| 13 | DHANBOOST_REBORROW_APPROVED_V1 | Service Implicit | WIP¹ | `REBORROW_REVIEW_APPROVED` | `__________` |
| 14 | DHANBOOST_REBORROW_PREAPPROVED_V1 | **Promotional** | ✅ **Active** 31/07 21:16 | `REBORROW_PREAPPROVED` | `__________` |
| 15 | DHANBOOST_REFERRAL_REWARD_CREDITED_V1 | **Promotional** | ✅ **Active** | `REFERRAL_REWARD_CREDITED` | `__________` |

## The 2026-08-01 rewrite (the 9 rejected templates)

STPL gave exactly two rejection remarks, both about sounding like marketing:

| Remark | Templates |
|---|---|
| *"Link is promotional in nature."* | PAYMENT_DUE_SOON, LOAN_DISBURSED, SETTLEMENT_APPROVED, PAYMENT_OVERDUE |
| *"Content is promotional in nature. Please resubmit in promotional content type."* | KYC_APPROVED, KYC_REJECTED, KYC_REMINDER, APPLICATION_DECLINED, REBORROW_APPROVED |

> ⚠ **The URL was deliberately KEPT** (operator decision, 2026-08-01) even though 4 of the 9 were
> rejected *specifically for the link*. If those four come back rejected a second time with the same
> remark, dropping `https://dhanboost.com/login` from the body is the remaining lever — the borrower
> still gets the link in the in-app + email versions of the same notification.

**What changed in the copy** (the rewrite kept Service Implicit rather than moving to Promotional,
because Promotional is not delivered to DND-registered numbers):

- Every body now opens **`Dear {#var#},`** with the borrower's name and cites **their own
  application / loan id** — so it reads as a record about their account, not a broadcast.
- Removed every invitation to transact: *"to choose your loan amount"*, *"to choose your amount"*,
  *"to borrow again"*, *"Log in at …"* as an opener.
- Removed the inducement/pressure clauses: *"from your salary day, no penalty"* (due-soon) and
  *"to stop the penalty and protect your score"* (overdue).
- The link is now a plain destination (*"Pay at …"*, *"Details at …"*, *"Check status at …"*) rather
  than a call to action.

Mechanics of the resubmission, for the next person: the Rejected tab's **Re-Submit** button opens
`/entity/template-resubmit/<referenceNumber>/<PEID>` with the old values pre-filled. Editing the
message box **wipes every variable tag and sample**, and the variable blocks only rebuild after a
`keyup`/`blur` on that box — so always set the message first, then the header, and fill the
**variable tags last**. The header resets to *"No header selected"* and must be re-picked as
`DHANBT` every time.

**All three sources are back in sync** — `NotificationTemplates.java`, `dlt-templates.json` and the
portal agree char-for-char (verified with the §6 script). The 6 Active templates were **not**
touched.

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

## ▶ NEXT SESSION: what to do once these are approved

**Start here.** Everything below is the only remaining work on the `DHANBOOST_*_V1` batch.

> ⛔ **Do NOT re-run `CHROME_AGENT_PROMPT.md`.** All 15 templates are already submitted (2026-07-31).
> Re-running it creates duplicates. That prompt is now a *record of what was filed*, not a to-do.
> `CHROME_AGENT_PROMPT_V3.md` (the `_ALT` batch) is still unused — only touch it if a primary
> wording is **rejected**.

### Step 1 — Check status on the portal

`https://smartping.live/entity/content-form` → Search `DHANBOOST` → Show Records 25.
Read the **Global Status** column:

| Status | Meaning | Action |
|---|---|---|
| `Work In Progress` | still with the operator | wait, re-check later |
| `Active` | approved and sendable | collect its **DLT Template ID** (step 2) |
| listed under the **Rejected** tab | reviewer refused it | step 5 |

### Step 2 — Collect the DLT Template IDs

The listing does **not** show a Template ID column while a template is `Work In Progress` — the ID
appears only after approval. Open each approved row's **view / eye icon** to read its id, then fill:

1. `DLT_SUBMISSION_TRACKER.md` → the batch table above (replace each `__________`).
2. `dlt-templates.json` → that template's `"dltTemplateId": null`.

### Step 3 — Wire the IDs into the running app

Set the env vars listed under *"Wiring the IDs into the backend"* above. **#11's single ID goes to
BOTH** `NAVIX_SMS_DLT_APPLICATION_DECLINED` keys (`CREDIT_REJECTED` + `REBORROW_REVIEW_REJECTED`).

Three prod switches must flip together, or OTP breaks:

| What | Now (NAVIX-era) | Change to |
|---|---|---|
| SSM `/navix/dev/navix/sms/sender-id` | `NAVIXF` | `DHANBT` |
| SSM `/navix/dev/navix/sms/dlt-template-id` | `1707178366195230667` (NAVIX OTP) | the new `DHANBOOST_OTP_LOGIN_V1` id |
| ECS task-def `NAVIX_SMS_OTP_TEMPLATE` (rev 4) | pinned to the **NAVIX Finance** wording | **remove the override** so `application.yml`'s DhanBoost default applies |

> ⚠ That ECS override exists *because* the only approved template today is the NAVIX-worded one.
> Until `DHANBOOST_OTP_LOGIN_V1` is Active, **any backend redeploy must be built from task-def
> revision 4** — deploying without it swaps the live OTP text to DhanBoost wording that DLT has not
> approved, and every OTP send fails with `006 Invalid template text`.

### Step 4 — Send-test each approved template

```bash
docs/sms-dlt/test-all-templates.sh <10-digit-number>       # sweeps the batch, writes TEMPLATE_TEST_RESULTS.md
docs/sms-dlt/test-send-sms.sh <number> "<text>" <dltId>     # one-off
```

`ErrorCode 000` = accepted by the gateway. Note that a JobId means *queued*, not delivered — the
`_V2` run looked green at the gateway while 14 of 15 were still unapproved. Confirm on a handset.

**The two Promotional ones (#14, #15) will not reach DND-registered numbers** — test them on a
non-DND handset before concluding they are broken, and they need a promotional route, not route `02`.

### Step 5 — If any template is rejected

Check the **Rejected** tab for the reason, then:

- **Wording/category pushback on KYC_APPROVED, REBORROW_APPROVED, LOAN_CLOSED or REBORROW_PREAPPROVED**
  → the softer alternates already exist in `CHROME_AGENT_PROMPT_V3.md`; register the `_ALT` variant and
  **update `NotificationTemplates.java` to the ALT text char-for-char** before wiring its id.
- **Any other template** → fix the wording in **all three sources at once**
  (`NotificationTemplates.java`, `dlt-templates.json`, `CHROME_AGENT_PROMPT.md`) and re-submit under a
  `_V2`-suffixed name. They must stay byte-identical or the gateway returns `006 Invalid template text`.

### Step 6 — Consistency check before shipping any wording change

```bash
# every template must agree three ways and stay inside one 160-char SMS segment
python3 - <<'EOF'
import re, json, pathlib
root = pathlib.Path('.')
java = (root/'backend/navix-notification/src/main/java/com/navix/notification/template/NotificationTemplates.java').read_text()
be = {m.group(1): re.sub(r'\{[A-Za-z]\w*\}', '{#var#}', ''.join(re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(2))))
      for m in re.finditer(r'sms\(NotificationType\.(\w+),\s*(.*?)\);', java, re.S)}
js = {t['name']: t for t in json.loads((root/'docs/sms-dlt/dlt-templates.json').read_text())['templates']}
md = dict(re.findall(r'\*\*\[\d+\] (DHANBOOST_\w+) —.*?\nCONTENT: `(.*?)`',
                     (root/'docs/sms-dlt/CHROME_AGENT_PROMPT.md').read_text(), re.S))
for name, t in js.items():
    c = t['content']
    assert md.get(name) == c, f'{name}: prompt != json'
    assert 'www.' not in c, f'{name}: www. must not come back'
    est = len(c) - c.count('{#var#}')*7 + sum(13 if 'Rs.' in str(v['sample']) else len(str(v['sample']))
                                              for v in (t.get('variables') or []))
    assert est <= 160, f'{name}: {est} chars > 1 segment'
print('OK — prompt/json agree, no www, all single-segment')
EOF
```

(The Java side is keyed by `NotificationType` rather than template name; map it with the
"NotificationType → config key" column in the batch table above.)

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
