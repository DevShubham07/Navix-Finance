-- NAVIX Finance — extend the integer-paise policy (handoff.md §3.1) to the remaining money
-- columns across onboarding, income-risk and collections. Amounts become BIGINT paise; scores
-- and rates stay numeric. Tables are empty; the USING clause documents the rupee→paise (×100)
-- conversion.

alter table borrower
    alter column declared_salary type bigint using round(declared_salary * 100)::bigint;

alter table income_profile
    alter column monthly_salary type bigint using round(monthly_salary * 100)::bigint;

alter table risk_assessment
    alter column limit_granted type bigint using round(limit_granted * 100)::bigint;

alter table settlement
    alter column settlement_amount type bigint using round(settlement_amount * 100)::bigint;
