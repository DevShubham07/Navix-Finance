-- Offline demo stack ONLY (scripts/run-demo.ps1, Postgres on :5433). V92 blanks the seeded staff
-- passwords on every database; this restores them for local demos and e2e runs. Never run it against RDS.
--   psql -h localhost -p 5433 -U navix -d navix -f scripts/restore-demo-logins.sql
update staff_user
   set password_hash = '$2a$10$exssI9R9G/cJdEzsk0Apmemf5x7pUWRYlrwVWsb3WOKoq4R31pq/W'
 where email like '%@navix.example' and password_hash is null;
