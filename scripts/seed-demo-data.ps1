# seed-demo-data.ps1 — populate every ADMIN-console surface for the walkthrough demo.
#
# Replaces the removed scripts/populate-demo-data.ps1, which predated the JWT seam-flip
# (it authenticated with X-Demo-Actor-* headers that no longer exist, posted `applicantId`
# instead of `customerId`, sent an `aadhaar` profile field dropped in V35, and never created
# the application_verification rows that submit-kyc now hard-gates on).
#
# PREREQUISITES
#   - Stack up via  .\scripts\run-demo.ps1   (backend :8090, Postgres :5433, frontend :3000)
#   - Backend booted with NAVIX_SMS_MOCK=true (borrower OTP 123456) and
#     NAVIX_BUREAU_FIXTURE=classpath:samplepan.json (real 1-5 star credit briefs offline)
#   - psql on PATH (C:\Program Files\PostgreSQL\17\bin\psql)
#
# USAGE
#   .\scripts\seed-demo-data.ps1              # reset + seed everything (~5-10 min)
#   .\scripts\seed-demo-data.ps1 -SkipReset   # add to existing data (may hit ACTIVE_APPLICATION)
#   .\scripts\seed-demo-data.ps1 -Verify      # seed nothing; audit every admin surface
#
# IDENTITY SCHEME
#   Persona NNN -> mobile 9819000NNN, customerId 9000NNN, PAN AAAPD0NNNA.
#   customerId is derived by the backend from the last 7 digits of the mobile and claimed
#   once in borrower_mobile (V40), so the mobiles must not collide on their last 7 digits.
#
# WHAT NEEDS RAW SQL (and why)
#   - loan.disbursed_on / due_date backdating: LoanMath.dueDateFromSalary always stamps a
#     future date, and loan OVERDUE is compute-on-read from due_date. Nothing in the codebase
#     ever writes LoanStatus.OVERDUE.
#   - loan_application.status DEFAULTED / WRITTEN_OFF: no code path writes them.
#   - Dashboard trend spreading and the ADMIN notification inbox: see seed-demo-sql.sql.

param(
  [string]$BackendBase = "http://localhost:8090",
  [int]   $DbPort      = 5433,
  [switch]$Verify,
  [switch]$SkipReset,
  [string]$PsqlPath    = "C:\Program Files\PostgreSQL\17\bin\psql.exe"
)

$ErrorActionPreference = "Stop"
$scriptDir = $PSScriptRoot
$repoRoot  = Split-Path -Parent $scriptDir

$script:tokens  = @{}
$script:created = @{}
$script:step    = 0

# ---------------------------------------------------------------- helpers

function Say($msg, $colour = "Gray") { Write-Host $msg -ForegroundColor $colour }

function Head($msg) {
  Write-Host ""
  Write-Host "=== $msg " -ForegroundColor Cyan -NoNewline
  Write-Host ("=" * [Math]::Max(0, 60 - $msg.Length)) -ForegroundColor Cyan
}

# Single call wrapper. Invoke-RestMethod throws on non-2xx, so unwrap the backend's
# ApiResponse envelope on success and surface error.code on failure.
function Api {
  param(
    [string]$Method,
    [string]$Path,
    $Body,
    [string]$Token,
    [switch]$Tolerant
  )
  $headers = @{ Authorization = "Bearer $Token" }
  $uri = "$BackendBase$Path"
  try {
    if ($null -eq $Body) {
      $r = Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -ContentType "application/json"
    } else {
      $json = $Body | ConvertTo-Json -Depth 6 -Compress
      $r = Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -ContentType "application/json" -Body $json
    }
    return $r.data
  } catch {
    $code = "HTTP_ERROR"
    $detail = $_.Exception.Message
    try {
      $stream = $_.Exception.Response.GetResponseStream()
      $reader = New-Object System.IO.StreamReader($stream)
      $raw = $reader.ReadToEnd()
      $parsed = $raw | ConvertFrom-Json
      if ($parsed.error) { $code = $parsed.error.code; $detail = $parsed.error.message }
    } catch { }
    if ($Tolerant) {
      Say ("      ! {0} {1} -> {2}: {3}" -f $Method, $Path, $code, $detail) "DarkYellow"
      return $null
    }
    throw ("{0} {1} failed -> {2}: {3}" -f $Method, $Path, $code, $detail)
  }
}

function Login-Staff([string]$Role) {
  if ($script:tokens.ContainsKey($Role)) { return $script:tokens[$Role] }
  $emails = @{
    KYC_APPROVER         = "ananya.rao@navix.example"
    CREDIT_EXECUTIVE     = "rahul.mehta@navix.example"
    CREDIT_HEAD          = "priya.nair@navix.example"
    DISBURSEMENT_HEAD    = "vikram.shah@navix.example"
    ACCOUNTANT           = "deepa.iyer@navix.example"
    COLLECTION_HEAD      = "arjun.patel@navix.example"
    COLLECTION_EXECUTIVE = "sana.khan@navix.example"
    ADMIN                = "meera.krishnan@navix.example"
  }
  $body = @{ email = $emails[$Role]; password = "Admin@12345" } | ConvertTo-Json -Compress
  $r = Invoke-RestMethod -Method Post -Uri "$BackendBase/api/auth/staff/login" -ContentType "application/json" -Body $body
  $script:tokens[$Role] = $r.data.token
  return $r.data.token
}

function Login-Borrower([string]$Mobile, [string]$Name) {
  $b1 = @{ mobile = $Mobile } | ConvertTo-Json -Compress
  Invoke-RestMethod -Method Post -Uri "$BackendBase/api/auth/borrower/otp/request" -ContentType "application/json" -Body $b1 | Out-Null
  $b2 = @{ mobile = $Mobile; otp = "123456"; name = $Name } | ConvertTo-Json -Compress
  $r = Invoke-RestMethod -Method Post -Uri "$BackendBase/api/auth/borrower/login" -ContentType "application/json" -Body $b2
  return $r.data.token
}

# NOTE: never redirect psql's stderr with 2>&1 in Windows PowerShell 5.1. psql writes its
# RAISE NOTICE output to stderr, and redirecting a native command's stderr wraps every line
# in an ErrorRecord (NativeCommandError) which $ErrorActionPreference='Stop' then turns into
# a terminating error even when psql exited 0. Check $LASTEXITCODE instead, and relax
# ErrorActionPreference around the call.
function Invoke-Psql([string[]]$PsqlArgs) {
  $env:PGPASSWORD = "navix"
  $prev = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  $out = & $PsqlPath -h localhost -p $DbPort -U navix -d navix @PsqlArgs
  $code = $LASTEXITCODE
  $ErrorActionPreference = $prev
  return @{ Out = $out; Code = $code }
}

function Sql([string]$Text) {
  $r = Invoke-Psql @("-v", "ON_ERROR_STOP=1", "-q", "-c", $Text)
  if ($r.Code -ne 0) { throw "psql failed (exit $($r.Code)): $($r.Out)" }
  return $r.Out
}

function SqlFile([string]$Path) {
  $r = Invoke-Psql @("-v", "ON_ERROR_STOP=1", "-f", $Path)
  if ($r.Code -ne 0) { throw "psql -f $Path failed (exit $($r.Code)): $($r.Out)" }
  return $r.Out
}

function SqlScalar([string]$Text) {
  $r = Invoke-Psql @("-tAc", $Text)
  return ($r.Out | Select-Object -First 1)
}

# ---------------------------------------------------------------- persona table

$FirstNames = @("Aarav","Vivaan","Aditya","Vihaan","Arjun","Sai","Reyansh","Ayaan","Krishna","Ishaan",
           "Ananya","Diya","Aadhya","Saanvi","Myra","Anika","Navya","Kiara","Ira","Riya",
           "Rohan","Kabir","Dev","Nikhil","Rahul","Manav","Yash","Tanvi","Meera","Priya",
           "Karan","Siddharth","Neha","Pooja","Rhea","Varun","Aditi","Sneha","Rajat","Kavya",
           "Harsh","Nisha","Gaurav","Shreya","Amit","Divya","Farhan","Lakshmi","Om","Sara","Tara","Umesh")
$LastNames  = @("Sharma","Verma","Patel","Reddy","Nair","Iyer","Gupta","Mehta","Singh","Desai",
           "Kulkarni","Rao","Joshi","Chopra","Bose","Menon","Shah","Bhat","Pillai","Sinha")
$EMPLOYERS = @("Infosys Ltd","TCS","Wipro Technologies","HCL Technologies","Tech Mahindra",
               "Accenture India","Cognizant","Capgemini India","LTIMindtree","Zoho Corp")
$BANKS = @("HDFC Bank","ICICI Bank","Axis Bank","State Bank of India","Kotak Mahindra Bank")
$CITIES = @("12 MG Road, Bengaluru 560001","44 Linking Road, Mumbai 400050",
            "8 Park Street, Kolkata 700016","210 Anna Salai, Chennai 600002",
            "17 Sector 29, Gurugram 122001","5 Banjara Hills, Hyderabad 500034")
$PURPOSES = @("Medical expenses","Home repair","School fees","Travel","Debt consolidation",
              "Wedding expenses","Vehicle repair","Electronics purchase")

function Persona([int]$N) {
  $i = $N - 10
  $name = "{0} {1}" -f $FirstNames[$i % $FirstNames.Count], $LastNames[$i % $LastNames.Count]
  # Salary between 35k and 120k, deterministic per persona so re-runs match.
  $salary = (35 + (($i * 7) % 86)) * 100000
  $nnn = "{0:D3}" -f $N
  return @{
    N        = $N
    Name     = $name
    Mobile   = "9819000$nnn"
    Customer = [int64]("9000$nnn")
    Pan      = "AAAPD0${nnn}A"
    Email    = "demo.$nnn@dhanboost.example"
    Salary   = [int64]$salary
    Employer = $EMPLOYERS[$i % $EMPLOYERS.Count]
    Bank     = $BANKS[$i % $BANKS.Count]
    Address  = $CITIES[$i % $CITIES.Count]
    Purpose  = $PURPOSES[$i % $PURPOSES.Count]
    Dob      = (Get-Date "1990-01-01").AddDays($i * 97).ToString("yyyy-MM-dd")
    SalaryDay = 25 + ($i % 5)
  }
}

# 25% of salary, floored to the nearest Rs.100 (LoanMath.eligibleLimitPaise).
function Eligible([int64]$SalaryPaise) {
  $raw = [Math]::Floor($SalaryPaise * 0.25)
  return [int64]([Math]::Floor($raw / 10000) * 10000)
}

# ---------------------------------------------------------------- the KYC ladder

# Creates the application and drives it to KYC_PENDING through the real verification gate.
# submit-kyc requires PASS|REVIEW rows for PAN, EMAIL, ADDRESS, AADHAAR, BUREAU, SALARY,
# PENNY_DROP, SELFIE plus customer_profile.agreement_accepted. AADHAAR + DIGILOCKER are
# auto-REVIEWed by allowAadhaarManualReview at submit time, so seven must be supplied here.
function New-Application([hashtable]$P, [string]$Admin) {
  $app = Api POST "/api/applications" @{ customerId = $P.Customer } $Admin
  $script:created[$P.N] = @{ AppId = $app.id; Customer = $P.Customer; Name = $P.Name }
  return $app.id
}

function Save-Profile([int]$AppId, [hashtable]$P, [string]$Admin) {
  Api PUT "/api/applications/$AppId/profile" @{
    fullName           = $P.Name
    pan                = $P.Pan
    mobile             = $P.Mobile
    dob                = $P.Dob
    address            = $P.Address
    employer           = $P.Employer
    employmentStatus   = "SALARIED"
    monthlySalaryPaise = $P.Salary
    salaryBank         = $P.Bank
    email              = $P.Email
  } $Admin | Out-Null
}

function Pass-Check([int]$AppId, [string]$Check, [string]$Admin, [bool]$Decision = $true) {
  Api POST "/api/applications/$AppId/verifications/$Check/decision" `
      @{ decision = $Decision; notes = "Seeded for demo" } $Admin -Tolerant | Out-Null
}

# $Checks controls how far the ladder runs: "all" | "partial" | "fail" | "none".
function Complete-Kyc([int]$AppId, [hashtable]$P, [string]$Admin, [string]$Checks = "all") {
  Save-Profile $AppId $P $Admin
  if ($Checks -eq "none") { return }

  # Real, fully-local salary step: always PASS and it sets eligibleLimit (a manual SALARY
  # override would leave monthlySalaryPaise/eligibleLimit null and break the limit display).
  Api POST "/api/applications/$AppId/verify/salary" `
      @{ monthlySalaryPaise = $P.Salary; slipObjectKeys = @(); salaryCreditDay = $P.SalaryDay } $Admin | Out-Null

  if ($Checks -eq "partial") {
    Pass-Check $AppId "PAN" $Admin
    Pass-Check $AppId "EMAIL" $Admin
    return
  }
  if ($Checks -eq "fail") {
    Pass-Check $AppId "PAN" $Admin
    Pass-Check $AppId "EMAIL" $Admin
    Pass-Check $AppId "PENNY_DROP" $Admin $false   # a real FAIL for the triage bucket
    return
  }

  # Bundled bureau fixture -> genuine PASS + 1-5 star credit brief, no vendor call.
  Api POST "/api/applications/$AppId/verify/bureau" $null $Admin -Tolerant | Out-Null
  # Only this endpoint sets agreement_accepted; a manual AGREEMENT override does NOT.
  Api POST "/api/applications/$AppId/verify/agreement" `
      @{ versions = @("loan-agreement-v1","kfs-v1","privacy-v1") } $Admin | Out-Null
  # PAN and SELFIE must go through the manual override: verifyPan has no try/catch (500
  # offline) and verifySelfie presigns before its try block (500 without S3).
  foreach ($c in @("PAN","EMAIL","ADDRESS","PENNY_DROP","SELFIE")) { Pass-Check $AppId $c $Admin }
}

function Progress([hashtable]$P, [string]$State, [int]$AppId) {
  $script:step++
  Say ("[{0,3}] {1}  {2,-18} -> {3}  (app {4})" -f $script:step, $P.Customer, $P.Name, $State, $AppId) "Gray"
}

# Drives a persona from nothing to $Target. Returns the application id.
function Seed-Persona([int]$N, [string]$Target, [string]$Admin) {
  $P = Persona $N
  $appId = New-Application $P $Admin

  switch ($Target) {
    "DRAFT_NONE"    { Complete-Kyc $appId $P $Admin "none";    Progress $P "DRAFT (no checks)" $appId; return $appId }
    "DRAFT_PARTIAL" { Complete-Kyc $appId $P $Admin "partial"; Progress $P "DRAFT (partial)"   $appId; return $appId }
    "DRAFT_FAIL"    { Complete-Kyc $appId $P $Admin "fail";    Progress $P "DRAFT (has fail)"  $appId; return $appId }
  }

  Complete-Kyc $appId $P $Admin "all"
  Api POST "/api/applications/$appId/submit-kyc" $null $Admin | Out-Null
  if ($Target -eq "KYC_PENDING") { Progress $P "KYC_PENDING" $appId; return $appId }

  if ($Target -eq "KYC_REJECTED") {
    Api POST "/api/applications/$appId/kyc-decision" @{ decision = $false; notes = "PAN mismatch with bureau record" } $Admin | Out-Null
    Progress $P "KYC_REJECTED" $appId; return $appId
  }

  Api POST "/api/applications/$appId/kyc-decision" @{ decision = $true; notes = "Documents verified" } $Admin | Out-Null

  $limit  = Eligible $P.Salary
  $amount = [int64]([Math]::Floor(($limit * 0.8) / 10000) * 10000)
  if ($amount -lt 100000) { $amount = 100000 }
  Api POST "/api/applications/$appId/apply" `
      @{ amountPaise = $amount; purpose = $P.Purpose; eligibleLimitPaise = $limit; salaryCreditDay = $P.SalaryDay } $Admin | Out-Null
  $script:created[$N].Amount = $amount
  if ($Target -eq "APPLIED") { Progress $P "KYC_APPROVED (applied)" $appId; return $appId }

  if ($Target -eq "CANCELLED") {
    Api POST "/api/applications/$appId/cancel" $null $Admin | Out-Null
    Progress $P "CANCELLED" $appId; return $appId
  }

  # Assign to a real Credit Executive so the credit queue shows an assignee. NOT tolerant:
  # a silent INVALID_ASSIGNEE here leaves the app at KYC_APPROVED and the next call dies
  # with a confusing ILLEGAL_TRANSITION instead of pointing at the real cause.
  $execId = @(2,3,4)[$N % 3]
  Api POST "/api/applications/$appId/assign" @{ executiveId = $execId } (Login-Staff CREDIT_HEAD) | Out-Null
  if ($Target -eq "CREDIT_EXEC_PENDING") { Progress $P "CREDIT_EXEC_PENDING" $appId; return $appId }

  Api POST "/api/applications/$appId/exec-decision" `
      @{ decision = $true; notes = "Income and obligations verified; recommend approval" } (Login-Staff CREDIT_EXECUTIVE) | Out-Null
  if ($Target -eq "CREDIT_HEAD_PENDING") { Progress $P "CREDIT_HEAD_PENDING" $appId; return $appId }

  if ($Target -eq "REJECTED") {
    Api POST "/api/applications/$appId/head-decision" @{ decision = $false; notes = "Obligations exceed policy threshold" } (Login-Staff CREDIT_HEAD) | Out-Null
    Progress $P "REJECTED (credit)" $appId; return $appId
  }

  Api POST "/api/applications/$appId/head-decision" @{ decision = $true; notes = "Approved within policy" } (Login-Staff CREDIT_HEAD) | Out-Null
  if ($Target -eq "DISBURSEMENT_PENDING") { Progress $P "DISBURSEMENT_PENDING" $appId; return $appId }

  if ($Target -eq "ACCOUNTANT_PENDING") {
    # No txnRef -> routes to the accountant instead of the fast-path.
    Api POST "/api/applications/$appId/disbursement-decision" @{ decision = $true } (Login-Staff DISBURSEMENT_HEAD) | Out-Null
    Progress $P "ACCOUNTANT_PENDING" $appId; return $appId
  }

  if ($Target -eq "DISBURSEMENT_FAILED") {
    Api POST "/api/applications/$appId/disbursement-decision" @{ decision = $true } (Login-Staff DISBURSEMENT_HEAD) | Out-Null
    Api POST "/api/applications/$appId/accountant-validate" @{ decision = $false; notes = "Beneficiary IFSC rejected by bank" } (Login-Staff ACCOUNTANT) | Out-Null
    Progress $P "DISBURSEMENT_FAILED" $appId; return $appId
  }

  # ACTIVE: disbursement-head fast-path (txnRef present) -> DISBURSED -> ACTIVE, mints the loan.
  Api POST "/api/applications/$appId/disbursement-decision" `
      @{ decision = $true; txnRef = ("DISB-{0}" -f $P.Customer) } (Login-Staff DISBURSEMENT_HEAD) | Out-Null
  $view = Api GET "/api/applications/$appId" $null $Admin
  $script:created[$N].LoanId = $view.loanId
  Progress $P "ACTIVE (loan $($view.loanId))" $appId
  return $appId
}

# ---------------------------------------------------------------- verify mode

function Invoke-Verify {
  $admin = Login-Staff ADMIN
  $acct  = Login-Staff ACCOUNTANT
  $dh    = Login-Staff DISBURSEMENT_HEAD
  $from  = (Get-Date).AddDays(-40).ToString("yyyy-MM-dd")
  $to    = (Get-Date).AddDays(1).ToString("yyyy-MM-dd")

  $checks = @(
    @{ Label = "Applications: KYC_PENDING";     Path = "/api/applications?status=KYC_PENDING";            Tok = $admin }
    @{ Label = "Applications: KYC_APPROVED";    Path = "/api/applications?status=KYC_APPROVED";           Tok = $admin }
    @{ Label = "Applications: CREDIT_EXEC";     Path = "/api/applications?status=CREDIT_EXEC_PENDING";    Tok = $admin }
    @{ Label = "Applications: CREDIT_HEAD";     Path = "/api/applications?status=CREDIT_HEAD_PENDING";    Tok = $admin }
    @{ Label = "Applications: DISBURSEMENT";    Path = "/api/applications?status=DISBURSEMENT_PENDING";   Tok = $admin }
    @{ Label = "Applications: ACCOUNTANT";      Path = "/api/applications?status=ACCOUNTANT_PENDING";     Tok = $admin }
    @{ Label = "Applications: DISB_FAILED";     Path = "/api/applications?status=DISBURSEMENT_FAILED";    Tok = $admin }
    @{ Label = "Applications: ACTIVE";          Path = "/api/applications?status=ACTIVE";                 Tok = $admin }
    @{ Label = "Applications: CLOSED";          Path = "/api/applications?status=CLOSED";                 Tok = $admin }
    @{ Label = "Applications: REJECTED";        Path = "/api/applications?status=REJECTED";               Tok = $admin }
    @{ Label = "Applications: CANCELLED";       Path = "/api/applications?status=CANCELLED";              Tok = $admin }
    @{ Label = "Applications: PRE_APPROVED";    Path = "/api/applications?status=PRE_APPROVED";           Tok = $admin }
    @{ Label = "Applications: REVIEW_PENDING";  Path = "/api/applications?status=REVIEW_PENDING";         Tok = $admin }
    @{ Label = "Applications: DEFAULTED";       Path = "/api/applications?status=DEFAULTED";              Tok = $admin }
    @{ Label = "Applications: WRITTEN_OFF";     Path = "/api/applications?status=WRITTEN_OFF";            Tok = $admin }
    @{ Label = "Credit queue";                  Path = "/api/applications/credit-queue";                  Tok = Login-Staff CREDIT_HEAD }
    @{ Label = "All-applications register";     Path = "/api/applications/all";                           Tok = $admin }
    @{ Label = "Verification overview";         Path = "/api/applications/verifications/overview";        Tok = $admin }
    @{ Label = "Customers roll-up";             Path = "/api/customers";                                  Tok = $admin }
    @{ Label = "Transactions ledger";           Path = "/api/loan/transactions?from=$from&to=$to";        Tok = $acct }
    @{ Label = "Pending repayments";            Path = "/api/loan/pending-repayments";                    Tok = $acct }
    @{ Label = "Collections: cases";            Path = "/api/collections/cases";                          Tok = $admin }
    @{ Label = "Collections: collectible";      Path = "/api/collections/loans";                          Tok = $admin }
    @{ Label = "Collections: settlements";      Path = "/api/collections/settlements";                    Tok = $admin }
    @{ Label = "Admin: expenses";               Path = "/api/admin/expenses";                             Tok = $admin }
    @{ Label = "Admin: blocklist";              Path = "/api/admin/blocklist";                            Tok = $admin }
    @{ Label = "Admin: staff roster";           Path = "/api/staff";                                      Tok = $admin }
    @{ Label = "Admin: invites";                Path = "/api/staff/invites";                              Tok = $admin }
    @{ Label = "Referral payouts (PENDING)";    Path = "/api/referral/payouts?status=PENDING";            Tok = $dh }
    @{ Label = "Referral payouts (PAID)";       Path = "/api/referral/payouts?status=PAID";               Tok = $dh }
  )

  Head "Verification"
  $empty = 0
  foreach ($c in $checks) {
    $data = Api GET $c.Path $null $c.Tok -Tolerant
    $n = 0
    if ($null -ne $data) {
      if ($data -is [array]) { $n = $data.Count }
      # The verification overview returns an object carrying rows[] + tallies, and the paged
      # endpoints return {content: [...]} - count the collection, not the wrapper.
      elseif ($data.PSObject.Properties.Name -contains "rows")    { $n = @($data.rows).Count }
      elseif ($data.PSObject.Properties.Name -contains "content") { $n = @($data.content).Count }
      else { $n = 1 }
    }
    if ($n -gt 0) {
      Write-Host ("  PASS  {0,-32} {1,4}" -f $c.Label, $n) -ForegroundColor Green
    } else {
      Write-Host ("  EMPTY {0,-32} {1,4}" -f $c.Label, 0) -ForegroundColor Red
      $empty++
    }
  }

  # This endpoint returns the bare count as `data`, not an object.
  $unread = Api GET "/api/notifications/unread-count" $null $admin -Tolerant
  if ($null -ne $unread -and [int]$unread -gt 0) {
    Write-Host ("  PASS  {0,-32} {1,4}" -f "ADMIN notification bell", [int]$unread) -ForegroundColor Green
  } else {
    Write-Host ("  EMPTY {0,-32} {1,4}" -f "ADMIN notification bell", 0) -ForegroundColor Red
    $empty++
  }

  Write-Host ""
  if ($empty -eq 0) {
    Write-Host "  All surfaces populated. Safe to record." -ForegroundColor Green
    exit 0
  }
  Write-Host "  $empty surface(s) EMPTY - see DEMO_WALKTHROUGH.md troubleshooting table." -ForegroundColor Red
  exit 1
}

# ---------------------------------------------------------------- main

Head "Preflight"
try {
  $h = Invoke-RestMethod -Uri "$BackendBase/actuator/health" -TimeoutSec 10
  Say "  backend $BackendBase -> $($h.status)" "Green"
} catch { throw "Backend not reachable at $BackendBase. Start it with .\scripts\run-demo.ps1" }
if (-not (Test-Path $PsqlPath)) {
  # Fall back to whatever psql is on PATH before giving up.
  $onPath = Get-Command psql -ErrorAction SilentlyContinue
  if ($onPath) { $PsqlPath = $onPath.Source }
  else { throw "psql not found at $PsqlPath and none on PATH. Pass -PsqlPath '<path to psql.exe>'." }
}
$dbv = SqlScalar "select count(*) from flyway_schema_history;"
Say "  postgres localhost:$DbPort -> $dbv migrations applied" "Green"

if ($Verify) { Invoke-Verify }

if (-not $SkipReset) {
  Head "Reset"
  SqlFile (Join-Path $scriptDir "reset-test-data.sql") | Out-Null
  Say "  demo data wiped (staff, payment settings and feature flags preserved)" "Green"
}

$admin = Login-Staff ADMIN
Say "  authenticated as ADMIN (Meera Krishnan)" "Green"

# reset-test-data.sql deliberately preserves staff_user, so the role change and the disabled
# account this script creates at the end would survive into the next run and starve `assign`
# of an active Credit Executive. Restore the roster to its seeded shape first, which keeps
# repeated runs identical.
foreach ($id in 2,3,4) {
  Api PUT "/api/staff/$id" @{ role = "CREDIT_EXECUTIVE"; status = "ACTIVE" } $admin -Tolerant | Out-Null
}
Say "  credit-executive roster normalised (staff 2, 3, 4 active)" "Green"

Head "Pipeline queues"
foreach ($n in 10..12) { Seed-Persona $n "KYC_PENDING"          $admin | Out-Null }
foreach ($n in 13..15) { Seed-Persona $n "APPLIED"              $admin | Out-Null }
foreach ($n in 16..18) { Seed-Persona $n "CREDIT_EXEC_PENDING"  $admin | Out-Null }
foreach ($n in 19..21) { Seed-Persona $n "CREDIT_HEAD_PENDING"  $admin | Out-Null }
foreach ($n in 22..24) { Seed-Persona $n "DISBURSEMENT_PENDING" $admin | Out-Null }
foreach ($n in 25..26) { Seed-Persona $n "ACCOUNTANT_PENDING"   $admin | Out-Null }
Seed-Persona 27 "DISBURSEMENT_FAILED" $admin | Out-Null

Head "Live, overdue and closed loans"
foreach ($n in 28..40) { Seed-Persona $n "ACTIVE" $admin | Out-Null }

Head "Terminal and rejected states"
Seed-Persona 41 "KYC_REJECTED" $admin | Out-Null
Seed-Persona 42 "REJECTED"     $admin | Out-Null
Seed-Persona 43 "REJECTED"     $admin | Out-Null
Seed-Persona 44 "CANCELLED"    $admin | Out-Null

Head "Verification-dashboard personas (stay pre-submit)"
Seed-Persona 45 "DRAFT_NONE"    $admin | Out-Null
Seed-Persona 46 "DRAFT_FAIL"    $admin | Out-Null
Seed-Persona 47 "DRAFT_PARTIAL" $admin | Out-Null

# ---------------------------------------------------------------- backdating

Head "Backdating loans (SQL)"
# DPD bands for the collections buckets. Personas 31-35 become overdue; 36-38 get backdated
# so they can be closed with a realistic paid-late history; 28-30 stay comfortably current.
$bands = @{ 31 = 4; 32 = 18; 33 = 45; 34 = 75; 35 = 110; 36 = 12; 37 = 20; 38 = 30 }
foreach ($n in $bands.Keys) {
  $loanId = $script:created[$n].LoanId
  if (-not $loanId) { continue }
  $dpd = $bands[$n]
  $disb = $dpd + 28
  Sql "update loan set disbursed_on = current_date - $disb, due_date = current_date - $dpd where id = $loanId;" | Out-Null
  Say ("  loan {0,-4} (cust {1}) -> DPD {2}" -f $loanId, $script:created[$n].Customer, $dpd) "Gray"
}
# Spread the still-current disbursals across the last 30 days so the dashboard trend
# sparklines and the MONTH-default transactions ledger both have movement.
$spread = 2
foreach ($n in 28..30 + 39..40) {
  $loanId = $script:created[$n].LoanId
  if (-not $loanId) { continue }
  Sql "update loan set disbursed_on = current_date - $spread where id = $loanId and due_date > current_date - $spread;" | Out-Null
  $spread += 5
}

# ---------------------------------------------------------------- repayments

Head "Repayments"
$acct = Login-Staff ACCOUNTANT

# Fully repay 36-38 so they CLOSE. Outstanding is penalty-aware on every read, so the exact
# figure must be read AFTER backdating - paying the no-penalty total leaves a residue open.
foreach ($n in 36..38) {
  $loanId = $script:created[$n].LoanId
  if (-not $loanId) { continue }
  $out = Api GET "/api/loan/$loanId/outstanding" $null $admin
  $due = $out.outstandingPaise
  if (-not $due) { $due = $out.totalPaise }
  $paidOn = (Get-Date).AddDays(-1 * (3 + $n % 7)).ToString("yyyy-MM-dd")
  $pay = Api POST "/api/loan/$loanId/repayments" `
      @{ amountPaise = $due; method = "UPI"; txnRef = "UPI-CLOSE-$n"; proofUrl = "upi-receipt-$n.png"; paidOn = $paidOn } $admin
  Api POST "/api/loan/$loanId/repayments/$($pay.id)/verify" $null $acct | Out-Null
  Say ("  loan {0,-4} closed with {1} paise" -f $loanId, $due) "Gray"
}

# Partial verified repayments on live loans -> populates the ledger and the trend chart.
foreach ($n in 28..30) {
  $loanId = $script:created[$n].LoanId
  if (-not $loanId) { continue }
  $paidOn = (Get-Date).AddDays(-1 * (2 + $n % 9)).ToString("yyyy-MM-dd")
  $pay = Api POST "/api/loan/$loanId/repayments" `
      @{ amountPaise = 200000; method = "BANK_TRANSFER"; txnRef = "NEFT-PART-$n"; proofUrl = "neft-$n.pdf"; paidOn = $paidOn } $admin
  Api POST "/api/loan/$loanId/repayments/$($pay.id)/verify" $null $acct | Out-Null
}

# Unverified repayments -> the accountant's verify queue.
foreach ($n in 31..33) {
  $loanId = $script:created[$n].LoanId
  if (-not $loanId) { continue }
  Api POST "/api/loan/$loanId/repayments" `
      @{ amountPaise = 150000; method = "UPI"; txnRef = "UPI-PENDING-$n"; proofUrl = "screenshot-$n.jpg"; paidOn = (Get-Date).ToString("yyyy-MM-dd") } $admin | Out-Null
}
Say "  3 repayments left PENDING_VERIFICATION for the accountant queue" "Gray"

# One rejected repayment - reject BEFORE verifying (PAYMENT_ALREADY_VERIFIED otherwise).
$loanId = $script:created[34].LoanId
if ($loanId) {
  $bad = Api POST "/api/loan/$loanId/repayments" `
      @{ amountPaise = 50000; method = "UPI"; txnRef = "UPI-BAD-34"; proofUrl = "blurred.jpg"; paidOn = (Get-Date).ToString("yyyy-MM-dd") } $admin
  Api POST "/api/loan/$loanId/repayments/$($bad.id)/reject" $null $acct -Tolerant | Out-Null
  Say "  1 repayment rejected (unreadable proof)" "Gray"
}

# ---------------------------------------------------------------- collections

Head "Collections"
$colHead = Login-Staff COLLECTION_HEAD
$colExec = Login-Staff COLLECTION_EXECUTIVE
$caseIds = @()
# Open cases on 3 of the 5 overdue loans; 34 and 35 stay case-free so the
# "Collectible loans" panel is not empty (opening a case flips the loan to IN_COLLECTIONS).
foreach ($n in 31..33) {
  $loanId = $script:created[$n].LoanId
  if (-not $loanId) { continue }
  $case = Api POST "/api/collections/cases" @{ loanId = $loanId } $colHead
  $caseIds += $case.id
  Api POST "/api/collections/cases/$($case.id)/assign" @{ officerId = 9 } $colHead -Tolerant | Out-Null
  $interactions = @(
    @{ type = "CALL";  outcome = "NO_ANSWER" },
    @{ type = "CALL";  outcome = "CONNECTED" },
    @{ type = "SMS";   outcome = "PROMISE_TO_PAY"; promiseToPayDate = (Get-Date).AddDays(5).ToString("yyyy-MM-dd") }
  )
  foreach ($i in $interactions) {
    Api POST "/api/collections/cases/$($case.id)/interactions" $i $colExec -Tolerant | Out-Null
  }
  Say ("  case {0} on loan {1} - officer Sana Khan, 3 interactions" -f $case.id, $loanId) "Gray"
}

# Two settlements on two different cases: SoD requires proposer != approver, and a settlement
# can only go one way (SETTLEMENT_NOT_PENDING), so two are needed to show approve AND reject.
if ($caseIds.Count -ge 2) {
  $s1 = Api POST "/api/collections/cases/$($caseIds[0])/settlements" @{ settlementAmountPaise = 700000 } $colExec -Tolerant
  if ($s1) { Api POST "/api/collections/settlements/$($s1.id)/approve" $null $colHead -Tolerant | Out-Null; Say "  settlement $($s1.id) APPROVED" "Gray" }
  $s2 = Api POST "/api/collections/cases/$($caseIds[1])/settlements" @{ settlementAmountPaise = 300000 } $colExec -Tolerant
  if ($s2) { Api POST "/api/collections/settlements/$($s2.id)/reject" $null $colHead -Tolerant | Out-Null; Say "  settlement $($s2.id) REJECTED" "Gray" }
  $s3 = Api POST "/api/collections/cases/$($caseIds[2])/settlements" @{ settlementAmountPaise = 550000 } $colExec -Tolerant
  if ($s3) { Say "  settlement $($s3.id) left PROPOSED for the live approval demo" "Gray" }
}

# ---------------------------------------------------------------- admin registers

Head "Company expenses"
$expenses = @(
  @{ d = "AWS infrastructure - July";      a = 4850000; p = "Amazon Web Services";  n = "ECS Fargate, RDS, S3"; ago = 3 },
  @{ d = "Office rent - July";             a = 12000000; p = "Prestige Estates";    n = "Koramangala unit";     ago = 6 },
  @{ d = "SMS gateway top-up";             a = 1500000; p = "UltronSMS";            n = "Transactional credits"; ago = 9 },
  @{ d = "Credit bureau API credits";      a = 3200000; p = "Signzy Technologies";  n = "Experian + CRIF pulls"; ago = 12 },
  @{ d = "Legal retainer - Q2";            a = 7500000; p = "Khaitan Associates";   n = "NBFC compliance";      ago = 16 },
  @{ d = "Laptops for credit team";        a = 21000000; p = "Dell India";          n = "6 units";              ago = 20 },
  @{ d = "Statutory audit fees";           a = 9000000; p = "S R Batliboi and Co";  n = "FY26 audit";           ago = 24 },
  @{ d = "Performance marketing - July";   a = 6500000; p = "Google India";         n = "Search campaign";      ago = 28 }
)
foreach ($e in $expenses) {
  # receiptObjectKey is deliberately omitted: the list endpoint presigns every key, which is
  # unreliable without S3 credentials in this offline demo.
  Api POST "/api/admin/expenses" @{
    description = $e.d; amountPaise = $e.a; paidTo = $e.p; notes = $e.n
    expenseDate = (Get-Date).AddDays(-1 * $e.ago).ToString("yyyy-MM-dd")
  } $admin -Tolerant | Out-Null
}
Say "  $($expenses.Count) expenses recorded across the last 30 days" "Gray"

Head "Fraud blocklist"
$blocks = @(
  @{ type = "PAN";          value = "BLKPD9911X";         reason = "Confirmed first-party fraud - forged salary slips" },
  @{ type = "PHONE";        value = "9800000001";         reason = "Multiple applications with conflicting identities" },
  @{ type = "BANK_ACCOUNT"; value = "50100099887766";     reason = "Penny-drop name mismatch across 4 applicants" },
  @{ type = "DEVICE";       value = "dev-8f2a41c9e7b3";   reason = "Device linked to a known fraud ring" }
)
foreach ($b in $blocks) { Api POST "/api/admin/blocklist" $b $admin -Tolerant | Out-Null }
Say "  $($blocks.Count) blocklist entries added" "Gray"

Head "Staff administration"
Api POST "/api/staff/invites" @{ email = "priyanka.das@navix.example"; role = "CREDIT_EXECUTIVE" } $admin -Tolerant | Out-Null
Api POST "/api/staff/invites" @{ email = "rakesh.menon@navix.example"; role = "COLLECTION_EXECUTIVE" } $admin -Tolerant | Out-Null
Api POST "/api/staff" @{ email = "tanya.roy@navix.example"; name = "Tanya Roy"; role = "ACCOUNTANT"; password = "Admin@12345" } $admin -Tolerant | Out-Null
Say "  2 invites, 1 new staff member" "Gray"
# The role change + disable are applied at the very END of the run (see "Staff roster
# changes"): they consume Credit Executives that later personas still need for `assign`.

Head "Salary edits and remarks (audit trail)"
foreach ($n in 28..29) {
  $cid = $script:created[$n].Customer
  $cur = Api GET "/api/customers/$cid" $null $admin -Tolerant
  if (-not $cur) { continue }
  $p = $cur.profile
  # Full replace, not a patch: omitted fields are nulled AND logged as changes.
  $newSalary = [int64]($p.monthlySalaryPaise * 1.12)
  Api PUT "/api/customers/$cid/profile" @{
    fullName            = $p.fullName
    address             = $p.address
    employer            = $p.employer
    employmentStatus    = $p.employmentStatus
    monthlySalaryPaise  = $newSalary
    annualSalaryPaise   = ($newSalary * 12)
    salaryPercentage    = 100
    incrementPercentage = 12
    salaryBank          = $p.salaryBank
  } $admin -Tolerant | Out-Null
  Say ("  customer {0} salary revised -> {1} paise (logged)" -f $cid, $newSalary) "Gray"
}
$remarks = @("Called to confirm revised salary; documents on file.",
             "Requested an extension to the salary date - approved verbally.",
             "Good repayment history; eligible for a limit review next cycle.")
$i = 0
foreach ($n in 28..30) {
  $cid = $script:created[$n].Customer
  Api POST "/api/customers/$cid/remarks" @{ body = $remarks[$i] } $admin -Tolerant | Out-Null
  $i++
}
Say "  3 customer remarks added" "Gray"

# ---------------------------------------------------------------- reborrow + referral

Head "Reborrow (PRE_APPROVED / REVIEW_PENDING)"
# 048 has a clean closed loan -> PRE_APPROVED. 049 repaid late -> REVIEW_PENDING.
# reborrow needs a BORROWER token: an ADMIN token passes requireRole but then misreads the
# staff id as the customer id.
foreach ($pair in @(@{ N = 48; Late = $false }, @{ N = 49; Late = $true })) {
  $n = $pair.N
  $P = Persona $n
  $appId = Seed-Persona $n "ACTIVE" $admin
  $loanId = $script:created[$n].LoanId
  if (-not $loanId) { continue }
  if ($pair.Late) {
    Sql "update loan set disbursed_on = current_date - 50, due_date = current_date - 20 where id = $loanId;" | Out-Null
  } else {
    Sql "update loan set disbursed_on = current_date - 40, due_date = current_date - 12 where id = $loanId;" | Out-Null
  }
  $out = Api GET "/api/loan/$loanId/outstanding" $null $admin
  $due = $out.outstandingPaise
  if (-not $due) { $due = $out.totalPaise }
  # Late persona pays AFTER the due date -> hasPastDelinquency -> REVIEW_PENDING on reborrow.
  if ($pair.Late) { $paidOn = (Get-Date).AddDays(-2).ToString("yyyy-MM-dd") }
  else            { $paidOn = (Get-Date).AddDays(-13).ToString("yyyy-MM-dd") }
  $pay = Api POST "/api/loan/$loanId/repayments" `
      @{ amountPaise = $due; method = "UPI"; txnRef = "UPI-RB-$n"; proofUrl = "rb-$n.png"; paidOn = $paidOn } $admin
  Api POST "/api/loan/$loanId/repayments/$($pay.id)/verify" $null $acct | Out-Null

  $btok = Login-Borrower $P.Mobile $P.Name
  $rb = Api POST "/api/applications/reborrow" $null $btok -Tolerant
  if ($rb) { Say ("  customer {0} reborrow -> {1}" -f $P.Customer, $rb.status) "Gray" }
}

Head "Referral chain"
$P50 = Persona 50
$P51 = Persona 51
# Referrer needs a borrower session to mint a code.
$tok50 = Login-Borrower $P50.Mobile $P50.Name
$code = Api GET "/api/referral/me" $null $tok50 -Tolerant
if ($code -and $code.code) {
  Say "  referrer $($P50.Customer) code = $($code.code)" "Gray"
  # The referred borrower must have ZERO loans (NOT_NEW_BORROWER) - apply before disbursal.
  $tok51 = Login-Borrower $P51.Mobile $P51.Name
  Api POST "/api/referral/apply" @{ code = $code.code } $tok51 -Tolerant | Out-Null
  Seed-Persona 51 "ACTIVE" $admin | Out-Null
  $dh = Login-Staff DISBURSEMENT_HEAD
  $pending = Api GET "/api/referral/payouts?status=PENDING" $null $dh -Tolerant
  if ($pending -and @($pending).Count -gt 0) {
    $firstPayout = @($pending)[0]
    Api POST "/api/referral/payouts/$($firstPayout.id)/pay" @{ txnRef = "UPI-REWARD-001" } $dh -Tolerant | Out-Null
    Say "  $(@($pending).Count) payouts created; 1 settled, rest left PENDING" "Gray"
  }
}
# Give the referrer an application of their own so they appear in the customer roll-up.
Seed-Persona 50 "APPLIED" $admin | Out-Null

# ---------------------------------------------------------------- staff roster changes

# Deliberately last: both of these remove an active Credit Executive, and every persona
# above needs one for the `assign` step (INVALID_ASSIGNEE otherwise).
Head "Staff roster changes"
# Promotion, for the audit story on /staff/admin/staff.
Api PUT "/api/staff/3" @{ role = "CREDIT_HEAD"; status = "ACTIVE" } $admin -Tolerant | Out-Null
Say "  Kabir Singh (3) promoted to CREDIT_HEAD" "Gray"
# Disable a SPARE persona only - never id 10 (the ADMIN you sign in as), id 9 (the
# collections officer) or id 2 (the last remaining Credit Executive).
Api DELETE "/api/staff/4" $null $admin -Tolerant | Out-Null
Say "  Neha Gupta (4) disabled" "Gray"

# ---------------------------------------------------------------- SQL finishing pass

Head "SQL finishing pass"
$d = $script:created[39]
if ($d -and $d.AppId) { Sql "update loan_application set status = 'DEFAULTED' where id = $($d.AppId);" | Out-Null; Say "  app $($d.AppId) -> DEFAULTED" "Gray" }
$w = $script:created[40]
if ($w -and $w.AppId) { Sql "update loan_application set status = 'WRITTEN_OFF' where id = $($w.AppId);" | Out-Null; Say "  app $($w.AppId) -> WRITTEN_OFF" "Gray" }

SqlFile (Join-Path $scriptDir "seed-demo-sql.sql") | Out-Null
Say "  trend spreading + ADMIN notification inbox seeded" "Green"

# ---------------------------------------------------------------- done

$state = Join-Path $scriptDir ".demo-seed-state.json"
# ConvertTo-Json in PowerShell 5.1 cannot serialise a hashtable with non-string keys
# (NonStringKeyInDictionary), and the persona index is an int - restring the keys first.
$stateMap = @{}
foreach ($k in $script:created.Keys) { $stateMap["$k"] = $script:created[$k] }
$stateMap | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 $state

Head "Done"
Say "  applications created : $($script:created.Count)" "Green"
Say "  id map written to    : $state" "Green"
Write-Host ""
Say "  Next:  .\scripts\seed-demo-data.ps1 -Verify" "Yellow"
Say "  Then:  http://localhost:3000/staff/login  (meera.krishnan@navix.example / Admin@12345)" "Yellow"
Say "  Script: DEMO_WALKTHROUGH.md" "Yellow"
