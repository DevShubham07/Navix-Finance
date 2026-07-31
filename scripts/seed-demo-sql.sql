-- =====================================================================
-- seed-demo-sql.sql — the id-independent finishing pass for the demo seed.
-- Invoked by scripts/seed-demo-data.ps1 after the API phase. Idempotent:
-- safe to run repeatedly. Every block no-ops when its table is absent or empty.
--
-- Two things live here that the REST API cannot express:
--   1. Dashboard trend spreading (timestamps must land inside the 30-day window).
--   2. The ADMIN notification inbox (no NotificationType targets ADMINs — see below).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Dashboard trend spreading
--
-- DashboardService.trends (navix-loan) builds its three sparklines from:
--     application_event.at  where action = 'CREATE'   -> "Applications"
--     loan.disbursed_on                               -> "Disbursals"
--     payment.paid_on       where status = 'VERIFIED' -> "Repayments"
-- over a 30-day window in Asia/Kolkata, plus this-week vs last-week deltas taken
-- from the trailing 7 days and the 7 before that. The API creates everything
-- "today", so without this the cards render flat at zero with no deltas.
--
-- Spread deterministically so a re-seed produces the same picture, and make sure
-- BOTH trailing 7-day windows get activity (id % 26 covers days 0..25).
-- ---------------------------------------------------------------------
do $$
begin
    if to_regclass('public.application_event') is not null then
        update application_event
           set at = (now() - ((id % 26) || ' days')::interval)
         where action = 'CREATE';
        raise notice 'Spread % CREATE events across the last 26 days',
            (select count(*) from application_event where action = 'CREATE');
    end if;
end $$;

-- Only nudge loans that are still current: the seeder has already backdated the
-- overdue/closed cohort into precise DPD bands and must not be disturbed.
do $$
begin
    if to_regclass('public.loan') is not null then
        update loan
           set disbursed_on = current_date - ((id % 24)::int)
         where due_date > current_date
           and disbursed_on > current_date - 26;
    end if;
end $$;

-- Verified payments drive the "Repayments" sparkline; keep them inside the window
-- and never in the future.
do $$
begin
    if to_regclass('public.payment') is not null then
        update payment
           set paid_on = current_date - ((id % 25)::int)
         where status = 'VERIFIED'
           and (paid_on is null or paid_on > current_date);
    end if;
end $$;

-- ---------------------------------------------------------------------
-- 2. ADMIN notification inbox
--
-- Direct insert, deliberately. RecipientPolicy.TO_ADMINS is declared in
-- navix-common but NO NotificationType actually uses it ("reserved/unused in v1;
-- ADMIN gets oversight via dashboards"), so an ADMIN's bell is structurally empty
-- no matter which business actions you drive through the API. Every other role
-- (KYC_APPROVER, ACCOUNTANT, COLLECTION_HEAD...) receives notifications naturally.
--
-- recipient_id 10 = Meera Krishnan (ADMIN, seeded in V10). Schema: V21.
-- Five are left unread so the bell badge shows a count.
-- ---------------------------------------------------------------------
do $$
declare
    admin_id bigint := 10;
begin
    if to_regclass('public.notification') is null then
        return;
    end if;

    -- Idempotency: dedupe_key is unused by v1 code, so it is free for our marker.
    delete from notification where dedupe_key = 'demo-seed-admin';

    insert into notification
        (recipient_type, recipient_id, type, category, title, body, in_app, read_at,
         actor_id, actor_role, dedupe_key, created_at)
    values
        ('STAFF', admin_id, 'KYC_SUBMITTED', 'KYC',
         'New KYC submissions awaiting review',
         'Three applications completed verification and are queued for a KYC approver.',
         true, null, '1', 'KYC_APPROVER', 'demo-seed-admin', now() - interval '35 minutes'),

        ('STAFF', admin_id, 'CREDIT_APPROVED', 'CREDIT',
         'Credit head approved an application',
         'Priya Nair approved an advance within policy; it has moved to disbursement.',
         true, null, '5', 'CREDIT_HEAD', 'demo-seed-admin', now() - interval '2 hours'),

        ('STAFF', admin_id, 'LOAN_DISBURSED', 'DISBURSEMENT',
         'Advance disbursed',
         'A loan was released on the fast path with a recorded transaction reference.',
         true, null, '6', 'DISBURSEMENT_HEAD', 'demo-seed-admin', now() - interval '5 hours'),

        ('STAFF', admin_id, 'REPAYMENT_RECORDED', 'REPAYMENT',
         'Repayments awaiting verification',
         'Three borrower repayments are pending accountant verification.',
         true, null, '7', 'ACCOUNTANT', 'demo-seed-admin', now() - interval '8 hours'),

        ('STAFF', admin_id, 'COLLECTION_CASE_OPENED', 'COLLECTIONS',
         'Collection case opened',
         'An overdue loan crossed into collections and was assigned to Sana Khan.',
         true, null, '8', 'COLLECTION_HEAD', 'demo-seed-admin', now() - interval '1 day'),

        ('STAFF', admin_id, 'SETTLEMENT_PROPOSED', 'COLLECTIONS',
         'Settlement proposed for approval',
         'A collection executive proposed a partial settlement; collection head approval is required.',
         true, now() - interval '20 hours', '9', 'COLLECTION_EXECUTIVE', 'demo-seed-admin', now() - interval '1 day 4 hours'),

        ('STAFF', admin_id, 'PAYMENT_OVERDUE', 'REPAYMENT',
         'Loans past due',
         'Five advances are past their salary-linked due date and are accruing late penalty.',
         true, now() - interval '2 days', null, 'SYSTEM', 'demo-seed-admin', now() - interval '2 days'),

        ('STAFF', admin_id, 'STAFF_ROLE_CHANGED', 'STAFF_IAM',
         'Staff role updated',
         'Kabir Singh was promoted from Credit Executive to Credit Head.',
         true, now() - interval '3 days', '10', 'ADMIN', 'demo-seed-admin', now() - interval '3 days');

    raise notice 'Seeded 8 ADMIN notifications (5 unread)';
end $$;
