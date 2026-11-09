-- Runs first on a freshly restored copy. The marker is what every later script checks for.
begin;

create table sim_marker (created_at timestamptz not null default now());
insert into sim_marker default values;

-- The restored copy still carries production's application password.
alter role navix_app password :'app_pw';

do $$
declare r record;
begin
  for r in select rolname from pg_roles
           where rolcanlogin and rolname not like 'rds%' and rolname not in (current_user, 'navix_app')
  loop
    execute format('alter role %I nologin', r.rolname);
  end loop;
end $$;

update staff_user set status = 'DISABLED', active_session_id = null, active_session_at = null;
delete from password_reset_token;
delete from staff_invite where accepted_at is null;

update feature_flag set enabled = false where flag_key in ('digio-mandate', 'digio-debit', 'payu-repayment');

create extension if not exists pgcrypto;
insert into staff_user (email, name, role, status, password_hash, created_at, created_by)
values ('sim.admin@dhanboost.com', 'Training Admin', 'ADMIN', 'ACTIVE',
        crypt(:'admin_pw', gen_salt('bf', 10)), now(), 'sim-seed');

commit;
