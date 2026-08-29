#Requires -Version 5.1
<#
.SYNOPSIS
    Create a distribution ZIP from an already-built Raw-Xml-Data-Puller installer folder.
    Run this after build.ps1 has completed successfully.

.DESCRIPTION
    Packages the target/installer/Raw-Xml-Data-Puller/ folder into a ZIP.
    The ZIP contains the .exe launcher and the bundled JRE  -  everything a recipient needs.
    Recipients do NOT need Java installed.

.PARAMETER OutputPath
    Optional full path for the ZIP file.
    Default: target\Raw-Xml-Data-Puller-<APP_VERSION>.zip

.PARAMETER IncludeTimestamp
    Append a timestamp to the ZIP filename (e.g. Raw-Xml-Data-Puller-1.0-20260803.zip).

.USAGE
    powershell -ExecutionPolicy Bypass -File zip.ps1
    powershell -ExecutionPolicy Bypass -File zip.ps1 -IncludeTimestamp
    powershell -ExecutionPolicy Bypass -File zip.ps1 -OutputPath "C:\Releases\v1.0.zip"
#>

param(
    [string]$OutputPath      = "",
    [switch]$IncludeTimestamp
)

$ErrorActionPreference = "Continue"

# ============================================================
#  CONFIG   -  keep in sync with build.ps1
# ============================================================
$APP_NAME    = "Raw-Xml-Data-Puller"
$APP_VERSION = "1.0"
# ============================================================

$ProjectRoot     = $PSScriptRoot
$TargetDir       = Join-Path $ProjectRoot "target"
$InstallerFolder = Join-Path $TargetDir "installer\$APP_NAME"

# Build default output path
if (-not $OutputPath) {
    $suffix = if ($IncludeTimestamp) { "-$(Get-Date -Format 'yyyyMMdd')" } else { "" }
    $OutputPath = Join-Path $TargetDir "$APP_NAME-$APP_VERSION$suffix.zip"
}

Write-Host ""
Write-Host ">>> Creating distribution ZIP" -ForegroundColor Cyan
Write-Host "    Source : $InstallerFolder"
Write-Host "    Output : $OutputPath"
Write-Host ""

# ============================================================
#  VALIDATE SOURCE
# ============================================================
if (-not (Test-Path $InstallerFolder)) {
    Write-Host "  ERROR: Installer folder not found: $InstallerFolder" -ForegroundColor Red
    Write-Host "  Run build.ps1 first to produce the app-image." -ForegroundColor Yellow
    Write-Host ""
    exit 1
}

$exeFile = Join-Path $InstallerFolder "$APP_NAME.exe"
if (-not (Test-Path $exeFile)) {
    Write-Host "  ERROR: $APP_NAME.exe not found in installer folder." -ForegroundColor Red
    Write-Host "  The build may be incomplete. Run build.ps1 again." -ForegroundColor Yellow
    Write-Host ""
    exit 1
}

$runtimeDir = Join-Path $InstallerFolder "runtime"
if (-not (Test-Path $runtimeDir)) {
    Write-Host "  ERROR: runtime/ folder not found  -  bundled JRE is missing." -ForegroundColor Red
    Write-Host "  Run build.ps1 again." -ForegroundColor Yellow
    Write-Host ""
    exit 1
}

# ============================================================
#  SHOW WHAT WILL BE ZIPPED
# ============================================================
$exeKB  = [math]::Round((Get-Item $exeFile).Length / 1KB, 0)
$jreMB  = [math]::Round((Get-ChildItem $runtimeDir -Recurse | Measure-Object -Property Length -Sum).Sum / 1MB, 1)
$totalMB = [math]::Round((Get-ChildItem $InstallerFolder -Recurse | Measure-Object -Property Length -Sum).Sum / 1MB, 1)

Write-Host "    Contents to zip:" -ForegroundColor Gray
Write-Host "      $APP_NAME.exe     $exeKB KB"
Write-Host "      runtime\          $jreMB MB  (bundled JRE)"
Write-Host "      Total uncompressed: $totalMB MB"
Write-Host ""

# ============================================================
#  CREATE ZIP
# ============================================================
# Remove existing ZIP if present
if (Test-Path $OutputPath) {
    Remove-Item $OutputPath -Force
    Write-Host "    Removed existing ZIP." -ForegroundColor Gray
}

# Ensure output directory exists
$outDir = Split-Path $OutputPath -Parent
if (-not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
}

Compress-Archive -Path $InstallerFolder -DestinationPath $OutputPath

if (-not (Test-Path $OutputPath)) {
    Write-Host "  ERROR: ZIP was not created at $OutputPath" -ForegroundColor Red
    Write-Host ""
    exit 1
}

$zipMB = [math]::Round((Get-Item $OutputPath).Length / 1MB, 1)

Write-Host "    OK  ZIP created: $OutputPath  ($zipMB MB)" -ForegroundColor Green
Write-Host ""
Write-Host "  To distribute:" -ForegroundColor Cyan
Write-Host "    1. Send the ZIP to the recipient"
Write-Host "    2. Recipient extracts the ZIP"
Write-Host "    3. Recipient runs $APP_NAME\$APP_NAME.exe"
Write-Host "    4. No Java installation required"
Write-Host ""
