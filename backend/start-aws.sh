#!/usr/bin/env bash
# start-aws.sh — Run the NAVIX backend locally connected to AWS RDS + S3 + SSM.
# Prerequisites:
#   1. aws configure --profile navix-dev  (one-time; add the navix-dev IAM key/secret)
#   2. Your IP must be in the RDS SG — the script adds it automatically if you have
#      ec2:AuthorizeSecurityGroupIngress permission.
#   3. Java 21 must be on JAVA_HOME (sdkman: sdk use java 21.0.11-tem)
#   4. Run from backend/  (mvnw must be present)

set -euo pipefail

export AWS_PROFILE=navix-dev
export AWS_REGION=ap-south-1
export NAVIX_ENV=dev
export NAVIX_SMS_MOCK=true     # demo OTP 123456 — no real SMS

# ── 1. Verify AWS credentials ────────────────────────────────────────────────
echo "→ Checking AWS credentials (profile: navix-dev)…"
IDENTITY=$(aws sts get-caller-identity --profile navix-dev 2>&1) || {
  echo ""
  echo "ERROR: AWS credentials not configured for the 'navix-dev' profile."
  echo "Run:  aws configure --profile navix-dev"
  echo "Then enter the Access Key ID and Secret for arn:aws:iam::382188661325:user/navix-dev"
  exit 1
}
echo "  ✓ $(echo "$IDENTITY" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["Arn"])')"

# ── 2. Add current IP to RDS security group (idempotent) ────────────────────
MY_IP=$(curl -s https://checkip.amazonaws.com)
RDS_SG="sg-082443872704e48e4"
echo "→ Ensuring $MY_IP/32 is allowed on RDS SG ($RDS_SG) port 5432…"
aws ec2 authorize-security-group-ingress \
  --group-id "$RDS_SG" \
  --protocol tcp --port 5432 --cidr "${MY_IP}/32" \
  --profile navix-dev --region ap-south-1 2>&1 | grep -v "already exists" || true
echo "  ✓ RDS access OK"

# ── 3. Verify RDS reachability ───────────────────────────────────────────────
RDS_HOST="navix-finance-dev.cf22os0umu8l.ap-south-1.rds.amazonaws.com"
echo "→ Checking TCP connectivity to RDS ($RDS_HOST:5432)…"
nc -z -w 5 "$RDS_HOST" 5432 && echo "  ✓ RDS reachable" || {
  echo "  ✗ Cannot reach RDS on port 5432 — SG may still be propagating (wait 10s and retry)"
  exit 1
}

# ── 4. Build if needed ──────────────────────────────────────────────────────
if [[ ! -f navix-app/target/navix-app-*.jar ]]; then
  echo "→ Building navix-app JAR (first run — this takes ~1 min)…"
  JAVA_HOME="${JAVA_HOME:-$(sdk home java 21.0.11-tem 2>/dev/null || echo '')}" \
    ./mvnw -pl navix-app -am package -DskipTests -q
fi

# ── 5. Start the backend (Spring Cloud AWS pulls SSM params on boot) ─────────
echo ""
echo "→ Starting NAVIX backend (SSM → RDS → S3)…"
echo "   Swagger UI: http://localhost:8080/swagger-ui.html"
echo "   Health:     http://localhost:8080/actuator/health"
echo ""

./mvnw -pl navix-app spring-boot:run \
  -Dspring-boot.run.jvmArguments="-DAWS_PROFILE=navix-dev -DAWS_REGION=ap-south-1 -DNAVIX_ENV=dev -DNAVIX_SMS_MOCK=true"
