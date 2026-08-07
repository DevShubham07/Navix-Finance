-- V46 — Phase 3 of the user-journey revamp (see revamp.md).
--
-- Between the Credit Executive's sanction (V45) and disbursement sits the borrower's own journey:
-- draw down an amount, lock the repayment date, DigiLocker, references, selfie, geo-address, read
-- the sanction letter, eSign, then confirm where the money lands. This migration adds what those
-- screens capture and the abuse control on the one step that costs money to run (penny drop).

-- 1. The disbursal account the borrower confirms on the last screen.
--    Kept on the application, NOT on customer_profile: profile.salary_account_* is where their
--    salary lands (captured at intake and used by credit to sanity-check the file), while this is
--    where THIS advance is paid. They're usually the same, and when they aren't, overwriting the
--    salary account would destroy the very thing credit verified against.
alter table loan_application add column disbursal_account_number varchar(32);
alter table loan_application add column disbursal_ifsc           varchar(16);
alter table loan_application add column disbursal_holder_name    varchar(160);
alter table loan_application add column disbursal_bank           varchar(120);
-- True when the borrower typed an account other than their salary account, which is the ONLY case
-- that fires a penny drop (revamp.md decision 9). False means the money goes to an account number
-- that was never externally verified — deliberate, and visible here rather than inferred.
alter table loan_application add column disbursal_account_changed boolean not null default false;
alter table loan_application add column disbursal_account_verified boolean not null default false;
alter table loan_application add column disbursal_confirmed_at    timestamptz;

comment on column loan_application.disbursal_account_number is
    'Where THIS advance is paid, stored in full (revamp.md decision 16). Never logged, never exported.';
comment on column loan_application.disbursal_account_verified is
    'Penny-drop confirmed. False on the unchanged-salary-account path, which never runs one (decision 9).';

-- 2. References captured on /loan/references — two contacts, one family + one work in practice,
--    though the relation list is open. Capture only: deliberately NOT wired to the referral
--    rewards program (revamp.md decision 39), which keys on referral_code, not on these rows.
create table application_reference (
    id             bigserial   primary key,
    application_id bigint      not null,
    customer_id    bigint      not null,
    slot           smallint    not null,   -- 1 or 2, the position on the form
    full_name      varchar(160) not null,
    mobile         varchar(15) not null,
    relation       varchar(32) not null,   -- PARENT | SPOUSE | SIBLING | RELATIVE | FRIEND | COLLEAGUE | MANAGER | NEIGHBOUR
    created_at     timestamptz not null,
    created_by     varchar(160),
    updated_at     timestamptz,
    updated_by     varchar(160)
);
-- One row per slot per application: re-submitting the form updates in place rather than piling up.
create unique index uq_application_reference_slot on application_reference (application_id, slot);
create index idx_application_reference_customer on application_reference (customer_id);

-- 3. Penny-drop abuse control (revamp.md decision 40): 3 failures per borrower → a 12-hour lock →
--    3 more. Every attempt is recorded, so the count is auditable rather than a bare counter, and
--    the lock is a separate singleton row per customer that a success clears.
create table penny_drop_attempt (
    id             bigserial   primary key,
    customer_id    bigint      not null,
    application_id bigint,
    account_number varchar(32),
    ifsc           varchar(16),
    succeeded      boolean     not null,
    failure_reason varchar(300),
    created_at     timestamptz not null,
    created_by     varchar(160),
    updated_at     timestamptz,
    updated_by     varchar(160)
);
-- The "failures since the last success/lock" scan runs on every attempt.
create index idx_penny_drop_attempt_customer on penny_drop_attempt (customer_id, id desc);

create table customer_penny_drop_lock (
    id           bigserial   primary key,
    customer_id  bigint      not null,
    locked_until timestamptz not null,
    failures     integer     not null default 0,
    created_at   timestamptz not null,
    created_by   varchar(160),
    updated_at   timestamptz,
    updated_by   varchar(160)
);
-- One lock row per customer (the surrogate id is only so the entity matches every other one here).
create unique index uq_customer_penny_drop_lock on customer_penny_drop_lock (customer_id);

comment on table customer_penny_drop_lock is
    'Singleton per customer. locked_until in the past means the lock has expired and the next 3
     failures start a fresh window; a successful penny drop deletes the row outright.';
