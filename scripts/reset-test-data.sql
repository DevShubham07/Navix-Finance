-- =====================================================================
-- NAVIX Finance — SAFE test-data reset
-- ---------------------------------------------------------------------
-- Wipes all borrower / application / loan / collections / notification
-- test data so you can restart testing with NO pending applications or
-- loans. KEEPS staff/auth (staff_user, staff_invite), Flyway history,
-- payment settings, and the fraud blocklist — so you can still log in.
--
-- Robust: only truncates tables that actually EXIST (the schema has some
-- legacy/dormant tables that may or may not be present), so a missing
-- table never aborts the wipe. No FK constraints exist in this schema, so
-- order is irrelevant; CASCADE + RESTART IDENTITY are included for safety
-- and to reset the bigint id sequences back to 1.
--
-- Idempotent — safe to run repeatedly. Quiesce the backend first so no new
-- rows are written between the count and the truncate. DESTRUCTIVE and
-- irreversible — take an RDS snapshot first if the data has any value.
-- =====================================================================

DO $$
DECLARE
    -- Tables to WIPE (test/borrower data). staff_user / staff_invite /
    -- flyway_schema_history / payment_settings / blocklist_entry are
    -- deliberately ABSENT — they are preserved.
    wipe text[] := ARRAY[
        'loan_application', 'application_event', 'applicant_profile',
        'application_document', 'application_verification',
        'loan', 'loan_document', 'payment', 'repayment_plan',
        'notification', 'notification_delivery',
        'collection_case', 'interaction_log', 'settlement',
        'borrower', 'co_applicant', 'signup_application',
        'kyc_case', 'kyc_check', 'digilocker_session',
        'income_profile', 'risk_assessment',
        'disbursement_request', 'approval_step'
    ];
    present text[] := '{}';
    t text;
    n bigint;
    list text;
BEGIN
    -- BEFORE counts (only for tables that exist), and collect the present set.
    FOREACH t IN ARRAY wipe LOOP
        IF to_regclass('public.' || t) IS NOT NULL THEN
            EXECUTE format('SELECT count(*) FROM %I', t) INTO n;
            RAISE NOTICE 'BEFORE  % = % rows', rpad(t, 26), n;
            present := array_append(present, t);
        END IF;
    END LOOP;

    IF array_length(present, 1) IS NULL THEN
        RAISE NOTICE 'No matching tables present — nothing to wipe.';
        RETURN;
    END IF;

    -- Single TRUNCATE of every present target table.
    SELECT string_agg(quote_ident(x), ', ') INTO list FROM unnest(present) x;
    EXECUTE 'TRUNCATE TABLE ' || list || ' RESTART IDENTITY CASCADE';
    RAISE NOTICE '--- Truncated % table(s). ---', array_length(present, 1);

    -- AFTER counts (must all be 0).
    FOREACH t IN ARRAY present LOOP
        EXECUTE format('SELECT count(*) FROM %I', t) INTO n;
        RAISE NOTICE 'AFTER   % = % rows', rpad(t, 26), n;
    END LOOP;
END $$;

-- Sanity: the KEEP tables must remain populated (proves we did not wipe auth/migrations).
SELECT 'KEEP' AS phase, 'staff_user'            AS tbl, count(*) AS rows FROM staff_user
UNION ALL SELECT 'KEEP', 'staff_invite',          count(*) FROM staff_invite
UNION ALL SELECT 'KEEP', 'flyway_schema_history', count(*) FROM flyway_schema_history
ORDER BY tbl;
