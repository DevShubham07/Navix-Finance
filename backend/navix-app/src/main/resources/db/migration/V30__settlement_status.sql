-- Settlement maker-checker status. Previously only proposed→approved was modelled (the proposer
-- set the row, an approver stamped approved_by/at); there was no way to explicitly REJECT a
-- proposed settlement (see the TODO that was on the Settlement entity). Add an explicit status
-- plus rejected_by / rejected_at audit columns mirroring the approve columns. Existing rows with
-- an approver backfill to APPROVED; everything else stays PROPOSED.
alter table settlement
    add column status      varchar(16) not null default 'PROPOSED',
    add column rejected_by bigint,
    add column rejected_at timestamp(6) with time zone;

update settlement set status = 'APPROVED' where approved_by is not null;

alter table settlement
    add constraint settlement_status_check check (status in ('PROPOSED', 'APPROVED', 'REJECTED'));
