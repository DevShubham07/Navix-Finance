# NAVIX DLT template submission tracker (_V2 batch)

Submitted to the STPL DLT portal as **NAVIX FINANCE PRIVATE LIMITED**, sender **NAVIXF**.
On submit the portal returned **no numeric DLT Template ID** — every row shows `-` for
"Verified Till" / registration date, status **Work In Progress** pending DLT approval.
IDs are assigned after approval → **check the listing back later and fill the `DLT Template ID`
column below**, then paste into the backend config `navix.sms.dlt-template-ids` (keyed by
`NotificationType`).

> Reminder: **#11 is one template bound to TWO NotificationTypes** — map its single ID to both
> `CREDIT_REJECTED` and `REBORROW_REVIEW_REJECTED`.

| # | Template name | Type | NotificationType → config key | Vars (tag → sample) | DLT Template ID |
|---|---|---|---|---|---|
| 1 | NAVIX_OTP_LOGIN_V2 | Service Implicit | `OTP_LOGIN` (BorrowerOtpService) | Number `123456`; Number `5` | _pending_ |
| 2 | NAVIX_KYC_APPROVED_V2 | Service Implicit | `KYC_APPROVED` | none | _pending_ |
| 3 | NAVIX_KYC_REJECTED_V2 | Service Implicit | `KYC_REJECTED` | none | _pending_ |
| 4 | NAVIX_KYC_REMINDER_V2 | Service Implicit | `KYC_REMINDER` | none | _pending_ |
| 5 | NAVIX_LOAN_DISBURSED_V2 | Service Implicit | `LOAN_DISBURSED` | Alphanum `Rs. 8,820`; Alphanum `Rs. 12,700`; Alphanum `30 Jun 2026` | _pending_ |
| 6 | NAVIX_REPAYMENT_VERIFIED_V2 | Service Implicit | `REPAYMENT_VERIFIED` | Alphanum `Rs. 5,000`; Alphanum `Rs. 7,700` | _pending_ |
| 7 | NAVIX_REPAYMENT_REJECTED_V2 | Service Implicit | `REPAYMENT_REJECTED` | Alphanum `Rs. 5,000` | _pending_ |
| 8 | NAVIX_PAYMENT_DUE_SOON_V2 | Service Implicit | `PAYMENT_DUE_SOON` | Alphanum `Rs. 12,700`; Number `3`; Alphanum `30 Jun 2026` | _pending_ |
| 9 | NAVIX_PAYMENT_OVERDUE_V2 | Service Implicit | `PAYMENT_OVERDUE` | Alphanum `Rs. 12,700`; Number `5` | _pending_ |
| 10 | NAVIX_LOAN_CLOSED_V2 | Service Implicit | `LOAN_CLOSED` | none | _pending_ |
| 11 | NAVIX_APPLICATION_DECLINED_V2 | Service Implicit | `CREDIT_REJECTED` **and** `REBORROW_REVIEW_REJECTED` | none | _pending_ (one ID → both keys) |
| 12 | NAVIX_SETTLEMENT_APPROVED_V2 | Service Implicit | `SETTLEMENT_APPROVED` | Alphanum `Rs. 9,000` | _pending_ |
| 13 | NAVIX_REBORROW_APPROVED_V2 | Service Implicit | `REBORROW_REVIEW_APPROVED` | none | _pending_ |
| 14 | NAVIX_REBORROW_PREAPPROVED_V2 | **Service Explicit** | `REBORROW_PREAPPROVED` | none | _pending_ |
| 15 | NAVIX_REFERRAL_REWARD_CREDITED_V2 | **Service Explicit** | `REFERRAL_REWARD_CREDITED` | Alphanum `Rs. 500`; Alphanum `TXN123456` | _pending_ |

## Notes carried from submission

- **Tag mappings deviated from the spec's OTP/Amount/Date tags** — the portal dropdown lacked those,
  so `Number` / `Alphanum` were used (the runbook's allowed fallback). Functionally safe: Alphanumeric
  is the most permissive type, so `Rs. …`, `30 Jun 2026`, `TXN123456` all validate; numeric OTP under
  `Number` is fine.
- **#14 and #15 registered as Service Explicit** (not Implicit) on reviewer-risk grounds — these send
  only to consented recipients, which fits reborrow-preapproved and referral-reward.
- **No GIF captured** for the first creation (0 frames). Re-record on request if documentation needs it.

## When IDs arrive

1. Fill the `DLT Template ID` column above.
2. Wire into `navix.sms.dlt-template-ids` (per-NotificationType). Map #11's single ID to **both**
   `CREDIT_REJECTED` and `REBORROW_REVIEW_REJECTED`.
3. Confirm each SMS body in `NotificationTemplates.java` still matches the registered content
   character-for-character (only `{#var#}` slots substituted).
