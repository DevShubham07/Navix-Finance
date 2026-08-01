All 15 DHANBOOST_*_V1 templates were found Active/approved, plus 9 earlier-rejected attempts of the same names (all later resubmitted and approved). No read-only view was skipped; DHANBOOST_LOAN_CLOSED_V1 and DHANBOOST_REBORROW_PREAPPROVED_V1 have no eye/view icon in the list row, but their Template ID/Header were still retrievable via the Global Status "Active" badge → "Template details" modal. No New/Edit/Delete/Submit/Blacklist/Suspend controls were touched.

**A) Listing table**

| # | Template name | Global Status | Template type | DLT Template ID | Header |
|---|---|---|---|---|---|
|1|DHANBOOST_PAYMENT_OVERDUE_V1|Active|Service Implicit|1777178556852586580|DHANBT|
|2|DHANBOOST_REBORROW_APPROVED_V1|Active|Service Implicit|1777178556873259005|DHANBT|
|3|DHANBOOST_KYC_REMINDER_V1|Active|Service Implicit|1777178556869196458|DHANBT|
|4|DHANBOOST_APPLICATION_DECLINED_V1|Active|Service Implicit|1777178556864993650|DHANBT|
|5|DHANBOOST_LOAN_DISBURSED_V1|Active|Service Implicit|1777178556842056405|DHANBT|
|6|DHANBOOST_OTP_LOGIN_V1|Active|Service Implicit|1777178551180955540|DHANBT|
|7|DHANBOOST_SETTLEMENT_APPROVED_V1|Active|Service Implicit|1777178556848308137|DHANBT|
|8|DHANBOOST_REPAYMENT_REJECTED_V1|Active|Service Implicit|1777178551218012610|DHANBT|
|9|DHANBOOST_REFERRAL_REWARD_CREDITED_V1|Active|Promotional|1777178551284965972|DHANBT|
|10|DHANBOOST_KYC_REJECTED_V1|Active|Service Implicit|1777178556856596751|DHANBT|
|11|DHANBOOST_PAYMENT_DUE_SOON_V1|Active|Service Implicit|1777178556822213797|DHANBT|
|12|DHANBOOST_KYC_APPROVED_V1|Active|Service Implicit|1777178556860826081|DHANBT|
|13|DHANBOOST_LOAN_CLOSED_V1|Active|Service Implicit|1777178551234723180|DHANBT|
|14|DHANBOOST_REPAYMENT_VERIFIED_V1|Active|Service Implicit|1777178551212221383|DHANBT|
|15|DHANBOOST_REBORROW_PREAPPROVED_V1|Active|Promotional|1777178551273887503|DHANBT|

**B) Per-template detail blocks**

```
NAME: DHANBOOST_PAYMENT_OVERDUE_V1
ID: 1777178556852586580
TYPE: Service Implicit
CONTENT: Dear {#alp#}, repayment of {#alp#} on your DhanBoost loan {#num#} is overdue by {#num#} day(s). Pay at https://dhanboost.com/login. - DhanBoost
VARS: 1. alp (name) | Rahul Sharma   2. alp (amount) | Rs. 12,700   3. num | 1042   4. num | 5
```

```
NAME: DHANBOOST_REBORROW_APPROVED_V1
ID: 1777178556873259005
TYPE: Service Implicit
CONTENT: Dear {#alp#}, your DhanBoost application {#num#} is approved. Complete the remaining steps at https://dhanboost.com/login. - DhanBoost
VARS: 1. alp | Rahul Sharma   2. num | 3071
```

```
NAME: DHANBOOST_KYC_REMINDER_V1
ID: 1777178556869196458
TYPE: Service Implicit
CONTENT: Dear {#alp#}, verification steps on your DhanBoost application {#num#} are pending. Complete them at https://dhanboost.com/login. - DhanBoost
VARS: 1. alp | Rahul Sharma   2. num | 3071
```

```
NAME: DHANBOOST_APPLICATION_DECLINED_V1
ID: 1777178556864993650
TYPE: Service Implicit
CONTENT: Dear {#alp#}, your DhanBoost loan application {#num#} could not be approved at this time. Details at https://dhanboost.com/login. - DhanBoost
VARS: 1. alp | Rahul Sharma   2. num | 3071
```

```
NAME: DHANBOOST_LOAN_DISBURSED_V1
ID: 1777178556842056405
TYPE: Service Implicit
CONTENT: Dear {#alp#}, DhanBoost has credited {#alp#} to your bank a/c. Repay {#alp#} by {#alp#} at https://dhanboost.com/login. - DhanBoost
VARS: 1. alp | Rahul Sharma   2. alp | Rs. 8,820   3. alp | Rs. 12,700   4. alp | 30 Jun 2026
```

```
NAME: DHANBOOST_OTP_LOGIN_V1
ID: 1777178551180955540
TYPE: Service Implicit
CONTENT: Your OTP for DhanBoost login is {#num#}. It is valid for {#num#} minutes. Do not share this OTP with anyone. - DhanBoost
VARS: 1. num | 123456   2. num | 5
```

```
NAME: DHANBOOST_SETTLEMENT_APPROVED_V1
ID: 1777178556848308137
TYPE: Service Implicit
CONTENT: Dear {#alp#}, a full and final settlement of {#alp#} is approved on DhanBoost loan {#num#}. Pay at https://dhanboost.com/login. - DhanBoost
VARS: 1. alp | Rahul Sharma   2. alp | Rs. 9,000   3. num | 1042
```

```
NAME: DHANBOOST_REPAYMENT_REJECTED_V1
ID: 1777178551218012610
TYPE: Service Implicit
CONTENT: Your payment of {#alp#} could not be verified by DhanBoost. Log in at https://dhanboost.com/login to check and record it again. - DhanBoost
VARS: 1. alp | Rs. 5,000
```

```
NAME: DHANBOOST_REFERRAL_REWARD_CREDITED_V1
ID: 1777178551284965972
TYPE: Promotional
CONTENT: Your DhanBoost referral reward of {#alp#} is credited with reference {#alp#}. Log in at https://dhanboost.com/login to view it. - DhanBoost
VARS: 1. alp | Rs. 500   2. alp | TXN123456
```

```
NAME: DHANBOOST_KYC_REJECTED_V1
ID: 1777178556856596751
TYPE: Service Implicit
CONTENT: Dear {#alp#}, your KYC for DhanBoost application {#num#} could not be verified. Re-submit documents at https://dhanboost.com/login. - DhanBoost
VARS: 1. alp | Rahul Sharma   2. num | 3071
```

```
NAME: DHANBOOST_PAYMENT_DUE_SOON_V1
ID: 1777178556822213797
TYPE: Service Implicit
CONTENT: Dear {#alp#}, repayment of {#alp#} on your DhanBoost loan {#num#} is due on {#alp#}. Pay at https://dhanboost.com/login. - DhanBoost
VARS: 1. alp | Rahul Sharma   2. alp | Rs. 12,700   3. num | 1042   4. alp | 30 Jun 2026
```

```
NAME: DHANBOOST_KYC_APPROVED_V1
ID: 1777178556860826081
TYPE: Service Implicit
CONTENT: Dear {#alp#}, your KYC for DhanBoost application {#num#} is verified. Check status at https://dhanboost.com/login. - DhanBoost
VARS: 1. alp | Rahul Sharma   2. num | 3071
```

```
NAME: DHANBOOST_LOAN_CLOSED_V1
ID: 1777178551234723180
TYPE: Service Implicit
CONTENT: Your loan with DhanBoost is fully repaid and closed. Thank you. Visit https://dhanboost.com/login to borrow again. - DhanBoost
VARS: (none — no variable tags on this template)
```

```
NAME: DHANBOOST_REPAYMENT_VERIFIED_V1
ID: 1777178551212221383
TYPE: Service Implicit
CONTENT: Your payment of {#alp#} to DhanBoost is confirmed. Outstanding balance is {#alp#}. View details at https://dhanboost.com/login. - DhanBoost
VARS: 1. alp | Rs. 5,000   2. alp | Rs. 7,700
```

```
NAME: DHANBOOST_REBORROW_PREAPPROVED_V1
ID: 1777178551273887503
TYPE: Promotional
CONTENT: Welcome back to DhanBoost. You can apply for another loan now. Log in at https://dhanboost.com/login to choose your amount. - DhanBoost
VARS: (none — no variable tags on this template)
```

**C) Env-var block**

```
NAVIX_SMS_DLT_TEMPLATE_ID=1777178551180955540          # DHANBOOST_OTP_LOGIN_V1
NAVIX_SMS_DLT_KYC_APPROVED=1777178556860826081
NAVIX_SMS_DLT_KYC_REJECTED=1777178556856596751
NAVIX_SMS_DLT_KYC_REMINDER=1777178556869196458
NAVIX_SMS_DLT_LOAN_DISBURSED=1777178556842056405
NAVIX_SMS_DLT_REPAYMENT_VERIFIED=1777178551212221383
NAVIX_SMS_DLT_REPAYMENT_REJECTED=1777178551218012610
NAVIX_SMS_DLT_PAYMENT_DUE_SOON=1777178556822213797
NAVIX_SMS_DLT_PAYMENT_OVERDUE=1777178556852586580
NAVIX_SMS_DLT_LOAN_CLOSED=1777178551234723180
NAVIX_SMS_DLT_APPLICATION_DECLINED=1777178556864993650
NAVIX_SMS_DLT_SETTLEMENT_APPROVED=1777178556848308137
NAVIX_SMS_DLT_REBORROW_APPROVED=1777178556873259005
NAVIX_SMS_DLT_REBORROW_PREAPPROVED=1777178551273887503
NAVIX_SMS_DLT_REFERRAL_REWARD_CREDITED=1777178551284965972
NAVIX_SMS_SENDER_ID=DHANBT
```

**Rejected tab — 9 earlier DHANBOOST_* attempts found** (all superseded by the Active versions above; recorded per your instructions with rejection reason verbatim):

```
DHANBOOST_LOAN_DISBURSED_V1 (prior attempt) — ID 1777178551206852774 — Remarks: "Link is promotional in nature."
DHANBOOST_PAYMENT_DUE_SOON_V1 (prior attempt) — ID 1777178551224469068 — Remarks: "Link is promotional in nature."
DHANBOOST_PAYMENT_OVERDUE_V1 (prior attempt) — ID 1777178551230499838 — Remarks: "Link is promotional in nature."
DHANBOOST_SETTLEMENT_APPROVED_V1 (prior attempt) — ID 1777178551245161666 — Remarks: "Link is promotional in nature."
DHANBOOST_KYC_APPROVED_V1 (prior attempt) — ID 1777178551188204656 — Remarks: "Content is promotional in nature. Please resubmit in promotional content type."
DHANBOOST_KYC_REJECTED_V1 (prior attempt) — ID 1777178551193134588 — Remarks: "Content is promotional in nature. Please resubmit in promotional content type."
DHANBOOST_KYC_REMINDER_V1 (prior attempt) — ID 1777178551198185193 — Remarks: "Content is promotional in nature. Please resubmit in promotional content type."
DHANBOOST_APPLICATION_DECLINED_V1 (prior attempt) — ID 1777178551239158585 — Remarks: "Content is promotional in nature. Please resubmit in promotional content type."
DHANBOOST_REBORROW_APPROVED_V1 (prior attempt) — ID 1777178551249301445 — Remarks: "Content is promotional in nature. Please resubmit in promotional content type."
```

Note on variable tag notation: I abbreviated "Alphanumeric (Name, Date, Address)" as `alp` and "NUMBER (OTP, Amount, Serial Number, Reference IDs)" as `num` to match the `{#alp#}`/`{#num#}` tokens shown in the content — these are the portal's own token labels, not something I invented.

Once you paste back what you have in your `dlt-templates.json`/tracker, I'll do a character-by-character diff against what's captured above and flag any discrepancy without correcting it.