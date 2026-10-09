-- V78 — application_event.acting_role: the "work as" sub-role behind each audited action.
--
-- Staff can now work as a sub-role (ADMIN as any desk, CREDIT_HEAD as CREDIT_EXECUTIVE,
-- COLLECTION_HEAD as COLLECTION_EXECUTIVE) via the X-Acting-Role header. Authorization still uses
-- the real role (actor_role); this column records which hat they wore, so the trail can tell an
-- admin acting as an executive from an executive. NULL = no acting role (the common case).
ALTER TABLE application_event ADD COLUMN acting_role VARCHAR(64) NULL;
