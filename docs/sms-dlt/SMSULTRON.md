DHANBOOST - SMS TEMPLATE DOCUMENTATION (UltronSMS)
=======================================================
Brand (in message body): DhanBoost
Legal entity (DLT registration): NAVIX FINANCE PRIVATE LIMITED (CIN U64990HR2026PTC144926)
Source: STPL / smartping.live
Destination: https://ultronsms.com/Web/MT/MyTemplate.aspx
Sender ID (all templates): DHANBT
PE-ID (entity-level, constant): 1701178039634361131
Total templates: 15 (status: PENDING SUBMISSION — DhanBoost _V1 batch, no DLT ids assigned yet)
Note: Dynamic values (amounts, day counts, dates, OTP codes, reference numbers) are represented as ##Field## placeholders.

WHY THE IDS ARE BLANK
-----------------------------------------------------
A DLT Template ID is bound to its exact registered content. The NAVIX -> DhanBoost rebrand changed the
brand string AND the URL in every body, so all 15 previously-assigned _V2 ids are INVALID — including
1707178366195230667 (NAVIX_OTP_LOGIN_V2), which was the one template that had reached full DLT approval
and was sending live. The backend already ships the DhanBoost wording (NotificationTemplates.java and
application.yml navix.sms.otp-template), so the code and the old registrations no longer agree: sending
against a _V2 id today returns "006 Invalid template text". This batch must be re-registered from scratch.

BLOCKERS BEFORE SUBMISSION (in order)
-----------------------------------------------------
1. Register the domain dhanboost.com. As of 2026-07-31 it does not exist; every non-OTP template links to it.
2. Register "DhanBoost" as a brand/trademark under NAVIX FINANCE PRIVATE LIMITED on the DLT portal.
   Without this, submissions fail with "Entity brand name is not mentioned in the SMS content".
3. Register/activate the 6-char header DHANBT under the entity (replaces the retired NAVIXF).
4. URL-whitelist https://www.dhanboost.com/login under the entity, char-for-char.
Only then submit the 15 templates below.

-------------------------------------------------------
1. Template Name: DHANBOOST_OTP_LOGIN_V1
   DLT Template ID: <PENDING>
   Content: Your OTP for DhanBoost login is ##Field##. It is valid for ##Field## minutes. Do not share this OTP with anyone. - DhanBoost

2. Template Name: DHANBOOST_KYC_APPROVED_V1
   DLT Template ID: <PENDING>
   Content: Your KYC is verified with DhanBoost. Log in at https://www.dhanboost.com/login to choose your loan amount. - DhanBoost

3. Template Name: DHANBOOST_KYC_REJECTED_V1
   DLT Template ID: <PENDING>
   Content: We could not verify your KYC with DhanBoost. Log in at https://www.dhanboost.com/login to review and resubmit. - DhanBoost

4. Template Name: DHANBOOST_KYC_REMINDER_V1
   DLT Template ID: <PENDING>
   Content: Your verification with DhanBoost is incomplete. Log in at https://www.dhanboost.com/login to complete your pending steps. - DhanBoost

5. Template Name: DHANBOOST_LOAN_DISBURSED_V1
   DLT Template ID: <PENDING>
   Content: DhanBoost has disbursed Rs. ##Field## to your bank account. Repay Rs. ##Field## by ##Field## at https://www.dhanboost.com/login. - DhanBoost

6. Template Name: DHANBOOST_REPAYMENT_VERIFIED_V1
   DLT Template ID: <PENDING>
   Content: Your payment of Rs. ##Field## to DhanBoost is confirmed. Outstanding balance is Rs. ##Field##. View details at https://www.dhanboost.com/login. - DhanBoost

7. Template Name: DHANBOOST_REPAYMENT_REJECTED_V1
   DLT Template ID: <PENDING>
   Content: Your payment of Rs. ##Field## could not be verified by DhanBoost. Log in at https://www.dhanboost.com/login to check the reference and record it again. - DhanBoost

8. Template Name: DHANBOOST_PAYMENT_DUE_SOON_V1
   DLT Template ID: <PENDING>
   Content: Your DhanBoost payment of Rs. ##Field## is due in ##Field## day(s) by ##Field##. Pay at https://www.dhanboost.com/login on or after your salary day with no penalty. - DhanBoost

9. Template Name: DHANBOOST_PAYMENT_OVERDUE_V1
   DLT Template ID: <PENDING>
   Content: Your DhanBoost payment of Rs. ##Field## is overdue by ##Field## day(s). Pay now at https://www.dhanboost.com/login to stop the daily penalty and protect your credit score. - DhanBoost

10. Template Name: DHANBOOST_LOAN_CLOSED_V1
    DLT Template ID: <PENDING>
    Content: Your loan with DhanBoost is fully repaid and closed. Thank you. Visit https://www.dhanboost.com/login to borrow again. - DhanBoost

11. Template Name: DHANBOOST_APPLICATION_DECLINED_V1
    DLT Template ID: <PENDING>
    Content: DhanBoost is unable to approve your loan application at this time. Visit https://www.dhanboost.com/login for details. - DhanBoost
    Note: ONE template, TWO NotificationTypes — its single id maps to both CREDIT_REJECTED and REBORROW_REVIEW_REJECTED.

12. Template Name: DHANBOOST_SETTLEMENT_APPROVED_V1
    DLT Template ID: <PENDING>
    Content: A full and final settlement of Rs. ##Field## is approved on your DhanBoost loan. Pay at https://www.dhanboost.com/login to close the loan. - DhanBoost

13. Template Name: DHANBOOST_REBORROW_APPROVED_V1
    DLT Template ID: <PENDING>
    Content: Your loan application with DhanBoost is approved. Log in at https://www.dhanboost.com/login to choose your amount. - DhanBoost

14. Template Name: DHANBOOST_REBORROW_PREAPPROVED_V1
    DLT Template ID: <PENDING>
    Content: Welcome back to DhanBoost. You can apply for another loan now. Log in at https://www.dhanboost.com/login to choose your amount. - DhanBoost
    Note: BORDERLINE — the _V2 equivalent was accepted only as Service EXPLICIT. Expect the same.

15. Template Name: DHANBOOST_REFERRAL_REWARD_CREDITED_V1
    DLT Template ID: <PENDING>
    Content: Your DhanBoost referral reward of Rs. ##Field## is credited with reference ##Field##. Log in at https://www.dhanboost.com/login to view it. - DhanBoost
    Note: BORDERLINE — the _V2 equivalent was accepted only as Service EXPLICIT. Expect the same.
=======================================================

SUPERSEDED: the NAVIX_*_V2 batch
-----------------------------------------------------
Retired by the rebrand. Sender NAVIXF, brand "NAVIX Finance", url https://www.navixfinance.com/login.
The 15 assigned ids are kept in DLT_SUBMISSION_TRACKER.md for audit only. Do NOT send against them —
the app no longer produces text that matches them. Historical send results are in TEMPLATE_TEST_RESULTS.md.
=======================================================
END OF DOCUMENTATION
