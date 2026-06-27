# run-local.ps1 — local dev launcher for NAVIX (this machine).
#
# LOCAL-ONLY / UNTRACKED. Encodes the port + JDK overrides this machine needs:
#   - Postgres on host port 5433 (5432 is taken by a native Postgres) — see docker-compose.override.yml
#   - Backend on 8090 (127.0.0.1:8080 is held by a Windows http.sys reservation)
#   - JDK 21 (the build won't compile on 17) + Asia/Kolkata (Postgres rejects the JVM's Asia/Calcutta alias)
#
# Usage (from the repo root, in your own terminal so it survives this Claude session & laptop sleeps):
#   ./run-local.ps1            # start db + backend + frontend, each in its own window
#   ./run-local.ps1 -Rebuild   # rebuild the backend jar first (after backend source changes)
#   ./run-local.ps1 -NoFrontend / -NoBackend   # start only part of the stack

param(
  [switch]$Rebuild,
  [switch]$NoBackend,
  [switch]$NoFrontend,
  [string]$JdkHome  = "C:\Program Files\Java\jdk-21",
  [int]   $DbPort   = 5433,
  [int]   $BackendPort = 8090
)

$ErrorActionPreference = "Stop"
$root     = $PSScriptRoot
$backend  = Join-Path $root "backend"
$frontend = Join-Path $root "frontend"
$jar      = Join-Path $backend "navix-app\target\navix-app-0.0.1-SNAPSHOT.jar"

if (-not (Test-Path (Join-Path $JdkHome "bin\java.exe"))) {
  throw "JDK 21 not found at '$JdkHome'. Pass -JdkHome '<path-to-jdk-21>'."
}

# 1. Postgres (Docker) — idempotent; the override remaps host port to $DbPort.
Write-Host "==> Postgres (docker compose up -d)..." -ForegroundColor Cyan
Push-Location $root
docker compose up -d | Out-Host
Pop-Location

# 2. Backend (Spring Boot) in its own window.
if (-not $NoBackend) {
  if ($Rebuild -or -not (Test-Path $jar)) {
    Write-Host "==> Building backend jar (mvnw install -DskipTests)..." -ForegroundColor Cyan
    Push-Location $backend
    $env:JAVA_HOME = $JdkHome
    & .\mvnw install -DskipTests -q
    if ($LASTEXITCODE -ne 0) { Pop-Location; throw "Backend build failed." }
    Pop-Location
  }
  Write-Host "==> Backend -> http://localhost:$BackendPort (new window)" -ForegroundColor Green
  $backendCmd =
    "`$env:JAVA_HOME='$JdkHome';" +
    "`$env:DB_URL='jdbc:postgresql://localhost:$DbPort/navix';" +
    "`$env:DB_USERNAME='navix'; `$env:DB_PASSWORD='navix';" +
    "& '$JdkHome\bin\java.exe' -Duser.timezone=Asia/Kolkata -Dserver.port=$BackendPort " +
    "-jar '$jar'"
  Start-Process powershell -WorkingDirectory $backend -ArgumentList "-NoExit","-Command",$backendCmd
}

# 3. Frontend (Next.js dev) in its own window.
if (-not $NoFrontend) {
  if (-not (Test-Path (Join-Path $frontend "node_modules"))) {
    Write-Host "==> Installing frontend deps (npm install)..." -ForegroundColor Cyan
    Push-Location $frontend; npm install | Out-Host; Pop-Location
  }
  if (-not (Test-Path (Join-Path $frontend ".env.local"))) {
    Write-Host "==> Writing frontend/.env.local (BACKEND_BASE_URL -> :$BackendPort)..." -ForegroundColor Cyan
    @(
      "NEXT_PUBLIC_API_BASE_URL=http://localhost:3000/api",
      "BACKEND_BASE_URL=http://localhost:$BackendPort",
      "AUTH_SECRET=local-dev-secret-please-change-0a1b2c3d4e5f6071",
      "NEXT_PUBLIC_DEMO_MODE=true"
    ) | Set-Content -Encoding utf8 (Join-Path $frontend ".env.local")
  }
  Write-Host "==> Frontend -> http://localhost:3000 (new window)" -ForegroundColor Green
  Start-Process powershell -WorkingDirectory $frontend -ArgumentList "-NoExit","-Command","npm run dev"
}

Write-Host ""
Write-Host "NAVIX local stack:" -ForegroundColor Yellow
Write-Host "  Frontend  http://localhost:3000"
Write-Host "  Backend   http://localhost:$BackendPort  (Swagger: /swagger-ui.html)"
Write-Host "  Postgres  localhost:$DbPort   Adminer http://localhost:8081"
Write-Host "  Demo: borrower /login (OTP 123456) | staff /staff/login (pick a role)"
