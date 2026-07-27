#!/usr/bin/env bash
# Build the desktop app and stage a Windows-runnable classpath.
#
# Gradle has to run under WSL because :shared is a KMP module with an Android target and there is
# no Android SDK on the Windows side. `stageWindowsRuntime` compensates by pulling in the Windows
# Skiko native alongside the Linux one. Launch the result with run-desktop-windows.cmd.
set -euo pipefail

SRC=/mnt/c/Users/newbie/Documents/vphysics-compile/SlipstreamCLI
DST=/home/reil/SlipstreamCLI-ui

if command -v rsync >/dev/null 2>&1; then
  rsync -a --delete \
    --exclude '.gradle' --exclude 'desktop/build' --exclude 'shared/build' \
    --exclude 'app/build' --exclude 'build/' --exclude '.git' --exclude 'engines' \
    "$SRC/" "$DST/"
else
  rm -rf "$DST/shared/src" "$DST/desktop/src"
  cp -a "$SRC/shared/src" "$DST/shared/"
  cp -a "$SRC/desktop/src" "$DST/desktop/"
  cp -a "$SRC/shared/build.gradle.kts" "$DST/shared/"
  cp -a "$SRC/desktop/build.gradle.kts" "$DST/desktop/"
fi

echo "sdk.dir=/home/reil/android-sdk" > "$DST/local.properties"
export ANDROID_HOME=/home/reil/android-sdk ANDROID_SDK_ROOT=/home/reil/android-sdk HOME=/home/reil
export PATH="/home/reil/.cargo/bin:$ANDROID_HOME/platform-tools:$PATH"

cd "$DST"
chmod +x gradlew
./gradlew :desktop:stageWindowsRuntime --no-daemon 2>&1 \
  | grep -E "^e: |FAILURE|BUILD " | head -20

STAGE="$DST/desktop/build/windows-runtime/lib"
OUT="$SRC/desktop/build/windows-runtime/lib"
mkdir -p "$OUT"
rm -rf "${OUT:?}/"*
cp -a "$STAGE/." "$OUT/"
echo "STAGED=$(ls "$OUT" | wc -l) jars -> $OUT"
