DHANBOOST - SMS TEMPLATE DOCUMENTATION (UltronSMS)
=======================================================
Brand (in message body): DhanBoost
Legal entity (DLT registration): NAVIX FINANCE PRIVATE LIMITED (CIN U64990HR2026PTC144926)
Source: STPL / smartping.live
Destination: https://ultronsms.com/Web/MT/MyTemplate.aspx
Sender ID (all templates): DHANBT
PE-ID (entity-level, constant): 1701178039634361131
Total templates: 15 — status as of 2026-08-01: 6 Active, 9 Work In Progress. No DLT ids assigned yet.
Note: Dynamic values are shown as ##Field## placeholders. IMPORTANT: for money, "Rs." is INSIDE the
variable value (the substituted value is literally "Rs. 12,700"), NOT static text in the template.

WHY THE IDS ARE BLANK
-----------------------------------------------------
A DLT Template ID is bound to its exact registered content. The NAVIX -> DhanBoost rebrand changed the
brand string AND the URL in every body, so all 15 previously-assigned _V2 ids are INVALID — including
1707178366195230667 (NAVIX_OTP_LOGIN_V2), which was the one template that had reached full DLT approval
and was sending live. Sending against a _V2 id today returns "006 Invalid template text".
The portal only reveals a template's id once it reaches Active, so the ids below stay <PENDING> until
each one is approved. Collect them per DLT_SUBMISSION_TRACKER.md -> "NEXT SESSION".

URL: the whitelisted CTA is the APEX https://dhanboost.com/login — there is NO "www.". Do not
reintroduce it; the URL is checked char-for-char.

STATUS LEGEND
-----------------------------------------------------
[ACTIVE]  approved by STPL on 2026-07-31, content frozen — do NOT edit these bodies.
[WIP]     rejected 2026-07-31 for sounding promotional, rewritten and re-submitted 2026-08-01,
          awaiting operator approval. Rewrite = "Dear <name>," opener + the borrower's own
          application/loan id + no invitation to transact. Details in DLT_SUBMISSION_TRACKER.md.

-------------------------------------------------------
1. Template Name: DHANBOOST_OTP_LOGIN_V1                             [ACTIVE]
   DLT Template ID: <PENDING>
   Content: Your OTP for DhanBoost login is ##Field##. It is valid for ##Field## minutes. Do not share this OTP with anyone. - DhanBoost

2. Template Name: DHANBOOST_KYC_APPROVED_V1                          [WIP]
   DLT Template ID: <PENDING>
   Content: Dear ##Field##, your KYC for DhanBoost application ##Field## is verified. Check status at https://dhanboost.com/login. - DhanBoost

3. Template Name: DHANBOOST_KYC_REJECTED_V1                          [WIP]
   DLT Template ID: <PENDING>
   Content: Dear ##Field##, your KYC for DhanBoost application ##Field## could not be verified. Re-submit documents at https://dhanboost.com/login. - DhanBoost

4. Template Name: DHANBOOST_KYC_REMINDER_V1                          [WIP]
   DLT Template ID: <PENDING>
   Content: Dear ##Field##, verification steps on your DhanBoost application ##Field## are pending. Complete them at https://dhanboost.com/login. - DhanBoost

5. Template Name: DHANBOOST_LOAN_DISBURSED_V1                        [WIP]
   DLT Template ID: <PENDING>
   Content: Dear ##Field##, DhanBoost has credited ##Field## to your bank a/c. Repay ##Field## by ##Field## at https://dhanboost.com/login. - DhanBoost

6. Template Name: DHANBOOST_REPAYMENT_VERIFIED_V1                    [ACTIVE]
   DLT Template ID: <PENDING>
   Content: Your payment of ##Field## to DhanBoost is confirmed. Outstanding balance is ##Field##. View details at https://dhanboost.com/login. - DhanBoost

7. Template Name: DHANBOOST_REPAYMENT_REJECTED_V1                    [ACTIVE]
   DLT Template ID: <PENDING>
   Content: Your payment of ##Field## could not be verified by DhanBoost. Log in at https://dhanboost.com/login to check and record it again. - DhanBoost

8. Template Name: DHANBOOST_PAYMENT_DUE_SOON_V1                      [WIP]
   DLT Template ID: <PENDING>
   Content: Dear ##Field##, repayment of ##Field## on your DhanBoost loan ##Field## is due on ##Field##. Pay at https://dhanboost.com/login. - DhanBoost

9. Template Name: DHANBOOST_PAYMENT_OVERDUE_V1                       [WIP]
   DLT Template ID: <PENDING>
   Content: Dear ##Field##, repayment of ##Field## on your DhanBoost loan ##Field## is overdue by ##Field## day(s). Pay at https://dhanboost.com/login. - DhanBoost

10. Template Name: DHANBOOST_LOAN_CLOSED_V1                          [ACTIVE]
    DLT Template ID: <PENDING>
    Content: Your loan with DhanBoost is fully repaid and closed. Thank you. Visit https://dhanboost.com/login to borrow again. - DhanBoost

11. Template Name: DHANBOOST_APPLICATION_DECLINED_V1                 [WIP]
    DLT Template ID: <PENDING>
    Content: Dear ##Field##, your DhanBoost loan application ##Field## could not be approved at this time. Details at https://dhanboost.com/login. - DhanBoost
    Note: ONE template, TWO NotificationTypes — its single id maps to both CREDIT_REJECTED and REBORROW_REVIEW_REJECTED.

12. Template Name: DHANBOOST_SETTLEMENT_APPROVED_V1                  [WIP]
    DLT Template ID: <PENDING>
    Content: Dear ##Field##, a full and final settlement of ##Field## is approved on DhanBoost loan ##Field##. Pay at https://dhanboost.com/login. - DhanBoost

13. Template Name: DHANBOOST_REBORROW_APPROVED_V1                    [WIP]
    DLT Template ID: <PENDING>
    Content: Dear ##Field##, your DhanBoost application ##Field## is approved. Complete the remaining steps at https://dhanboost.com/login. - DhanBoost

14. Template Name: DHANBOOST_REBORROW_PREAPPROVED_V1                 [ACTIVE, registered PROMOTIONAL]
    DLT Template ID: <PENDING>
    Content: Welcome back to DhanBoost. You can apply for another loan now. Log in at https://dhanboost.com/login to choose your amount. - DhanBoost
    Note: Registered as Promotional (Service Explicit no longer exists on the portal). Promotional is
    NOT delivered to DND-registered numbers and needs a promotional route, not route 02.

15. Template Name: DHANBOOST_REFERRAL_REWARD_CREDITED_V1             [ACTIVE, registered PROMOTIONAL]
    DLT Template ID: <PENDING>
    Content: Your DhanBoost referral reward of ##Field## is credited with reference ##Field##. Log in at https://dhanboost.com/login to view it. - DhanBoost
    Note: Same Promotional/DND caveat as #14.
=======================================================

SUPERSEDED: the NAVIX_*_V2 batch
-----------------------------------------------------
Retired by the rebrand. Sender NAVIXF, brand "NAVIX Finance", url https://www.navixfinance.com/login.
The 15 assigned ids are kept in DLT_SUBMISSION_TRACKER.md for audit only. Do NOT send against them —
the app no longer produces text that matches them. Historical send results are in TEMPLATE_TEST_RESULTS.md.
=======================================================
END OF DOCUMENTATION
