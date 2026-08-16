-- One live console session per staffer, every role including ADMIN. The staff JWT now carries a
-- `sid` claim; this column is the only session id considered current. A second sign-in while a
-- session is live is REJECTED (SESSION_CONFLICT) unless the caller passes force=true, which mints
-- a new sid and thereby invalidates the old token on its next request.
--
-- Column, not a table: exactly one live session per staffer by construction, so a row-per-session
-- table would need its own uniqueness rule to say the same thing.
alter table staff_user add column active_session_id varchar(64);
alter table staff_user add column active_session_at timestamptz;
