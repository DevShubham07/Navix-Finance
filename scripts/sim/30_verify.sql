-- Fails unless the copy is locked down and shows the training figures.
do $$
declare
  v_total bigint;
  v_avg numeric;
  v_active int;
begin
  if to_regclass('public.sim_marker') is null then
    raise exception 'REFUSED: not the simulation database (sim_marker missing)';
  end if;

  select count(*) into v_active from staff_user where status = 'ACTIVE';
  if v_active <> 1 then raise exception 'Expected one active staff user, found %', v_active; end if;

  if exists (select 1 from feature_flag where enabled and flag_key in ('digio-mandate', 'digio-debit', 'payu-repayment')) then
    raise exception 'A payment flag is still on';
  end if;

  select sum(l.principal) into v_total
    from loan l join loan_application a on a.loan_id = l.id
   where a.status <> 'DRAFT' and l.disbursed_on is not null;

  select avg(closed::numeric / due) into v_avg from (
    select count(*) due, count(*) filter (where l.status = 'CLOSED') closed
      from loan l join loan_application a on a.loan_id = l.id
     where a.status <> 'DRAFT' and l.disbursed_on is not null
       and l.due_date < date_trunc('month', current_date)
     group by date_trunc('month', l.due_date)) m;

  if abs(v_total - 700000000) > 7000000 then raise exception 'Total principal off target: %', v_total; end if;
  if abs(v_avg - 0.60) > 0.02 then raise exception 'Average closure off target: %', v_avg; end if;

  raise notice 'VERIFIED: total % paise, average monthly closure %, one active staff user', v_total, round(v_avg, 4);
end $$;
