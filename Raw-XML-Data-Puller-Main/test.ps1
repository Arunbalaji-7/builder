#Requires -Version 5.1
<#
.SYNOPSIS
    Test runner for Raw-XML-Data-Puller.
    Runs JUnit unit tests via Maven and verifies build artifacts exist.
    Optionally performs an EXE smoke launch (starts the app for 5s then kills it).

.USAGE
    powershell -ExecutionPolicy Bypass -File test.ps1
    powershell -ExecutionPolicy Bypass -File test.ps1 -SkipSmokeTest
    powershell -ExecutionPolicy Bypass -File test.ps1 -SkipUnitTests
#>

param(
    [switch]$SkipSmokeTest,
    [switch]$SkipUnitTests
)

$ErrorActionPreference = "Continue"

# ============================================================
#  CONFIG   -  keep in sync with build.ps1
# ============================================================
$APP_NAME      = "Raw-Xml-Data-Puller"
$JAVA_HOME_DIR = "C:\Program Files\Zulu\zulu-21"
$JAVAFX_JMODS  = "C:/javafx-jmods-21.0.12"
$MVN           = "mvn"
# ============================================================

$ProjectRoot = $PSScriptRoot
$TargetDir   = Join-Path $ProjectRoot "target"
$PassCount   = 0
$FailCount   = 0
$SkipCount   = 0

function Write-Step([string]$msg) {
    Write-Host ""
    Write-Host ">>> $msg" -ForegroundColor Cyan
}

function Test-Pass([string]$msg) {
    $script:PassCount++
    Write-Host "  PASS  $msg" -ForegroundColor Green
}

function Test-Fail([string]$msg) {
    $script:FailCount++
    Write-Host "  FAIL  $msg" -ForegroundColor Red
}

function Test-Skip([string]$msg) {
    $script:SkipCount++
    Write-Host "  SKIP  $msg" -ForegroundColor Yellow
}

# ============================================================
#  ENVIRONMENT CHECKS
# ============================================================
Write-Step "Environment checks"

if (Test-Path "$JAVA_HOME_DIR\bin\java.exe") {
    $javaLine = (& "$JAVA_HOME_DIR\bin\java.exe" "-version" 2>&1)[0].ToString()
    Test-Pass "Java: $javaLine"
} else {
    Test-Fail "Java not found at $JAVA_HOME_DIR"
}

$mvnOut = (& $MVN "--version" 2>&1 | Select-Object -First 1).ToString()
if ($mvnOut -match 'Maven') {
    Test-Pass "Maven: $mvnOut"
} else {
    Test-Fail "Maven not found: $MVN"
}

if (Test-Path "$JAVAFX_JMODS\javafx.controls.jmod") {
    Test-Pass "JavaFX JMODs: $JAVAFX_JMODS"
} else {
    Test-Fail "JavaFX JMODs not found at $JAVAFX_JMODS"
}

# ============================================================
#  JUNIT UNIT TESTS
# ============================================================
Write-Step "JUnit unit tests (mvn test)"

if ($SkipUnitTests) {
    Test-Skip "Unit tests skipped (-SkipUnitTests)"
} else {
    $env:JAVA_HOME = $JAVA_HOME_DIR
    & $MVN "-f" "$ProjectRoot\pom.xml" test "-Djavafx.jmods=$JAVAFX_JMODS" "--no-transfer-progress"

    if ($LASTEXITCODE -eq 0) {
        Test-Pass "All JUnit tests passed (Maven exit 0)"
    } else {
        Test-Fail "JUnit tests failed (Maven exit $LASTEXITCODE)  -  see Maven output above"
    }
}

# ============================================================
#  ARTIFACT EXISTENCE CHECKS
# ============================================================
Write-Step "Build artifact checks (requires a completed build)"

$jarPlain = Get-ChildItem $TargetDir -Filter "*.jar" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'proguarded' } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1

$jarGuarded = Get-ChildItem $TargetDir -Filter "*proguarded.jar" -ErrorAction SilentlyContinue |
              Sort-Object LastWriteTime -Descending |
              Select-Object -First 1

$exePath = Join-Path $TargetDir "installer\$APP_NAME\$APP_NAME.exe"
$jrePath = Join-Path $TargetDir "installer\$APP_NAME\runtime"
$appPath = Join-Path $TargetDir "installer\$APP_NAME\app"

if ($jarPlain) {
    $kb = [math]::Round($jarPlain.Length / 1KB, 0)
    Test-Pass "Plain JAR: $($jarPlain.Name)  ($kb KB)"
} else {
    Test-Fail "Plain JAR not found in $TargetDir   -  run build.ps1 first"
}

if ($jarGuarded) {
    $kb = [math]::Round($jarGuarded.Length / 1KB, 0)
    Test-Pass "ProGuarded JAR: $($jarGuarded.Name)  ($kb KB)"
} else {
    Test-Fail "ProGuarded JAR not found   -  run build.ps1 first"
}

if (Test-Path $exePath) {
    $kb = [math]::Round((Get-Item $exePath).Length / 1KB, 0)
    Test-Pass "EXE launcher: $([System.IO.Path]::GetFileName($exePath))  ($kb KB)"
} else {
    Test-Fail "EXE not found: $exePath   -  run build.ps1 first"
}

if (Test-Path $jrePath) {
    $mb = [math]::Round((Get-ChildItem $jrePath -Recurse | Measure-Object -Property Length -Sum).Sum / 1MB, 1)
    Test-Pass "Bundled JRE: $mb MB"
} else {
    Test-Fail "Bundled JRE not found: $jrePath   -  run build.ps1 first"
}

if (Test-Path $appPath) {
    $jarCount = (Get-ChildItem $appPath -Filter "*.jar").Count
    Test-Pass "App folder: $jarCount JARs in $appPath"
} else {
    Test-Fail "App folder not found: $appPath   -  run build.ps1 first"
}

# ============================================================
#  EXE SMOKE LAUNCH
# ============================================================
Write-Step "EXE smoke launch (start app, wait 5s, verify still running)"

if ($SkipSmokeTest) {
    Test-Skip "Smoke test skipped (-SkipSmokeTest)"
} elseif (-not (Test-Path $exePath)) {
    Test-Skip "EXE not built  -  run build.ps1 first"
} else {
    $proc = $null
    try {
        $proc = Start-Process -FilePath $exePath -PassThru -ErrorAction Stop
        Start-Sleep -Seconds 5

        if ($null -eq $proc -or $proc.HasExited) {
            $code = if ($proc) { $proc.ExitCode } else { "unknown" }
            Test-Fail "EXE exited within 5s (code: $code)  -  indicates a launch failure"
        } else {
            Test-Pass "EXE launched and running (PID $($proc.Id))  -  terminating"
            Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        }
    } catch {
        Test-Fail "Failed to start EXE: $_"
    } finally {
        if ($proc -and -not $proc.HasExited) {
            Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        }
    }
}

# ============================================================
#  RESULTS SUMMARY
# ============================================================
$total = $PassCount + $FailCount + $SkipCount
Write-Host ""
Write-Host "============================================================"
Write-Host "  Results: $PassCount passed  |  $FailCount failed  |  $SkipCount skipped  (total: $total)"
if ($FailCount -eq 0) {
    Write-Host "  ALL CHECKS PASSED" -ForegroundColor Green
} else {
    Write-Host "  $FailCount CHECK(S) FAILED" -ForegroundColor Red
}
Write-Host "============================================================"
Write-Host ""

if ($FailCount -gt 0) { exit 1 }
