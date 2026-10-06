# Borrower Messaging Guide — WhatsApp (SmartChat) · SMS (UltronSMS) · DLT

The one reference for every outbound borrower message channel except email. Read this before touching
a template, a provider credential, or `NotificationTemplates.java`.

| Channel | Provider | Template approval by | Code seam | Config |
|---|---|---|---|---|
| WhatsApp | **SmartChat** (`smartchatapi.live`, Meta BSP) | **Meta** (minutes–24h) | `WhatsAppGateway` → `SmartChatWhatsAppClient` | `navix.whatsapp.*` |
| SMS | **UltronSMS** (`ultronsms.com`) | **DLT** via STPL (days) | `SmsGateway` → `UltronSmsClient` | `navix.sms.*` |

Both channels hang off the same notification engine (`navix-notification`): a domain event →
`NotificationDispatcher` → one `ChannelSender` per channel. Every send is best-effort — a failed
WhatsApp or SMS is a `FAILED` row in `notification_delivery`, never a failed business action.

---

## Part A — WhatsApp (SmartChat)

### A1. Account

| | |
|---|---|
| Portal | https://smartchatapi.live/portal (user **`dhanboost26`**; the password is with the operator, never in the repo) |
| Business number | **+91 92113 31167** (display name "Navix" in the portal header) |
| Wallet | prepaid INR; ₹2,977.96 on 2026-09-23. Check: `GET /Api/getuserbalance?token=…` |
| API token | SSM SecureString **`/navix/dev/navix/whatsapp/token`** (ap-south-1) → `navix.whatsapp.token`. Also shown in the portal under *API → View API (For GET,POST)*. |

### A1b. Business profile (logo / About / description) — NOT editable in SmartChat

Messages arrive with no logo and no bio because the WhatsApp **Business Profile** of +91 92113 31167 is
empty. Templates can't fix that. The SmartChat portal has no profile screen, and its API has no profile
call (checked 2026-09-28). The profile is set on Meta's side, by either:
1. **SmartChat support** (they host the WABA; they call Meta's
   `POST /{phone-number-id}/whatsapp_business_profile` + profile-photo upload for us), or
2. **Meta Business Suite → WhatsApp Manager → Phone numbers → Profile**, if the WABA sits in our own
   Business Manager.

What to hand over (ready copy):

| Field | Limit | Value |
|---|---|---|
| Profile photo | JPG/PNG, 640×640, <5 MB | `docs/whatsapp/whatsapp-profile-photo.png` (the app-icon tile, upscaled from `frontend/public/navix-mark.png`) |
| Display name | Meta review | **DhanBoost** — currently shows "Navix". Needs Meta approval and must match the brand on dhanboost.com |
| About | ≤139 chars | `Salary advance for working professionals. Instant, 100% digital. Official DhanBoost account.` |
| Description | ≤512 chars | `DhanBoost gives salaried employees a short-term salary advance, repaid in one go on payday. Apply online, complete KYC digitally and get money in your bank account quickly. Loans are provided by NAVIX Finance Private Limited. We will never ask for your OTP, PIN or password on WhatsApp.` |
| Category (vertical) | enum | Finance / Financial Services |
| Email | — | support address (confirm; SES-verified domain is still navixfinance.com) |
| Website | ≤2 URLs | `https://dhanboost.com` |
| Address | ≤256 chars | registered office of NAVIX Finance Private Limited |

The green **verified tick** is separate: it needs Meta Verified for Business / an Official Business
Account request, also filed through the BSP.

### A2. API — what the docs say vs what actually works (verified 2026-09-23)

Base: `https://smartchatapi.live/portal/Api/`

- **Auth quirk:** `POST` → token in a **`token:` header**. `GET` → the header is ignored ("Token is
  missing"); pass **`?token=…`** as a query param instead. The portal docs only mention the header.
- **`sender_whatsapp_number` is the RECIPIENT**, `91XXXXXXXXXX`.
- **`insert_template_api` is broken** — HTTP 500 for every payload shape tried, and nothing gets
  created. Create normal templates through the **portal UI** (A5). The auth-template insert
  (`load_insert_template_auth`) works.

| Call | Method | Body / params | Real response |
|---|---|---|---|
| `send_template_message` | POST | `sender_whatsapp_number, template_name, broadcast_name, url:"", parameter_value1..n` | ✅ `{"status":200,"messsage":"Message Send Successfully","message_id":"wamid…"}` (note: `message_id`, not the documented `request_id`; `messsage` is misspelt) |
| `send_template_message_auth` | POST | `sender_whatsapp_number, template_name, broadcast_name, otp_code` | ✅ `{"result":"true","message":"success","request_id":"wamid…"}` |
| any error | — | — | `{"result":"false","message":"Invalid Template Name."}` |
| `get_view_template` | GET `?token=` | — | JSON array of every template with `template_status` (APPROVED/PENDING/REJECTED), `rejected_reason`, buttons |
| `get_template_status` | POST | `template_name` | `{"result":"true","msg":"Template is APPROVED"}` |
| `load_insert_template_auth` | POST | `template_name, language:"en", template_category:"AUTHENTICATION", otp_label, otp_secure:"true", otp_secure_time` | `{"success":"Template Uploaded Successfully","status":"APPROVED"}`. `otp_label` must be letters/digits only (`CopyCode`, not `Copy Code`). |
| `template_delete` | POST | `template_name` | — |
| `getoutgoingmessage` | GET `?token=` | — | sent rows with rendered text + params, but **no delivery status** — see Delivered/Read/Failed in the portal: *View Messages → View Outgoing Messages* |
| `getuserbalance` | GET `?token=` | — | `{"result":"true","data":[{"wallet_balance":"2977.9597"}]}` |

Live end-to-end test 2026-09-28: `db_kyc_approved` + `dhanboost_otp` to a team handset → both **DELIVERED** (portal Outgoing Messages).

Quick checks:
```bash
T=$(AWS_PROFILE=navix-dev aws ssm get-parameter --name /navix/dev/navix/whatsapp/token --with-decryption --region ap-south-1 --query Parameter.Value --output text)
curl -s "https://smartchatapi.live/portal/Api/get_view_template?token=$T" | python3 -c 'import json,sys;[print(t["template_name"],t["template_status"],t["rejected_reason"]) for t in json.load(sys.stdin)]'
# A real send (rings a real handset, costs money — only to a number you own):
curl -s -X POST -H "token: $T" -H 'Content-Type: application/json' \
  -d '{"sender_whatsapp_number":"91XXXXXXXXXX","template_name":"db_kyc_approved","broadcast_name":"test","url":"","parameter_value1":"Test","parameter_value2":"123"}' \
  https://smartchatapi.live/portal/Api/send_template_message
```

### A3. How the backend sends

```
domain event ─► NotificationDispatcher ─► WhatsAppSender (navix-notification)
                      │                        │  subject = template name
                      │ addressFor(WHATSAPP)   │  body    = ordered {{n}} values joined by \u001F
                      ▼ = borrower mobile      ▼
               NotificationTemplates      WhatsAppGateway ─► SmartChatWhatsAppClient (navix-app)
               .whatsapp(type, name, keys…)                      POST send_template_message
```

- **Registry:** `NotificationTemplates.whatsapp(TYPE, "template_name", "key1", "key2", …)`. The keys
  are model keys (`name`, `applicationId`, `loanId`, `amount`, `netDisbursed`, `totalRepayable`,
  `outstanding`, `dueDate`, `daysOverdue`, `settlementAmount`, `reason`, `txnRef`, `limit`,
  `previousAmount`, `pendingSteps`) and fill `{{1}}..{{n}}` **in order**. The wording is NOT in our code
  — it lives with Meta. Changing a key's order, or renaming the template here without an approved
  template of that name, breaks the send.
- **Channel list:** each `NotificationType` names `WHATSAPP` in its channel set. `WhatsAppSenderTest`
  fails if a type lists `WHATSAPP` without a registry entry.
- **Blank values** are sent as `—` (Meta rejects an empty parameter).
- **Opt-out:** no separate toggle. `BorrowerPreferenceAdapter` maps `smsOptIn=false` to SMS **and**
  WhatsApp opted out (same mobile). Staff have no mobile → `SKIPPED/NO_MOBILE`.
- **OTP:** `BorrowerOtpService.request` sends the code by SMS **and** by the `dhanboost_otp`
  AUTHENTICATION template. `sent=true` if either one gets through, so WhatsApp covers SMS/DLT outages.
  Mock mode (`NAVIX_SMS_MOCK=true`) skips both.
- **Failure modes:** a blank token or `enabled=false` → every send `FAILED` ("WhatsApp disabled or
  token not configured"). An unapproved or rejected template → `FAILED` with SmartChat's message.
  Query `notification_delivery where channel='WHATSAPP'` to see them.

Config (`application.yml`):
```yaml
navix.whatsapp:
  base-url: ${NAVIX_WHATSAPP_BASE_URL:https://smartchatapi.live/portal/Api/}
  token: ${NAVIX_WHATSAPP_TOKEN:}          # prod: SSM /navix/<env>/navix/whatsapp/token
  enabled: ${NAVIX_WHATSAPP_ENABLED:true}
  mock: ${NAVIX_WHATSAPP_MOCK:false}       # true = mock id, no HTTP
  otp-template: ${NAVIX_WHATSAPP_OTP_TEMPLATE:dhanboost_otp}
```
No ECS task-definition change is needed: the SSM param is imported by `spring.config.import=aws-parameterstore:/navix/${NAVIX_ENV}/`.

### A4. Template catalogue (submitted 2026-09-23)

All are `en`, footer `DhanBoost`, and carry a static **Open DhanBoost → https://dhanboost.com/login**
URL button unless marked "no button". Status as of submission: check live with `get_view_template`.

| NotificationType | Template | Cat. | Body (`{{n}}` = key) | Status 09-28 |
|---|---|---|---|---|
| (login OTP) | `dhanboost_otp` | AUTH | Meta's fixed OTP text + Copy-code button, 5-min expiry | APPROVED per API; portal table still showed *Pending* on 09-28 — trust a real test send |
| KYC_APPROVED | `db_kyc_approved` | UTIL | Dear {{1 name}}, your KYC for DhanBoost application {{2 applicationId}} has been verified successfully. You can check your application status anytime on the DhanBoost website. | APPROVED |
| KYC_REJECTED | `db_kyc_rejected` | UTIL | …could not verify the KYC details for your DhanBoost application {{2}}. Please log in and re-submit your documents… | APPROVED |
| KYC_REMINDER | `db_kyc_reminder` | UTIL | …steps are still pending on your DhanBoost application {{2}}: {{3 pendingSteps}}… | APPROVED |
| KYC_REOPENED_RESCORE | `db_back_in_review` | UTIL | …application {{2}} is back in review after we re-checked your credit profile… | APPROVED |
| REBORROW_PREAPPROVED | `db_reborrow_preapproved` | **MKT** | Welcome back, {{1}}! You are pre-approved for another salary advance… | APPROVED |
| REBORROW_REVIEW_APPROVED | `db_reborrow_approved` | UTIL | …application {{2}} has been approved… | APPROVED |
| CREDIT_REJECTED, REBORROW_REVIEW_REJECTED | `db_application_declined` | UTIL | …after assessing your DhanBoost loan application {{2}}, we are unable to approve it at this time… | APPROVED |
| LOAN_SANCTIONED | `db_loan_sanctioned` | UTIL | …loan application {{2}} has been sanctioned. Please log in to review your offer… | APPROVED |
| SANCTIONED_AMOUNT_REVISED | `db_amount_revised` | UTIL | …revised to {{3 amount}} (previously {{4 previousAmount}})… | APPROVED |
| LOAN_LIMIT_REVISED | `db_limit_increased` | UTIL | …borrowing limit has been increased to {{2 limit}}… | APPROVED |
| DISBURSEMENT_REJECTED | `db_disbursal_declined` | UTIL | …application {{2}} was declined at the disbursement stage… | APPROVED |
| LOAN_DISBURSED | `db_loan_disbursed` | UTIL | DhanBoost has credited {{2 netDisbursed}}… Total repayable: {{3 totalRepayable}} Due date: {{4 dueDate}}… | APPROVED |
| SANCTION_LETTER_SIGNED | `db_sanction_letter_signed` | UTIL | …thank you for e-signing the sanction letter for… {{2}}… (no button) | APPROVED |
| REPAYMENT_RECORDED | `db_payment_recorded` | UTIL | …received your payment record of {{2 amount}} for DhanBoost loan {{3 loanId}}… pending verification (no button) | APPROVED |
| REPAYMENT_VERIFIED | `db_repayment_verified` | UTIL | …payment of {{2}} has been confirmed. Outstanding balance: {{3 outstanding}}… | APPROVED |
| REPAYMENT_REJECTED | `db_repayment_rejected` | UTIL | …could not be verified. Reason: {{4 reason}}… (SMS can't carry the reason; this can) | APPROVED |
| PAYMENT_DUE_SOON | `db_payment_due_soon` | UTIL | repayment of {{2}} on your DhanBoost loan {{3}} is due on {{4 dueDate}}… | APPROVED |
| PAYMENT_OVERDUE | `db_payment_overdue` | UTIL | …is overdue by {{4 daysOverdue}} day(s)… 2% per day penalty… | APPROVED |
| LOAN_CLOSED | `db_loan_closed` | UTIL | …loan for application {{2}} is fully repaid and closed… | APPROVED |
| SETTLEMENT_APPROVED | `db_settlement_approved` | UTIL | …settlement of {{2 settlementAmount}} approved on your DhanBoost loan {{3}}… | APPROVED |
| REFERRAL_REWARD_CREDITED | `db_referral_reward` | UTIL | …referral reward of {{2 amount}} has been credited. Reference: {{3 txnRef}}… | APPROVED |
| APPLICATION_CANCELLED | `db_application_cancelled` | UTIL | …application {{2}} has been cancelled… | APPROVED |
| ~~BUREAU_QUESTION_PENDING~~ | `db_bureau_question` | UTIL | link as body variable `{{3}}` | **REJECTED** — not wired, see A6 |

Exact full bodies: `get_view_template`, or the submit script at the bottom of this file (A7).

WhatsApp beats SMS for **REBORROW_PREAPPROVED** and **REFERRAL_REWARD_CREDITED**: those SMS are DLT-
Promotional and never reach DND numbers. WhatsApp also adds six types SMS never covered
(rescore, amount/limit revised, disbursal declined, sanction letter signed, payment recorded, cancelled).

### A5. Creating / changing a template

1. **Portal:** *Template → Add Template*. Name must be lowercase/underscore. Category Utility for
   anything triggered by the borrower's own account; Marketing for anything inviting a new loan.
2. Portal rules (from the form): body ≤1024 chars; `{{1}}` onward with no gaps; ≤2 consecutive
   newlines; **no double quotes**; don't write the word "WhatsApp"; no variable at the very start or
   end of the body; footer ≤60 chars with no emoji; button text ≤20 chars.
3. **Detected Parameters** fills itself (`{{1}},{{2}}`). No sample values are asked for.
4. Submit and wait on the View Template page. Submission can take 10–20 s. Firing a second submission
   before the first redirects silently drops it, so do them one at a time.
5. Add the `whatsapp(TYPE, "name", keys…)` line in `NotificationTemplates` and add `WHATSAPP` to the
   type's channel set. Deploy. Sends to a still-PENDING template just `FAIL` until Meta approves it.
6. **Editing an approved template's wording = new template name** (e.g. `db_loan_closed_v2`) + code
   change. Meta re-reviews edits and keeps the old one live until then.

Claude-in-Chrome: the operator logs in (Claude must not type passwords). Claude then fills the form
by script. Set these fields: `#template_name`, `#category` (Utility/Marketing), `#select_language=en`,
`#body_text`, `#body_text_p` (the `{{n}}` list), `#footer_text`, `#select_button='Call to action'`,
click **Add Website**, then set `website_button_text[]`, `select_type_w[]='Static'` and
`website_button_url[]`, then click `#submitd`.

### A6. New-template backlog (moments with no borrower message today)

Found by reviewing the borrower journey and every `publishEvent` on 2026-09-23. Each needs a new
`NotificationType` + event (or a scheduler), then a template:

| # | Moment | Trigger point | Suggested template | Cat. | Vars |
|---|---|---|---|---|---|
| 1 | **Bureau KBA question** (resubmit) | `BUREAU_QUESTION_PENDING` already fires | `db_bureau_question_v2` with a **Dynamic URL button** (base `https://dhanboost.com/` + `{{1}}` path) instead of a link in the body; send via the dynamic-url send variant (`button_url`) | UTIL | name, applicationId, path |
| 2 | **Document requested / file parked** | `ApplicationFlowService.markPending` (no notification today, "decision 30") | `db_document_requested` | UTIL | name, applicationId, reason |
| 3 | **Offer journey stalled** (sanctioned, not finished in 24h) | new scheduler over `SANCTIONED` apps by `journey_step` age | `db_offer_pending` | UTIL | name, applicationId, sanctioned amount |
| 4 | **eSign not completed** | `OfferService.esignInit` without success after N hours | `db_esign_pending` | UTIL | name, applicationId |
| 5 | **Disbursal account confirmed** (awaiting transfer) | `OfferService.confirmDisbursalAccount` | `db_disbursal_queued` | UTIL | name, applicationId, a/c last 4 |
| 6 | **Intake abandoned** (DRAFT, no submit in 24h) | new scheduler over `DRAFT` by last `journey_step` | `db_application_incomplete` | UTIL | name |
| 7 | **Password reset link** | `PasswordResetService.issueAndSend` (email only; bypasses dispatcher on purpose) | AUTH-style / UTIL with a dynamic URL button | UTIL | name, token path |
| 8 | **Referral qualified** (before payout) | `ReferralService` → `ReferralPayoutCreatedEvent` (staff-only today) | `db_referral_qualified` | UTIL | name, amount |
| 9 | **Settlement offered to borrower** | `SettlementService` proposal (staff-only; product decision needed) | `db_settlement_offer` | UTIL | name, amount, loanId |
| 10 | **Due-today / salary-day nudge** | `PaymentReminderScheduler` (currently due-soon + overdue only) | `db_payment_due_today` | UTIL | name, amount, loanId |
| 11 | **Win-back** (closed loan, no reborrow in 30 days) | new scheduler | `db_winback` | **MKT** | name, limit |
| 12 | **Inbound replies** | SmartChat *API → Receive Message (By Webhook)* | route to a support inbox/lead | — | — |

---

## Part B — SMS (UltronSMS)

Detailed docs live in [`docs/sms-dlt/`](../sms-dlt/). The essentials:

- **Gateway:** `GET https://ultronsms.com/api/mt/SendSMS` with `APIKey` (or `user`+`password`),
  `senderid=DHANBT`, `channel=Trans`, `route=02`, `DCS=0`, `flashsms=0`, `number=91…`, `text`,
  `peid=1701178039634361131`, `DLTTemplateId`. Response `{ErrorCode, ErrorMessage, JobId}`, and
  `0`/`000` = success. `006 Invalid template text` = the body doesn't match the DLT registration.
- **Code:** `UltronSmsClient` (navix-app) implements `SmsGateway`. `SmsSender` (navix-notification)
  sends each `sms(...)` body from `NotificationTemplates`, and `TemplateRenderer` turns `₹` into
  `Rs. ` (₹ forces UCS-2 and doubles the cost).
- **Per-type DLT id:** `navix.sms.dlt-template-ids.<NotificationType>` in `application.yml`. Unmapped
  types fall back to `navix.sms.dlt-template-id` (the OTP id).
- **Credentials:** SSM `/navix/dev/navix/sms/{user,password,sender-id,channel,route,peid,dlt-template-id}`.
- **Mock:** `NAVIX_SMS_MOCK=true` → OTP `123456`, no SMS and no WhatsApp OTP.
- ⚠️ ECS task-def **revision 4 pins `NAVIX_SMS_OTP_TEMPLATE`** to the old DLT-approved NAVIX wording.
  Don't redeploy from yml defaults until the DhanBoost OTP template is approved.
- Live test scripts: `docs/sms-dlt/test-send-sms.sh`, `test-all-templates.sh`.

## Part C — DLT (India telecom regulation, SMS only)

WhatsApp is **not** subject to DLT. SMS is. Full rules are in [`docs/sms-dlt/SMSGuide.md`](../sms-dlt/SMSGuide.md).

- Entity: **NAVIX FINANCE PRIVATE LIMITED**, PE-ID `1701178039634361131`, via aggregator **STPL**
  (smartping.live). Header **DHANBT**. Brand **DhanBoost** must appear in every body (`- DhanBoost`).
- Category: **Service Implicit** for everything (Transactional is banks-only).
- Every body must match the registered template char-for-char, and `{#var#}` is ≤30 chars. The URL must
  be whitelisted exactly as `https://dhanboost.com/login` (apex, no `www.`).
- Put descriptive text before every variable; a leading bare variable gets rejected ("purpose of
  first variable is not clear").
- The template list + ids are in [`SMSULTRON.md`](../sms-dlt/SMSULTRON.md), status in
  [`DLT_SUBMISSION_TRACKER.md`](../sms-dlt/DLT_SUBMISSION_TRACKER.md), machine-readable in
  [`dlt-templates.json`](../sms-dlt/dlt-templates.json). After DLT approval, the template must also
  be added in the UltronSMS panel (*MyTemplate*, runbook [`ULTRON_FILL_RUNBOOK.md`](../sms-dlt/ULTRON_FILL_RUNBOOK.md)).
- New SMS type checklist: DLT register → UltronSMS panel add → id into `dlt-template-ids` →
  `sms(...)` body in `NotificationTemplates` identical to the registration, with `{key}` where
  DLT has `{#var#}` → add `SMS` to the type's channel set.

## Part D — Adding a notification on both channels (checklist)

1. `NotificationType` constant: category, `Set.of(IN_APP, SMS, EMAIL, WHATSAPP)`, audience.
2. Publish/map the domain event (`NotificationEventListener`). Never call the engine directly.
3. `NotificationTemplates`: `inApp`, `email`, `sms` (DLT-identical) and `whatsapp(type, name, keys…)`.
4. SMS: DLT + UltronSMS + `dlt-template-ids`. WhatsApp: submit the template in the SmartChat portal.
5. `./mvnw test -pl navix-notification`. `WhatsAppSenderTest.everyWhatsAppTypeHasATemplate` guards the registry.

### A7. Appendix — bulk submit script used on 2026-09-23

`localStorage.__wafill` held this function, and each template was one
`navigate(addtemplate) → eval(localStorage.__wafill)({n,c,b,u}) → wait 15s` round:
```js
(async(s)=>{window.alert=()=>{};window.confirm=()=>true;const $=window.jQuery;
const set=(sel,v)=>{const e=document.querySelector(sel);e.value=v;['input','keyup','change'].forEach(t=>e.dispatchEvent(new Event(t,{bubbles:true})));if($)$(e).trigger('change');};
set('#template_name',s.n);set('#category',s.c);set('#select_language','en');set('#body_text',s.b);
document.getElementById('body_text_p').value=[...new Set(s.b.match(/\{\{\d+\}\}/g)||[])].join(',');
set('#footer_text','DhanBoost');
if(s.u){set('#select_button','Call to action');[...document.querySelectorAll('button')].find(b=>b.innerText.trim()=='Add Website').click();
 await new Promise(r=>setTimeout(r,300));set('[name="website_button_text[]"]','Open DhanBoost');
 set('[name="select_type_w[]"]','Static');set('[name="website_button_url[]"]','https://dhanboost.com/login');}
document.getElementById('submitd').click();})
```
