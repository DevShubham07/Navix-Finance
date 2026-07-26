-- V40 — Collision guard for the mobile-derived customer id.
--
-- AuthController.deriveCustomerId keeps only the LAST 7 DIGITS of a 10-digit mobile, so two
-- different borrowers whose numbers share those 7 digits collapse to ONE customerId — and therefore
-- one account, one JWT subject, one borrower_credential row. That is a silent merge of two people's
-- loans and PII, with no attacker involved. At ~10k borrowers a handful of colliding pairs is the
-- expected outcome, not a tail risk.
--
-- This registry records which mobile first claimed each id, so the second one fails LOUDLY at login
-- (CUSTOMER_ID_COLLISION) instead of merging.
--
-- A GUARD, not a re-key: the derivation itself is unchanged. Re-keying to a surrogate customer id
-- is the real fix and is a separate, migration-heavy piece of work.
--
-- Deliberately distinct from customer_profile.mobile, which is NON-unique by design: V12 added
-- global unique indexes on pan/aadhaar/mobile and V23 dropped them so a returning borrower could
-- re-onboard. That decision stands — its grain is one row per APPLICATION. The grain here is one
-- row per BORROWER, so the two do not conflict.
create table borrower_mobile (
    customer_id bigint      primary key,
    mobile      varchar(10) not null unique,
    created_at  timestamptz not null default now()
);

-- Backfill from the borrowers we already know about, so an existing account's number is the
-- claimant rather than whoever logs in first after this deploys. Where two stored mobiles already
-- collide, the most recent application wins the id and the other surfaces at its next login.
insert into borrower_mobile (customer_id, mobile)
select distinct on (right(mobile, 7)::bigint) right(mobile, 7)::bigint, mobile
from customer_profile
where mobile ~ '^[0-9]{10}$'
order by right(mobile, 7)::bigint, application_id desc
on conflict do nothing;
