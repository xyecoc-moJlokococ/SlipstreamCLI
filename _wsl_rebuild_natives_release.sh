#!/usr/bin/env bash
# Rebuild slipstream/s3fu/cdnfu natives (JNI package app.smugly) + release APK.
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
SLIP_SRC=$WIN_ROOT/vphysics-compile/slipstream-rust
S3_SRC=$WIN_ROOT/s3-fuckup
CDN_SRC=$WIN_ROOT/cdn-fuckup

echo "=== verify JNI package renames ==="
grep -q 'Java_app_smugly_tunnel_SlipstreamBridge_' \
  "$SLIP_SRC/crates/slipstream-client/src/lib.rs"
grep -q 'app/smugly/tunnel/SlipstreamBridge' \
  "$SLIP_SRC/crates/slipstream-client/src/lib.rs"
grep -q 'Java_app_smugly_tunnel_HevSocks5Tunnel_' \
  "$SRC/app/src/main/cpp/hev-socks5-tunnel/hev_jni.c"
grep -q 'Java_app_smugly_tunnel_S3fuBridge_' \
  "$S3_SRC/crates/s3fu-jni/src/lib.rs"
grep -q 'Java_app_smugly_tunnel_CdnfuBridge_' \
  "$CDN_SRC/crates/cdnfu-jni/src/lib.rs"
echo "source renames OK"

# slipstream-rust must live at ../../slipstream-rust relative to app/
ln -sfn "$SLIP_SRC" /home/reil/slipstream-rust

# Sync app sources into WSL-native tree (fast I/O for gradle)
mkdir -p "$DST"
if command -v rsync >/dev/null 2>&1; then
  rsync -a --delete \
    --exclude '.gradle' --exclude 'desktop/build' --exclude 'shared/build' \
    --exclude 'app/build' --exclude 'build/' --exclude '.git' \
    "$SRC/" "$DST/"
else
  rm -rf "$DST/app/src" "$DST/shared" 2>/dev/null || true
  cp -a "$SRC/app/src" "$DST/app/"
  cp -a "$SRC/shared" "$DST/"
  cp -a "$SRC/app/build.gradle.kts" "$DST/app/build.gradle.kts"
fi
echo "sdk.dir=/home/reil/android-sdk" > "$DST/local.properties"
cd "$DST"
chmod +x gradlew

echo "=== cargoBuildArm64 (libslipstream.so) ==="
./gradlew :app:cargoBuildArm64 --no-daemon 2>&1 | tee /tmp/slipstream-cargo.log | tail -80
test -f app/build/rustJniLibs/android/arm64-v8a/libslipstream.so

echo "=== verify slipstream symbols ==="
nm -D app/build/rustJniLibs/android/arm64-v8a/libslipstream.so | grep SlipstreamBridge | head -5
if nm -D app/build/rustJniLibs/android/arm64-v8a/libslipstream.so | grep -q 'Java_app_slipnet_tunnel_SlipstreamBridge'; then
  echo "FAIL: still has app_slipnet symbols" >&2
  exit 1
fi
if ! nm -D app/build/rustJniLibs/android/arm64-v8a/libslipstream.so | grep -q 'Java_app_smugly_tunnel_SlipstreamBridge'; then
  echo "FAIL: missing app_smugly symbols" >&2
  exit 1
fi
echo "slipstream symbols OK"

echo "=== s3fu ==="
export S3FU_OUT_DIR="$DST/app/build/s3fuJniLibs/arm64-v8a"
mkdir -p "$S3FU_OUT_DIR"
bash "$S3_SRC/build-android.sh" 2>&1 | tee /tmp/s3fu-build.log | tail -30

echo "=== cdnfu ==="
export CDNFU_OUT_DIR="$DST/app/build/cdnfuJniLibs/arm64-v8a"
mkdir -p "$CDNFU_OUT_DIR"
bash "$CDN_SRC/build-android.sh" 2>&1 | tee /tmp/cdnfu-build.log | tail -30

# Stage natives back to Windows tree for future UI-only builds
mkdir -p "$SRC/app/build/rustJniLibs/android/arm64-v8a"
cp -a app/build/rustJniLibs/android/arm64-v8a/. "$SRC/app/build/rustJniLibs/android/arm64-v8a/"
mkdir -p "$SRC/app/build/s3fuJniLibs/arm64-v8a" "$SRC/app/build/cdnfuJniLibs/arm64-v8a"
cp -a "$S3FU_OUT_DIR/." "$SRC/app/build/s3fuJniLibs/arm64-v8a/"
cp -a "$CDNFU_OUT_DIR/." "$SRC/app/build/cdnfuJniLibs/arm64-v8a/"

echo "=== assembleRelease (hev ndk rebuilds here) ==="
./gradlew :app:assembleRelease --no-daemon \
  -x cargoBuildArm64 -x cargoBuildS3fu -x cargoBuildCdnfu -x buildXrayAar \
  2>&1 | tee /tmp/slipstream-release-build.log | grep -E "^e: |FAILURE|BUILD |error:" | head -40

if [ -f app/build/outputs/apk/release/app-release.apk ]; then
  mkdir -p "$SRC/app/build/outputs/apk/release"
  cp -a app/build/outputs/apk/release/app-release.apk \
    "$SRC/app/build/outputs/apk/release/app-release.apk"
  echo "APK_READY=$SRC/app/build/outputs/apk/release/app-release.apk"
else
  echo "APK_MISSING — see /tmp/slipstream-release-build.log" >&2
  exit 1
fi
