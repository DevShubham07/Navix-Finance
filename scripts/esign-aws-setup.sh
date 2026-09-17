#!/usr/bin/env bash
#
# eSign callback config — the AWS writes the vendor-resilience change needs BEFORE it is deployed.
#
# Runs anywhere the AWS CLI is authenticated: AWS CloudShell (no profile needed — it inherits the
# console session) or a laptop (export AWS_PROFILE=navix-dev first). Idempotent, safe to re-run.
#
#   bash scripts/esign-aws-setup.sh                      # dev (default)
#   NAVIX_ENV=prod bash scripts/esign-aws-setup.sh       # another SSM prefix
#   SKIP_ECS=1 bash scripts/esign-aws-setup.sh           # SSM only, leave the ECS service alone
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

export AWS_REGION="${AWS_REGION:-ap-south-1}"
export AWS_DEFAULT_REGION="$AWS_REGION"
ENV_NAME="${NAVIX_ENV:-dev}"
PREFIX="/navix/${ENV_NAME}/navix/esign"
KMS_ALIAS="alias/navix-finance"
CLUSTER="navix-cluster"
SERVICE="navix-backend"
ALB_HOST="navix-alb-148184383.ap-south-1.elb.amazonaws.com"

# The PUBLIC backend host Signzy must be able to reach. NOT the Vercel frontend — the callback hits
# the Spring controller at /api/webhooks/signzy/contract, which SecurityConfig already permits.
# The ALB is HTTP-only today (aws.md §8). The callback only accelerates a poll that re-reads from
# Signzy, so a forged one cannot forge a signature. Override for prod or once HTTPS is in front.
CALLBACK_URL="${NAVIX_ESIGN_CALLBACK_URL:-http://${ALB_HOST}/api/webhooks/signzy/contract}"

echo "region=$AWS_REGION env=$ENV_NAME prefix=$PREFIX"
aws sts get-caller-identity --query 'Arn' --output text || { echo "AWS auth failed"; exit 1; }

echo
echo "=== 1) callback URL -> $PREFIX/callback-url ==="
echo "  $CALLBACK_URL"
aws ssm put-parameter --name "$PREFIX/callback-url" --type String \
  --value "$CALLBACK_URL" --overwrite --query 'Version' --output text | sed 's/^/  version /' \
  || { echo "  FAILED to write the callback URL"; exit 1; }

echo
echo "=== 2) callback secret -> $PREFIX/callback-secret (SecureString) ==="
# Keep an existing secret rather than rotating it on every run: rotation invalidates the callbacks of
# contracts already in flight, and a borrower mid-signature would lose their accelerator.
EXISTING="$(aws ssm get-parameter --name "$PREFIX/callback-secret" --with-decryption \
  --query 'Parameter.Value' --output text 2>/dev/null || true)"
if [ -n "$EXISTING" ] && [ "$EXISTING" != "None" ] && [ "${#EXISTING}" -ge 32 ]; then
  echo "  already set (${#EXISTING} chars) — left alone"
else
  SECRET="${NAVIX_ESIGN_CALLBACK_SECRET:-$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 48)}"
  if [ "${#SECRET}" -lt 32 ]; then echo "  could not generate a secret"; exit 1; fi
  # SecureString with the project KMS key, matching the other navix secrets. If the alias is not
  # readable, fall back to the account default key rather than leaving the parameter unset.
  aws ssm put-parameter --name "$PREFIX/callback-secret" --type SecureString --key-id "$KMS_ALIAS" \
      --value "$SECRET" --overwrite --query 'Version' --output text 2>/dev/null \
    || aws ssm put-parameter --name "$PREFIX/callback-secret" --type SecureString \
      --value "$SECRET" --overwrite --query 'Version' --output text \
    || { echo "  FAILED to write the callback secret"; exit 1; }
  echo "  written (48 random chars; never printed)"
fi

echo
echo "=== 3) verify both are readable ==="
aws ssm get-parameters --names "$PREFIX/callback-url" "$PREFIX/callback-secret" --with-decryption \
  --query 'Parameters[].[Name,Type]' --output text | sed 's/^/  ok  /'
MISSING="$(aws ssm get-parameters --names "$PREFIX/callback-url" "$PREFIX/callback-secret" \
  --query 'InvalidParameters' --output text)"
if [ -n "$MISSING" ] && [ "$MISSING" != "None" ]; then
  echo "  STILL MISSING: $MISSING — do not deploy yet"; exit 1
fi

if [ "${SKIP_ECS:-0}" = "1" ]; then
  echo; echo "SKIP_ECS=1 — leaving the ECS service untouched."; exit 0
fi

echo
echo "=== 4) ECS deployment safety — a bad boot must not take the service down ==="
# The startup guard turns a missing parameter into a crash-loop. That is only safe if ECS keeps the
# PREVIOUS task serving while the new one fails, which is what the circuit breaker buys.
# ci.yml only waits for services-stable; on its own it does not roll back.
CFG="$(aws ecs describe-services --cluster "$CLUSTER" --services "$SERVICE" \
  --query 'services[0].deploymentConfiguration' --output json)"
echo "$CFG" | sed 's/^/  /'
if echo "$CFG" | grep -q '"rollback": *true'; then
  echo "  circuit breaker with rollback is already enabled — nothing to change"
else
  echo "  enabling the circuit breaker (config change only; this does NOT start a new deployment)"
  aws ecs update-service --cluster "$CLUSTER" --service "$SERVICE" \
    --deployment-configuration \
      'deploymentCircuitBreaker={enable=true,rollback=true},minimumHealthyPercent=100,maximumPercent=200' \
    --query 'service.deploymentConfiguration' --output json | sed 's/^/  /' \
    || echo "  could not update the service — enable the circuit breaker before deploying"
fi

echo
echo "=== 5) the service that is serving right now ==="
curl -s -o /dev/null -w "  ALB /actuator/health -> HTTP %{http_code}\n" \
  "http://${ALB_HOST}/actuator/health" || echo "  ALB health check did not answer"

echo
echo "Done. Both parameters are set, so the new image will boot. Deploy next (aws.md §8)."
echo "After the deploy: run ONE end-to-end signing with an internal test borrower before telling"
echo "anyone. Every initiate is a real, billable, legally binding contract and there is no sandbox."
