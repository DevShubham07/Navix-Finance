-- V48 — retire the accountant hop from disbursement entirely.
--
-- V47 made the Disbursement Head release directly with a transaction id, but left
-- ACCOUNTANT_PENDING reachable so files already parked there could still be walked out. That
-- half-measure is now closed: the transfer is validated by the Head supplying its transaction id,
-- and there is no second desk behind them. `accountantValidate` and its endpoint are gone.
--
-- Two things follow.

-- 1. Move the files still parked at ACCOUNTANT_PENDING back onto the Disbursement Head's desk.
--    Deleting the code path without moving these would strand them permanently — the only actor
--    who could advance them would no longer have an action.
--
--    An append-only audit row goes with each move: the aggregate's whole design is that a status
--    never changes without a trace, and a migration is no excuse to break that.
--    actor_id is a NOT NULL varchar (it holds a staff id as text, or a system marker) — 'SYSTEM'
--    is the honest value here: no person made this decision, a schema change did.
insert into application_event (application_id, from_status, to_status, actor_id, actor_role, action, notes, at)
select id, 'ACCOUNTANT_PENDING', 'DISBURSEMENT_PENDING', 'SYSTEM', 'SYSTEM', 'MIGRATE_V48',
       'Accountant disbursement hop retired; returned to the Disbursement Head to release', now()
from loan_application
where status = 'ACCOUNTANT_PENDING';

update loan_application set status = 'DISBURSEMENT_PENDING' where status = 'ACCOUNTANT_PENDING';

-- 2. ACCOUNTANT_PENDING stays in the status CHECK constraint and in the ApplicationStatus enum.
--    It is unreachable going forward, but `application_event` rows record it as a from/to status on
--    every loan that ever went through the old chain, and those must still parse. The vocabulary is
--    history; only the route is gone.

comment on column loan_application.status is
    'Canonical lifecycle state. ACCOUNTANT_PENDING and CREDIT_HEAD_* are historical only — no live '
    'transition reaches them (V45 retired the credit head hop, V48 the accountant disbursement hop).';
