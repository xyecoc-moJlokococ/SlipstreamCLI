#!/usr/bin/env bash
# Debug-signed release APK: same code, debuggable off, so ART AOT-compiles it.
# Use this build to judge real UI performance; assembleDebug is 2-3x slower by nature.
set -euo pipefail
SRC=/mnt/c/Users/newbie/Documents/vphysics-compile/SlipstreamCLI
DST=/home/reil/Smugly-ui
mkdir -p "$DST"
if command -v rsync >/dev/null 2>&1; then
  rsync -a --delete \
    --exclude '.gradle' --exclude 'desktop/build' --exclude 'shared/build' \
    --exclude 'app/build' --exclude 'build/' --exclude '.git' "$SRC/" "$DST/"
else
  # Full tree copy when rsync is unavailable (keep native libs staging below).
  rm -rf "$DST"
  mkdir -p "$DST"
  cp -a "$SRC/." "$DST/"
  rm -rf "$DST/app/build" "$DST/shared/build" "$DST/desktop/build" "$DST/build" "$DST/.gradle" 2>/dev/null || true
fi
if [ -f "$SRC/app/build/rustJniLibs/android/arm64-v8a/libslipstream.so" ]; then
  mkdir -p "$DST/app/build/rustJniLibs/android/arm64-v8a"
  cp -a "$SRC/app/build/rustJniLibs/android/arm64-v8a/." "$DST/app/build/rustJniLibs/android/arm64-v8a/"
fi
[ -d "$SRC/app/build/s3fuJniLibs" ] && { mkdir -p "$DST/app/build/s3fuJniLibs"; cp -a "$SRC/app/build/s3fuJniLibs/." "$DST/app/build/s3fuJniLibs/"; }
[ -d "$SRC/app/build/cdnfuJniLibs" ] && { mkdir -p "$DST/app/build/cdnfuJniLibs"; cp -a "$SRC/app/build/cdnfuJniLibs/." "$DST/app/build/cdnfuJniLibs/"; }
echo "sdk.dir=/home/reil/android-sdk" > "$DST/local.properties"
export ANDROID_HOME=/home/reil/android-sdk ANDROID_SDK_ROOT=/home/reil/android-sdk HOME=/home/reil
export PATH="/home/reil/.cargo/bin:$ANDROID_HOME/platform-tools:$PATH"
cd "$DST"
chmod +x gradlew
# Native libs are pre-staged from the debug build; skip the Rust/Go rebuilds.
./gradlew :app:assembleRelease --no-daemon \
  -x cargoBuildArm64 -x cargoBuildS3fu -x cargoBuildCdnfu -x buildXrayAar \
  2>&1 | tee /tmp/slipstream-release-build.log | grep -E "^e: |FAILURE|BUILD |error:" | head -40
if [ -f app/build/outputs/apk/release/app-release.apk ]; then
  mkdir -p "$SRC/app/build/outputs/apk/release"
  cp -a app/build/outputs/apk/release/app-release.apk "$SRC/app/build/outputs/apk/release/app-release.apk"
  echo "APK_READY=$SRC/app/build/outputs/apk/release/app-release.apk"
else
  echo "APK_MISSING — see /tmp/slipstream-release-build.log"
  exit 1
fi
