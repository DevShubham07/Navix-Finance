# UltronSMS panel — registering the 15 approved DhanBoost templates

DLT approval happens on **SmartPing** (done — all 15 `DHANBOOST_*_V1` are Active, ids in
[`DLT_SUBMISSION_TRACKER.md`](./DLT_SUBMISSION_TRACKER.md)). This runbook is the **gateway** half:
mirroring those approved templates into the UltronSMS panel so sends resolve against them.

**Panel:** `https://ultronsms.com/Web/MT/MyTemplate.aspx` (log in yourself — automating login is out
of scope).

## Constants for every row

| Field | Value |
|---|---|
| Sender ID / Header | `DHANBT` |
| PE-ID (entity, constant) | `1701178039634361131` |
| Route | `02` — **except** #14 and #15, which are **Promotional** and need a promotional route |
| Channel | `Trans` (Promo for #14/#15) |

## Rules

1. **Paste the content character-for-character** from the table below. Any drift → the gateway
   returns `006 Invalid template text` at send time, not at registration time, so a typo here shows
   up later as a silent delivery failure.
2. The panel's variable token is `##Field##` (SmartPing writes `{#alp#}`/`{#num#}`, our JSON writes
   `{#var#}` — all three mean the same slot). Use whatever token the panel's own help text shows.
3. **`Rs.` is inside the variable value**, not static text — the substituted value is literally
   `Rs. 12,700`. Never use `₹` (forces UCS-2: 70 chars/segment and higher cost).
4. The URL is the **apex** `https://dhanboost.com/login` — **no `www.`**. It is checked
   char-for-char against the whitelisted CTA.
5. Pair each template with its **DLT Template ID** below. Do not retype an id from memory — copy it.
6. Do not touch any Delete / Deactivate control, and never trigger a browser confirm dialog.

## The 15 templates

Content is the DLT-registered body verbatim. Full per-variable samples are in
[`dlt-templates.json`](./dlt-templates.json); the same bodies live in `NotificationTemplates.java`
(and `application.yml` for #1).

| # | Name | DLT Template ID | Type | Content |
|---|---|---|---|---|
| 1 | DHANBOOST_OTP_LOGIN_V1 | `1777178551180955540` | Service Implicit | `Your OTP for DhanBoost login is ##Field##. It is valid for ##Field## minutes. Do not share this OTP with anyone. - DhanBoost` |
| 2 | DHANBOOST_KYC_APPROVED_V1 | `1777178556860826081` | Service Implicit | `Dear ##Field##, your KYC for DhanBoost application ##Field## is verified. Check status at https://dhanboost.com/login. - DhanBoost` |
| 3 | DHANBOOST_KYC_REJECTED_V1 | `1777178556856596751` | Service Implicit | `Dear ##Field##, your KYC for DhanBoost application ##Field## could not be verified. Re-submit documents at https://dhanboost.com/login. - DhanBoost` |
| 4 | DHANBOOST_KYC_REMINDER_V1 | `1777178556869196458` | Service Implicit | `Dear ##Field##, verification steps on your DhanBoost application ##Field## are pending. Complete them at https://dhanboost.com/login. - DhanBoost` |
| 5 | DHANBOOST_LOAN_DISBURSED_V1 | `1777178556842056405` | Service Implicit | `Dear ##Field##, DhanBoost has credited ##Field## to your bank a/c. Repay ##Field## by ##Field## at https://dhanboost.com/login. - DhanBoost` |
| 6 | DHANBOOST_REPAYMENT_VERIFIED_V1 | `1777178551212221383` | Service Implicit | `Your payment of ##Field## to DhanBoost is confirmed. Outstanding balance is ##Field##. View details at https://dhanboost.com/login. - DhanBoost` |
| 7 | DHANBOOST_REPAYMENT_REJECTED_V1 | `1777178551218012610` | Service Implicit | `Your payment of ##Field## could not be verified by DhanBoost. Log in at https://dhanboost.com/login to check and record it again. - DhanBoost` |
| 8 | DHANBOOST_PAYMENT_DUE_SOON_V1 | `1777178556822213797` | Service Implicit | `Dear ##Field##, repayment of ##Field## on your DhanBoost loan ##Field## is due on ##Field##. Pay at https://dhanboost.com/login. - DhanBoost` |
| 9 | DHANBOOST_PAYMENT_OVERDUE_V1 | `1777178556852586580` | Service Implicit | `Dear ##Field##, repayment of ##Field## on your DhanBoost loan ##Field## is overdue by ##Field## day(s). Pay at https://dhanboost.com/login. - DhanBoost` |
| 10 | DHANBOOST_LOAN_CLOSED_V1 | `1777178551234723180` | Service Implicit | `Your loan with DhanBoost is fully repaid and closed. Thank you. Visit https://dhanboost.com/login to borrow again. - DhanBoost` |
| 11 | DHANBOOST_APPLICATION_DECLINED_V1 | `1777178556864993650` | Service Implicit | `Dear ##Field##, your DhanBoost loan application ##Field## could not be approved at this time. Details at https://dhanboost.com/login. - DhanBoost` |
| 12 | DHANBOOST_SETTLEMENT_APPROVED_V1 | `1777178556848308137` | Service Implicit | `Dear ##Field##, a full and final settlement of ##Field## is approved on DhanBoost loan ##Field##. Pay at https://dhanboost.com/login. - DhanBoost` |
| 13 | DHANBOOST_REBORROW_APPROVED_V1 | `1777178556873259005` | Service Implicit | `Dear ##Field##, your DhanBoost application ##Field## is approved. Complete the remaining steps at https://dhanboost.com/login. - DhanBoost` |
| 14 | DHANBOOST_REBORROW_PREAPPROVED_V1 | `1777178551273887503` | **Promotional** | `Welcome back to DhanBoost. You can apply for another loan now. Log in at https://dhanboost.com/login to choose your amount. - DhanBoost` |
| 15 | DHANBOOST_REFERRAL_REWARD_CREDITED_V1 | `1777178551284965972` | **Promotional** | `Your DhanBoost referral reward of ##Field## is credited with reference ##Field##. Log in at https://dhanboost.com/login to view it. - DhanBoost` |

> #11 is **one** template bound to **two** NotificationTypes (`CREDIT_REJECTED` and
> `REBORROW_REVIEW_REJECTED`) — register it once; the backend maps its single id to both keys.
> #14 and #15 are **Promotional**: not delivered to DND-registered numbers, and they need a
> promotional route rather than `02`.

## Agent prompt (paste to the Claude-in-Chrome agent)

> The UltronSMS panel is open and logged in at `https://ultronsms.com/Web/MT/MyTemplate.aspx` in my
> active Chrome tab. Read `docs/sms-dlt/ULTRON_FILL_RUNBOOK.md` and register all 15 templates from
> its table, one at a time.
>
> - Use the tab I have open — do not create a new one. `read_page` first to learn the form's actual
>   field labels (name, sender/header, DLT template id, content box, variable token, submit).
> - Content must match the table **character-for-character**. Before each submit, `read_page` and
>   verify the box text equals the row's content exactly — no double spaces, no trailing space, URL
>   is the apex `https://dhanboost.com/login` with no `www.`, body ends with ` - DhanBoost`.
> - Copy each DLT Template ID from the table; never retype or infer one.
> - Sender `DHANBT`, PE-ID `1701178039634361131`. Rows 14 and 15 are **Promotional** — if the form
>   asks for type/route, mark them promotional, not transactional/route 02.
> - Pause and ask me before the first submit, and any time a field label is ambiguous, a submit
>   errors, or the content won't match after two attempts. Do not guess through repeated failures.
> - Never click Delete/Deactivate, and never trigger a JavaScript alert/confirm dialog — it freezes
>   the extension.
> - At the end print `{name → what the panel returned, status}` for all 15.

## Status

**Done 2026-08-02** — all 15 registered in the panel under sender `DHANBT`, each verified
char-for-char against the table above (name, body, DLT id). All show **Pending** — UltronSMS reviews
them panel-side before a send will resolve; re-check for `Approved` before running the send test.

The form has only SenderId / Name / Template / DLT Template ID — **no type or route field**, so the
Promotional handling for #14/#15 is purely a send-time concern (promotional route, not `02`).

The 19 legacy `NAVIX_*_V2` rows are still listed and are dead (blacklisted on DLT). Deleting them is
optional cleanup and needs a confirm dialog — do it by hand.

## After filling

Send-test with the ids now baked into the script:

```bash
NAVIX_SMS_USER=... NAVIX_SMS_PASSWORD=... docs/sms-dlt/test-all-templates.sh <10-digit-number>
```

`ErrorCode 000` = accepted by the gateway; a JobId means *queued*, not delivered — confirm on a
handset. Then the three prod switches in
[`DLT_SUBMISSION_TRACKER.md` → "▶ NEXT SESSION" Step 3](./DLT_SUBMISSION_TRACKER.md).
