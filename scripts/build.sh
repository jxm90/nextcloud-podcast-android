#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source ./scripts/dev-env.sh
./gradlew :app:assembleDebug --no-daemon
APK="app/build/outputs/apk/debug/app-debug.apk"
echo "Built: $PWD/$APK"
