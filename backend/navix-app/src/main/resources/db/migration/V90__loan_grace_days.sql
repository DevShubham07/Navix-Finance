-- Per-loan salary grace. Until now the one penalty-free day after the due date was a constant
-- (LoanMath.SALARY_GRACE_DAYS) applied on every read, so it could only be changed for every loan at
-- once — including closed and overdue ones whose accrued figures must never move. Storing it on the
-- row freezes each loan's terms: every loan that exists today keeps its 1 day, and the day can be
-- withdrawn for loans going forward (a later migration flips the default and the not-yet-due book).
ALTER TABLE loan ADD COLUMN grace_days INT NOT NULL DEFAULT 1;
