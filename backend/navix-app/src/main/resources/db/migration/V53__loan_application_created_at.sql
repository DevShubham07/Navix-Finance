-- The application aggregate has never carried a creation timestamp (§10). Staff queues stood in
-- id-ascending order as a proxy for arrival order; the live-applications table now shows a real
-- Date column and filters on it.
alter table loan_application add column created_at timestamptz;

-- Backfill from the append-only audit trail: the CREATE event is written inside createDraft's own
-- transaction, so its `at` IS the creation instant for every historical row.
update loan_application a
   set created_at = e.first_at
  from (select application_id, min(at) as first_at
          from application_event
         group by application_id) e
 where e.application_id = a.id
   and a.created_at is null;

-- Rows with no event at all (none expected; belt-and-braces so the NOT NULL below can't fail).
update loan_application set created_at = now() where created_at is null;

alter table loan_application alter column created_at set not null;
alter table loan_application alter column created_at set default now();

create index idx_loan_application_created_at on loan_application (created_at desc);
create index idx_loan_application_status_created_at on loan_application (status, created_at desc);
