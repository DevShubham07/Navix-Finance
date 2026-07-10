# NAVIX _V2 SMS template test results

- **Run:** 2026-07-10 18:21 IST
- **Gateway:** https://ultronsms.com/api/mt/SendSMS · sender `NAVIXF` · peid `1701178039634361131` · route `02` · channel `Trans`
- **Sent to:** 917417682036
- **Summary:** 1 live / 14 not sendable, of 15 templates

`000`/`0` = accepted & delivered. `006 Invalid template text` = template not yet approved/active on the UltronSMS+DLT panel (text was verified char-for-char against SMSULTRON.md, so it is an approval-status issue, not a content issue).

| Template | DLT Template ID | Code | JobId | Result |
|---|---|---|---|---|
| NAVIX_OTP_LOGIN_V2 | 1707178366195230667 | 000 | 202779674 | ✅ LIVE |
| NAVIX_KYC_APPROVED_V2 | 1707178366932300977 | 006 | — | ❌ error:Invalid template text |
| NAVIX_KYC_REJECTED_V2 | 1707178366348720389 | 006 | — | ❌ error:Invalid template text |
| NAVIX_KYC_REMINDER_V2 | 1707178366418625468 | 006 | — | ❌ error:Invalid template text |
| NAVIX_LOAN_DISBURSED_V2 | 1707178366447724079 | 006 | — | ❌ error:Invalid template text |
| NAVIX_REPAYMENT_VERIFIED_V2 | 1707178366455225240 | 006 | — | ❌ error:Invalid template text |
| NAVIX_REPAYMENT_REJECTED_V2 | 1707178366462035431 | 006 | — | ❌ error:Invalid template text |
| NAVIX_PAYMENT_DUE_SOON_V2 | 1707178366469516547 | 006 | — | ❌ error:Invalid template text |
| NAVIX_PAYMENT_OVERDUE_V2 | 1707178366491271206 | 006 | — | ❌ error:Invalid template text |
| NAVIX_LOAN_CLOSED_V2 | 1707178367132471619 | 006 | — | ❌ error:Invalid template text |
| NAVIX_APPLICATION_DECLINED_V2 | 1707178366517137780 | 006 | — | ❌ error:Invalid template text |
| NAVIX_SETTLEMENT_APPROVED_V2 | 1707178366525825089 | 006 | — | ❌ error:Invalid template text |
| NAVIX_REBORROW_APPROVED_V2 | 1707178367131025190 | 006 | — | ❌ error:Invalid template text |
| NAVIX_REBORROW_PREAPPROVED_V2 | 1707178366559342535 | 006 | — | ❌ error:Invalid template text |
| NAVIX_REFERRAL_REWARD_CREDITED_V2 | 1707178366569621398 | 006 | — | ❌ error:Invalid template text |
