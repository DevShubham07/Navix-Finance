# DhanBoost simulation environment

A separate training copy of the business, entered by signing in as `sim.admin@dhanboost.com` on the
normal staff login. Nothing in it can reach the production database. Design:
`docs/superpowers/specs/2026-11-09-simulation-admin-environment-design.md`.

**It holds real customer identity data. Delete it when training ends.**

Compliance sign-off confirmed by the owner on: 2026-11-09

## Get the password

    aws ssm get-parameter --profile navix-dev --region ap-south-1 --name /navix-sim-tools/admin-password --with-decryption --query Parameter.Value --output text

## Reset after a training session

Figures drift as trainees disburse and close cases. To reset, delete the instance and restore again:

    aws rds delete-db-instance --profile navix-dev --region ap-south-1 --db-instance-identifier navix-finance-sim --skip-final-snapshot --delete-automated-backups
    aws rds wait db-instance-deleted --profile navix-dev --region ap-south-1 --db-instance-identifier navix-finance-sim
    COMPLIANCE_SIGNOFF_CONFIRMED_BY_OWNER=yes bash scripts/sim/infra.sh restore
    bash scripts/sim/infra.sh tools prepare
    aws ecs update-service --profile navix-dev --region ap-south-1 --cluster navix-sim --service navix-sim-backend --force-new-deployment
    aws ecs wait services-stable --profile navix-dev --region ap-south-1 --cluster navix-sim --services navix-sim-backend
    bash scripts/sim/infra.sh tools loans
    bash scripts/sim/infra.sh tools verify

Production keeps receiving migrations. If the sim backend refuses to start after a restore because the
backup is newer than its image, rebuild from the latest `main` with `bash scripts/sim/infra.sh images`.

## Pause between sessions

    aws ecs update-service --profile navix-dev --region ap-south-1 --cluster navix-sim --service navix-sim-backend --desired-count 0
    aws rds stop-db-instance --profile navix-dev --region ap-south-1 --db-instance-identifier navix-finance-sim

AWS restarts a stopped database after 7 days. To resume, start the database, then set desired count to 1.

## Delete everything

    CONFIRM_TEARDOWN=navix-sim bash scripts/sim/infra.sh teardown

Then remove `SIM_LOGIN_EMAIL`, `SIM_BACKEND_BASE_URL` and `SIM_GATEWAY_KEY` from the GitHub repository secrets and
redeploy. With those unset the sim route is inert.

## Limits

- One account allows one active session, so one person is signed in at a time.
- Admin corrections gated by an OTP are not expected to work, because no messages can be sent.
- Made-up customers have no documents.
