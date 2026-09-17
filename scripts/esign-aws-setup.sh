#!/usr/bin/env bash
#
# eSign callback config — the AWS writes the vendor-resilience change needs BEFORE it is deployed.
#
# Same posture as scripts/p0-aws-setup.sh: a reviewed-and-run-by-a-human script, because Claude does
# not mutate shared cloud infra. Idempotent — safe to re-run.
#
#   bash scripts/esign-aws-setup.sh              # dev  (default)
#   NAVIX_ENV=prod bash scripts/esign-aws-setup.sh
#
# WHY THIS EXISTS
#   navix.esign.callback-url defaulted to an empty string and SignzyEsignAdapter sent it verbatim.
#   Signzy's contract spec marks the field mandatory, so it rejected every contract with
#   400 "callbackUrl is not allowed to be empty" — 156 attempts across 119 borrowers between
#   2026-08-21 and 2026-09-16, not one success. Nobody noticed for four weeks because esignInit
#   catches the failure and quietly offers the drawn-signature fallback.
#
#   EsignConfig now REFUSES TO START without both parameters. That is deliberate: a misconfiguration
#   that degrades silently stays broken. It also means a deploy without these parameters crash-loops,
#   so run this first.
#
set -uo pipefail
cd "$(dirname "$0")/.."

export AWS_PROFILE="${AWS_PROFILE:-navix-dev}"
export AWS_REGION="${AWS_REGION:-ap-south-1}"
ENV_NAME="${NAVIX_ENV:-dev}"
PREFIX="/navix/${ENV_NAME}/navix/esign"
KMS_ALIAS="alias/navix-finance"
CLUSTER="navix-cluster"
SERVICE="navix-backend"

# The PUBLIC backend host Signzy must be able to reach. Not the Vercel frontend — the callback hits
# the Spring controller at /api/webhooks/signzy/contract, which SecurityConfig already permits.
# The ALB is HTTP-only today (aws.md §8); the callback only accelerates a poll that re-reads from
# Signzy, so a forged one cannot forge a signature. Override for prod.
CALLBACK_URL="${NAVIX_ESIGN_CALLBACK_URL:-http://navix-alb-148184383.ap-south-1.elb.amazonaws.com/api/webhooks/signzy/contract}"

echo "AWS_PROFILE=$AWS_PROFILE region=$AWS_REGION env=$ENV_NAME"
aws sts get-caller-identity --query 'Arn' --output text || { echo "AWS auth failed"; exit 1; }

echo
echo "=== 1) callback URL -> $PREFIX/callback-url ==="
echo "  $CALLBACK_URL"
aws ssm put-parameter --name "$PREFIX/callback-url" --type String \
  --value "$CALLBACK_URL" --overwrite --query 'Version' --output text | sed 's/^/  version /'

echo
echo "=== 2) callback secret -> $PREFIX/callback-secret (SecureString) ==="
# Keep an existing secret rather than rotating it on every run: rotating invalidates the callbacks
# of contracts already in flight, and a borrower mid-signature would lose their accelerator.
EXISTING="$(aws ssm get-parameter --name "$PREFIX/callback-secret" --with-decryption \
  --query 'Parameter.Value' --output text 2>/dev/null || true)"
if [ -n "$EXISTING" ] && [ "$EXISTING" != "None" ] && [ "${#EXISTING}" -ge 32 ]; then
  echo "  already set (${#EXISTING} chars) — left alone"
else
  SECRET="${NAVIX_ESIGN_CALLBACK_SECRET:-$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 48)}"
  aws ssm put-parameter --name "$PREFIX/callback-secret" --type SecureString --key-id "$KMS_ALIAS" \
    --value "$SECRET" --overwrite --query 'Version' --output text | sed 's/^/  version /'
  echo "  (48-char random secret written; it is never printed here)"
fi

echo
echo "=== 3) verify both are readable ==="
aws ssm get-parameters-by-path --path "$PREFIX" --with-decryption \
  --query 'Parameters[].[Name,Type]' --output text | sed 's/^/  /'

echo
echo "=== 4) ECS deployment safety — a bad boot must not take the service down ==="
# The guard above turns a missing parameter into a crash-loop. That is only safe if ECS keeps the
# PREVIOUS task serving while the new one fails, which is what the circuit breaker and
# minimumHealthyPercent=100 buy. ci.yml only waits for services-stable; it does not roll back.
aws ecs describe-services --cluster "$CLUSTER" --services "$SERVICE" \
  --query 'services[0].deploymentConfiguration' --output json | sed 's/^/  /'
echo
echo "  Want: minimumHealthyPercent 100 AND deploymentCircuitBreaker {enable:true, rollback:true}."
echo "  If either is missing, enable the circuit breaker BEFORE deploying:"
echo
echo "    aws ecs update-service --cluster $CLUSTER --service $SERVICE \\"
echo "      --deployment-configuration \\"
echo "        'deploymentCircuitBreaker={enable=true,rollback=true},minimumHealthyPercent=100,maximumPercent=200'"
echo
echo "Done. Deploy next (aws.md §8), then watch the task reach RUNNING — a crash-loop here means one"
echo "of the two parameters above is still wrong, and the message in the logs names which."
