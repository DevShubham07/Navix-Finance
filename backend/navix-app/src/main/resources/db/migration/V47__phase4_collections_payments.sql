-- V47 — Phase 4: collections-side payments, direct disbursement, re-apply carry-over.
--
-- Three things land here. See revamp.md Phase 4 (decisions 42, 43, 44, 45, 46, 47).
--
--  1. `collection_payment` — the row a Collection Executive (or Head) raises when a borrower in
--     collections pays. Part/full payments go straight to the Accountant; a settlement is approved
--     by the Collection Head first. The Accountant is the single checker either way.
--  2. `loan_application.reapplied_from` — which prior application a re-apply carried its sanction,
--     evidence and disbursal account over from, so staff can see the carry-over rather than
--     wondering why a brand-new file already has a DigiLocker row.
--  3. Nothing for the disbursement change: releasing directly with a txn id needs no new column,
--     only the required-txnRef rule in ApplicationFlowService.

-- 1. Collections payments -----------------------------------------------------------------
--
-- Deliberately its own table rather than a flag on `payment` (navix-loan): this is a
-- *collections* artefact with its own two-step approval, and it only becomes a ledger payment
-- once the Accountant validates it. `ledger_payment_id` is that link, written on validation.
--
-- `settlement_id` is set only for kind = 'SETTLEMENT' and points at the settlement the Collection
-- Head approved; the payment cannot reach the Accountant before that approval exists.
create table collection_payment (
    id                 uuid not null,
    collection_case_id uuid   not null,
    loan_id            bigint not null,
    -- PART_PAYMENT | FULL_PAYMENT | SETTLEMENT
    kind               varchar(20) not null,
    amount_paise       bigint not null,
    paid_on            date,
    txn_ref            varchar(120),
    -- Collections proof stays flexible (screenshot key / text / txn id) — an existing product rule.
    proof_ref          varchar(1000),
    settlement_id      uuid,
    -- PENDING_HEAD | PENDING_ACCOUNTANT | VALIDATED | REJECTED
    status             varchar(24) not null,
    raised_by          bigint not null,
    raised_at          timestamptz not null,
    validated_by       bigint,
    validated_at       timestamptz,
    -- The Accountant's remarks, notified back to the raising executive and the Collection Head.
    remarks            text,
    -- The `payment` row minted in the loan ledger when this was validated (null until then).
    ledger_payment_id  bigint,
    primary key (id),
    constraint collection_payment_kind_check
        check (kind in ('PART_PAYMENT', 'FULL_PAYMENT', 'SETTLEMENT')),
    constraint collection_payment_status_check
        check (status in ('PENDING_HEAD', 'PENDING_ACCOUNTANT', 'VALIDATED', 'REJECTED')),
    constraint collection_payment_amount_check check (amount_paise > 0)
);

-- The Accountant's queue reads by status; the case detail reads by case; the ledger view by loan.
create index idx_collection_payment_status on collection_payment (status, raised_at);
create index idx_collection_payment_case   on collection_payment (collection_case_id, raised_at desc);
create index idx_collection_payment_loan   on collection_payment (loan_id, raised_at desc);

-- A validated payment must credit the loan exactly once. The partial unique index lets an executive
-- re-raise a rejected payment with the same txn id without tripping over the dead row.
create unique index uq_collection_payment_live_txn
    on collection_payment (loan_id, txn_ref)
    where txn_ref is not null and status <> 'REJECTED';

-- 2. Re-apply provenance ------------------------------------------------------------------
alter table loan_application
    add column if not exists reapplied_from bigint;

comment on column loan_application.reapplied_from is
    'The prior application this re-apply carried its sanction, verifications, references and '
    'disbursal account over from (revamp.md decisions 45, 46). Null for a first-time borrower.';
