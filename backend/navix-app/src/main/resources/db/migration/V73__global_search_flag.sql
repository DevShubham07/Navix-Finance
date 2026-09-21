-- V73 — kill switch for the staff console's global search (Cmd/Ctrl+K palette).
--
-- The palette fans one query out across customers, applications, loans, collections cases, leads,
-- staff users and the blocklist, delegating each group to the same scoped service its list page
-- already calls. That makes it cheap to reason about for RBAC, but it also means one slow group
-- (the loans register enriches the whole book before filtering) shows up on every keystroke of
-- every staffer. If that ever becomes a problem in production, this turns the whole feature off —
-- endpoint and UI trigger both — without a redeploy:
--     update feature_flag set enabled = false where flag_key = 'global-search';
--
-- Read with defaultWhenMissing=TRUE, unlike the risk-bearing flags (bureau-auto-reject, digitap-*)
-- which default OFF so that deleting their row fails safe. Search takes no money-affecting action
-- and shows nobody anything their role could not already open, so the safe default here is ON: a
-- fresh environment that has not run this migration still gets a working console.
--
-- `do nothing` on conflict so an operator who has already switched it off is not overridden by a
-- redeploy.
insert into feature_flag (flag_key, enabled, description, created_at)
values ('global-search', true,
        'Staff console global search (Cmd/Ctrl+K) + GET /api/staff/search. OFF hides the header '
            || 'trigger and the endpoint returns FEATURE_DISABLED.',
        now())
on conflict (flag_key) do nothing;
