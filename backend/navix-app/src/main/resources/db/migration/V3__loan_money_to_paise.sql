-- NAVIX Finance — convert loan-module money columns to integer paise.
--
-- Decision (handoff.md §3.1/§5.7): money is stored as integer paise (BIGINT), not
-- numeric(14,2) rupees. The backend LoanMath computes in paise; the columns now match.
-- daily_interest_rate stays numeric (it is a rate, not an amount).
--
-- Tables are empty at this point; the USING clause documents the rupee→paise conversion
-- (×100) so the migration is correct even if rows existed.

alter table loan
    alter column principal       type bigint using round(principal * 100)::bigint,
    alter column processing_fee  type bigint using round(processing_fee * 100)::bigint,
    alter column gst             type bigint using round(gst * 100)::bigint,
    alter column net_disbursed   type bigint using round(net_disbursed * 100)::bigint,
    alter column total_repayable type bigint using round(total_repayable * 100)::bigint,
    alter column outstanding     type bigint using round(outstanding * 100)::bigint;

alter table loan_application
    alter column amount_requested type bigint using round(amount_requested * 100)::bigint,
    alter column eligible_limit   type bigint using round(eligible_limit * 100)::bigint;

alter table payment
    alter column amount type bigint using round(amount * 100)::bigint;
