-- V59 — record WHICH staff member did the work, for the staff-performance dashboard.
--
-- Several kinds of staff work are currently unattributable. `BaseAuditEntity.created_by` looks like
-- an actor column but stores the actor's *display name* (AuditingConfig feeds it
-- ActorContext.get().name()) — mutable (a staffer can rename themselves via PUT /api/staff/me) and
-- not unique, so it can never key a per-employee aggregation. Real staff ids live only on
-- application_event.actor_id, settlement.*_by, collection_payment.raised_by/validated_by,
-- dsa_commission_event.actor_id, lead.created_by_staff_id, lead_outreach.dsa_staff_id and
-- customer_owner.owner_staff_id.
--
-- These three writes had no actor at all:
--   * payment            — the Accountant verifying/rejecting a repayment (their core daily work)
--   * interaction_log    — the Collection Executive logging a call (the "are they logging calls?" signal)
--   * customer_call_log  — the Telecaller's call outcome (name-only via created_by until now)
--
-- Nullable, no backfill, no FK (schema convention — see V41/V58 headers). Historical rows stay
-- unattributed on purpose: there is nothing truthful to backfill them from, and inventing an actor
-- would be worse than a gap. The dashboard renders those periods as "not tracked before <date>"
-- rather than a 0, which would read as "this employee did nothing".
--
-- payment carries one decided_by/decided_at pair rather than separate verified_*/rejected_* columns:
-- payment.status already records which way the decision went, so a second pair would be redundant
-- state that can disagree with it.

alter table payment add column if not exists decided_by bigint;
alter table payment add column if not exists decided_at timestamptz;

alter table interaction_log add column if not exists logged_by_staff_id bigint;

alter table customer_call_log add column if not exists created_by_staff_id bigint;

comment on column payment.decided_by is
    'staff_user.id of the Accountant who verified or rejected this repayment; null for pre-V59 rows. Which way it went is payment.status.';
comment on column payment.decided_at is
    'When decided_by acted; null for pre-V59 rows.';
comment on column interaction_log.logged_by_staff_id is
    'staff_user.id of the staffer who logged this interaction; null for pre-V59 rows.';
comment on column customer_call_log.created_by_staff_id is
    'staff_user.id of the staffer who logged this call; null for pre-V59 rows. created_by holds their display name, which is mutable and must not be aggregated on.';

-- application_event is the spine of the dashboard (every credit/disbursement decision), but it has
-- only an application_id index — a per-staff, date-windowed read would full-scan the whole audit
-- trail on every page load.
create index if not exists idx_application_event_actor_at on application_event (actor_id, at);
