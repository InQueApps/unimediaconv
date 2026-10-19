# Run full AnyConvert test suite (unit + on-device instrumented).
#
# Usage (from repo root):
#   .\scripts\run_all_tests.ps1
#
# Optional: copy your fixtures into androidTest assets before building:
#   .\scripts\run_all_tests.ps1 -SyncAssetsFrom ".\test-assets"
#
# test-assets/ layout (any subset is fine):
#   conversions\sample.txt
#   geometry\square_512.png
#   geometry\portrait_clip.mp4
# Bundled device-test assets (packaged into androidTest APK):
#   app/src/androidTest/assets/conversions/test.mp4
#   app/src/androidTest/assets/conversions/sample.{txt,html,rtf}
#
# Requires: JDK, Android SDK, adb on PATH, one device/emulator connected for instrumented tests.

param(
    [string]$SyncAssetsFrom = "",
    [switch]$UnitOnly,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $RepoRoot

function Write-Step($msg) {
    Write-Host ""
    Write-Host "==> $msg" -ForegroundColor Cyan
}

function Test-AdbDevice {
    $devices = (& adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match "device\s*$" }
    return @($devices).Count -gt 0
}

function Sync-TestAssets([string]$SourceDir) {
    if (-not (Test-Path $SourceDir)) {
        Write-Host "Asset source not found: $SourceDir" -ForegroundColor Yellow
        return
    }
    $destRoot = Join-Path $RepoRoot "app\src\androidTest\assets"
    New-Item -ItemType Directory -Force -Path $destRoot | Out-Null
    Write-Step "Syncing test assets from $SourceDir -> $destRoot"
    Copy-Item -Path (Join-Path $SourceDir "*") -Destination $destRoot -Recurse -Force
}

# Default: also pick up repo-level test-assets/ if present and -SyncAssetsFrom not set
if ($SyncAssetsFrom) {
    Sync-TestAssets $SyncAssetsFrom
} elseif (Test-Path (Join-Path $RepoRoot "test-assets")) {
    Sync-TestAssets (Join-Path $RepoRoot "test-assets")
}

Write-Step "Unit tests (JVM)"
if ($SkipBuild) {
    & .\gradlew :app:testDebugUnitTest
} else {
    & .\gradlew :app:testDebugUnitTest :app:assembleDebug
}
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if ($UnitOnly) {
    Write-Host ""
    Write-Host "Unit tests passed. (-UnitOnly: skipping instrumented tests)" -ForegroundColor Green
    exit 0
}

Write-Step "Checking for Android device"
if (-not (Test-AdbDevice)) {
    Write-Host "No adb device found. Connect a phone/emulator or run with -UnitOnly." -ForegroundColor Yellow
    exit 1
}

Write-Step "Install app + instrumented test APK"
& .\gradlew :app:installDebug :app:installDebugAndroidTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Step "Instrumented tests (all classes in ab.inque.converter)"
$instrumentCmd = @(
    "shell", "am", "instrument", "-w",
    "-e", "package", "ab.inque.converter",
    "ab.inque.converter.test/androidx.test.runner.AndroidJUnitRunner"
)
& adb @instrumentCmd
$deviceExit = $LASTEXITCODE

Write-Host ""
if ($deviceExit -eq 0) {
    Write-Host "All tests passed." -ForegroundColor Green
} else {
    Write-Host "Some tests failed (exit $deviceExit)." -ForegroundColor Red
}
exit $deviceExit
