-- V55 — DSA (Direct Selling Agent) role + commission ledger.
--
-- External commission agents who bring leads but are firewalled from the platform: no customer
-- data, no pipeline, no other agent's leads. A DSA earns 3.5% of the net disbursed amount of
-- their lead's FIRST loan, payable only after the borrower has fully repaid. Follows V42
-- (role) + V43 (lead table) verbatim in style: no FKs, indexes only, timestamptz audit columns,
-- named check(... in (...)) constraints, guarded seed insert.

-- 1. Add the DSA role to the current (V45) role vocabulary.
alter table staff_user   drop constraint if exists staff_user_role_check;
alter table staff_invite drop constraint if exists staff_invite_role_check;

alter table staff_user add constraint staff_user_role_check check (role in (
    'CREDIT_EXECUTIVE','CREDIT_HEAD','DISBURSEMENT_HEAD','ACCOUNTANT',
    'COLLECTION_HEAD','COLLECTION_EXECUTIVE','TELECALLER','DSA','ADMIN','DEVELOPER'));

alter table staff_invite add constraint staff_invite_role_check check (role in (
    'CREDIT_EXECUTIVE','CREDIT_HEAD','DISBURSEMENT_HEAD','ACCOUNTANT',
    'COLLECTION_HEAD','COLLECTION_EXECUTIVE','TELECALLER','DSA','ADMIN','DEVELOPER'));

-- Demo DSA. Password: Admin@12345 — the same verified BCrypt hash V17 seeded onto every demo
-- persona (DEMO ONLY; rotate before real exposure). Guarded so a re-run is a no-op.
insert into staff_user (email, name, role, status, password_hash, created_at, created_by)
select 'arjun.rao@navix.example', 'Arjun Rao', 'DSA', 'ACTIVE',
       '$2a$10$exssI9R9G/cJdEzsk0Apmemf5x7pUWRYlrwVWsb3WOKoq4R31pq/W',
       now(), 'seed'
 where not exists (select 1 from staff_user where email = 'arjun.rao@navix.example');

-- 2. lead gains DSA ownership + PAN. Telecaller leads (no PAN, no owner_dsa_id) are unaffected.
alter table lead add column owner_dsa_id bigint;
alter table lead add column pan varchar(10);
alter table lead add constraint chk_lead_pan check (pan is null or pan ~ '^[A-Z]{5}[0-9]{4}[A-Z]$');
-- The cross-DSA duplicate guard: only one DSA lead may ever hold a given PAN.
create unique index uq_lead_dsa_pan on lead (pan) where owner_dsa_id is not null;
create index idx_lead_owner_dsa on lead (owner_dsa_id, id desc);

-- 3. dsa_commission — one row per (lead -> first loan). The unique index on lead_id is the
--    backstop that makes "only the first loan ever earns commission" unbreakable even if the
--    application-layer guard in DsaCommissionService has a bug.
create table dsa_commission (
    id                     bigserial primary key,
    dsa_staff_id           bigint      not null,
    lead_id                bigint      not null,
    customer_id            bigint      not null,
    application_id         bigint      not null,
    loan_id                bigint      not null,
    net_disbursed_paise    bigint      not null,
    rate_bps               integer     not null,
    amount_paise           bigint      not null,
    status                 varchar(16) not null default 'ACCRUED',
    accrued_at             timestamptz not null,
    payable_at             timestamptz,
    paid_at                timestamptz,
    txn_ref                varchar(64),
    paid_by                varchar(160),
    void_reason            varchar(240),
    voided_at              timestamptz,
    created_at             timestamptz not null,
    created_by             varchar(160),
    updated_at             timestamptz,
    updated_by             varchar(160),
    constraint chk_dsa_commission_status check (status in ('ACCRUED','PAYABLE','PAID','VOID'))
);
create unique index uq_dsa_commission_lead on dsa_commission (lead_id);
create index idx_dsa_commission_dsa_status on dsa_commission (dsa_staff_id, status, id desc);
create index idx_dsa_commission_loan on dsa_commission (loan_id);

-- 4. dsa_commission_event — append-only audit trail for every ADMIN override (pay/void/reassign/
--    manual create).
create table dsa_commission_event (
    id                   bigserial primary key,
    commission_id        bigint      not null,
    action                varchar(24) not null,
    from_dsa_staff_id     bigint,
    to_dsa_staff_id       bigint,
    actor_id              bigint,
    notes                 text,
    at                    timestamptz not null,
    constraint chk_dsa_commission_event_action check (
        action in ('PAID','VOIDED','REASSIGNED','CREATED_MANUALLY'))
);
create index idx_dsa_commission_event_commission on dsa_commission_event (commission_id, id);

-- 5. dsa_lead_rejection — every entry-time PAN rejection, so bulk PAN enumeration is visible to
--    ADMIN rather than silent; also backs the lead-creation rate limit.
create table dsa_lead_rejection (
    id             bigserial primary key,
    dsa_staff_id   bigint      not null,
    pan            varchar(10) not null,
    reason         varchar(24) not null,
    created_at     timestamptz not null,
    constraint chk_dsa_lead_rejection_reason check (reason in ('OTHER_DSA','EXISTING_CUSTOMER'))
);
create index idx_dsa_lead_rejection_dsa on dsa_lead_rejection (dsa_staff_id, created_at);

-- 6. lead_outreach — every SMS/email send (success or failure) the DSA makes to their lead.
create table lead_outreach (
    id             bigserial primary key,
    lead_id        bigint      not null,
    dsa_staff_id   bigint      not null,
    channel        varchar(8)  not null,
    address        varchar(160) not null,
    subject        varchar(240),
    body           text,
    status         varchar(16) not null,
    provider_ref   varchar(120),
    error          varchar(240),
    created_at     timestamptz not null,
    constraint chk_lead_outreach_channel check (channel in ('SMS','EMAIL'))
);
create index idx_lead_outreach_lead on lead_outreach (lead_id, id desc);
create index idx_lead_outreach_dsa on lead_outreach (dsa_staff_id, created_at);
