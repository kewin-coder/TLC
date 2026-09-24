# Prepare the TLC Windows installer payload.
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
Copy-Item (Join-Path $PSScriptRoot "install.ps1") $OutputDir -Force
Write-Host "TLC Windows installer payload prepared in $OutputDir"
Write-Host "Use a Windows installer builder to produce the signed TLC-Setup.exe."
