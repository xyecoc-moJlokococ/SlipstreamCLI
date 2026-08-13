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
# libs3fu.so / libcdnfu.so: their cargo tasks cannot run from $DST (they resolve the Rust
# workspace as ../../../<repo>, which only exists next to the Windows checkout), so stage
# them here. Build s3fu if no copy is around, otherwise the APK ships without the engine.
S3FU_OUT="$DST/app/build/s3fuJniLibs/arm64-v8a"
mkdir -p "$S3FU_OUT"
if [ -f "$SRC/app/build/s3fuJniLibs/arm64-v8a/libs3fu.so" ]; then
  cp -a "$SRC/app/build/s3fuJniLibs/arm64-v8a/libs3fu.so" "$S3FU_OUT/"
elif [ -f "$HOME/s3fu-stage/libs3fu.so" ]; then
  cp -a "$HOME/s3fu-stage/libs3fu.so" "$S3FU_OUT/"
else
  ANDROID_NDK_HOME="$HOME/android-sdk/ndk/29.0.14206865" S3FU_OUT_DIR="$S3FU_OUT"     bash /mnt/c/Users/newbie/Documents/s3-fuckup/build-android.sh
fi
CDNFU_OUT="$DST/app/build/cdnfuJniLibs/arm64-v8a"
mkdir -p "$CDNFU_OUT"
if [ -f "$SRC/app/build/cdnfuJniLibs/arm64-v8a/libcdnfu.so" ]; then
  cp -a "$SRC/app/build/cdnfuJniLibs/arm64-v8a/libcdnfu.so" "$CDNFU_OUT/"
else
  ANDROID_NDK_HOME="$HOME/android-sdk/ndk/29.0.14206865" CDNFU_OUT_DIR="$CDNFU_OUT"     bash /mnt/c/Users/newbie/Documents/cdn-fuckup/build-android.sh
fi
# A UI-only build must not quietly drop an engine: an APK missing one of these installs
# fine and dies with UnsatisfiedLinkError the moment that protocol is used.
for lib in "$DST/app/build/rustJniLibs/android/arm64-v8a/libslipstream.so" \
           "$S3FU_OUT/libs3fu.so" "$CDNFU_OUT/libcdnfu.so"; do
  [ -f "$lib" ] || { echo "MISSING NATIVE: $lib" >&2; exit 1; }
done
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
