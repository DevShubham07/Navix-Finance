-- V83 — gate for the Digio NACH eMandate step of the offer journey.
--
-- With this flag ON, confirming the disbursal account no longer hands the file to the Disbursement
-- Head: the borrower first registers an eMandate (OfferStep.OFFER_MANDATE, "mandate"), and the file is
-- handed off only once a UMRN lands. With it OFF the journey is exactly what it was before V82 — the
-- step is not offered and confirming the account routes straight to DISBURSEMENT_PENDING. A customer
-- who already holds a usable UMRN skips the step in either case.
--
-- It ships DISABLED and is read with defaultWhenMissing=FALSE (like bureau-auto-reject, V64): with
-- provider=digio a registration files a REAL, legally binding NPCI mandate and there is no sandbox, so
-- this is a money-affecting rail that must never switch itself on because a row is missing. Enable it
-- only once the Digio credentials are in SSM (/navix/<env>/navix/digio/...):
--     update feature_flag set enabled = true where flag_key = 'digio-mandate';
--
-- `do nothing` on conflict so a redeploy never overrides an operator's choice.
insert into feature_flag (flag_key, enabled, description, created_at)
values ('digio-mandate', false,
        'Digio NACH eMandate step after the disbursal account in the offer journey. OFF: the step is '
            || 'skipped and confirming the account routes straight to DISBURSEMENT_PENDING.',
        now())
on conflict (flag_key) do nothing;
