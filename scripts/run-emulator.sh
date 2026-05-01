#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/dev-env.sh"
AVD_NAME="${1:-Pixel_8_Pro_API_34}"
emulator -avd "$AVD_NAME" -no-snapshot -netdelay none -netspeed full
