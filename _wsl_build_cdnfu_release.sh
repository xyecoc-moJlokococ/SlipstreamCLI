#!/usr/bin/env bash
# Rebuild libcdnfu (+ ensure s3fu/slipstream present) and assemble Smugly release APK.
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
CDN_SRC=$WIN_ROOT/cdn-fuckup
S3_SRC=$WIN_ROOT/s3-fuckup
SLIP_SRC=$WIN_ROOT/vphysics-compile/slipstream-rust

ln -sfn "$SLIP_SRC" /home/reil/slipstream-rust

echo "=== sync sources to WSL tree ==="
mkdir -p "$DST"
if command -v rsync >/dev/null 2>&1; then
  rsync -a --delete \
    --exclude '.gradle' --exclude 'desktop/build' --exclude 'shared/build' \
    --exclude 'app/build' --exclude 'build/' --exclude '.git' \
    "$SRC/" "$DST/"
else
  cp -a "$SRC/app/src" "$DST/app/"
  cp -a "$SRC/shared" "$DST/"
  cp -a "$SRC/app/build.gradle.kts" "$DST/app/build.gradle.kts"
  cp -a "$SRC/app/src/main/AndroidManifest.xml" "$DST/app/src/main/AndroidManifest.xml"
fi
# Keep already-built natives if present
mkdir -p "$DST/app/build/rustJniLibs/android/arm64-v8a"
mkdir -p "$DST/app/build/s3fuJniLibs/arm64-v8a"
mkdir -p "$DST/app/build/cdnfuJniLibs/arm64-v8a"
if [ -f "$SRC/app/build/rustJniLibs/android/arm64-v8a/libslipstream.so" ]; then
  cp -a "$SRC/app/build/rustJniLibs/android/arm64-v8a/libslipstream.so" \
    "$DST/app/build/rustJniLibs/android/arm64-v8a/" || true
fi
if [ -f "$SRC/app/build/s3fuJniLibs/arm64-v8a/libs3fu.so" ]; then
  cp -a "$SRC/app/build/s3fuJniLibs/arm64-v8a/libs3fu.so" \
    "$DST/app/build/s3fuJniLibs/arm64-v8a/" || true
fi

echo "sdk.dir=/home/reil/android-sdk" > "$DST/local.properties"
cd "$DST"
chmod +x gradlew

# Prefer freshly cargo-built slipstream from first rebuild, else SRC copy, else rebuild.
has_smugly_syms() {
  # Avoid pipefail+grep -q SIGPIPE false negatives.
  nm -D "$1" 2>/dev/null | grep 'Java_app_smugly_tunnel_SlipstreamBridge' >/tmp/smugly-syms.txt || true
  [ -s /tmp/smugly-syms.txt ]
}

if ! has_smugly_syms app/build/rustJniLibs/android/arm64-v8a/libslipstream.so; then
  if has_smugly_syms "$SRC/app/build/rustJniLibs/android/arm64-v8a/libslipstream.so"; then
    cp -a "$SRC/app/build/rustJniLibs/android/arm64-v8a/libslipstream.so" \
      app/build/rustJniLibs/android/arm64-v8a/
  elif [ -f /mnt/c/Users/newbie/Documents/vphysics-compile/slipstream-rust/target/aarch64-linux-android/debug/libslipstream.so ]; then
    cp -a /mnt/c/Users/newbie/Documents/vphysics-compile/slipstream-rust/target/aarch64-linux-android/debug/libslipstream.so \
      app/build/rustJniLibs/android/arm64-v8a/
  else
    echo "=== cargoBuildArm64 ==="
    ./gradlew :app:cargoBuildArm64 --no-daemon 2>&1 | tee /tmp/slipstream-cargo.log | tail -20
  fi
fi
test -f app/build/rustJniLibs/android/arm64-v8a/libslipstream.so
has_smugly_syms app/build/rustJniLibs/android/arm64-v8a/libslipstream.so \
  || { echo "FAIL: missing app_smugly SlipstreamBridge symbols" >&2; exit 1; }
echo "slipstream OK"

# s3fu
if [ ! -f app/build/s3fuJniLibs/arm64-v8a/libs3fu.so ]; then
  echo "=== s3fu android ==="
  export S3FU_OUT_DIR="$DST/app/build/s3fuJniLibs/arm64-v8a"
  mkdir -p "$S3FU_OUT_DIR"
  bash "$S3_SRC/build-android.sh" 2>&1 | tee /tmp/s3fu-build.log | tail -20
fi
test -f app/build/s3fuJniLibs/arm64-v8a/libs3fu.so
echo "s3fu OK"

echo "=== cdnfu android (always rebuild — JNI multipath arg) ==="
export CDNFU_OUT_DIR="$DST/app/build/cdnfuJniLibs/arm64-v8a"
mkdir -p "$CDNFU_OUT_DIR"
bash "$CDN_SRC/build-android.sh" 2>&1 | tee /tmp/cdnfu-build.log | tail -40
test -f "$CDNFU_OUT_DIR/libcdnfu.so"
nm -D "$CDNFU_OUT_DIR/libcdnfu.so" 2>/dev/null | grep 'Java_app_smugly_tunnel_CdnfuBridge_nativeStartClient' >/tmp/cdnfu-syms.txt || true
[ -s /tmp/cdnfu-syms.txt ] || { echo "FAIL: missing CdnfuBridge symbols" >&2; cat /tmp/cdnfu-build.log | tail -50 >&2; exit 1; }
sed -n '1,8p' /tmp/cdnfu-syms.txt || true
echo "cdnfu OK"

# Stage natives back to Windows tree
mkdir -p "$SRC/app/build/rustJniLibs/android/arm64-v8a" \
  "$SRC/app/build/s3fuJniLibs/arm64-v8a" \
  "$SRC/app/build/cdnfuJniLibs/arm64-v8a"
cp -a app/build/rustJniLibs/android/arm64-v8a/. "$SRC/app/build/rustJniLibs/android/arm64-v8a/"
cp -a app/build/s3fuJniLibs/arm64-v8a/. "$SRC/app/build/s3fuJniLibs/arm64-v8a/"
cp -a "$CDNFU_OUT_DIR/." "$SRC/app/build/cdnfuJniLibs/arm64-v8a/"

echo "=== assembleRelease ==="
./gradlew :app:assembleRelease --no-daemon \
  -x cargoBuildArm64 -x cargoBuildS3fu -x cargoBuildCdnfu -x buildXrayAar \
  2>&1 | tee /tmp/smugly-release-build.log | tail -60

if [ -f app/build/outputs/apk/release/app-release.apk ]; then
  mkdir -p "$SRC/app/build/outputs/apk/release"
  cp -a app/build/outputs/apk/release/app-release.apk \
    "$SRC/app/build/outputs/apk/release/app-release.apk"
  # Also stage a clearly named copy
  cp -a app/build/outputs/apk/release/app-release.apk \
    "$SRC/app/build/outputs/apk/release/Smugly-cdnfu-release.apk"
  ls -lh app/build/outputs/apk/release/app-release.apk
  unzip -l app/build/outputs/apk/release/app-release.apk | grep -E 'libcdnfu|libs3fu|libslipstream' || true
  echo "APK_READY=$SRC/app/build/outputs/apk/release/app-release.apk"
  echo "APK_NAMED=$SRC/app/build/outputs/apk/release/Smugly-cdnfu-release.apk"
else
  echo "APK_MISSING — see /tmp/smugly-release-build.log" >&2
  grep -E '^e: |FAILURE|error:|What went wrong' /tmp/smugly-release-build.log | head -40 >&2 || true
  exit 1
fi
