param(
    [ValidateSet("update", "status")]
    [string]$Action = "update"
)

# Agent hooks send an event payload on stdin. Consume it before running CRG so
# the hook works consistently in native Windows shells.
$null = [Console]::In.ReadToEnd()

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$candidates = @(
    (Join-Path $env:USERPROFILE ".local\bin\code-review-graph.exe"),
    "code-review-graph.exe",
    "code-review-graph"
)

$crg = $null
foreach ($candidate in $candidates) {
    if ([System.IO.Path]::IsPathRooted($candidate)) {
        if (Test-Path -LiteralPath $candidate) {
            $crg = $candidate
            break
        }
    } else {
        $command = Get-Command $candidate -ErrorAction SilentlyContinue
        if ($command) {
            $crg = $command.Source
            break
        }
    }
}

# CRG is developer tooling. A missing/broken local install must never make the
# edit that triggered this hook look like it failed.
if (-not $crg) {
    exit 0
}

try {
    if ($Action -eq "status") {
        & $crg status --repo $repoRoot *> $null
    } else {
        & $crg update --skip-flows --repo $repoRoot *> $null
    }
} catch {
    # The watch daemon and pre-commit hook provide additional refresh paths.
}

exit 0
