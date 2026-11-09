#!/usr/bin/env bash
# Exercises the sim SQL against a throwaway local database built from the real migrations.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
export PGHOST=localhost PGPORT=5433 PGUSER=navix PGPASSWORD=navix SIM_ALLOW_LOCAL=1
export SIM_APP_DB_PASSWORD=new-app-password SIM_ADMIN_PASSWORD=training-password-1
RUN="$ROOT/scripts/sim/sim-run.sh"
DB=navix_simtest

fresh() {
  psql -q -X -d postgres -c "drop database if exists $DB" -c "drop role if exists navix_app" -c "create database $DB"
  export PGDATABASE=$DB
  for f in $(ls "$ROOT"/backend/navix-app/src/main/resources/db/migration/V*.sql | sort -V); do
    psql -q -X -v ON_ERROR_STOP=1 -f "$f" >/dev/null
  done
  psql -q -X -v ON_ERROR_STOP=1 -f "$ROOT/scripts/sim/test/fixture.sql"
}

val() { psql -X -At -c "$1"; }
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1" >&2; exit 1; }

fresh
if "$RUN" loans 2>/dev/null; then fail "loans ran without a sim marker"; fi
pass "loans refuses a database with no sim marker"

if PGHOST=navix-finance-dev.cf22os0umu8l.ap-south-1.rds.amazonaws.com "$RUN" prepare 2>/dev/null; then fail "ran against production host"; fi
pass "wrapper refuses the production host"

real_before=$(val "select count(*) from loan")
"$RUN" prepare
[ "$(val "select count(*) from staff_user where status='ACTIVE'")" = 1 ] || fail "more than one active staff user"
[ "$(val "select role from staff_user where status='ACTIVE'")" = ADMIN ] || fail "active user is not ADMIN"
[ "$(val "select email from staff_user where status='ACTIVE'")" = sim.admin@dhanboost.com ] || fail "wrong active email"
[ "$(val "select password_hash = crypt('training-password-1', password_hash) from staff_user where status='ACTIVE'")" = t ] || fail "bad password hash"
pass "prepare locks staff down to the one admin"

"$RUN" loans
"$RUN" verify
[ "$(val "select count(*) from loan where created_by <> 'sim-seed' or created_by is null")" = "$real_before" ] || fail "real loan count changed"
[ "$(val "select count(*) from loan where created_by is null and principal <> 1000000")" = 0 ] || fail "a real loan was edited"
[ "$(val "select count(*) from customer_profile where mobile not like '555%' and full_name like 'Sim Trainee %'")" = 0 ] || fail "trainee with a plausible mobile"
for s in CREDIT_EXEC_PENDING SANCTIONED DISBURSEMENT_PENDING; do
  [ "$(val "select count(*) from loan_application where status='$s'")" -ge 5 ] || fail "too few $s files"
done
[ "$(val "select count(*) from payment where status='PENDING_VERIFICATION'")" -ge 5 ] || fail "too few repayments awaiting verification"
pass "training loans meet the targets and leave real rows alone"

if "$RUN" loans 2>/dev/null; then fail "loans ran twice"; fi
pass "loans refuses a second run"

fresh
"$RUN" prepare
psql -q -X -c "update loan set principal = 80000000 where id = (select min(id) from loan)"
psql -q -X -c "update loan set principal = 650000000 where id = (select max(id) from loan)"
before=$(val "select count(*) from loan")
if "$RUN" loans 2>/dev/null; then fail "loans ran with the book above target"; fi
[ "$(val "select count(*) from loan")" = "$before" ] || fail "partial insert left behind"
pass "stops without changes when the real book exceeds the target"

echo "ALL PASSED"
