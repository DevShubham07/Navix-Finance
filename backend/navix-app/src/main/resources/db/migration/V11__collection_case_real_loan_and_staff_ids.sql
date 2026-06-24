-- Bridge collections onto the real loans and real staff (FUTURE.md D2).
--
-- Until now collection_case.loan_id and the staff id columns were UUIDs on an
-- isolated island: they held random demo UUIDs with no real loan/staff behind
-- them. We re-point them at the real bigint ids (loan.id / staff_user.id).
--
-- There is no UUID->bigint cast and the existing demo rows reference nothing
-- real, so we discard them and retype the columns. No FK constraints are added
-- (indexes only — FK hardening is deferred, FUTURE.md D1); the child deletes
-- below keep things consistent in the absence of FKs.

delete from interaction_log;
delete from settlement;
delete from repayment_plan;
delete from collection_case;

-- collection_case: loan_id and assigned_officer_id -> bigint
drop index if exists idx_collection_case_loan_id;
alter table collection_case drop column loan_id;
alter table collection_case add column loan_id bigint not null;
alter table collection_case alter column assigned_officer_id type bigint using null::bigint;
create index idx_collection_case_loan_id on collection_case (loan_id);

-- settlement: proposer/approver -> real staff bigint ids
alter table settlement alter column proposed_by type bigint using null::bigint;
alter table settlement alter column approved_by type bigint using null::bigint;
