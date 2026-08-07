-- V44 — Phase 1 of the user-journey revamp (see revamp.md).
--
-- The 12-step onboarding wizard is replaced by a 10-screen intake that fires only three external
-- APIs, all on the consent screen. This migration adds what the new screens capture, the
-- server-side journey pointer that makes cross-device resume work, and the rejection register
-- that backs the self-employed auto-reject (and, from Phase 4, the past-delinquency rule).

-- 1. Server-authoritative journey position (revamp.md C1).
--    Resume was a localStorage breadcrumb, so a second device restarted the wizard. This column is
--    the durable pointer; JourneyService still cross-checks it against status + verification rows,
--    so a stale value can never send a borrower backwards past work they've already done.
alter table loan_application add column journey_step varchar(40);

-- 2. What the new intake screens capture.
alter table customer_profile add column official_email         varchar(255);
alter table customer_profile add column salary_account_number  varchar(32);
alter table customer_profile add column salary_ifsc            varchar(16);
alter table customer_profile add column salary_account_mobile  varchar(15);
alter table customer_profile add column previous_salary_date   date;
alter table customer_profile add column terms_version          varchar(40);
alter table customer_profile add column terms_accepted_at      timestamptz;
alter table customer_profile add column pep_declared_at        timestamptz;

comment on column customer_profile.email is
    'Personal email — the borrower''s contact address (approvals, reset links, statements).';
comment on column customer_profile.official_email is
    'Official work email — the only address the email-verification API is run against (it corroborates the employer).';
comment on column customer_profile.previous_salary_date is
    'The date the borrower was last paid. loan_application.salary_credit_day is derived from its day-of-month.';
comment on column customer_profile.salary_account_number is
    'Salary account number, stored in full (revamp.md decision 16). Never logged, never exported.';
comment on column customer_profile.pep_declared_at is
    'When the borrower declared they are NOT a Politically Exposed Person. The tick is mandatory, so a
     row that reached KYC_PENDING always has this set; there is no PEP-accepted path.';

-- 3. Rejection register — every rejection, automatic or manual, in one place for the admin view.
--    application_id is nullable: a blocked re-attempt is refused before a draft is ever created.
create table application_rejection (
    id             bigserial primary key,
    application_id bigint,
    customer_id    bigint      not null,
    mobile         varchar(15),
    reason_code    varchar(32) not null,   -- SELF_EMPLOYED | PAST_DELINQUENCY | MANUAL
    reason_detail  varchar(500),
    auto           boolean     not null default true,
    blocked_until  timestamptz,
    created_at     timestamptz not null,
    created_by     varchar(160),
    updated_at     timestamptz,
    updated_by     varchar(160)
);
create index idx_application_rejection_customer on application_rejection (customer_id);
create index idx_application_rejection_reason   on application_rejection (reason_code, id desc);
-- The cooling-off lookup runs on every new application, keyed on mobile (revamp.md decision 20).
create index idx_application_rejection_block
    on application_rejection (mobile, blocked_until)
    where blocked_until is not null;

-- 4. Wipe in-flight DRAFTs (revamp.md decision 3) — the old wizard's partial state cannot be mapped
--    onto the new 10 screens, so those borrowers restart. Their logins (borrower_credential,
--    borrower_mobile) are deliberately NOT deleted; only the abandoned application and its children.
--
--    IRREVERSIBLE, and DRAFT is exactly the `incomplete` segment the telecaller CRM surfaces as call
--    targets (lib/customers/segments.ts) — that list is emptied. Confirm the target database first.
--
--    Children are deleted before the parent so no orphans are left behind (the schema has indexes,
--    not FK constraints, so nothing cascades on our behalf).
delete from notification_delivery
 where notification_id in (
       select n.id from notification n
        where n.application_id in (select id from loan_application where status = 'DRAFT'));

delete from notification
 where application_id in (select id from loan_application where status = 'DRAFT');

delete from profile_change_log
 where application_id in (select id from loan_application where status = 'DRAFT');

delete from application_verification
 where application_id in (select id from loan_application where status = 'DRAFT');

delete from application_document
 where application_id in (select id from loan_application where status = 'DRAFT');

delete from application_event
 where application_id in (select id from loan_application where status = 'DRAFT');

delete from customer_profile
 where application_id in (select id from loan_application where status = 'DRAFT');

delete from loan_application where status = 'DRAFT';
