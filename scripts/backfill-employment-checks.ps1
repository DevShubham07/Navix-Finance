# backfill-employment-checks.ps1 -- run the EPFO/UAN employment lookup on files that predate it.
#
# WHY
#   The EMPLOYMENT check only started returning data once the client was repointed from Digitap's
#   UAN Advanced V4 (412 -- not provisioned on our account) to UAN Basic V3. New signups now fire it
#   automatically alongside the bureau pull, but every application created before that has no
#   EMPLOYMENT row at all, so its staff Employment (EPFO) card reads "no check has been run".
#
# WHAT IT DOES
#   Calls POST /api/applications/{id}/verifications/EMPLOYMENT/retry (ADMIN / credit roles) for each
#   target application. That is the same service path the staff "Retry API" button uses, so the row,
#   the audit trail and the provider-call log all look identical to a staff-triggered run.
#
# COST -- READ THIS BEFORE THE -Execute RUN
#   Each resolved lookup is a BILLABLE Digitap call. Per the vendor guide only result_code 101 bills
#   (103 "no record" should not), but that is worth confirming against an invoice rather than trusting.
#   The script therefore DRY RUNS BY DEFAULT: it reports the target count and does not call anything
#   until you pass -Execute.
#
#   verifyEmployment short-circuits on an existing PASS row, so re-running is safe and does not
#   double-bill. Rows in REVIEW are re-run (that is usually the point of a re-run).
#
# USAGE
#   .\scripts\backfill-employment-checks.ps1                          # dry run, prints the plan
#   .\scripts\backfill-employment-checks.ps1 -Execute                 # actually fire
#   .\scripts\backfill-employment-checks.ps1 -Execute -Limit 25       # cap the spend on a first pass
#   .\scripts\backfill-employment-checks.ps1 -BaseUrl http://localhost:8090 -Email a@b -Password x

[CmdletBinding()]
param(
    # Live-but-undisbursed files a reviewer can still act on, plus borrowers with money out -- the
    # latter so collections can see whether the employer is still current.
    [string[]] $Statuses = @('KYC_PENDING', 'CREDIT_EXEC_PENDING', 'SANCTIONED', 'DISBURSEMENT_PENDING',
                             'ACCOUNTANT_PENDING', 'ACTIVE', 'OVERDUE'),
    [string]   $BaseUrl  = 'http://localhost:8090',
    [string]   $Email    = 'meera.krishnan@navix.example',
    [string]   $Password = 'Admin@12345',
    [int]      $Limit    = 0,          # 0 = no cap
    [int]      $DelayMs  = 400,        # be polite to the provider
    [switch]   $Execute
)

$ErrorActionPreference = 'Stop'

function Invoke-Api {
    param([string] $Method, [string] $Path, $Body, [string] $Token)
    $headers = @{ Authorization = "Bearer $Token" }
    $args = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $headers; ContentType = 'application/json' }
    if ($null -ne $Body) { $args.Body = ($Body | ConvertTo-Json -Depth 6) }
    return Invoke-RestMethod @args
}

Write-Host "Signing in as $Email ..." -ForegroundColor Cyan
$login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/staff/login" -ContentType 'application/json' `
    -Body (@{ email = $Email; password = $Password; force = $true } | ConvertTo-Json)
$token = $login.data.token
if (-not $token) { throw "Login failed: no token returned" }

# Collect the target applications, de-duplicated across statuses.
# A plain Hashtable, not [ordered]@{} -- OrderedDictionary's indexer is ambiguous with
# integer keys: $dict[123] = x dispatches to the positional set_Item(int, object) overload
# and throws ArgumentOutOfRangeException once 123 exceeds Count, instead of setting a key.
# Application ids are ints, so this bit on the very first insert.
$targets = @{}
foreach ($status in $Statuses) {
    $resp = Invoke-Api -Method Get -Path "/api/applications?status=$status" -Token $token
    foreach ($app in $resp.data) {
        if (-not $targets.Contains($app.id)) {
            $targets[$app.id] = [pscustomobject]@{
                Id = $app.id; Customer = $app.customerName; Status = $status
            }
        }
    }
    Write-Host ("  {0,-22} {1,4} applications" -f $status, @($resp.data).Count)
}

# Skip anything that already has a PASS -- the service would short-circuit anyway, but not asking is
# faster and keeps the reported plan honest about what will actually be spent.
$plan = @()
foreach ($t in $targets.Values) {
    $existing = $null
    try { $existing = (Invoke-Api -Method Get -Path "/api/applications/$($t.Id)/verifications" -Token $token).data } catch { }
    $emp = $existing | Where-Object { $_.checkType -eq 'EMPLOYMENT' }
    if ($emp -and $emp.status -eq 'PASS') { continue }
    $plan += $t
}
if ($Limit -gt 0 -and $plan.Count -gt $Limit) { $plan = $plan[0..($Limit - 1)] }

Write-Host ""
Write-Host "Plan: $($plan.Count) application(s) to check (of $($targets.Count) in scope)." -ForegroundColor Yellow
Write-Host "Each resolved lookup is a billable Digitap call." -ForegroundColor Yellow

if (-not $Execute) {
    $plan | Select-Object -First 20 | Format-Table -AutoSize
    if ($plan.Count -gt 20) { Write-Host "  ... and $($plan.Count - 20) more" }
    Write-Host ""
    Write-Host "DRY RUN -- nothing was called. Re-run with -Execute to fire." -ForegroundColor Green
    return
}

$ok = 0; $review = 0; $failed = 0
foreach ($t in $plan) {
    try {
        $r = Invoke-Api -Method Post -Path "/api/applications/$($t.Id)/verifications/EMPLOYMENT/retry" -Body @{} -Token $token
        $status = $r.data.status
        if ($status -eq 'PASS') { $ok++ } else { $review++ }
        Write-Host ("  #{0,-7} {1,-8} {2}" -f $t.Id, $status, $r.data.message)
    } catch {
        $failed++
        Write-Host ("  #{0,-7} ERROR    {1}" -f $t.Id, $_.Exception.Message) -ForegroundColor Red
    }
    Start-Sleep -Milliseconds $DelayMs
}

Write-Host ""
Write-Host "Done. $ok confirmed, $review need review, $failed errored." -ForegroundColor Cyan
Write-Host "A REVIEW is not a red flag: a first job, a cash employer or a non-PF establishment all"
Write-Host "legitimately have no EPFO record."
