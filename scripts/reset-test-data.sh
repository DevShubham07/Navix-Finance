#!/usr/bin/env bash
# =====================================================================
# NAVIX Finance — run the test-data reset against RDS (or local Postgres).
# Wipes borrower/application/loan/collections/notification data; keeps
# staff/auth + Flyway history so you can still log in. See reset-test-data.sql.
#
# Usage:
#   Local Docker Postgres (navix/navix on localhost:5432):
#       ./scripts/reset-test-data.sh local
#
#   AWS RDS (dev) — pulls connection details from SSM, needs:
#     * AWS creds with ssm:GetParameter + kms:Decrypt on /navix/dev/spring/datasource/*
#     * your current public IP allow-listed on the RDS security group :5432
#       (RDS is publicly accessible; SG sg-082443872704e48e4 gates it)
#       AWS_PROFILE=navix-dev ./scripts/reset-test-data.sh rds
#
# Quiesce the backend first (stop the ECS service / local app) so nothing
# writes new rows mid-reset. DESTRUCTIVE — snapshot RDS first if unsure.
# =====================================================================
set -euo pipefail

MODE="${1:-local}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL="${HERE}/reset-test-data.sql"
REGION="${AWS_REGION:-ap-south-1}"

if ! command -v psql >/dev/null 2>&1; then
  echo "error: psql not found on PATH (install postgresql client)." >&2
  exit 1
fi
[[ -f "$SQL" ]] || { echo "error: $SQL not found." >&2; exit 1; }

case "$MODE" in
  local)
    echo ">> Resetting LOCAL Postgres (localhost:5432/navix)…"
    PGPASSWORD="${DB_PASSWORD:-navix}" psql \
      -h "${DB_HOST:-localhost}" -p "${DB_PORT:-5432}" \
      -U "${DB_USERNAME:-navix}" -d "${DB_NAME:-navix}" \
      -v ON_ERROR_STOP=1 -f "$SQL"
    ;;
  rds)
    echo ">> Fetching RDS connection details from SSM (/navix/dev/spring/datasource/*)…"
    JDBC_URL="$(aws ssm get-parameter --region "$REGION" --name /navix/dev/spring/datasource/url            --query Parameter.Value --output text)"
    DB_USER="$(aws ssm get-parameter  --region "$REGION" --name /navix/dev/spring/datasource/username       --query Parameter.Value --output text)"
    # Password is a SecureString — kept in an env var, never echoed.
    PGPASSWORD="$(aws ssm get-parameter --region "$REGION" --name /navix/dev/spring/datasource/password --with-decryption --query Parameter.Value --output text)"
    export PGPASSWORD
    # jdbc:postgresql://HOST:PORT/DB?params  ->  HOST / PORT / DB
    NOPREFIX="${JDBC_URL#jdbc:postgresql://}"
    HOSTPORT="${NOPREFIX%%/*}"
    DBPART="${NOPREFIX#*/}"
    DB_HOST="${HOSTPORT%%:*}"
    DB_PORT="${HOSTPORT##*:}"; [[ "$DB_PORT" == "$HOSTPORT" ]] && DB_PORT=5432
    DB_NAME="${DBPART%%\?*}"
    echo ">> Resetting RDS ${DB_HOST}:${DB_PORT}/${DB_NAME} as ${DB_USER}…"
    psql "host=${DB_HOST} port=${DB_PORT} dbname=${DB_NAME} user=${DB_USER} sslmode=require" \
      -v ON_ERROR_STOP=1 -f "$SQL"
    ;;
  *)
    echo "usage: $0 [local|rds]" >&2
    exit 2
    ;;
esac

echo ">> Done. Verify the AFTER counts above are all 0 and the KEEP tables are intact."
