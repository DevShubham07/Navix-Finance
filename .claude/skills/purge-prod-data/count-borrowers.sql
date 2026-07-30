-- Read-only preflight for purge-borrowers.sql. Run this FIRST and report the numbers:
-- a handful of rows is test data, thousands are real customers.
-- Also doubles as the post-purge verification (borrower rows 0, staff_user unchanged).
--
-- Version-tolerant: skips tables absent from the current schema (applicant_profile was
-- renamed customer_profile in V33; the disbursement_request chain was dropped in V39).
do $$
declare
    t    text;
    n    bigint;
    kind text;
begin
    -- rpad(), not printf-style widths: in raise notice, % is the placeholder character, so a
    -- format spec like %-28s is parsed as a placeholder followed by the literal "-28s".
    raise notice '% | %', rpad('table', 28), 'rows';
    raise notice '%', repeat('-', 38);
    foreach t in array array[
        -- borrower-side: everything here goes
        'customer_profile', 'applicant_profile', 'borrower', 'borrower_mobile', 'signup_application',
        'borrower_credential', 'borrower_preferences', 'loan_application', 'application_event',
        'application_verification', 'application_document', 'co_applicant', 'loan', 'loan_document',
        'repayment_plan', 'payment', 'income_profile', 'risk_assessment', 'kyc_case', 'kyc_check',
        'digilocker_session', 'collection_case', 'interaction_log', 'settlement', 'referral',
        'referral_code', 'referral_payout', 'profile_change_log', 'customer_remark',
        -- kept: shown so the caller can confirm they survive
        'staff_user', 'staff_invite', 'feature_flag', 'payment_settings', 'company_expense',
        'blocklist_entry', 'email_suppression', 'flyway_schema_history'
    ]
    loop
        if to_regclass(t) is null then
            continue;
        end if;
        execute format('select count(*) from %I', t) into n;
        kind := case
            when t in ('staff_user','staff_invite','feature_flag','payment_settings',
                       'company_expense','blocklist_entry','email_suppression','flyway_schema_history')
            then '  [KEPT]' else '' end;
        raise notice '% | %', rpad(t, 28), lpad(n::text, 7) || kind;
    end loop;
end $$;

-- The two mixed-audience tables: only the BORROWER rows are deleted, staff rows survive.
select 'notification' as tbl, recipient_type as audience, count(*)
  from notification group by recipient_type
union all
select 'password_reset_token', subject_type, count(*)
  from password_reset_token group by subject_type
order by 1, 2;
