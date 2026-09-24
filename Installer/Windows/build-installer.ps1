# Prepare a TLC Windows release payload.
param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path,
    [string]$OutputDir = (Join-Path $PSScriptRoot "dist")
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$payload = Join-Path $OutputDir "TLC"
New-Item -ItemType Directory -Force -Path $payload | Out-Null

Copy-Item (Join-Path $RepoRoot "Client") (Join-Path $payload "Client") -Recurse -Force
Copy-Item (Join-Path $RepoRoot "Server") (Join-Path $payload "Server") -Recurse -Force
Copy-Item (Join-Path $RepoRoot "README.md") (Join-Path $payload "README.md") -Force

# TlcBackup.jar is intentionally required by the installer. Build/package it
# before creating the final signed EXE release.
$backupJar = Join-Path $OutputDir "TlcBackup.jar"
if (-not (Test-Path $backupJar)) {
    Write-Warning "TlcBackup.jar is not present. The payload is source-only until the Java release build supplies it."
}

Copy-Item (Join-Path $PSScriptRoot "install.ps1") $OutputDir -Force

Write-Host "TLC Windows payload prepared in $OutputDir"
Write-Host "Final release: package this payload with a Windows installer builder and sign the resulting TLC-Setup.exe."
