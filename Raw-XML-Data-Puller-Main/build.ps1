#Requires -Version 5.1
<#
.SYNOPSIS
    Full build script for Raw-XML-Data-Puller.
    Pipeline: Maven compile -> ProGuard -> moditect -> jlink -> jpackage

.DESCRIPTION
    Edit the CONFIG section below, then run:
        powershell -ExecutionPolicy Bypass -File build.ps1

.NOTES
    First run: ~15 minutes (downloads Maven dependencies).
    Subsequent runs: ~5 minutes (local Maven cache).
#>

$ErrorActionPreference = "Continue"

# ============================================================
#  CONFIG   -  edit this section to match your environment
# ============================================================
$APP_NAME       = "Raw-Xml-Data-Puller"          # must match --name in pom.xml
$APP_VERSION    = "21.0"                           # used for output ZIP name
$VENDOR         = "Walgreens Pharmacy Systems"
$JAVA_HOME_DIR  = "C:\Program Files\Zulu\zulu-21" # full path to JDK 21
$JAVAFX_JMODS   = "C:/javafx-jmods-21.0.12"       # folder containing *.jmod files
$MVN            = "mvn"                            # or full path to mvn.cmd
$PROGUARD_SHRINK    = $false  # $true to remove unused classes (breaks FXML reflection)
$PROGUARD_OBFUSCATE = $true  # $true to obfuscate names (breaks FXML fx:controller)
$ZIP_OUTPUT     = $true       # $false to skip ZIP creation after build
# ============================================================

$ProjectRoot = $PSScriptRoot
$TargetDir   = Join-Path $ProjectRoot "target"
$CfgPath     = Join-Path $ProjectRoot "proguard.cfg"

function Write-Step([string]$msg) {
    Write-Host ""
    Write-Host ">>> $msg" -ForegroundColor Cyan
}

function Write-OK([string]$label, [string]$detail = "") {
    $out = "    OK   $label"
    if ($detail) { $out += "  $detail" }
    Write-Host $out -ForegroundColor Green
}

function Write-FAIL([string]$msg) {
    Write-Host ""
    Write-Host "  FAILED: $msg" -ForegroundColor Red
    Write-Host ""
    exit 1
}

# ============================================================
#  STEP 1  -  PREREQUISITES
# ============================================================
Write-Step "Checking prerequisites"

if (-not (Test-Path "$JAVA_HOME_DIR\bin\java.exe")) {
    Write-FAIL "Java not found at $JAVA_HOME_DIR  -  update `$JAVA_HOME_DIR in CONFIG"
}
$javaLine = (& "$JAVA_HOME_DIR\bin\java.exe" "-version" 2>&1)[0].ToString()
Write-OK "Java" $javaLine

$mvnOut = (& $MVN "--version" 2>&1 | Select-Object -First 1).ToString()
if (-not ($mvnOut -match 'Maven')) {
    Write-FAIL "Maven not found or not working: $MVN  -  update `$MVN in CONFIG"
}
Write-OK "Maven" $mvnOut

if (-not (Test-Path "$JAVAFX_JMODS\javafx.controls.jmod")) {
    Write-FAIL "JavaFX jmods not found at $JAVAFX_JMODS  -  update `$JAVAFX_JMODS in CONFIG"
}
Write-OK "JavaFX JMODs" $JAVAFX_JMODS

if (-not (Test-Path "$JAVA_HOME_DIR\bin\jlink.exe")) {
    Write-FAIL "jlink.exe not found  -  $JAVA_HOME_DIR must be a full JDK (not JRE)"
}
if (-not (Test-Path "$JAVA_HOME_DIR\bin\jpackage.exe")) {
    Write-FAIL "jpackage.exe not found  -  requires JDK 14+ (found in JDK 21)"
}
Write-OK "jlink / jpackage" "found in $JAVA_HOME_DIR\bin"

# ============================================================
#  STEP 2  -  CONFIGURE PROGUARD FLAGS
# ============================================================
Write-Step "Configuring ProGuard flags"

if (-not (Test-Path $CfgPath)) {
    Write-FAIL "proguard.cfg not found at $CfgPath"
}

# Read lines, strip any existing shrink/obfuscate flags, re-inject based on CONFIG
$lines  = Get-Content $CfgPath
$lines  = $lines | Where-Object { $_ -ne '-dontshrink' -and $_ -ne '-dontobfuscate' }
$inject = [System.Collections.Generic.List[string]]::new()
if (-not $PROGUARD_SHRINK)    { $inject.Add('-dontshrink') }
if (-not $PROGUARD_OBFUSCATE) { $inject.Add('-dontobfuscate') }
$lines  = $inject.ToArray() + $lines
Set-Content -Path $CfgPath -Value $lines

Write-OK "Shrinking"   $(if ($PROGUARD_SHRINK)    { "ENABLED" } else { "DISABLED (-dontshrink)" })
Write-OK "Obfuscation" $(if ($PROGUARD_OBFUSCATE) { "ENABLED" } else { "DISABLED (-dontobfuscate)" })

# ============================================================
#  STEP 3  -  MAVEN BUILD
# ============================================================
Write-Step "Running Maven build  (first run: ~15 min  |  subsequent: ~5 min)"

$env:JAVA_HOME = $JAVA_HOME_DIR
$startTime = Get-Date

& $MVN "-f" "$ProjectRoot\pom.xml" clean package "-Djavafx.jmods=$JAVAFX_JMODS" "--no-transfer-progress"

$elapsed = [int](New-TimeSpan -Start $startTime -End (Get-Date)).TotalSeconds

if ($LASTEXITCODE -ne 0) {
    Write-FAIL "Maven exited with code $LASTEXITCODE  (see Maven output above)"
}
Write-OK "Maven completed in ${elapsed}s"

# ============================================================
#  STEP 4  -  VERIFY ARTIFACTS
# ============================================================
Write-Step "Verifying output artifacts"

# Discover JARs by glob  -  handles any version string in the filename
$jarPlain = Get-ChildItem $TargetDir -Filter "*.jar" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'proguarded' } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1 -ExpandProperty FullName

$jarGuarded = Get-ChildItem $TargetDir -Filter "*proguarded.jar" -ErrorAction SilentlyContinue |
              Sort-Object LastWriteTime -Descending |
              Select-Object -First 1 -ExpandProperty FullName

$exePath = Join-Path $TargetDir "installer\$APP_NAME\$APP_NAME.exe"
$jrePath = Join-Path $TargetDir "installer\$APP_NAME\runtime"

if (-not $jarPlain)   { Write-FAIL "No plain JAR found in $TargetDir" }
if (-not $jarGuarded) { Write-FAIL "No proguarded JAR found in $TargetDir" }

$kbPlain    = [math]::Round((Get-Item $jarPlain).Length    / 1KB, 0)
$kbGuarded  = [math]::Round((Get-Item $jarGuarded).Length  / 1KB, 0)
Write-OK "Plain JAR   " "$([System.IO.Path]::GetFileName($jarPlain))  ($kbPlain KB)"
Write-Host "    OK   Modular JAR  $([System.IO.Path]::GetFileName($jarGuarded))  ($kbGuarded KB)  [JPMS module-info included]" -ForegroundColor Green

if (-not (Test-Path $exePath)) { Write-FAIL "EXE not found: $exePath" }
$kbExe = [math]::Round((Get-Item $exePath).Length / 1KB, 0)
Write-OK "EXE launcher" "$([System.IO.Path]::GetFileName($exePath))  ($kbExe KB)"

if (-not (Test-Path $jrePath)) { Write-FAIL "Bundled JRE not found at: $jrePath" }
$jreMB = [math]::Round((Get-ChildItem $jrePath -Recurse | Measure-Object -Property Length -Sum).Sum / 1MB, 1)
Write-Host "    OK   Bundled JRE    $jreMB MB  (JDK 21 + JavaFX 21.0.12)" -ForegroundColor Green

# ============================================================
#  STEP 5  -  OPTIONAL ZIP
# ============================================================
$zipPath = Join-Path $TargetDir "$APP_NAME-$APP_VERSION.zip"

if ($ZIP_OUTPUT) {
    Write-Step "Creating distribution ZIP"
    $installerFolder = Join-Path $TargetDir "installer\$APP_NAME"
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    Compress-Archive -Path $installerFolder -DestinationPath $zipPath
    $zipMB = [math]::Round((Get-Item $zipPath).Length / 1MB, 1)
    Write-OK "ZIP created" "$zipPath  ($zipMB MB)"
}

# ============================================================
#  SUMMARY
# ============================================================
Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
Write-Host "  BUILD SUCCESSFUL" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Green
Write-Host ""
Write-Host "  EXE  :  $exePath"
Write-Host "  JAR  :  $jarPlain"
if ($ZIP_OUTPUT) {
    Write-Host "  ZIP  :  $zipPath"
}
Write-Host ""
Write-Host "  Distribute the full folder (or the ZIP above):"
Write-Host "  $(Join-Path $TargetDir "installer\$APP_NAME")"
Write-Host ""
