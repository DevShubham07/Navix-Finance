-- V45 — Phase 2: delete KYC_APPROVER, add the SANCTIONED state and the credit-sanction columns.
--
-- The four-actor credit chain (KYC Approver → Credit Head → Credit Executive → Credit Head)
-- collapses to two: the Credit Head assigns, the Credit Executive decides, and that decision is
-- FINAL — it sanctions an amount + repayment date and hands straight to disbursement. See
-- revamp.md Phase 2 (decisions 2, 27, 28, 29, 30, 31, 33).

-- 1. KYC_APPROVER is gone. Existing holders become CREDIT_EXECUTIVEs — the role that absorbed
--    their work — so nobody is locked out. Runs BEFORE the CHECK is tightened.
update staff_user   set role = 'CREDIT_EXECUTIVE' where role = 'KYC_APPROVER';
update staff_invite set role = 'CREDIT_EXECUTIVE' where role = 'KYC_APPROVER';

alter table staff_user   drop constraint if exists staff_user_role_check;
alter table staff_invite drop constraint if exists staff_invite_role_check;

alter table staff_user add constraint staff_user_role_check check (role in (
    'CREDIT_EXECUTIVE','CREDIT_HEAD','DISBURSEMENT_HEAD','ACCOUNTANT',
    'COLLECTION_HEAD','COLLECTION_EXECUTIVE','TELECALLER','ADMIN','DEVELOPER'));

alter table staff_invite add constraint staff_invite_role_check check (role in (
    'CREDIT_EXECUTIVE','CREDIT_HEAD','DISBURSEMENT_HEAD','ACCOUNTANT',
    'COLLECTION_HEAD','COLLECTION_EXECUTIVE','TELECALLER','ADMIN','DEVELOPER'));

-- 2. SANCTIONED — the borrower's Phase-3 journey (DigiLocker → references → selfie → eSign →
--    disbursal account) sits between the credit decision and disbursement, so the credit
--    approval can no longer route straight to DISBURSEMENT_PENDING.
--    CREDIT_HEAD_PENDING / CREDIT_HEAD_APPROVED / REVIEW_PENDING stay in the vocabulary for
--    historical rows but leave the live path.
alter table loan_application drop constraint if exists loan_application_status_check;

alter table loan_application
    add constraint loan_application_status_check check (status in (
        'DRAFT','KYC_PENDING','KYC_APPROVED','KYC_REJECTED','PRE_APPROVED','REVIEW_PENDING',
        'CREDIT_EXEC_PENDING','CREDIT_EXEC_APPROVED','CREDIT_HEAD_PENDING','CREDIT_HEAD_APPROVED',
        'SANCTIONED','DISBURSEMENT_PENDING','ACCOUNTANT_PENDING','DISBURSEMENT_FAILED','DISBURSED',
        'ACTIVE','OVERDUE','DEFAULTED','CLOSED','WRITTEN_OFF','REJECTED','CANCELLED'));

-- 3. The sanction the Credit Executive writes. `sanctioned_amount_paise` is the CEILING the
--    borrower may draw down from in Phase 3 (they may take less) — distinct from
--    `amount_requested`, which stays whatever they eventually ask for.
alter table loan_application
    add column if not exists sanctioned_amount_paise bigint,
    add column if not exists approved_repayment_date date,
    add column if not exists sanction_tenure_days    integer,
    add column if not exists sanction_remarks        text,
    add column if not exists sanctioned_by           bigint,
    add column if not exists sanctioned_at           timestamptz,
    -- "Mark lead pending" is a tag + reason only: the lead stays in the queue and the borrower is
    -- never told (decision 30), so this deliberately has no matching status.
    add column if not exists marked_pending_at       timestamptz,
    add column if not exists pending_reason          text;
