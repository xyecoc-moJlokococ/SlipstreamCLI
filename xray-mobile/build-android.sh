#!/usr/bin/env bash
#
# Build libxray.aar (Xray-core wrapped by gomobile bind) into app/libs/.
#
# Run from WSL, like the Rust builds:
#   bash xray-mobile/build-android.sh
#
# Env overrides:
#   ANDROID_NDK_HOME  NDK to cross-compile with (default: <sdk>/ndk/<only entry>)
#   ANDROID_HOME      SDK root (default: from ../local.properties, else ~/android-sdk)
#   XRAY_OUT          where to write libxray.aar (default: ../app/libs)
#   XRAY_TARGETS      gomobile -target (default: android/arm64, matching abiFilters)
#   XRAY_SKIP_ASSETS  set to 1 to reuse already-downloaded geo data
set -o errexit
set -o pipefail
set -o nounset

__dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$__dir"

OUT_DIR="${XRAY_OUT:-$__dir/../app/libs}"
TARGETS="${XRAY_TARGETS:-android/arm64}"

# --- toolchain -------------------------------------------------------------
export PATH="/usr/local/go/bin:${HOME}/go/bin:${PATH}"
command -v go >/dev/null 2>&1 || { echo "go not found on PATH" >&2; exit 1; }
# Xray-core needs Go 1.26; let the local toolchain fetch it if it is older.
export GOTOOLCHAIN="${GOTOOLCHAIN:-auto}"

if [[ -z "${ANDROID_HOME:-}" ]]; then
    sdk_from_props="$(sed -n 's/^sdk\.dir=//p' "$__dir/../local.properties" 2>/dev/null | head -1)"
    export ANDROID_HOME="${sdk_from_props:-$HOME/android-sdk}"
fi
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    ndk_dir="$(find "$ANDROID_HOME/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort | tail -1)"
    [[ -n "$ndk_dir" ]] || { echo "no NDK under $ANDROID_HOME/ndk; set ANDROID_NDK_HOME" >&2; exit 1; }
    export ANDROID_NDK_HOME="$ndk_dir"
fi
echo "ANDROID_HOME=$ANDROID_HOME"
echo "ANDROID_NDK_HOME=$ANDROID_NDK_HOME"

# --- geo data --------------------------------------------------------------
# gomobile embeds ./assets into the AAR; libxray.go's file reader falls back to
# them when the on-disk asset dir has no copy (see InitEnv). Only configs with
# geoip:/geosite: routing rules need these -- they cost ~28 MB of APK, so
# XRAY_NO_GEO=1 leaves them out (such rules then fail at core start with a clear
# "failed to open file" from Xray, and dropping the .dat files into the app's
# files/xray/ dir at runtime still works).
mkdir -p assets
if [[ "${XRAY_NO_GEO:-0}" == "1" ]]; then
    rm -f assets/geoip.dat assets/geosite.dat
elif [[ "${XRAY_SKIP_ASSETS:-0}" != "1" ]]; then
    for f in geoip.dat geosite.dat; do
        if [[ ! -s "assets/$f" ]]; then
            echo "Downloading $f..."
            curl -fsSL "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/$f" \
                -o "assets/$f.tmp"
            mv "assets/$f.tmp" "assets/$f"
        fi
    done
fi
ls -la assets

# --- build -----------------------------------------------------------------
# gomobile/gobind are installed as standalone binaries (outside this module, so
# they cannot perturb go.mod) exactly as AndroidLibXrayLite's CI does.
if [[ "${XRAY_SKIP_GOMOBILE_INSTALL:-0}" != "1" ]]; then
    go install golang.org/x/mobile/cmd/gomobile@latest
    go install golang.org/x/mobile/cmd/gobind@latest
fi

go mod tidy
gomobile init

mkdir -p "$OUT_DIR"
# -checklinkname=0: Xray's dependency tree uses //go:linkname against internal
# runtime symbols, which Go 1.23+ rejects by default (same flag AndroidLibXrayLite uses).
gomobile bind -v \
    -target="$TARGETS" \
    -androidapi 24 \
    -trimpath \
    -ldflags='-s -w -buildid= -checklinkname=0' \
    -o "$OUT_DIR/libxray.aar" \
    ./

echo "built: $OUT_DIR/libxray.aar"
ls -la "$OUT_DIR/libxray.aar"
