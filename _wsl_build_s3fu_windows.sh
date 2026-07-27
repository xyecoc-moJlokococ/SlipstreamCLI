#!/usr/bin/env bash
# Cross-compile s3fu.exe (the S3 dead-drop tunnel client) for Windows from WSL.
#
# No Windows toolchain and no sudo are needed: s3fu is pure Rust (rustls + ring, no OpenSSL),
# and cargo-zigbuild links through Zig, which ships the mingw-w64 headers and import libraries
# itself. That is why this targets *-pc-windows-gnu rather than -msvc.
set -euo pipefail

SRC=/mnt/c/Users/newbie/Documents/s3-fuckup
DST=/home/reil/s3fu-win
OUT=/mnt/c/Users/newbie/Documents/vphysics-compile/SlipstreamCLI/engines
TARGET=x86_64-pc-windows-gnu

export HOME=/home/reil
export PATH="/home/reil/.cargo/bin:/home/reil/.local/bin:$PATH"

# Building straight off /mnt/c is very slow; work on the WSL filesystem.
mkdir -p "$DST"
if command -v rsync >/dev/null 2>&1; then
  rsync -a --delete --exclude 'target' --exclude '.git' "$SRC/" "$DST/"
else
  rm -rf "$DST/crates" "$DST/s3fu"
  cp -a "$SRC/crates" "$SRC/s3fu" "$DST/"
  cp -a "$SRC/Cargo.toml" "$SRC/Cargo.lock" "$DST/" 2>/dev/null || true
fi

rustup target add "$TARGET"

# cargo-zigbuild finds Zig via the `ziglang` Python module when there is no `zig` binary.
if ! command -v zig >/dev/null 2>&1; then
  cat > "$DST/zig" <<'ZIG'
#!/usr/bin/env bash
exec python3 -m ziglang "$@"
ZIG
  chmod +x "$DST/zig"
  export PATH="$DST:$PATH"
fi

cd "$DST"
cargo zigbuild --release --target "$TARGET" -p s3fu

mkdir -p "$OUT"
cp -f "target/$TARGET/release/s3fu.exe" "$OUT/s3fu.exe"
ls -la "$OUT/s3fu.exe"
echo "S3FU_READY=$OUT/s3fu.exe"
