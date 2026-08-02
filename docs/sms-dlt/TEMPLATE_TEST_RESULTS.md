# DhanBoost `_V1` SMS template test results

- **Run:** 2026-08-02 13:20 IST
- **Gateway:** https://ultronsms.com/api/mt/SendSMS · sender `DHANBT` · peid `1701178039634361131` · route `02` · channel `Trans`
- **Sent to:** 917417682036
- **Summary:** 0 live / 15 not sendable / 0 not registered, of 15 templates

`000`/`0` = accepted by the gateway. `006 Invalid template text` = template not yet approved/active on the UltronSMS+DLT panel (verify the text char-for-char against SMSULTRON.md first — if it matches, it is an approval-status issue, not a content issue). `⏳ not registered` = no DLT id was exported for that template, so it was skipped.

| Template | DLT Template ID | Code | JobId | Result |
|---|---|---|---|---|
| DHANBOOST_OTP_LOGIN_V1 | 1777178551180955540 | 006 | — | ❌ error:Invalid template text |
| DHANBOOST_KYC_APPROVED_V1 | 1777178556860826081 | 006 | — | ❌ error:Invalid template text |
| DHANBOOST_KYC_REJECTED_V1 | 1777178556856596751 | 006 | — | ❌ error:Invalid template text |
| DHANBOOST_KYC_REMINDER_V1 | 1777178556869196458 | 006 | — | ❌ error:Invalid template text |
| DHANBOOST_LOAN_DISBURSED_V1 | 1777178556842056405 | 006 | — | ❌ error:Invalid template text |
| DHANBOOST_REPAYMENT_VERIFIED_V1 | 1777178551212221383 | 006 | — | ❌ error:Invalid template text |
| DHANBOOST_REPAYMENT_REJECTED_V1 | 1777178551218012610 | 006 | — | ❌ error:Invalid template text |
| DHANBOOST_PAYMENT_DUE_SOON_V1 | 1777178556822213797 | 006 | — | ❌ error:Invalid template text |
| DHANBOOST_PAYMENT_OVERDUE_V1 | 1777178556852586580 | 006 | — | ❌ error:Invalid template text |
| DHANBOOST_LOAN_CLOSED_V1 | 1777178551234723180 | 006 | — | ❌ error:Invalid template text |
| DHANBOOST_APPLICATION_DECLINED_V1 | 1777178556864993650 | 006 | — | ❌ error:Invalid template text |
| DHANBOOST_SETTLEMENT_APPROVED_V1 | 1777178556848308137 | 006 | — | ❌ error:Invalid template text |
| DHANBOOST_REBORROW_APPROVED_V1 | 1777178556873259005 | 006 | — | ❌ error:Invalid template text |
| DHANBOOST_REBORROW_PREAPPROVED_V1 | 1777178551273887503 | 006 | — | ❌ error:Invalid template text |
| DHANBOOST_REFERRAL_REWARD_CREDITED_V1 | 1777178551284965972 | 006 | — | ❌ error:Invalid template text |
