#!/usr/bin/env bash
# Rebuild s3fu (+cdnfu) natives and assemble Smugly release APK.
set -euo pipefail

export HOME=/home/reil
source /home/reil/.cargo/env
export ANDROID_HOME=/home/reil/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/29.0.14206865
export PATH="/home/reil/.cargo/bin:$ANDROID_HOME/platform-tools:$PATH"

WIN_ROOT=/mnt/c/Users/newbie/Documents
SRC=$WIN_ROOT/vphysics-compile/SlipstreamCLI
DST=/home/reil/Smugly-ui
S3_SRC=$WIN_ROOT/s3-fuckup
CDN_SRC=$WIN_ROOT/cdn-fuckup
SLIP_SRC=$WIN_ROOT/vphysics-compile/slipstream-rust

ln -sfn "$SLIP_SRC" /home/reil/slipstream-rust

mkdir -p "$DST"
if command -v rsync >/dev/null 2>&1; then
  rsync -a \
    --exclude '.gradle' --exclude 'desktop/build' --exclude 'shared/build' \
    --exclude 'app/build' --exclude 'build/' --exclude '.git' \
    "$SRC/" "$DST/"
else
  cp -a "$SRC/app/src" "$DST/app/"
  cp -a "$SRC/shared" "$DST/"
  cp -a "$SRC/app/build.gradle.kts" "$DST/app/build.gradle.kts"
fi

# Preserve staged slipstream .so if present
if [ -f "$SRC/app/build/rustJniLibs/android/arm64-v8a/libslipstream.so" ]; then
  mkdir -p "$DST/app/build/rustJniLibs/android/arm64-v8a"
  cp -a "$SRC/app/build/rustJniLibs/android/arm64-v8a/." \
    "$DST/app/build/rustJniLibs/android/arm64-v8a/"
elif [ -f "$DST/app/build/rustJniLibs/android/arm64-v8a/libslipstream.so" ]; then
  echo "keeping existing slipstream so in DST"
else
  # try from previous WSL build of slipstream via gradle target dir
  if [ -f "$SLIP_SRC/target/aarch64-linux-android/release/libslipstream.so" ]; then
    mkdir -p "$DST/app/build/rustJniLibs/android/arm64-v8a"
    cp -a "$SLIP_SRC/target/aarch64-linux-android/release/libslipstream.so" \
      "$DST/app/build/rustJniLibs/android/arm64-v8a/"
  fi
fi

echo "sdk.dir=/home/reil/android-sdk" > "$DST/local.properties"
cd "$DST"
chmod +x gradlew

echo "=== s3fu android ==="
export S3FU_OUT_DIR="$DST/app/build/s3fuJniLibs/arm64-v8a"
mkdir -p "$S3FU_OUT_DIR"
bash "$S3_SRC/build-android.sh" 2>&1 | tee /tmp/s3fu-build.log | tail -40
test -f "$S3FU_OUT_DIR/libs3fu.so"
nm -D "$S3FU_OUT_DIR/libs3fu.so" | grep S3fuBridge | head -n 6

echo "=== cdnfu android ==="
export CDNFU_OUT_DIR="$DST/app/build/cdnfuJniLibs/arm64-v8a"
mkdir -p "$CDNFU_OUT_DIR"
if [ -f "$CDN_SRC/build-android.sh" ]; then
  bash "$CDN_SRC/build-android.sh" 2>&1 | tee /tmp/cdnfu-build.log | tail -30
  nm -D "$CDNFU_OUT_DIR/libcdnfu.so" | grep CdnfuBridge | head -n 6 || true
else
  echo "cdnfu build script missing; keeping existing lib if any"
fi

mkdir -p "$SRC/app/build/s3fuJniLibs/arm64-v8a" "$SRC/app/build/cdnfuJniLibs/arm64-v8a"
cp -a "$S3FU_OUT_DIR/." "$SRC/app/build/s3fuJniLibs/arm64-v8a/"
[ -d "$CDNFU_OUT_DIR" ] && cp -a "$CDNFU_OUT_DIR/." "$SRC/app/build/cdnfuJniLibs/arm64-v8a/" || true

echo "=== assembleRelease ==="
./gradlew :app:assembleRelease --no-daemon \
  -x cargoBuildArm64 -x cargoBuildS3fu -x cargoBuildCdnfu -x buildXrayAar \
  2>&1 | tee /tmp/slipstream-release-build.log | tail -60

test -f app/build/outputs/apk/release/app-release.apk
mkdir -p "$SRC/app/build/outputs/apk/release"
cp -a app/build/outputs/apk/release/app-release.apk \
  "$SRC/app/build/outputs/apk/release/app-release.apk"
ls -la "$SRC/app/build/outputs/apk/release/app-release.apk"
echo "APK_READY=$SRC/app/build/outputs/apk/release/app-release.apk"
