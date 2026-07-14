# Fail if any arm64-v8a .so in the release AAB uses LOAD alignment < 16 KB (0x4000).
param(
    [string]$AabPath = ""
)

$ErrorActionPreference = "Stop"

if (-not $AabPath) {
    $AabPath = Join-Path $PSScriptRoot "..\app\build\outputs\bundle\release\app-release.aab"
}

if (-not (Test-Path $AabPath)) {
    Write-Error "AAB not found: $AabPath (run :app:bundleRelease first)"
}

$ndkRoot = $env:ANDROID_NDK_HOME
if (-not $ndkRoot) {
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = "$env:LOCALAPPDATA\Android\Sdk" }
    $ndkRoot = Get-ChildItem (Join-Path $sdk "ndk") -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $ndkRoot) { Write-Error "NDK not found; set ANDROID_NDK_HOME" }

$readelf = Join-Path $ndkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
if (-not (Test-Path $readelf)) {
    $readelf = Get-ChildItem (Join-Path $ndkRoot "toolchains") -Recurse -Filter "llvm-readelf*" |
        Select-Object -First 1 -ExpandProperty FullName
}

$temp = Join-Path $env:TEMP "umc-16k-check"
if (Test-Path $temp) { Remove-Item $temp -Recurse -Force }
New-Item -ItemType Directory -Path $temp | Out-Null
$zip = Join-Path $temp "app.zip"
Copy-Item $AabPath $zip
Expand-Archive -Path $zip -DestinationPath $temp -Force

$failed = @()
Get-ChildItem (Join-Path $temp "base\lib\arm64-v8a") -Filter "*.so" | ForEach-Object {
    $line = & $readelf -lW $_.FullName | Select-String "LOAD" | Select-Object -First 1
    if ($line -match '0x([0-9a-f]+)\s*$') {
        $align = [Convert]::ToInt32($matches[1], 16)
        if ($align -lt 16384) {
            $failed += "$($_.Name): align 0x$($matches[1])"
        }
    }
}

Remove-Item $temp -Recurse -Force -ErrorAction SilentlyContinue

if ($failed.Count -gt 0) {
    Write-Error ("16 KB alignment failures:`n" + ($failed -join "`n"))
}
Write-Host "All arm64-v8a libraries pass 16 KB alignment."
