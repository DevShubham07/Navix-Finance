---
name: purge-prod-data
description: Delete borrower/customer data from a NAVIX (DhanBoost) database — the live RDS or a local one — without orphaning rows or touching staff and platform config. Use when asked to wipe borrowers, clear customer data, reset test data, or purge an environment. Destructive and irreversible; always counts first and requires explicit confirmation.
---

# Purge borrower data

Deletes every borrower-side row from a NAVIX database. Staff accounts, platform config and the
migration history survive.

## Why this needs a skill rather than one DELETE

**The schema has no FK constraints** (documented debt in `CLAUDE.md` §10). Nothing cascades. A
`DELETE FROM customer_profile` leaves live loans, applications, repayments, collection cases and
notifications pointing at customers that no longer exist — the staff console and collections queues
then render broken rows, and those loans stay collectible. Every table must be named explicitly.

Two tables are **shared with staff** and must be filtered, never truncated:
`notification` (`recipient_type`) and `password_reset_token` (`subject_type`).

## Procedure

Do not skip steps 1–3. Step 4 is irreversible.

### 1. Identify the target — say it out loud

```bash
export AWS_PROFILE=navix-dev AWS_REGION=ap-south-1      # the default profile's SSO is expired
aws rds describe-db-instances \
  --query "DBInstances[].{id:DBInstanceIdentifier,ep:Endpoint.Address,backup:BackupRetentionPeriod}"
aws ssm get-parameter --name /navix/dev/spring/datasource/url --query Parameter.Value --output text
```

`navix-finance-dev` is the **live production database** despite the name — prod SSM points at it.
Backup retention is 1 day, so the recovery window is one day of PITR. State plainly which database
is about to be hit before going further.

### 2. Count what would be deleted

Run `count-borrowers.sql` (same connection recipe as step 4, read-only). Report the real numbers to
the user. A handful of rows means test data; thousands means real customers and the user should
know that before confirming.

### 3. Confirm, and offer a snapshot

Get an explicit yes for the counts just shown. Offer:

```bash
aws rds create-db-snapshot --db-instance-identifier navix-finance-dev \
  --db-snapshot-identifier navix-preprurge-$(date +%Y%m%d-%H%M%S)
```

If the user declines the snapshot, proceed — it is their call — but do not skip asking.

### 4. Execute

`psql` is usually not installed locally; run it from Docker.

```bash
export AWS_PROFILE=navix-dev AWS_REGION=ap-south-1
export PGPASSWORD=$(aws ssm get-parameter --name /navix/dev/spring/datasource/password \
  --with-decryption --query Parameter.Value --output text)
docker run --rm -i -e PGPASSWORD postgres:16 psql \
  -h navix-finance-dev.cf22os0umu8l.ap-south-1.rds.amazonaws.com -U navix_app -d navix \
  -v ON_ERROR_STOP=1 < .claude/skills/purge-prod-data/purge-borrowers.sql
```

Reading the SSM password may need `Bash(aws ssm get-parameter:*)` allowed. If the permission is
denied, stop and ask — do not source the secret from somewhere else to get around the denial.

The script runs in **one transaction** and prints a per-table row count, so a wrong table name rolls
the whole thing back instead of half-deleting. It self-skips tables absent from the current schema
version (`applicant_profile` was renamed to `customer_profile` in V33).

### 5. Verify

Re-run `count-borrowers.sql`. Borrower tables should be 0 and `staff_user` unchanged. Report both.

## What is kept, deliberately

`staff_user`, `staff_invite`, `feature_flag`, `payment_settings`, `company_expense`,
`flyway_schema_history`, and:

- **`blocklist_entry`** — the fraud blocklist. Purging it un-blocks known bad actors, which is
  usually the opposite of what someone clearing test data wants. Delete it only if asked directly.
- **`email_suppression`** — bounce/complaint addresses. Dropping these makes SES re-send to addresses
  that already hard-bounced, hurting domain reputation.

Mention both when reporting, so the user can ask for them explicitly.

## Scope variants

- **Full purge** (default) — `purge-borrowers.sql`.
- **Specific customers** — add `where customer_id in (…)` per table; the ordering in the script still
  applies. Note that `notification` keys on `recipient_id`, not `customer_id`.
- **Profiles only** — possible but leaves orphans by definition; warn before doing it.

## Local database

Same script, different connection — no AWS, no SSM:

```bash
docker exec -i navix-postgres psql -U navix -d navix -v ON_ERROR_STOP=1 \
  < .claude/skills/purge-prod-data/purge-borrowers.sql
```

## After a production purge

Borrowers mid-onboarding will hold a `navix_borrower` cookie for a customer row that no longer
exists, and the frontend caches `appId` in `localStorage`. They land on errors until they clear
site data or sign in again. Say so if the purge hits a live environment.
