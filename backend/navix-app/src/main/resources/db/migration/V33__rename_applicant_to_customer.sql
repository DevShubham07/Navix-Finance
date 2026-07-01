-- V33 — Rename the durable per-person concept "applicant" -> "customer" across the schema.
-- The id VALUE is unchanged (still the mobile-derived id); only column/table/index NAMES change.
-- The guarantor concept `co_applicant` (keyed by borrower_id) is DELIBERATELY left untouched.
-- No FK constraints exist (indexes only, per V11/FUTURE.md), so RENAME COLUMN is safe.

-- 1. Per-person id column: applicant_id -> customer_id
ALTER TABLE income_profile       RENAME COLUMN applicant_id TO customer_id;
ALTER TABLE risk_assessment      RENAME COLUMN applicant_id TO customer_id;
ALTER TABLE loan                 RENAME COLUMN applicant_id TO customer_id;
ALTER TABLE loan_application     RENAME COLUMN applicant_id TO customer_id;
ALTER TABLE profile_change_log   RENAME COLUMN applicant_id TO customer_id;
ALTER TABLE borrower_preferences RENAME COLUMN applicant_id TO customer_id;
ALTER TABLE referral_code        RENAME COLUMN applicant_id TO customer_id;

-- referral / referral_payout carry role-qualified id columns
ALTER TABLE referral         RENAME COLUMN referrer_applicant_id     TO referrer_customer_id;
ALTER TABLE referral         RENAME COLUMN referred_applicant_id     TO referred_customer_id;
ALTER TABLE referral_payout  RENAME COLUMN beneficiary_applicant_id  TO beneficiary_customer_id;
ALTER TABLE referral_payout  RENAME COLUMN counterparty_applicant_id TO counterparty_customer_id;

-- 2. The per-application KYC snapshot table: applicant_profile -> customer_profile
ALTER TABLE applicant_profile RENAME TO customer_profile;

-- 3. Rename the indexes/constraints whose NAMES embed "applicant" (IF EXISTS = resilient).
ALTER INDEX IF EXISTS idx_income_profile_applicant_id    RENAME TO idx_income_profile_customer_id;
ALTER INDEX IF EXISTS idx_risk_assessment_applicant_id   RENAME TO idx_risk_assessment_customer_id;
ALTER INDEX IF EXISTS idx_loan_applicant_id              RENAME TO idx_loan_customer_id;
ALTER INDEX IF EXISTS idx_loan_application_applicant_id  RENAME TO idx_loan_application_customer_id;
ALTER INDEX IF EXISTS idx_profile_change_log_applicant   RENAME TO idx_profile_change_log_customer;
ALTER INDEX IF EXISTS uq_referral_code_applicant         RENAME TO uq_referral_code_customer;
ALTER INDEX IF EXISTS ix_applicant_profile_pan           RENAME TO ix_customer_profile_pan;
ALTER INDEX IF EXISTS ix_applicant_profile_aadhaar       RENAME TO ix_customer_profile_aadhaar;
ALTER INDEX IF EXISTS ix_applicant_profile_mobile        RENAME TO ix_customer_profile_mobile;

-- The 1:1 uniqueness constraint on customer_profile(application_id)
ALTER TABLE customer_profile RENAME CONSTRAINT uq_applicant_profile_application TO uq_customer_profile_application;
