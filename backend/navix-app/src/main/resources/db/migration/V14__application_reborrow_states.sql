-- NAVIX Finance — returning-borrower (reborrow) lifecycle states.
--
-- A repeat borrower reuses their saved KYC profile and skips the fresh KYC + credit gates:
--   PRE_APPROVED   — cleared, awaiting amount; on apply routes straight to DISBURSEMENT_PENDING.
--   REVIEW_PENDING — a borrower with past delinquency held for KYC-approver re-review before
--                    they may proceed (→ PRE_APPROVED on approve, REJECTED on reject).
--
-- Only the status CHECK vocabulary changes — no new column/table (the "ever overdue" flag is
-- computed from loan history, and the disbursement fast-track section is derived on read).

alter table loan_application drop constraint if exists loan_application_status_check;

alter table loan_application
    add constraint loan_application_status_check check (status in (
        'DRAFT','KYC_PENDING','KYC_APPROVED','KYC_REJECTED','PRE_APPROVED','REVIEW_PENDING',
        'CREDIT_EXEC_PENDING','CREDIT_EXEC_APPROVED','CREDIT_HEAD_PENDING','CREDIT_HEAD_APPROVED',
        'DISBURSEMENT_PENDING','ACCOUNTANT_PENDING','DISBURSEMENT_FAILED','DISBURSED',
        'ACTIVE','OVERDUE','DEFAULTED','CLOSED','WRITTEN_OFF','REJECTED','CANCELLED'));
