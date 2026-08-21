-- V60 — index the call logs by (who, when), for the staff-performance "calls" metric.
--
-- V59 added customer_call_log.created_by_staff_id and interaction_log.logged_by_staff_id but no
-- index on either, and neither table had one on its timestamp. The dashboard groups both tables by
-- staff id over a date window on every load, which without these is a sequential scan of every call
-- ever logged.
--
-- Column order is (staff, time) rather than (time, staff): the roster is always a bounded IN-list of
-- staff ids, so leading with it is the selective half — the same shape as V59's
-- idx_application_event_actor_at.

create index if not exists idx_customer_call_log_staff_at
    on customer_call_log (created_by_staff_id, created_at);

create index if not exists idx_interaction_log_staff_at
    on interaction_log (logged_by_staff_id, logged_at);
