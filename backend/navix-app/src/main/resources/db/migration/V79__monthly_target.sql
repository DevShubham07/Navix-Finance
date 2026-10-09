-- V79 — monthly_target: the admin-editable monthly goals the staff dashboard measures against.
--
-- The revamped staff dashboard (Target vs Achieved, Deficit Collection) needs two numbers per month
-- that no table held: how much the company wants to DISBURSE, and what share of the amount falling
-- due it wants to COLLECT. They are edited by ADMIN only (PUT /api/dashboard/targets/{yyyy-MM}) and
-- read by every staff role except DSA.
--
-- One row per calendar month, keyed by that month's first day. A missing month is not an error: the
-- API reports it with no disbursal target and the 88% default collection target, so the dashboard
-- works before anyone has set anything. disbursal_target_paise is nullable for the same reason --
-- NULL means "no target set", which the dashboard shows as "not measurable", never as a zero target.
-- collection_target_bp is in basis points (8800 = 88.00%), bounded 0..10000.
-- All money is paise, like every other money column.
create table monthly_target (
    month                 date        primary key,
    disbursal_target_paise bigint,
    collection_target_bp  integer     not null default 8800
        check (collection_target_bp between 0 and 10000),
    updated_by            varchar(64),
    updated_at            timestamptz not null default now(),
    constraint chk_monthly_target_first_of_month check (extract(day from month) = 1),
    constraint chk_monthly_target_paise check (disbursal_target_paise is null or disbursal_target_paise >= 0)
);
