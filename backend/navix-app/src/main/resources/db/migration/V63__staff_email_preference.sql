-- A staffer's opt-out of their own operational email notifications (mirrors
-- borrower_preferences.email_opt_in). Column, not a table (same reasoning as V54): staff have no
-- mobile channel to toggle (StaffContactAdapter hardcodes ContactInfo.mobile = null, so staff never
-- get SMS), so there is exactly one channel worth a preference. Default true preserves current
-- behaviour for every existing staffer. STAFF_IAM notifications (account/security mail) ignore this
-- flag entirely — see NotificationDispatcher.
alter table staff_user add column if not exists email_opt_in boolean not null default true;
