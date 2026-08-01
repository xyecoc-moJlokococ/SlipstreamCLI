#!/usr/bin/env bash
# Continue after cargoBuildArm64: stage natives, rebuild s3fu/cdnfu, assembleRelease.
set -euo pipefail

export HOME=/home/reil
export ANDROID_HOME=/home/reil/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/29.0.14206865
export PATH="/home/reil/.cargo/bin:$ANDROID_HOME/platform-tools:$PATH"
# shellcheck source=/dev/null
source /home/reil/.cargo/env

WIN_ROOT=/mnt/c/Users/newbie/Documents
SRC=$WIN_ROOT/vphysics-compile/SlipstreamCLI
DST=/home/reil/Smugly-ui
S3_SRC=$WIN_ROOT/s3-fuckup
CDN_SRC=$WIN_ROOT/cdn-fuckup

SO_DST="$DST/app/build/rustJniLibs/android/arm64-v8a/libslipstream.so"
SO_SRC="$SRC/app/build/rustJniLibs/android/arm64-v8a/libslipstream.so"
test -f "$SO_DST"
mkdir -p "$(dirname "$SO_SRC")"
cp -a "$SO_DST" "$SO_SRC"
echo "staged slipstream bytes=$(stat -c%s "$SO_SRC")"
nm -D "$SO_SRC" | grep -c app_smugly
nm -D "$SO_SRC" | grep -c app_slipnet || true

echo "=== s3fu ==="
export S3FU_OUT_DIR="$DST/app/build/s3fuJniLibs/arm64-v8a"
mkdir -p "$S3FU_OUT_DIR"
bash "$S3_SRC/build-android.sh"
mkdir -p "$SRC/app/build/s3fuJniLibs/arm64-v8a"
cp -a "$S3FU_OUT_DIR/." "$SRC/app/build/s3fuJniLibs/arm64-v8a/"
nm -D "$S3FU_OUT_DIR/libs3fu.so" | grep S3fuBridge | head -n 4 || true

echo "=== cdnfu ==="
export CDNFU_OUT_DIR="$DST/app/build/cdnfuJniLibs/arm64-v8a"
mkdir -p "$CDNFU_OUT_DIR"
bash "$CDN_SRC/build-android.sh"
mkdir -p "$SRC/app/build/cdnfuJniLibs/arm64-v8a"
cp -a "$CDNFU_OUT_DIR/." "$SRC/app/build/cdnfuJniLibs/arm64-v8a/"
nm -D "$CDNFU_OUT_DIR/libcdnfu.so" | grep CdnfuBridge | head -n 4 || true

echo "=== assembleRelease ==="
cd "$DST"
echo "sdk.dir=/home/reil/android-sdk" > local.properties
# ensure hev JNI rename is present in WSL tree
cp -a "$SRC/app/src/main/cpp/hev-socks5-tunnel/hev_jni.c" \
  "$DST/app/src/main/cpp/hev-socks5-tunnel/hev_jni.c"
# re-stage slipstream into DST in case rsync wiped it earlie
mkdir -p "$DST/app/build/rustJniLibs/android/arm64-v8a"
cp -a "$SO_SRC" "$DST/app/build/rustJniLibs/android/arm64-v8a/libslipstream.so"

./gradlew :app:assembleRelease --no-daemon \
  -x cargoBuildArm64 -x cargoBuildS3fu -x cargoBuildCdnfu -x buildXrayAar \
  2>&1 | tee /tmp/slipstream-release-build.log | tail -n 50

if [ -f app/build/outputs/apk/release/app-release.apk ]; then
  mkdir -p "$SRC/app/build/outputs/apk/release"
  cp -a app/build/outputs/apk/release/app-release.apk \
    "$SRC/app/build/outputs/apk/release/app-release.apk"
  echo "APK_READY=$SRC/app/build/outputs/apk/release/app-release.apk"
  ls -la "$SRC/app/build/outputs/apk/release/app-release.apk"
else
  echo "APK_MISSING"
  tail -n 80 /tmp/slipstream-release-build.log
  exit 1
fi
