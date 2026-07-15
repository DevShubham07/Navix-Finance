# NAVIX DLT template submission tracker (_V2 batch)

Submitted to the STPL DLT portal as **NAVIX FINANCE PRIVATE LIMITED**, sender **NAVIXF**.
All 15 now carry assigned **DLT Template IDs** (see `SMSULTRON.md`) and were **accepted by the
UltronSMS gateway** on a live end-to-end send test (2026-07-11) — every template returned
`ErrorCode 0` with a JobId, confirming the id + content are valid/approved at the gateway.

> Gateway-accepted (JobId assigned) = the send was queued. Actual **handset delivery** is a separate
> DLR status not captured here. Test recipient: `917417682036`.

> Reminder: **#11 is one template bound to TWO NotificationTypes** — map its single ID to both
> `CREDIT_REJECTED` and `REBORROW_REVIEW_REJECTED`.

| # | Template name | Type | NotificationType → config key | DLT Template ID | Send test (JobId) |
|---|---|---|---|---|---|
| 1 | NAVIX_OTP_LOGIN_V2 | Service Implicit | `OTP_LOGIN` (BorrowerOtpService) | 1707178366195230667 | ✅ 202881808 |
| 2 | NAVIX_KYC_APPROVED_V2 | Service Implicit | `KYC_APPROVED` | 1707178366932300977 | ✅ 202881818 |
| 3 | NAVIX_KYC_REJECTED_V2 | Service Implicit | `KYC_REJECTED` | 1707178366348720389 | ✅ 202881824 |
| 4 | NAVIX_KYC_REMINDER_V2 | Service Implicit | `KYC_REMINDER` | 1707178366418625468 | ✅ 202881831 |
| 5 | NAVIX_LOAN_DISBURSED_V2 | Service Implicit | `LOAN_DISBURSED` | 1707178366447724079 | ✅ 202881840 |
| 6 | NAVIX_REPAYMENT_VERIFIED_V2 | Service Implicit | `REPAYMENT_VERIFIED` | 1707178366455225240 | ✅ 202881850 |
| 7 | NAVIX_REPAYMENT_REJECTED_V2 | Service Implicit | `REPAYMENT_REJECTED` | 1707178366462035431 | ✅ 202881859 |
| 8 | NAVIX_PAYMENT_DUE_SOON_V2 | Service Implicit | `PAYMENT_DUE_SOON` | 1707178366469516547 | ✅ 202881865 |
| 9 | NAVIX_PAYMENT_OVERDUE_V2 | Service Implicit | `PAYMENT_OVERDUE` | 1707178366491271206 | ✅ 202881875 |
| 10 | NAVIX_APPLICATION_DECLINED_V2 | Service Implicit | `CREDIT_REJECTED` **and** `REBORROW_REVIEW_REJECTED` | 1707178366517137780 | ✅ 202881881 (one ID → both keys) |
| 11 | NAVIX_SETTLEMENT_APPROVED_V2 | Service Implicit | `SETTLEMENT_APPROVED` | 1707178366525825089 | ✅ 202881894 |
| 12 | NAVIX_REBORROW_APPROVED_V2 | Service Implicit | `REBORROW_REVIEW_APPROVED` | 1707178367131025190 | ✅ 202881899 |
| 13 | NAVIX_LOAN_CLOSED_V2 | Service Implicit | `LOAN_CLOSED` | 1707178367132471619 | ✅ 202881907 |
| 14 | NAVIX_REBORROW_PREAPPROVED_V2 | **Service Explicit** | `REBORROW_PREAPPROVED` | 1707178366559342535 | ✅ 202881909 |
| 15 | NAVIX_REFERRAL_REWARD_CREDITED_V2 | **Service Explicit** | `REFERRAL_REWARD_CREDITED` | 1707178366569621398 | ✅ 202881915 |

## Notes carried from submission

- **Tag mappings deviated from the spec's OTP/Amount/Date tags** — the portal dropdown lacked those,
  so `Number` / `Alphanum` were used (the runbook's allowed fallback). Functionally safe.
- **#14 and #15 registered as Service Explicit** (not Implicit) on reviewer-risk grounds — these send
  only to consented recipients, which fits reborrow-preapproved and referral-reward.
- **Send test 2026-07-11**: all 15 accepted by UltronSMS (`docs/sms-dlt/test-send-sms.sh` request
  shape), sender `NAVIXF`, peid `1701178039634361131`, route `02`. Variable slots filled with the
  `dlt-templates.json` sample values; each body sent equals its approved template char-for-char.

## Wiring the IDs into the backend

`navix.sms.dlt-template-ids` (application.yml) is keyed by `NotificationType`. Set these env vars
(or the SSM equivalents) — **#10's single ID goes to both `APPLICATION_DECLINED` keys**:

```
NAVIX_SMS_DLT_KYC_APPROVED=1707178366932300977
NAVIX_SMS_DLT_KYC_REJECTED=1707178366348720389
NAVIX_SMS_DLT_KYC_REMINDER=1707178366418625468
NAVIX_SMS_DLT_LOAN_DISBURSED=1707178366447724079
NAVIX_SMS_DLT_REPAYMENT_VERIFIED=1707178366455225240
NAVIX_SMS_DLT_REPAYMENT_REJECTED=1707178366462035431
NAVIX_SMS_DLT_PAYMENT_DUE_SOON=1707178366469516547
NAVIX_SMS_DLT_PAYMENT_OVERDUE=1707178366491271206
NAVIX_SMS_DLT_APPLICATION_DECLINED=1707178366517137780   # → CREDIT_REJECTED + REBORROW_REVIEW_REJECTED
NAVIX_SMS_DLT_SETTLEMENT_APPROVED=1707178366525825089
NAVIX_SMS_DLT_REBORROW_APPROVED=1707178367131025190
NAVIX_SMS_DLT_LOAN_CLOSED=1707178367132471619
NAVIX_SMS_DLT_REBORROW_PREAPPROVED=1707178366559342535
NAVIX_SMS_DLT_REFERRAL_REWARD_CREDITED=1707178366569621398
NAVIX_SMS_DLT_TEMPLATE_ID=1707178366195230667            # OTP / global fallback
```

Then confirm each SMS body in `NotificationTemplates.java` still matches the registered content
character-for-character (only `{#var#}` slots substituted).
