-- V70 — A lead's outreach outcome, and one staff note the uploading DSA can read (2026-09-15).
--
-- Two things the lead row could not express.
--
-- 1. OUTCOME. `call_status` looks like the right home and is not: its seven values are *call-attempt*
--    dispositions (NOT_CALLED .. CONNECTED), and `LeadStats.byCallStatus` plus buildByDay's "called"
--    metric (`call_status <> 'NOT_CALLED'`) are computed from it, so adding outcome values there would
--    silently move existing numbers. This is a separate axis: did the outreach go anywhere.
--
--    CONFIRMED is deliberately NOT in the CHECK. It is resolved at read time from the attributed
--    application (DsaAttributionService), because there is no stored lead -> application link to drive
--    a write: attribution is a PAN match computed on read. A listener writing CONFIRMED on every
--    application transition would have to redo that lookup on each one, and would drift the moment a
--    PAN changed. So the column holds only what a human sets, and CONFIRMED overlays it on read.
--    Consequence worth knowing: a lead with no PAN can never resolve to CONFIRMED.
--
-- 2. THE NOTE. `lead` already has `notes` and `remarks`, and neither can be reused. `remarks` is the
--    telecaller's private call commentary, written by people who had no idea an external commission
--    agent would ever read it — exposing it would leak internal notes on every lead already in this
--    table. `notes` is intake text of ambiguous authorship (a DSA writes it on their own leads, staff
--    write it elsewhere). `dsa_note` is new and empty, so nothing already written can leak through it,
--    and everyone writing into it is told in the UI who reads it.
--
-- `not null default 'NEW'` back-fills every existing row without a table rewrite (PG 11+).
-- No FKs, per this schema's convention; add both columns to the hand-cascade wherever leads are
-- deleted, and to the purge-prod-data skill.
alter table lead add column lead_outcome varchar(16) not null default 'NEW';
alter table lead add constraint chk_lead_outcome check (
    lead_outcome in ('NEW', 'OUTREACHED', 'REJECTED')
);

alter table lead add column dsa_note text;

-- The DSA portal's "leads I uploaded" read: owner_dsa_id = me OR created_by_staff_id = me, newest
-- first. V43's idx_lead_created_by is single-column and stays for the createdBy filter; this mirrors
-- V55's idx_lead_owner_dsa (owner, id desc) shape so the ordered list is served by an index too.
create index idx_lead_created_by_id on lead (created_by_staff_id, id desc);
