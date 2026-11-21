# Point this clone's git hooks at the ones tracked in the repo.
#
# core.hooksPath rather than copying into .git/hooks: a copy silently goes stale the moment the
# tracked hook changes, and nothing would say so.

$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $Root
try {
    git config core.hooksPath scripts/hooks
    if ($LASTEXITCODE -ne 0) {
        throw "git config failed with exit code $LASTEXITCODE"
    }
    Write-Host "core.hooksPath -> scripts/hooks"
    Write-Host "Installed: pre-commit (refuses FORCE_DOCK_MODE_FOR_DEV left on, and a native source change without its rebuilt binary)"
} finally {
    Pop-Location
}
