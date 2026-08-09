[CmdletBinding(SupportsShouldProcess)]
param(
  [Parameter(Mandatory)]
  [string]$Bucket,

  [Parameter(Mandatory)]
  [ValidateNotNullOrEmpty()]
  [string[]]$Prefix,

  [switch]$ConfirmDelete
)

$ErrorActionPreference = 'Stop'

if (-not (Get-Command aws -ErrorAction SilentlyContinue)) {
  throw 'AWS CLI is required. Install/configure it with credentials limited to the approved demo bucket.'
}

if ($Prefix | Where-Object { $_ -match '(^$|^applications/|^kyc/|^loan/|^collections/|^admin/|^payment/)' }) {
  throw 'Refusing a production document prefix. Supply only an explicitly approved test/demo prefix.'
}

$keys = foreach ($item in $Prefix) {
  Write-Host "Inventorying s3://$Bucket/$item"
  $raw = aws s3api list-objects-v2 --bucket $Bucket --prefix $item --output json | ConvertFrom-Json
  @($raw.Contents | ForEach-Object { $_.Key })
}

$keys = @($keys | Where-Object { $_ } | Sort-Object -Unique)
Write-Host ("Found {0} object(s) across {1}." -f $keys.Count, ($Prefix -join ', '))
$keys | ForEach-Object { Write-Host $_ }

if (-not $ConfirmDelete) {
  Write-Host 'Dry run only. Re-run with -ConfirmDelete after reviewing this exact list.'
  return
}

foreach ($key in $keys) {
  if ($PSCmdlet.ShouldProcess("s3://$Bucket/$key", 'Delete object')) {
    aws s3api delete-object --bucket $Bucket --key $key | Out-Null
  }
}

Write-Host "Deleted $($keys.Count) object(s)."
