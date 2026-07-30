-- Full borrower purge — navix-finance-dev (LIVE production data).
-- Deletes every borrower-side row; leaves staff_user, staff_invite, feature_flag,
-- payment_settings, company_expense, email_suppression, blocklist_entry and
-- flyway_schema_history untouched.
--
-- There are NO FK constraints in this schema (CLAUDE.md §10 known debt), so nothing
-- cascades: every table has to be named explicitly or it is left orphaned.
-- Runs in ONE transaction — any error rolls the whole thing back.

begin;

-- Mixed-audience tables: filter, don't truncate (staff share these).
delete from notification_delivery
 where notification_id in (select id from notification where recipient_type = 'BORROWER');
delete from notification      where recipient_type = 'BORROWER';
delete from password_reset_token where subject_type = 'BORROWER';

-- Everything else is borrower-scoped in full. Skips tables that don't exist in this
-- schema version (names moved across V33/V39), so the script is version-tolerant.
do $$
declare
    t text;
    n bigint;
begin
    foreach t in array array[
        -- collections & settlements (reference loans)
        'settlement', 'interaction_log', 'collection_case',
        -- money
        'payment', 'repayment_plan', 'loan_document', 'loan',
        -- the application aggregate + its audit/verification trail
        'application_event', 'application_verification', 'application_document',
        'co_applicant', 'loan_application',
        -- risk / income
        'risk_assessment', 'income_profile',
        -- KYC
        'kyc_check', 'kyc_case', 'digilocker_session',
        -- referral program
        'referral_payout', 'referral', 'referral_code',
        -- borrower identity, auth & preferences
        'customer_remark', 'profile_change_log', 'borrower_preferences',
        'borrower_credential', 'signup_application', 'borrower_mobile', 'borrower',
        'customer_profile', 'applicant_profile'
    ]
    loop
        if to_regclass(t) is not null then
            execute format('delete from %I', t);
            get diagnostics n = row_count;
            raise notice 'deleted % row(s) from %', n, t;
        else
            raise notice 'skipped % (no such table)', t;
        end if;
    end loop;
end $$;

commit;
