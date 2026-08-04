#!/usr/bin/env bash
# Stage new libs3fu.so + assembleRelease, copy APK to Windows tree.
set -euo pipefail
export HOME=/home/reil
# shellcheck source=/dev/null
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

echo "=== sync sources ==="
mkdir -p "$DST"
# rsync may be missing; selective copy of sources (keep DST gradle caches)
cp -a "$SRC/app/src" "$DST/app/"
cp -a "$SRC/app/build.gradle.kts" "$DST/app/build.gradle.kts"
cp -a "$SRC/app/libs" "$DST/app/" 2>/dev/null || true
cp -a "$SRC/shared/src" "$DST/shared/"
cp -a "$SRC/shared/build.gradle.kts" "$DST/shared/build.gradle.kts"
cp -a "$SRC/build.gradle.kts" "$SRC/settings.gradle.kts" "$SRC/gradle.properties" "$DST/" 2>/dev/null || true
cp -a "$SRC/gradle" "$DST/" 2>/dev/null || true
cp -a "$SRC/gradlew" "$DST/gradlew"
# wipe stale generated compose under wrong package
rm -rf "$DST/shared/build" "$DST/app/build/generated" 2>/dev/null || true

echo "sdk.dir=/home/reil/android-sdk" > "$DST/local.properties"
cd "$DST"
chmod +x gradlew

# Keep previously-built slipstream if available (skip long cargoBuildArm64)
if [ -f "$SRC/app/build/rustJniLibs/android/arm64-v8a/libslipstream.so" ]; then
  mkdir -p app/build/rustJniLibs/android/arm64-v8a
  cp -a "$SRC/app/build/rustJniLibs/android/arm64-v8a/." app/build/rustJniLibs/android/arm64-v8a/
  echo "staged slipstream from Windows tree"
elif [ -f /home/reil/Smugly-ui/app/build/rustJniLibs/android/arm64-v8a/libslipstream.so ]; then
  echo "slipstream already in DST (rsync excluded app/build — restore)"
fi

# Force rebuild s3fu jni with latest sources
echo "=== s3fu jni ==="
export S3FU_OUT_DIR="$DST/app/build/s3fuJniLibs/arm64-v8a"
mkdir -p "$S3FU_OUT_DIR"
# touch core config so cargo rebuilds
touch "$S3_SRC/crates/s3fu-core/src/config.rs"
bash "$S3_SRC/build-android.sh" 2>&1 | tee /tmp/s3fu-jni.log | tail -20
test -f "$S3FU_OUT_DIR/libs3fu.so"
ls -la "$S3FU_OUT_DIR/libs3fu.so"
nm -D "$S3FU_OUT_DIR/libs3fu.so" | grep S3fuBridge | head -4

# cdnfu if present
if [ -f "$CDN_SRC/build-android.sh" ]; then
  echo "=== cdnfu jni ==="
  export CDNFU_OUT_DIR="$DST/app/build/cdnfuJniLibs/arm64-v8a"
  mkdir -p "$CDNFU_OUT_DIR"
  bash "$CDN_SRC/build-android.sh" 2>&1 | tee /tmp/cdnfu-jni.log | tail -15 || true
fi

# Clean broken compose generators
rm -rf shared/build/generated app/build/generated 2>/dev/null || true

echo "=== assembleRelease ==="
./gradlew :app:assembleRelease --no-daemon --rerun-tasks \
  -x cargoBuildArm64 -x cargoBuildS3fu -x cargoBuildCdnfu -x buildXrayAar \
  2>&1 | tee /tmp/apk-assemble.log | tail -100

APK=app/build/outputs/apk/release/app-release.apk
test -f "$APK"
# ensure libs3fu is inside apk
unzip -l "$APK" | grep -E 'libs3fu|libcdnfu|libslipstream' || true
ls -la "$APK"
mkdir -p "$SRC/app/build/outputs/apk/release"
cp -a "$APK" "$SRC/app/build/outputs/apk/release/app-release.apk"
mkdir -p "$SRC/app/build/s3fuJniLibs/arm64-v8a"
cp -a "$S3FU_OUT_DIR/." "$SRC/app/build/s3fuJniLibs/arm64-v8a/"
echo "APK_READY=$SRC/app/build/outputs/apk/release/app-release.apk"
stat -c '%y %s %n' "$SRC/app/build/outputs/apk/release/app-release.apk"
