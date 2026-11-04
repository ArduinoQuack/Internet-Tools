#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
bash ./gradlew --no-daemon assembleRelease
printf 'APK: build/outputs/apk/release/InternetTools-release.apk\n'
