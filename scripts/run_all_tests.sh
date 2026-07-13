#!/usr/bin/env bash
# Run full AnyConvert test suite (unit + on-device instrumented).
#
# Usage (from repo root):
#   ./scripts/run_all_tests.sh
#   ./scripts/run_all_tests.sh --sync-assets ./test-assets
#   ./scripts/run_all_tests.sh --unit-only
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

SYNC_ASSETS=""
UNIT_ONLY=0
SKIP_BUILD=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --sync-assets) SYNC_ASSETS="$2"; shift 2 ;;
    --unit-only)   UNIT_ONLY=1; shift ;;
    --skip-build)  SKIP_BUILD=1; shift ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

step() { echo ""; echo "==> $*"; }

sync_assets() {
  local src="$1"
  local dest="$REPO_ROOT/app/src/androidTest/assets"
  if [[ ! -d "$src" ]]; then
    echo "Asset source not found: $src"
    return
  fi
  step "Syncing test assets from $src -> $dest"
  mkdir -p "$dest"
  cp -R "$src"/. "$dest/"
}

if [[ -n "$SYNC_ASSETS" ]]; then
  sync_assets "$SYNC_ASSETS"
elif [[ -d "$REPO_ROOT/test-assets" ]]; then
  sync_assets "$REPO_ROOT/test-assets"
fi

step "Unit tests (JVM)"
if [[ "$SKIP_BUILD" -eq 1 ]]; then
  ./gradlew :app:testDebugUnitTest
else
  ./gradlew :app:testDebugUnitTest :app:assembleDebug
fi

if [[ "$UNIT_ONLY" -eq 1 ]]; then
  echo ""
  echo "Unit tests passed. (--unit-only: skipping instrumented tests)"
  exit 0
fi

step "Checking for Android device"
if ! adb devices | awk 'NR>1 && $2=="device"{found=1} END{exit !found}'; then
  echo "No adb device found. Connect a phone/emulator or use --unit-only."
  exit 1
fi

step "Install app + instrumented test APK"
./gradlew :app:installDebug :app:installDebugAndroidTest

step "Instrumented tests (all classes in ab.inque.converter)"
adb shell am instrument -w \
  -e package ab.inque.converter \
  ab.inque.converter.test/androidx.test.runner.AndroidJUnitRunner
