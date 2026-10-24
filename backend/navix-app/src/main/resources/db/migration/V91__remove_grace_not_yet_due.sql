-- Withdraw the salary grace day (the second half of V90). From this release LoanMath.SALARY_GRACE_DAYS
-- is 0, so every loan disbursed from now on carries grace_days = 0: interest runs through the salary
-- day and the 2%/day penalty starts the very next day — exactly what the signed sanction letter says.
--
-- Open loans that have NOT reached their due date yet are flipped too: for them the grace day lies in
-- the future, so no figure ever shown, paid or reported moves. Loans already on or past their due date
-- (in their grace day, overdue, in collections) and every closed loan keep grace_days = 1 — changing
-- them would rewrite penalty that has already accrued. OVERDUE is never stored (computed on read), so
-- "closed_on IS NULL AND due_date >= today (IST)" is the exact safety condition; no status filter.
--
-- The UPDATE is idempotent: re-run it by hand once the ECS rollout has drained the previous tasks,
-- which still stamp 1 on anything they disburse during the overlap. Undo for one loan:
--   UPDATE loan SET grace_days = 1 WHERE id = <id>;
ALTER TABLE loan ALTER COLUMN grace_days SET DEFAULT 0;

UPDATE loan
   SET grace_days = 0
 WHERE closed_on IS NULL
   AND due_date >= (now() AT TIME ZONE 'Asia/Kolkata')::date;
