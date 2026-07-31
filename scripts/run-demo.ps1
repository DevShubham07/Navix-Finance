# run-demo.ps1 — fully-offline local stack for the ADMIN dashboard demo.
#
# Differs from ../run-local.ps1 in one important way: it boots the backend with NO AWS at all.
# `backend/application.yml` declares `spring.config.import: optional:aws-parameterstore:/navix/<env>/`,
# and `backend/.env` (auto-loaded by spring-dotenv) sets AWS_PROFILE=navix-dev. Left alone, that
# combination makes SSM supply spring.datasource.* and the app silently talks to the dev RDS
# instance instead of your local Postgres. This script neutralises that by overriding
# SPRING_CONFIG_IMPORT with a no-op configtree and supplying static dummy AWS credentials so the
# S3 client/presigner beans still construct without reaching the network — the same pattern the
# project's own integration tests use (backend/navix-app/src/test/resources/application.yml).
#
# PREREQUISITES
#   - Docker Desktop running (Postgres is published on 5433; a native PostgreSQL owns 5432 here).
#   - JDK 21 at -JdkHome. A Java 17 JAVA_HOME fails the build with "release version 21 not supported".
#   - Rename backend/.env to backend/.env.aws-backup while demoing (this script warns if it is present).
#
# OFFLINE CAVEATS (accepted for the demo)
#   - Credit briefs show score + star rating + facts, but no PDF link (the S3 render step no-ops).
#   - Expense receipts and the payment-settings QR/PDF stay blank; document uploads are unavailable.
#
# USAGE (from the repo root)
#   .\scripts\run-demo.ps1              # start db + backend + frontend, each in its own window
#   .\scripts\run-demo.ps1 -Rebuild     # rebuild the backend jar first (required after backend changes)
#   .\scripts\run-demo.ps1 -NoFrontend  # backend only

param(
  [switch]$Rebuild,
  [switch]$NoBackend,
  [switch]$NoFrontend,
  [string]$JdkHome     = "C:\Program Files\Java\jdk-21",
  [int]   $DbPort      = 5433,
  [int]   $BackendPort = 8090
)

$ErrorActionPreference = "Stop"
$root     = Split-Path -Parent $PSScriptRoot
$backend  = Join-Path $root "backend"
$frontend = Join-Path $root "frontend"
$jar      = Join-Path $backend "navix-app\target\navix-app-0.0.1-SNAPSHOT.jar"

if (-not (Test-Path (Join-Path $JdkHome "bin\java.exe"))) {
  throw "JDK 21 not found at '$JdkHome'. Pass -JdkHome '<path-to-jdk-21>'."
}

if (Test-Path (Join-Path $backend ".env")) {
  Write-Host "WARNING: backend\.env exists. It sets AWS_PROFILE and will pull SSM config," -ForegroundColor Yellow
  Write-Host "         which can repoint the app at dev RDS. Rename it to .env.aws-backup." -ForegroundColor Yellow
}

# 1. Postgres (Docker) — the override publishes it on $DbPort.
Write-Host "==> Postgres (docker compose up -d)..." -ForegroundColor Cyan
Push-Location $root
docker compose up -d | Out-Host
Pop-Location

# 2. Backend, in its own window, fully offline.
if (-not $NoBackend) {
  if ($Rebuild -or -not (Test-Path $jar)) {
    # `clean` is NOT optional. Without it, stale .class files from an earlier build survive in
    # target/classes and get repackaged — e.g. the pre-V33 ApplicantProfile entity, which then
    # fails Hibernate's schema validation with "missing table [applicant_profile]" because V33
    # renamed that table to customer_profile.
    Write-Host "==> Building backend jar (JDK 21, mvnw clean install -DskipTests)..." -ForegroundColor Cyan
    Push-Location $backend
    $env:JAVA_HOME = $JdkHome
    & .\mvnw.cmd clean install -DskipTests -q
    if ($LASTEXITCODE -ne 0) { Pop-Location; throw "Backend build failed." }
    Pop-Location
  }

  Write-Host "==> Backend -> http://localhost:$BackendPort (new window)" -ForegroundColor Green
  $backendCmd =
    "`$env:JAVA_HOME='$JdkHome';" +
    "`$env:DB_URL='jdbc:postgresql://localhost:$DbPort/navix';" +
    "`$env:DB_USERNAME='navix'; `$env:DB_PASSWORD='navix';" +
    "`$env:NAVIX_ENV='dev';" +
    # Kill the AWS SSM parameter-store import (the RDS hazard).
    "`$env:SPRING_CONFIG_IMPORT='optional:configtree:./no-aws-config/';" +
    # Static dummy creds so the S3 beans construct offline instead of probing the credential chain.
    "`$env:SPRING_CLOUD_AWS_CREDENTIALS_ACCESS_KEY='test';" +
    "`$env:SPRING_CLOUD_AWS_CREDENTIALS_SECRET_KEY='test';" +
    "`$env:SPRING_CLOUD_AWS_REGION_STATIC='ap-south-1';" +
    "`$env:AWS_EC2_METADATA_DISABLED='true';" +
    # Borrower OTP is always 123456; no SMS gateway call.
    "`$env:NAVIX_SMS_MOCK='true';" +
    # Bundled bureau report -> real 1-5 star credit briefs with no vendor call.
    "`$env:NAVIX_BUREAU_FIXTURE='classpath:samplepan.json';" +
    "`$env:NAVIX_EMAIL_PROVIDER='log'; `$env:NAVIX_SES_EVENTS_ENABLED='false';" +
    "`$env:AUTH_SECRET='dhanboost-demo-local-secret-6f4a2c8e91b7';" +
    # Postgres rejects the JVM's Asia/Calcutta alias. Quote the -D args: PowerShell's native
    # argument parser mangles unquoted `-Dkey.sub=value` into two tokens.
    "& '$JdkHome\bin\java.exe' '-Duser.timezone=Asia/Kolkata' '-Dserver.port=$BackendPort' " +
    "'-jar' '$jar'"
  Start-Process powershell -WorkingDirectory $backend -ArgumentList "-NoExit","-Command",$backendCmd
}

# 3. Frontend (Next.js dev), in its own window. .env.local already points at :8090.
if (-not $NoFrontend) {
  if (-not (Test-Path (Join-Path $frontend "node_modules"))) {
    Write-Host "==> Installing frontend deps (npm install)..." -ForegroundColor Cyan
    Push-Location $frontend; npm install | Out-Host; Pop-Location
  }
  Write-Host "==> Frontend -> http://localhost:3000 (new window)" -ForegroundColor Green
  Start-Process powershell -WorkingDirectory $frontend -ArgumentList "-NoExit","-Command","npm run dev"
}

Write-Host ""
Write-Host "DhanBoost demo stack:" -ForegroundColor Yellow
Write-Host "  Frontend  http://localhost:3000        Staff console: /staff/login"
Write-Host "  Backend   http://localhost:$BackendPort  (Swagger: /swagger-ui.html)"
Write-Host "  Postgres  localhost:$DbPort            Adminer http://localhost:8081"
Write-Host ""
Write-Host "  Admin login: meera.krishnan@navix.example / Admin@12345"
Write-Host "  Next: .\scripts\seed-demo-data.ps1   then   .\scripts\seed-demo-data.ps1 -Verify"
Write-Host ""
Write-Host "  Sanity check: the backend log's JDBC URL must read localhost:$DbPort, NOT an RDS host." -ForegroundColor Yellow
