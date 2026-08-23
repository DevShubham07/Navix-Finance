-- Suspend the credit-score auto-reject (2026-08-23).
--
-- The floor moved 600 -> 550 when the primary bureau switched from Experian to CRIF Highmark, on the
-- assumption that a lower number is a looser rule. It is not: a score means different things on
-- different bureaus. On the live CRIF distribution the median sat at 510 and 60% of pulls fell under
-- 550, so the "looser" rule tripled the live rejection rate from roughly 15% to 45-60%, each rejection
-- carrying a 90-day cooling-off block.
--
-- Until the floor is recalibrated against CRIF's own distribution and observed default behaviour,
-- every bureau result goes to a human instead of being declined automatically. The code reads this
-- flag with defaultWhenMissing = FALSE, so the rule stays off even if this row is deleted: it takes
-- money-affecting, 90-day-blocking action without a human, and must be switched on deliberately.
--
-- To re-enable once a threshold is chosen (and MIN_BUREAU_SCORE updated to it):
--   update feature_flag set enabled = true where flag_key = 'bureau-auto-reject';
insert into feature_flag (flag_key, enabled, description, created_at)
values ('bureau-auto-reject', false,
        'Auto-reject an application whose bureau score is under the floor. SUSPENDED 2026-08-23: the floor is calibrated for Experian, not CRIF.',
        now())
on conflict (flag_key) do update set enabled = false;
