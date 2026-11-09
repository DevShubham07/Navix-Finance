-- Adds made-up borrowers until the book shows the training targets. Never edits an existing row.
-- One transaction: if the targets cannot be met, nothing is left behind.
begin;

do $$
begin
  if to_regclass('public.sim_marker') is null then
    raise exception 'REFUSED: not the simulation database (sim_marker missing)';
  end if;
  if exists (select 1 from customer_profile where full_name like 'Sim Trainee %') then
    raise exception 'Training loans already exist. Restore the copy again to reset.';
  end if;
end $$;

create function pg_temp.sim_application(p_n int, p_status text, p_principal bigint, p_loan bigint,
                                        p_created timestamptz, p_due date)
returns bigint language plpgsql as $f$
declare
  v_app bigint;
  v_customer bigint := 900000000 + p_n;
  v_mobile text := (5550000000::bigint + p_n)::text;
  v_salary bigint := p_principal * 5;
  v_sanctioned boolean := p_status <> 'CREDIT_EXEC_PENDING';
  v_path text[] := array['DRAFT', 'KYC_PENDING', 'CREDIT_EXEC_PENDING', 'SANCTIONED', 'DISBURSEMENT_PENDING',
                         'DISBURSED', 'ACTIVE', 'OVERDUE', 'CLOSED'];
  v_last int := array_position(v_path, p_status);
  v_prev text := null;
  v_step int := 0;
  v_to text;
begin
  insert into loan_application (customer_id, amount_requested, eligible_limit, status, purpose, loan_id,
                                salary_credit_day, sanctioned_amount_paise, approved_repayment_date,
                                sanction_tenure_days, sanctioned_at, disbursal_account_number, disbursal_ifsc,
                                disbursal_holder_name, disbursal_bank, disbursal_account_changed,
                                disbursal_account_verified, created_at)
  values (v_customer, p_principal, floor(v_salary * 0.25 / 10000)::bigint * 10000, p_status, 'Training case', p_loan,
          least(extract(day from p_due)::int, 28),
          case when v_sanctioned then p_principal end,
          case when v_sanctioned then p_due end,
          case when v_sanctioned then greatest(p_due - p_created::date, 1) end,
          case when v_sanctioned then p_created + interval '1 day' end,
          '000000' || p_n, 'SIMB0000001', 'Sim Trainee ' || lpad(p_n::text, 4, '0'), 'Sample Bank',
          false, true, p_created)
  returning id into v_app;

  insert into customer_profile (application_id, full_name, pan, aadhaar, mobile, dob, address, employer,
                                employment_status, monthly_salary_paise, salary_bank, email, official_email,
                                salary_account_number, salary_ifsc, bureau_score, bureau_source, risk_category,
                                pan_verified, aadhaar_linked, aadhaar_verified, email_verified, address_verified,
                                penny_drop_verified, created_at, created_by)
  values (v_app, 'Sim Trainee ' || lpad(p_n::text, 4, '0'), 'SIMXT' || lpad((p_n % 10000)::text, 4, '0') || 'Z',
          '0' || lpad(p_n::text, 11, '0'), v_mobile, date '1990-01-01', 'Training Address, Sample City',
          'Sample Employer Pvt Ltd', 'SALARIED', v_salary, 'Sample Bank',
          'trainee' || p_n || '@example.invalid', 'trainee' || p_n || '@example.invalid',
          '000000' || p_n, 'SIMB0000001', 720, 'SIMULATION', 'B',
          true, true, true, true, true, true, p_created, 'sim-seed');

  insert into borrower_mobile (customer_id, mobile, created_at) values (v_customer, v_mobile, p_created);

  foreach v_to in array v_path[1:v_last] loop
    -- A closed loan went through ACTIVE, not OVERDUE; only overdue files record that step.
    continue when v_to = 'OVERDUE' and p_status <> 'OVERDUE';
    insert into application_event (application_id, from_status, to_status, actor_id, actor_role, action, notes, at)
    values (v_app, v_prev, v_to, 'sim-seed', 'SYSTEM', case when v_prev is null then 'CREATE' else 'SIM_SEED' end,
            'Training data', p_created + make_interval(hours => v_step));
    v_prev := v_to;
    v_step := v_step + 1;
  end loop;
  return v_app;
end $f$;

create function pg_temp.sim_loan(p_n int, p_principal bigint, p_disbursed date, p_due date, p_status text)
returns bigint language plpgsql as $f$
declare
  v_fee bigint := round(p_principal * 0.10);
  v_gst bigint := round(v_fee * 0.18);
  v_days int := greatest(p_due - p_disbursed, 1);
  v_total bigint := p_principal + round(p_principal * 0.01 * v_days);
  v_closed boolean := p_status = 'CLOSED';
  v_paid date := least(p_due, current_date);
  v_loan bigint;
begin
  insert into loan (customer_id, principal, processing_fee, gst, net_disbursed, daily_interest_rate, disbursed_on,
                    due_date, total_repayable, outstanding, status, created_at, created_by, disbursal_txn_ref, closed_on)
  values (900000000 + p_n, p_principal, v_fee, v_gst, p_principal - v_fee - v_gst, 0.0100, p_disbursed, p_due, v_total,
          case when v_closed then 0 else v_total end, p_status, p_disbursed::timestamptz, 'sim-seed',
          'SIM-TXN-' || p_n, case when v_closed then v_paid end)
  returning id into v_loan;

  perform pg_temp.sim_application(p_n, p_status, p_principal, v_loan, (p_disbursed - 2)::timestamptz, p_due);

  if v_closed then
    insert into payment (loan_id, amount, method, status, txn_ref, paid_on, partial, created_at, created_by, decided_at)
    values (v_loan, v_total, 'UPI', 'VERIFIED', 'SIM-PAY-' || p_n, v_paid, false, v_paid::timestamptz, 'sim-seed',
            v_paid::timestamptz);
  end if;
  if p_status = 'OVERDUE' then
    insert into collection_case (id, loan_id, created_at) values (gen_random_uuid(), v_loan, (p_due + 1)::timestamptz);
  end if;
  return v_loan;
end $f$;

create function pg_temp.sim_principal(p_n int) returns bigint language sql as $f$
  select (array[200000, 300000, 400000, 500000, 600000, 700000, 800000]::bigint[])[(p_n % 7) + 1]
$f$;

do $$
declare
  c_target constant bigint := 700000000;   -- Rs 70,00,000
  c_average constant bigint := 500000;     -- mean of sim_principal
  c_closure constant numeric := 0.60;
  v_first_disbursed date;
  v_first_due date;
  v_real_total bigint;
  v_n int := 0;
  v_s text;
  v_i int;
  v_loan bigint;
  v_months date[];
  v_month date;
  v_count int;
  v_each int;
  v_extra int;
  v_quota int;
  v_real_due int;
  v_real_closed int;
  v_add_closed int;
  v_due date;
  v_total bigint;
  v_avg numeric;
begin
  select min(l.disbursed_on), min(l.due_date), coalesce(sum(l.principal), 0)
    into v_first_disbursed, v_first_due, v_real_total
    from loan l join loan_application a on a.loan_id = l.id
   where a.status <> 'DRAFT' and l.disbursed_on is not null;

  if v_first_disbursed is null then
    raise exception 'STOP: the copy has no disbursed loans to build on';
  end if;
  if v_real_total >= c_target then
    raise exception 'STOP: real book (% paise) already meets the target. Ask the owner.', v_real_total;
  end if;

  select array_agg(m::date order by m) into v_months
    from generate_series(date_trunc('month', v_first_due), date_trunc('month', current_date) - interval '1 month',
                         interval '1 month') m;
  if v_months is null then
    raise exception 'STOP: no completed months to spread training loans across';
  end if;

  -- Open files for every stage of the console.
  foreach v_s in array array['CREDIT_EXEC_PENDING', 'SANCTIONED', 'DISBURSEMENT_PENDING'] loop
    for v_i in 1..5 loop
      v_n := v_n + 1;
      perform pg_temp.sim_application(v_n, v_s, pg_temp.sim_principal(v_n), null, now() - interval '3 days',
                                      (current_date + 25)::date);
    end loop;
  end loop;
  for v_i in 1..5 loop
    v_n := v_n + 1;
    perform pg_temp.sim_loan(v_n, pg_temp.sim_principal(v_n), (current_date - 10)::date, (current_date + 20)::date, 'ACTIVE');
  end loop;
  for v_i in 1..5 loop
    v_n := v_n + 1;
    perform pg_temp.sim_loan(v_n, pg_temp.sim_principal(v_n), (current_date - 40)::date, (current_date - 5)::date, 'OVERDUE');
  end loop;
  for v_i in 1..5 loop
    v_n := v_n + 1;
    v_loan := pg_temp.sim_loan(v_n, pg_temp.sim_principal(v_n), (current_date - 15)::date, (current_date + 15)::date, 'ACTIVE');
    insert into payment (loan_id, amount, method, status, txn_ref, paid_on, partial, created_at, created_by)
    select id, total_repayable, 'UPI', 'PENDING_VERIFICATION', 'SIM-PAY-' || v_n, current_date, false, now(), 'sim-seed'
      from loan where id = v_loan;
  end loop;

  select sum(l.principal) into v_total
    from loan l join loan_application a on a.loan_id = l.id
   where a.status <> 'DRAFT' and l.disbursed_on is not null;

  v_count := round((c_target - v_total)::numeric / c_average);
  v_each := v_count / array_length(v_months, 1);
  v_extra := v_count % array_length(v_months, 1);

  foreach v_month in array v_months loop
    v_quota := v_each + case when v_extra > 0 then 1 else 0 end;
    v_extra := greatest(v_extra - 1, 0);

    select count(*), count(*) filter (where l.status = 'CLOSED') into v_real_due, v_real_closed
      from loan l join loan_application a on a.loan_id = l.id
     where a.status <> 'DRAFT' and l.disbursed_on is not null
       and date_trunc('month', l.due_date) = v_month;

    v_add_closed := greatest(0, least(v_quota, round(c_closure * (v_real_due + v_quota))::int - v_real_closed));

    for v_i in 1..v_quota loop
      v_n := v_n + 1;
      v_due := greatest(v_month + (v_n % 27), v_first_due);
      perform pg_temp.sim_loan(v_n, pg_temp.sim_principal(v_n), greatest(v_first_disbursed, v_due - (15 + v_n % 25)), v_due,
                               case when v_i <= v_add_closed then 'CLOSED' else 'OVERDUE' end);
    end loop;
  end loop;

  select sum(l.principal) into v_total
    from loan l join loan_application a on a.loan_id = l.id
   where a.status <> 'DRAFT' and l.disbursed_on is not null;

  select avg(closed::numeric / due) into v_avg from (
    select count(*) due, count(*) filter (where l.status = 'CLOSED') closed
      from loan l join loan_application a on a.loan_id = l.id
     where a.status <> 'DRAFT' and l.disbursed_on is not null
       and l.due_date < date_trunc('month', current_date)
     group by date_trunc('month', l.due_date)) m;

  if abs(v_total - c_target) > c_target / 100 or abs(v_avg - c_closure) > 0.02 then
    raise exception 'STOP: targets not reached (total % paise, average closure %). Nothing was saved. Ask the owner.',
      v_total, round(v_avg, 4);
  end if;

  raise notice 'Added % made-up files. Total % paise, average monthly closure %.', v_n, v_total, round(v_avg, 4);
end $$;

commit;
