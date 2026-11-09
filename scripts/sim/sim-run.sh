#!/bin/sh
# Runs the simulation SQL. Refuses any database host other than the simulation instance.
set -eu

case "${PGHOST:-}" in
  navix-finance-sim.*) ;;
  localhost)
    [ "${SIM_ALLOW_LOCAL:-}" = 1 ] || { echo "REFUSED: localhost needs SIM_ALLOW_LOCAL=1" >&2; exit 2; }
    ;;
  *)
    echo "REFUSED: ${PGHOST:-<unset>} is not the simulation database" >&2
    exit 2
    ;;
esac

DIR=$(cd "$(dirname "$0")" && pwd)
PSQL="psql -X -q -v ON_ERROR_STOP=1"

case "${1:-}" in
  prepare) $PSQL -v app_pw="$SIM_APP_DB_PASSWORD" -v admin_pw="$SIM_ADMIN_PASSWORD" -f "$DIR/10_prepare.sql" ;;
  loans)   $PSQL -f "$DIR/20_training_loans.sql" ;;
  verify)  $PSQL -f "$DIR/30_verify.sql" ;;
  *) echo "usage: sim-run.sh prepare|loans|verify" >&2; exit 64 ;;
esac
