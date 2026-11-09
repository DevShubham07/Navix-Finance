-- Every template gets a category (what it is for) and a plain label, so the Templates page can lead with
-- collections and repayment. Also saves the two discount wordings V98 left blank.

alter table message_template
    add column label   varchar(120),
    add column purpose varchar(24) not null default 'OTHER'
        check (purpose in ('COLLECTIONS', 'REPAYMENT', 'DISCOUNTS', 'LOAN_UPDATES', 'KYC', 'REFERENCES', 'OTHER'));

update message_template t set label = v.label, purpose = v.purpose
from (values
    ('db_overdue_soft_d7_v1_164', 'Gentle reminder, 7 days', 'COLLECTIONS'),
    ('db_overdue_soft_d15_v1_164', 'Reminder, 15 days', 'COLLECTIONS'),
    ('db_overdue_soft_d30_v1_164', 'Reminder, 30 days', 'COLLECTIONS'),
    ('db_overdue_strict_d7_v1_164', 'Firm reminder, 7 days', 'COLLECTIONS'),
    ('db_overdue_strict_d15_v1_164', 'Firm reminder, 15 days', 'COLLECTIONS'),
    ('db_overdue_strict_d30_v1_164', 'Firm reminder, 30 days', 'COLLECTIONS'),
    ('db_reported_soft_v1_164', 'Reported to credit bureaus', 'COLLECTIONS'),
    ('db_reported_strict_v1_164', 'Reported to credit bureaus, firm', 'COLLECTIONS'),
    ('db_cost_clock_v1_164', 'Daily cost of waiting', 'COLLECTIONS'),
    ('db_eligibility_window_v1_164', 'Keep your eligibility', 'COLLECTIONS'),
    ('db_salary_day_v1_164', 'Salary day reminder', 'COLLECTIONS'),
    ('db_officer_assigned_v1_164', 'Officer assigned', 'COLLECTIONS'),
    ('db_no_response_v1_164', 'No response', 'COLLECTIONS'),
    ('db_senior_review_v1_164', 'Senior review', 'COLLECTIONS'),
    ('db_notice_issued_v1_164', 'Formal notice issued', 'COLLECTIONS'),
    ('db_visit_notice_v1_164', 'Visit notice', 'COLLECTIONS'),
    ('db_payment_due_soon', 'Payment due soon', 'REPAYMENT'),
    ('db_payment_overdue', 'Payment overdue', 'REPAYMENT'),
    ('db_ptp_confirmation_v1_164', 'Promise to pay, confirmed', 'REPAYMENT'),
    ('db_ptp_missed_v1_164', 'Promise to pay, missed', 'REPAYMENT'),
    ('db_part_payment_v1_164', 'Part payment', 'REPAYMENT'),
    ('db_settlement_offer_v1_164', 'Settlement offer', 'REPAYMENT'),
    ('db_repay_discount_v1_164', 'Repay now, save on next loan', 'DISCOUNTS'),
    ('db_repay_discount_review_v1_164', 'Repay now, save on next loan (late)', 'DISCOUNTS'),
    ('db_reference_added_v1_164', 'Reference notice', 'REFERENCES'),
    ('DHANBOOST_OVERDUE_STATUS_V1', 'Overdue status', 'COLLECTIONS'),
    ('DHANBOOST_OVERDUE_SOFT_D7_V1', 'Gentle reminder, 7 days', 'COLLECTIONS'),
    ('DHANBOOST_OVERDUE_SOFT_D15_V1', 'Reminder, 15 days', 'COLLECTIONS'),
    ('DHANBOOST_OVERDUE_SOFT_D30_V1', 'Reminder, 30 days', 'COLLECTIONS'),
    ('DHANBOOST_OVERDUE_STRICT_D7_V1', 'Firm reminder, 7 days', 'COLLECTIONS'),
    ('DHANBOOST_OVERDUE_STRICT_D15_V1', 'Firm reminder, 15 days', 'COLLECTIONS'),
    ('DHANBOOST_OVERDUE_STRICT_D30_V1', 'Firm reminder, 30 days', 'COLLECTIONS'),
    ('DHANBOOST_REPORTED_SOFT_V1', 'Reported to credit bureaus', 'COLLECTIONS'),
    ('DHANBOOST_REPORTED_STRICT_V1', 'Reported to credit bureaus, firm', 'COLLECTIONS'),
    ('DHANBOOST_REFERENCE_OTP_V2', 'Reference OTP', 'REFERENCES')
) as v(name, label, purpose)
where t.name = v.name;

update message_template set purpose = 'DISCOUNTS' where name like 'DHANBOOST_REPAY_FEE%';
update message_template set purpose = 'REPAYMENT'
    where channel = 'WHATSAPP' and name in ('db_payment_recorded', 'db_repayment_verified', 'db_repayment_rejected', 'db_settlement_approved');
update message_template set purpose = 'KYC'
    where channel = 'WHATSAPP' and name in ('db_kyc_approved', 'db_kyc_rejected', 'db_kyc_reminder', 'db_back_in_review');
update message_template set purpose = 'LOAN_UPDATES'
    where channel = 'WHATSAPP' and name in ('db_loan_sanctioned', 'db_loan_disbursed', 'db_loan_closed', 'db_amount_revised',
        'db_limit_increased', 'db_disbursal_declined', 'db_sanction_letter_signed', 'db_application_declined',
        'db_application_cancelled', 'db_reborrow_approved', 'db_reborrow_preapproved', 'db_referral_reward');

update message_template
set body = $t$Dear {{1}}, repay your DhanBoost loan {{2}} in full by {{3}} and receive a discount equal to {{4}}% of the loan amount on the processing fee of your next approved DhanBoost loan. You can pay using the button below.$t$
where channel = 'WHATSAPP' and name = 'db_repay_discount_v1_164' and body is null;

update message_template
set body = $t$Dear {{1}}, repay your DhanBoost loan {{2}} in full by {{3}} and receive a discount equal to {{4}}% of the loan amount on the processing fee of your next approved DhanBoost loan. As this repayment is late, a new application will first need a review by our team with supporting documents. Please call 8510028510 for details. You can pay using the button below.$t$
where channel = 'WHATSAPP' and name = 'db_repay_discount_review_v1_164' and body is null;
