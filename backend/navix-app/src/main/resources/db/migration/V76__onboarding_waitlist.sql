-- V76 — pause new-customer onboarding ("waitlist mode").
--
-- Collections are not keeping up, so the business stops taking on new customers for a while
-- without taking the app away from existing borrowers. While `onboarding-paused` is ON, a
-- borrower who has never held a loan is not walked through intake (no Signzy / Digitap / Fintrix
-- spend): after OTP login they fill one plain form — name, email, mobile, PAN, Aadhaar — which lands
-- in `onboarding_waitlist`, and from then on they see "your application is under review". Anyone
-- with a loan (repaid or live) keeps the normal app, reborrow included. Staff queues are untouched.
--
-- Seeded ON on purpose: the pause is meant to start the moment this deploy lands. End it with
--     update feature_flag set enabled = false where flag_key = 'onboarding-paused';
-- Read with defaultWhenMissing=FALSE, so deleting the row re-opens onboarding (fail open —
-- the flag withholds a product, it never moves money).
insert into feature_flag (flag_key, enabled, description, created_at)
values ('onboarding-paused', true,
        'Pause new-customer onboarding. ON: borrowers without a loan get the waitlist form instead '
            || 'of intake, POST /api/applications refuses ONBOARDING_PAUSED, Admin sees the waitlist.',
        now())
on conflict (flag_key) do nothing;

-- One row per customer (customer_id is derived from the mobile, so this is one row per mobile).
-- Identifiers are stored as typed, unverified — nothing here has been through a vendor check.
create table onboarding_waitlist (
    id          bigserial primary key,
    customer_id bigint       not null unique,
    mobile      varchar(10)  not null,
    full_name   varchar(160) not null,
    email       varchar(200) not null,
    pan         varchar(10)  not null,
    aadhaar     varchar(12)  not null,
    created_at  timestamptz  not null default now(),
    created_by  varchar(64),
    updated_at  timestamptz,
    updated_by  varchar(64)
);
create index idx_onboarding_waitlist_pan on onboarding_waitlist (pan);
create index idx_onboarding_waitlist_aadhaar on onboarding_waitlist (aadhaar);
