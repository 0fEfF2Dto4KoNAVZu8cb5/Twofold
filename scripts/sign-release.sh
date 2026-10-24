#!/usr/bin/env bash
set -euo pipefail
# The key remains yours. Keep it and its password outside source control.
# Usage: ANDROID_HOME=/path/to/sdk ./scripts/sign-release.sh unsigned.apk signing.p12 signed.apk
if [ "$#" -ne 3 ]; then
    echo 'Usage: sign-release.sh UNSIGNED_APK PRIVATE_KEYSTORE SIGNED_APK' >&2
    exit 2
fi
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory}"
twofold_build_tools="$ANDROID_HOME/build-tools/35.0.0"
twofold_aligned="$(mktemp --suffix=.apk)"
trap 'rm -f "$twofold_aligned"' EXIT
"$twofold_build_tools/zipalign" -f -p 4 "$1" "$twofold_aligned"
# apksigner prompts for the keystore password, keeping it out of shell history.
"$twofold_build_tools/apksigner" sign --ks "$2" --out "$3" "$twofold_aligned"
"$twofold_build_tools/apksigner" verify --verbose "$3"
