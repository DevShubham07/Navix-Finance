-- V37 — Backfill loan_application.eligible_limit to the restored 25%-of-salary rule.
--
-- Product decision: the eligible limit is 25% of monthly salary, floored to the nearest ₹100 and
-- capped at the ₹10,00,000 instant ceiling (LoanMath / LimitCalculator). This recomputes the stored
-- limit ONLY for PRE-APPLY rows — applications that have not yet had an amount chosen — so applied /
-- active / closed loans keep the limit they were sanctioned under and are left untouched.
--
-- customer_profile is the per-application KYC snapshot (1:1 on application_id, renamed from
-- applicant_profile in V33), so the join is customer_profile.application_id = loan_application.id.
--
-- LEAST((monthly_salary_paise / 4) / 10000 * 10000, 100000000): integer division floors 25% of
-- salary to a ₹100 (10,000-paise) multiple, then caps it at ₹10,00,000 (100,000,000 paise).
--
-- NOTE: the task brief named this migration V35, but V35 (drop_customer_profile_aadhaar) and V36
-- (customer_remark) already exist on this branch, so it is created as V37 to keep Flyway versions
-- unique. The description suffix (eligible_limit_25pct_backfill) is unchanged.

UPDATE loan_application la
SET eligible_limit = LEAST((cp.monthly_salary_paise / 4) / 10000 * 10000, 100000000)
FROM customer_profile cp
WHERE cp.application_id = la.id
  AND cp.monthly_salary_paise IS NOT NULL
  AND la.amount_requested IS NULL
  AND la.status IN ('DRAFT', 'KYC_PENDING', 'KYC_APPROVED', 'PRE_APPROVED', 'REVIEW_PENDING');
