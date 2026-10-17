-- V85 — gate for Digio NACH debit PRESENTATION (the scheduled DPD 1/3/5/9 sweep and the manual
-- collections "debit" action) against a registered eMandate (V82, rows in V84).
--
-- With this flag OFF neither path presents anything: the sweep skips every loan and the manual
-- endpoint refuses. It ships DISABLED and is read with defaultWhenMissing=FALSE (like digio-mandate,
-- V83, and bureau-auto-reject, V64): a presentation pulls REAL money from a REAL borrower's account,
-- Digio has no sandbox and no idempotency key, so a missing row must never switch it on.
--
-- DO NOT ENABLE until the re-presentable reject-code allowlist in DebitService is confirmed against
-- one real Digio failure_code (it is a guess today; it fails closed, so retries would silently never
-- fire if the real codes differ). Then:
--     update feature_flag set enabled = true where flag_key = 'digio-debit';
--
-- `do nothing` on conflict so a redeploy never overrides an operator's choice.
insert into feature_flag (flag_key, enabled, description, created_at)
values ('digio-debit', false,
        'Digio NACH debit presentation against a registered mandate (scheduled sweep at DPD 1/3/5/9 and '
            || 'manual collections debit). OFF: nothing is presented.',
        now())
on conflict (flag_key) do nothing;
