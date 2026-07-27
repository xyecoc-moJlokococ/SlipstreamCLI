#!/usr/bin/env bash
set -euo pipefail
SRC=/mnt/c/Users/newbie/Documents/vphysics-compile/SlipstreamCLI
DST=/home/reil/SlipstreamCLI-ui
mkdir -p "$DST"
# Prefer rsync; fall back to cp (no rsync in minimal images).
if command -v rsync >/dev/null 2>&1; then
  rsync -a --delete \
    --exclude '.gradle' \
    --exclude 'desktop/build' \
    --exclude 'shared/build' \
    --exclude 'app/build' \
    --exclude 'build/' \
    --exclude '.git' \
    "$SRC/" "$DST/"
else
  # Fresh tree: wipe previous copy of source modules, keep nothing stale
  rm -rf "$DST/app/src" "$DST/shared" "$DST/desktop" "$DST/gradle" "$DST/settings.gradle.kts" "$DST/build.gradle.kts" 2>/dev/null || true
  cp -a "$SRC/app" "$DST/"
  cp -a "$SRC/shared" "$DST/"
  cp -a "$SRC/desktop" "$DST/" 2>/dev/null || true
  cp -a "$SRC/gradle" "$DST/"
  cp -a "$SRC/gradlew" "$SRC/gradlew.bat" "$SRC/gradle.properties" "$SRC/settings.gradle.kts" "$SRC/build.gradle.kts" "$DST/" 2>/dev/null || true
  # Drop heavy Windows build outputs except native .so
  rm -rf "$DST/app/build/intermediates" "$DST/app/build/tmp" "$DST/app/build/kotlin" \
         "$DST/app/build/generated" "$DST/app/build/outputs" 2>/dev/null || true
  rm -rf "$DST/shared/build" "$DST/desktop/build" 2>/dev/null || true
fi

# Reuse already-built native libs from Windows tree if present
if [ -f "$SRC/app/build/rustJniLibs/android/arm64-v8a/libslipstream.so" ]; then
  mkdir -p "$DST/app/build/rustJniLibs/android/arm64-v8a"
  cp -a "$SRC/app/build/rustJniLibs/android/arm64-v8a/." "$DST/app/build/rustJniLibs/android/arm64-v8a/"
fi
if [ -d "$SRC/app/build/s3fuJniLibs" ]; then
  mkdir -p "$DST/app/build/s3fuJniLibs"
  cp -a "$SRC/app/build/s3fuJniLibs/." "$DST/app/build/s3fuJniLibs/" || true
fi

echo "sdk.dir=/home/reil/android-sdk" > "$DST/local.properties"
export ANDROID_HOME=/home/reil/android-sdk
export ANDROID_SDK_ROOT=/home/reil/android-sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"
cd "$DST"
chmod +x gradlew
# UI-only rebuild: skip Rust cargo (libs already staged) and s3fu/xray.
export HOME=/home/reil
export PATH="/home/reil/.cargo/bin:$PATH"
./gradlew :app:assembleDebug --no-daemon \
  -x cargoBuildArm64 \
  -x cargoBuildS3fu \
  -x buildXrayAar \
  2>&1 | tee /tmp/slipstream-apk-build.log | tail -150
ls -la app/build/outputs/apk/debug/ || true
# Copy APK back to Windows tree for adb
if [ -f app/build/outputs/apk/debug/app-debug.apk ]; then
  mkdir -p "$SRC/app/build/outputs/apk/debug"
  cp -a app/build/outputs/apk/debug/app-debug.apk "$SRC/app/build/outputs/apk/debug/app-debug.apk"
  echo "APK_READY=$SRC/app/build/outputs/apk/debug/app-debug.apk"
fi
