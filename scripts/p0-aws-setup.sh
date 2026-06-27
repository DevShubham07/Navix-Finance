#!/usr/bin/env bash
#
# P0 — AWS infra writes for the NAVIX production migration.
# Reviewed-and-run-by-the-user script (Claude is not permitted to mutate shared
# cloud infra in auto mode). Reads secrets from ../.env — nothing secret is
# hardcoded here. Idempotent: safe to re-run.
#
#   bash scripts/p0-aws-setup.sh
#
set -uo pipefail
cd "$(dirname "$0")/.."

# --- load creds + AWS config from .env (sandbox_username / sandbox_pass / AWS_*) ---
set -a; source ./.env; set +a
export AWS_PROFILE="${AWS_PROFILE:-navix-dev}"
export AWS_REGION="${AWS_REGION:-ap-south-1}"

if [ -z "${sandbox_username:-}" ] || [ -z "${sandbox_pass:-}" ]; then
  echo "ERROR: sandbox_username / sandbox_pass not found in .env" >&2; exit 1
fi
echo "Using AWS_PROFILE=$AWS_PROFILE region=$AWS_REGION"
aws sts get-caller-identity --query 'Arn' --output text || { echo "AWS auth failed"; exit 1; }

SG_ID="sg-082443872704e48e4"
BUCKET="navix-finance-bucket"
KMS_ALIAS="alias/navix-finance"
DL_BASE="https://admin.fintrix.tech/__api/api/v1/"

echo
echo "=== 1) RDS security-group ingress for this host (port 5432) ==="
HOST_IP="$(curl -s --max-time 10 https://checkip.amazonaws.com)"
echo "host egress IP: $HOST_IP"
aws ec2 authorize-security-group-ingress --group-id "$SG_ID" \
  --protocol tcp --port 5432 --cidr "${HOST_IP}/32" \
  --query 'SecurityGroupRules[].SecurityGroupRuleId' --output text 2>&1 \
  | sed 's/^/  /' || true
echo "  (InvalidPermission.Duplicate just means the rule already exists — fine)"

echo
echo "=== 2) S3 bucket CORS (browser presigned PUT/GET) on $BUCKET ==="
aws s3api put-bucket-cors --bucket "$BUCKET" --cors-configuration '{
  "CORSRules":[{
    "AllowedHeaders":["*"],
    "AllowedMethods":["PUT","GET","HEAD"],
    "AllowedOrigins":["http://localhost:3000"],
    "ExposeHeaders":["ETag"],
    "MaxAgeSeconds":3000
  }]}'
echo "  CORS now:"
aws s3api get-bucket-cors --bucket "$BUCKET" --output json | sed 's/^/  /'

echo
echo "=== 3) Fintrix / DigiLocker creds -> SSM SecureString (KMS $KMS_ALIAS) ==="
put_secure () { # name value
  aws ssm put-parameter --type SecureString --key-id "$KMS_ALIAS" \
    --name "$1" --value "$2" --overwrite --query 'Version' --output text \
    | sed "s#^#  $1 -> v#"
}
put_plain () { # name value
  aws ssm put-parameter --type String \
    --name "$1" --value "$2" --overwrite --query 'Version' --output text \
    | sed "s#^#  $1 -> v#"
}
put_secure /navix/dev/navix/fintrix/client-id      "$sandbox_username"
put_secure /navix/dev/navix/fintrix/client-secret  "$sandbox_pass"
put_secure /navix/dev/navix/digilocker/client-id   "$sandbox_username"
put_secure /navix/dev/navix/digilocker/client-secret "$sandbox_pass"
put_plain  /navix/dev/navix/digilocker/base-url     "$DL_BASE"

echo
echo "=== verify SSM params present ==="
aws ssm get-parameters-by-path --path /navix/dev/ --recursive \
  --query 'Parameters[].Name' --output text | tr '\t' '\n' | sort | sed 's/^/  /'

echo
echo "P0 AWS setup complete. Next: run the backend (AWS_PROFILE=navix-dev NAVIX_ENV=dev)"
echo "and confirm  curl localhost:8080/actuator/health  -> UP."
