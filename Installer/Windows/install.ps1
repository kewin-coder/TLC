# TLC Windows installer/update gate
param(
    [string]$InstallDir = "$env:LOCALAPPDATA\TLC",
    [string]$PackageDir = (Split-Path -Parent $MyInvocation.MyCommand.Path)
)

$ErrorActionPreference = "Stop"
$dataDir = Join-Path $InstallDir "tlc-data"
$dbFile = Join-Path $InstallDir "tlc.db"
$keyFile = Join-Path $dataDir "encryption.key"
$backupFile = Join-Path ([Environment]::GetFolderPath("MyDocuments")) "TLC_Backup.tlcb"

function Has-ExistingData {
    return (Test-Path $dbFile) -or (Test-Path $keyFile)
}

function Show-BackupGate {
    Write-Host ""
    Write-Host "============================================" -ForegroundColor Cyan
    Write-Host "              TLC UPDATE" -ForegroundColor Cyan
    Write-Host "============================================" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Please create a backup copy before continuing." -ForegroundColor Yellow
    Write-Host "Your backup protects your encrypted messages and encryption key."
    Write-Host ""
    Write-Host "1. Create Backup"
    Write-Host "2. I Already Have a Backup"
    Write-Host "3. Cancel Update"
    Write-Host ""

    do { $choice = Read-Host "Choose 1, 2, or 3" } while ($choice -notin @("1","2","3"))
    if ($choice -eq "3") { throw "TLC update cancelled by user." }

    if ($choice -eq "1") {
        $backupTool = Join-Path $PackageDir "TlcBackup.jar"
        if (-not (Test-Path $backupTool)) {
            throw "TLC backup tool is missing from this installer package. Update cancelled."
        }

        New-Item -ItemType Directory -Force -Path (Split-Path $backupFile) | Out-Null
        & java -jar $backupTool backup $backupFile
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $backupFile)) {
            throw "Backup creation failed. TLC was not updated."
        }
        Write-Host "Backup created successfully: $backupFile" -ForegroundColor Green
    } else {
        $existing = Read-Host "Enter the full path to your TLC_Backup.tlcb"
        if (-not (Test-Path $existing -PathType Leaf)) {
            throw "The selected backup was not found. TLC was not updated."
        }
        Write-Host "Backup confirmed." -ForegroundColor Green
    }
}

if (Has-ExistingData) { Show-BackupGate }

New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null

$sourceRoot = Join-Path $PackageDir "TLC"
if (-not (Test-Path $sourceRoot -PathType Container)) {
    throw "TLC release payload directory is missing."
}

# Application files may be replaced; persistent data is deliberately outside
# the payload and is never deleted by this script.
Copy-Item -Path (Join-Path $sourceRoot "*") -Destination $InstallDir -Recurse -Force

Write-Host ""
Write-Host "TLC installation/update completed." -ForegroundColor Green
Write-Host "Persistent database and encryption key were preserved."
