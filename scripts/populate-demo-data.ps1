<#
.SYNOPSIS
  NAVIX Finance — per-stage demo data populator.

.DESCRIPTION
  Drives the REAL backend REST API (http://localhost:8090) with demo-actor headers
  (X-Demo-Actor-Id/-Name/-Role) to create one loan application parked at EVERY stage of
  the lifecycle, plus a primary borrower with a rich account history (closed + active +
  in-review). Because the flow service computes money (paise), salary-linked due dates and
  the application_event audit trail, the seeded data is realistic and shows up in every
  staff queue and (once Part A lands) the borrower account menu.

  The only thing the live API cannot produce is an OVERDUE / past-due loan — the server
  always stamps the due date in the FUTURE. So for the OVERDUE / past-delinquency personas
  the script runs one small SQL UPDATE (via `docker compose exec db psql`) to backdate the
  loan's disbursed_on / due_date. Pass -SkipBackdate to skip that (those personas then stay
  ACTIVE/CLOSED instead).

.PREREQUISITES
  The local stack must be up (Postgres :5433 + backend :8090) — e.g. `./run-local.ps1`.
  Seeded staff ids come from Flyway V10 (1=KYC_APPROVER, 2/3/4=CREDIT_EXECUTIVE,
  5=CREDIT_HEAD, 6=DISBURSEMENT_HEAD, 7=ACCOUNTANT, 8=COLLECTION_HEAD, 10=ADMIN).

.EXAMPLE
  ./scripts/populate-demo-data.ps1
  ./scripts/populate-demo-data.ps1 -BackendBase http://localhost:8090 -SkipBackdate
#>
[CmdletBinding()]
param(
  [string]$BackendBase = "http://localhost:8090",
  [string]$DbService   = "db",          # docker compose service name for Postgres
  [switch]$SkipBackdate                  # skip the SQL backdate (no Docker / no OVERDUE personas)
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$Today    = (Get-Date)

# --- Seeded staff actors (Flyway V10) -------------------------------------------------
$KYC   = @{ Id = 1;  Name = "Ananya Rao";     Role = "KYC_APPROVER" }
$EXEC  = @{ Id = 2;  Name = "Rahul Mehta";    Role = "CREDIT_EXECUTIVE" }
$HEAD  = @{ Id = 5;  Name = "Priya Nair";     Role = "CREDIT_HEAD" }
$DISB  = @{ Id = 6;  Name = "Vikram Shah";    Role = "DISBURSEMENT_HEAD" }
$ACCT  = @{ Id = 7;  Name = "Deepa Iyer";     Role = "ACCOUNTANT" }
$COLL  = @{ Id = 8;  Name = "Arjun Patel";    Role = "COLLECTION_HEAD" }
$ADMIN = @{ Id = 10; Name = "Meera Krishnan"; Role = "ADMIN" }

$script:IdSeq  = 0
$script:Results = @()

function New-BorrowerActor([long]$ApplicantId, [string]$Name) {
  return @{ Id = $ApplicantId; Name = $Name; Role = "BORROWER" }
}

# --- Low-level API helper -------------------------------------------------------------
function Invoke-Api {
  param(
    [Parameter(Mandatory)] [ValidateSet("GET","POST","PUT","DELETE")] [string]$Method,
    [Parameter(Mandatory)] [string]$Path,
    [Parameter(Mandatory)] [hashtable]$Actor,
    [object]$Body
  )
  $headers = @{
    "X-Demo-Actor-Id"   = [string]$Actor.Id
    "X-Demo-Actor-Name" = [string]$Actor.Name
    "X-Demo-Actor-Role" = [string]$Actor.Role
    "Accept"            = "application/json"
  }
  $uri = "$BackendBase$Path"
  try {
    if ($null -ne $Body) {
      $json = $Body | ConvertTo-Json -Depth 8 -Compress
      $resp = Invoke-RestMethod -Uri $uri -Method $Method -Headers $headers -Body $json -ContentType "application/json"
    } else {
      $resp = Invoke-RestMethod -Uri $uri -Method $Method -Headers $headers
    }
  } catch {
    $detail = ""
    try {
      $stream = $_.Exception.Response.GetResponseStream()
      $reader = New-Object System.IO.StreamReader($stream)
      $detail = $reader.ReadToEnd()
    } catch { }
    throw "API $Method $Path failed: $($_.Exception.Message) $detail"
  }
  if ($null -ne $resp.PSObject.Properties["success"] -and -not $resp.success) {
    throw "API $Method $Path returned error: $($resp.error.code) - $($resp.error.message)"
  }
  return $resp.data
}

# --- Unique KYC identity per profile (satisfies the pan/aadhaar/mobile unique indexes) -
function New-Identity {
  $script:IdSeq++
  $rp = Get-Random -Minimum 1000 -Maximum 9999
  $ra = Get-Random -Minimum 0 -Maximum 9999999
  $rm = Get-Random -Minimum 0 -Maximum 9999999
  return [pscustomobject]@{
    pan     = "DMO" + ("{0:D2}" -f $script:IdSeq) + $rp + "Z"          # 10 chars
    aadhaar = "9"   + ("{0:D4}" -f $script:IdSeq) + ("{0:D7}" -f $ra)  # 12 digits
    mobile  = "8"   + ("{0:D2}" -f $script:IdSeq) + ("{0:D7}" -f $rm)  # 10 digits
  }
}

function Set-Profile([long]$AppId, [hashtable]$Borrower, [string]$Name) {
  $id = New-Identity
  $profile = @{
    fullName           = $Name
    pan                = $id.pan
    aadhaar            = $id.aadhaar
    mobile             = $id.mobile
    dob                = "1991-07-12"
    address            = "221B Demo Layout, Indiranagar, Bengaluru 560038"
    employer           = "Acme Software Pvt Ltd"
    employmentStatus   = "SALARIED"
    monthlySalaryPaise = 5000000
    salaryBank         = "HDFC Bank"
  }
  Invoke-Api PUT "/api/applications/$AppId/profile" $Borrower $profile | Out-Null
}

# --- SQL backdate (the one non-API step) ----------------------------------------------
function Backdate-Loan {
  param([long]$LoanId, [int]$DisbursedDaysAgo, [int]$DueDaysAgo)
  if ($SkipBackdate) { return }
  $disb = $Today.AddDays(-$DisbursedDaysAgo).ToString("yyyy-MM-dd")
  $due  = $Today.AddDays(-$DueDaysAgo).ToString("yyyy-MM-dd")
  $sql  = "UPDATE loan SET disbursed_on='$disb', due_date='$due' WHERE id=$LoanId;"
  Push-Location $RepoRoot
  try {
    docker compose exec -T $DbService psql -U navix -d navix -c $sql | Out-Null
  } finally {
    Pop-Location
  }
}

# --- Core: walk an application to a target stage --------------------------------------
# Stages: DRAFT, KYC_PENDING, KYC_APPROVED, APPLIED, CREDIT_EXEC_PENDING,
#         CREDIT_HEAD_PENDING, DISBURSEMENT_PENDING, ACCOUNTANT_PENDING, ACTIVE
function Drive-Application {
  param(
    [Parameter(Mandatory)] [long]$ApplicantId,
    [Parameter(Mandatory)] [string]$Name,
    [Parameter(Mandatory)] [string]$StopAt,
    [bool]$AttachProfile     = $true,
    [long]$AmountPaise       = 1000000,    # Rs 10,000
    [long]$EligibleLimitPaise= 1250000,    # Rs 12,500 (25% of Rs 50,000)
    [int]$SalaryDay          = 28,
    [string]$Purpose         = "Personal expense",
    [string]$DisbTxnRef      = $null       # if set, Disbursement Head fast-paths to ACTIVE
  )
  $bor = New-BorrowerActor $ApplicantId $Name
  $app = Invoke-Api POST "/api/applications" $bor @{ applicantId = $ApplicantId }
  $appId = [long]$app.id
  if ($AttachProfile) { Set-Profile $appId $bor $Name }
  if ($StopAt -eq "DRAFT") { return @{ appId = $appId; loanId = $null; status = "DRAFT" } }

  Invoke-Api POST "/api/applications/$appId/submit-kyc" $bor | Out-Null
  if ($StopAt -eq "KYC_PENDING") { return @{ appId = $appId; loanId = $null; status = "KYC_PENDING" } }

  Invoke-Api POST "/api/applications/$appId/kyc-decision" $KYC @{ decision = $true; notes = "Documents verified (demo seed)" } | Out-Null
  if ($StopAt -eq "KYC_APPROVED") { return @{ appId = $appId; loanId = $null; status = "KYC_APPROVED" } }

  Invoke-Api POST "/api/applications/$appId/apply" $bor @{ amountPaise = $AmountPaise; purpose = $Purpose; eligibleLimitPaise = $EligibleLimitPaise; salaryCreditDay = $SalaryDay } | Out-Null
  if ($StopAt -eq "APPLIED") { return @{ appId = $appId; loanId = $null; status = "KYC_APPROVED (applied)" } }

  Invoke-Api POST "/api/applications/$appId/assign" $HEAD @{ executiveId = 2 } | Out-Null
  if ($StopAt -eq "CREDIT_EXEC_PENDING") { return @{ appId = $appId; loanId = $null; status = "CREDIT_EXEC_PENDING" } }

  Invoke-Api POST "/api/applications/$appId/exec-decision" $EXEC @{ decision = $true; notes = "Recommended (demo seed)" } | Out-Null
  if ($StopAt -eq "CREDIT_HEAD_PENDING") { return @{ appId = $appId; loanId = $null; status = "CREDIT_HEAD_PENDING" } }

  # Head id 5 != Exec id 2 -> SoD satisfied. Auto-routes to DISBURSEMENT_PENDING.
  Invoke-Api POST "/api/applications/$appId/head-decision" $HEAD @{ decision = $true; notes = "Approved (demo seed)" } | Out-Null
  if ($StopAt -eq "DISBURSEMENT_PENDING") { return @{ appId = $appId; loanId = $null; status = "DISBURSEMENT_PENDING" } }

  if ($DisbTxnRef) {
    # Fast-path: Disbursement Head finalizes directly (skips the accountant).
    Invoke-Api POST "/api/applications/$appId/disbursement-decision" $DISB @{ decision = $true; txnRef = $DisbTxnRef } | Out-Null
  } else {
    Invoke-Api POST "/api/applications/$appId/disbursement-decision" $DISB @{ decision = $true } | Out-Null
    if ($StopAt -eq "ACCOUNTANT_PENDING") { return @{ appId = $appId; loanId = $null; status = "ACCOUNTANT_PENDING" } }
    Invoke-Api POST "/api/applications/$appId/accountant-validate" $ACCT @{ decision = $true; txnRef = "DISB-$appId" } | Out-Null
  }

  $final = Invoke-Api GET "/api/applications/$appId" $bor
  return @{ appId = $appId; loanId = [long]$final.loanId; status = $final.status }
}

function Repay-Full([long]$LoanId, [hashtable]$Borrower, [string]$Tag) {
  $owed = [long]((Invoke-Api GET "/api/loan/$LoanId/outstanding" $Borrower).outstandingPaise)
  $pay  = Invoke-Api POST "/api/loan/$LoanId/repayments" $Borrower @{ amountPaise = $owed; method = "UPI"; txnRef = "$Tag-$LoanId"; paidOn = $Today.ToString("yyyy-MM-dd") }
  Invoke-Api POST "/api/loan/$LoanId/repayments/$($pay.id)/verify" $ACCT | Out-Null
}

function Repay-Partial([long]$LoanId, [hashtable]$Borrower) {
  $owed = [long]((Invoke-Api GET "/api/loan/$LoanId/outstanding" $Borrower).outstandingPaise)
  $half = [long][math]::Floor($owed / 2)
  $pay  = Invoke-Api POST "/api/loan/$LoanId/repayments" $Borrower @{ amountPaise = $half; method = "BANK_TRANSFER"; txnRef = "PART-$LoanId"; paidOn = $Today.ToString("yyyy-MM-dd") }
  Invoke-Api POST "/api/loan/$LoanId/repayments/$($pay.id)/verify" $ACCT | Out-Null
}

function Add-Result($Persona, $ApplicantId, $AppId, $Stage, $LoanId) {
  $script:Results += [pscustomobject]@{
    Persona = $Persona; ApplicantId = $ApplicantId; AppId = $AppId; Stage = $Stage; LoanId = $LoanId
  }
  $loanTxt = if ($LoanId) { "loan #$LoanId" } else { "" }
  Write-Host ("  [ok] {0,-26} app #{1,-4} -> {2} {3}" -f $Persona, $AppId, $Stage, $loanTxt) -ForegroundColor Green
}

# ======================================================================================
# Preflight
# ======================================================================================
Write-Host "NAVIX demo-data populator -> $BackendBase" -ForegroundColor Yellow
try {
  Invoke-Api GET "/api/applications?status=DRAFT" $ADMIN | Out-Null
} catch {
  Write-Error "Backend not reachable at $BackendBase. Start the stack first (./run-local.ps1). $_"
  exit 1
}

# ======================================================================================
# 1) Primary demo borrower — stable login, rich account history
#    Login: mobile 9819000001 (last 7 digits -> applicantId 9000001), OTP 123456
# ======================================================================================
$PRIMARY_ID   = 9000001
$PRIMARY_NAME = "Aarav Sharma"
Write-Host "`n== Primary borrower ($PRIMARY_NAME, login mobile 9819000001 -> applicant $PRIMARY_ID) ==" -ForegroundColor Cyan

# a) a fully-repaid CLOSED loan (history)
$pc = Drive-Application -ApplicantId $PRIMARY_ID -Name $PRIMARY_NAME -StopAt "ACTIVE" -AttachProfile $false -Purpose "Medical bill"
Repay-Full $pc.loanId (New-BorrowerActor $PRIMARY_ID $PRIMARY_NAME) "CLOSE"
$pcFinal = Invoke-Api GET "/api/applications/$($pc.appId)" (New-BorrowerActor $PRIMARY_ID $PRIMARY_NAME)
Add-Result "Primary: closed loan" $PRIMARY_ID $pc.appId $pcFinal.status $pc.loanId

# b) an ACTIVE loan with a verified partial repayment (current)
$pa = Drive-Application -ApplicantId $PRIMARY_ID -Name $PRIMARY_NAME -StopAt "ACTIVE" -AttachProfile $false -Purpose "Rent"
Repay-Partial $pa.loanId (New-BorrowerActor $PRIMARY_ID $PRIMARY_NAME)
Add-Result "Primary: active (partial)" $PRIMARY_ID $pa.appId "ACTIVE (partial paid)" $pa.loanId

# c) an in-flight application sitting in the Credit Head's queue (in review)
$pi = Drive-Application -ApplicantId $PRIMARY_ID -Name $PRIMARY_NAME -StopAt "CREDIT_HEAD_PENDING" -AttachProfile $true -Purpose "Education"
Add-Result "Primary: in review" $PRIMARY_ID $pi.appId $pi.status $pi.loanId

# ======================================================================================
# 2) One applicant parked at each remaining stage (distinct identities)
# ======================================================================================
Write-Host "`n== Per-stage staff-queue applicants ==" -ForegroundColor Cyan

$r = Drive-Application -ApplicantId 9000010 -Name "Bhavya Reddy" -StopAt "KYC_PENDING"
Add-Result "KYC pending" 9000010 $r.appId $r.status $null

$r = Drive-Application -ApplicantId 9000011 -Name "Chetan Verma" -StopAt "APPLIED"
Add-Result "Applied (credit-head queue)" 9000011 $r.appId $r.status $null

$r = Drive-Application -ApplicantId 9000012 -Name "Divya Menon" -StopAt "CREDIT_EXEC_PENDING"
Add-Result "Credit exec pending" 9000012 $r.appId $r.status $null

$r = Drive-Application -ApplicantId 9000013 -Name "Esha Pillai" -StopAt "CREDIT_HEAD_PENDING"
Add-Result "Credit head pending" 9000013 $r.appId $r.status $null

$r = Drive-Application -ApplicantId 9000014 -Name "Farhan Sheikh" -StopAt "DISBURSEMENT_PENDING"
Add-Result "Disbursement pending" 9000014 $r.appId $r.status $null

$r = Drive-Application -ApplicantId 9000015 -Name "Gauri Joshi" -StopAt "ACCOUNTANT_PENDING"
Add-Result "Accountant pending" 9000015 $r.appId $r.status $null

$r = Drive-Application -ApplicantId 9000016 -Name "Harsh Patel" -StopAt "ACTIVE" -DisbTxnRef "FASTPATH-TXN-001"
Add-Result "Active (disbursement fast-path)" 9000016 $r.appId $r.status $r.loanId

# ----- KYC rejected ----------
$bor = New-BorrowerActor 9000019 "Kavya Rao"
$app = Invoke-Api POST "/api/applications" $bor @{ applicantId = 9000019 }
Set-Profile ([long]$app.id) $bor "Kavya Rao"
Invoke-Api POST "/api/applications/$($app.id)/submit-kyc" $bor | Out-Null
Invoke-Api POST "/api/applications/$($app.id)/kyc-decision" $KYC @{ decision = $false; notes = "PAN/photo mismatch (demo seed)" } | Out-Null
Add-Result "KYC rejected" 9000019 $app.id "KYC_REJECTED" $null

# ----- Credit rejected ----------
$r = Drive-Application -ApplicantId 9000020 -Name "Lalit Shah" -StopAt "CREDIT_EXEC_PENDING"
Invoke-Api POST "/api/applications/$($r.appId)/exec-decision" $EXEC @{ decision = $false; notes = "Income insufficient (demo seed)" } | Out-Null
Add-Result "Credit rejected" 9000020 $r.appId "REJECTED" $null

# ----- Cancelled (by borrower, pre-disbursement) ----------
$bor = New-BorrowerActor 9000021 "Maya Iyer"
$app = Invoke-Api POST "/api/applications" $bor @{ applicantId = 9000021 }
Invoke-Api POST "/api/applications/$($app.id)/cancel" $bor @{ notes = "Changed my mind (demo seed)" } | Out-Null
Add-Result "Cancelled" 9000021 $app.id "CANCELLED" $null

# ======================================================================================
# 3) OVERDUE / collections (needs the SQL backdate)
# ======================================================================================
Write-Host "`n== Overdue / collections ==" -ForegroundColor Cyan

# a) OVERDUE, no case opened (shows as OVERDUE + openable in collections)
$ro = Drive-Application -ApplicantId 9000017 -Name "Ira Bose" -StopAt "ACTIVE"
Backdate-Loan -LoanId $ro.loanId -DisbursedDaysAgo 35 -DueDaysAgo 5
$loanView = Invoke-Api GET "/api/loan/$($ro.loanId)" $ADMIN
Add-Result "Overdue (no case)" 9000017 $ro.appId $loanView.status $ro.loanId

# b) OVERDUE with a collection case opened (loan -> IN_COLLECTIONS, shows in buckets)
if (-not $SkipBackdate) {
  $rc = Drive-Application -ApplicantId 9000018 -Name "Jay Nair" -StopAt "ACTIVE"
  Backdate-Loan -LoanId $rc.loanId -DisbursedDaysAgo 50 -DueDaysAgo 20
  try {
    Invoke-Api POST "/api/collections/cases" $COLL @{ loanId = $rc.loanId } | Out-Null
    $cv = Invoke-Api GET "/api/loan/$($rc.loanId)" $ADMIN
    Add-Result "In collections (case open)" 9000018 $rc.appId $cv.status $rc.loanId
  } catch {
    Write-Host "  [warn] could not open collection case for loan #$($rc.loanId): $_" -ForegroundColor DarkYellow
    Add-Result "Overdue (case open failed)" 9000018 $rc.appId "OVERDUE" $rc.loanId
  }
}

# ======================================================================================
# 4) Returning-borrower reborrow states
# ======================================================================================
Write-Host "`n== Reborrow states ==" -ForegroundColor Cyan

# a) PRE_APPROVED — clean history (prior loan repaid on time), then reborrow
$bor = New-BorrowerActor 9000022 "Nidhi Saxena"
$pre = Drive-Application -ApplicantId 9000022 -Name "Nidhi Saxena" -StopAt "ACTIVE" -AttachProfile $true
Repay-Full $pre.loanId $bor "CLOSE"
$rb = Invoke-Api POST "/api/applications/reborrow" $bor
Add-Result "Pre-approved (reborrow, clean)" 9000022 $rb.id $rb.status $null

# b) REVIEW_PENDING — past delinquency (late repayment), then reborrow
if (-not $SkipBackdate) {
  $bor = New-BorrowerActor 9000023 "Omkar Joshi"
  $rev = Drive-Application -ApplicantId 9000023 -Name "Omkar Joshi" -StopAt "ACTIVE" -AttachProfile $true
  Backdate-Loan -LoanId $rev.loanId -DisbursedDaysAgo 40 -DueDaysAgo 8   # due date now in the past
  Repay-Full $rev.loanId $bor "LATE"                                     # paidOn today -> after due date -> delinquent
  $rb = Invoke-Api POST "/api/applications/reborrow" $bor
  Add-Result "Review pending (reborrow, late)" 9000023 $rb.id $rb.status $null
}

# ======================================================================================
# Summary + coverage check
# ======================================================================================
Write-Host "`n=== Seeded applications ===" -ForegroundColor Yellow
$script:Results | Format-Table -AutoSize | Out-String | Write-Host

Write-Host "=== Application status spread (live count) ===" -ForegroundColor Yellow
foreach ($s in @("DRAFT","KYC_PENDING","KYC_APPROVED","KYC_REJECTED","CREDIT_EXEC_PENDING","CREDIT_HEAD_PENDING","DISBURSEMENT_PENDING","ACCOUNTANT_PENDING","ACTIVE","CLOSED","REJECTED","CANCELLED","PRE_APPROVED","REVIEW_PENDING")) {
  try {
    $rows = Invoke-Api GET "/api/applications?status=$s" $ADMIN
    Write-Host ("  {0,-22} {1}" -f $s, @($rows).Count)
  } catch {
    Write-Host ("  {0,-22} (query failed)" -f $s) -ForegroundColor DarkYellow
  }
}

Write-Host "`nDemo logins:" -ForegroundColor Yellow
Write-Host "  Borrower : /login  -> mobile 9819000001, OTP 123456  (Aarav Sharma, applicant 9000001)"
Write-Host "  Staff    : /staff/login -> pick the role whose queue you want to see:"
Write-Host "             KYC Approver | Credit Head | Credit Executive | Disbursement Head | Accountant | Collection Head | Administrator"
Write-Host "`nDone." -ForegroundColor Green
