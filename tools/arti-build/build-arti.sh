#!/usr/bin/env bash
#
# Builds libnostr_arti.so for Android (arm64-v8a, the only ABI LibreNostr ships) and installs it under
# app/src/main/jniLibs/arm64-v8a/.
#
#   ./build-arti.sh            build and install
#   ./build-arti.sh --out DIR  build into DIR/arm64-v8a instead of the app (used by verify-reproducible.sh)
#
# Prerequisites (see README.md): rustup with the toolchain and target from rust-toolchain.toml,
# cargo-ndk at the version in CARGO_NDK_VERSION, and the Android NDK revision in ANDROID_NDK_VERSION.
# Nothing here installs anything: it stops and says what is missing.
#
# NOTE: written from Amethyst's documented procedure and the Cargo/NDK behaviour described there; it
# has not been run in this repository yet because the Android toolchain is not installed on the
# development machine. The first real run is expected to need small fixes.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ABI="arm64-v8a"
TARGET="aarch64-linux-android"
LIB_NAME="libnostr_arti.so"
OUT_DIR="$REPO_ROOT/app/src/main/jniLibs"

while [ $# -gt 0 ]; do
    case "$1" in
        --out) OUT_DIR="$2"; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

# The build always happens in one fixed directory so the output does not depend on where the
# repository is checked out (see repro-env.sh, point 4).
BUILD_DIR="${ARTI_REPRO_DIR:-/tmp/librenostr-arti-build}"

PINNED_NDK="$(tr -d '[:space:]' < "$SCRIPT_DIR/ANDROID_NDK_VERSION")"
PINNED_CARGO_NDK="$(tr -d '[:space:]' < "$SCRIPT_DIR/CARGO_NDK_VERSION")"

fail() { echo "error: $*" >&2; exit 1; }

command -v cargo >/dev/null || fail "cargo not found"
command -v rustup >/dev/null || fail "rustup not found: the pinned toolchain and the Android target need it"
rustup target list --installed | grep -qx "$TARGET" \
    || fail "Rust target $TARGET is not installed (rustup target add $TARGET, or run cargo once in $SCRIPT_DIR so rust-toolchain.toml installs it)"

command -v cargo-ndk >/dev/null || fail "cargo-ndk not found (cargo install cargo-ndk --version $PINNED_CARGO_NDK --locked)"
installed_cargo_ndk="$(cargo ndk --version | awk '{print $NF}')"
[ "$installed_cargo_ndk" = "$PINNED_CARGO_NDK" ] \
    || echo "warning: cargo-ndk is $installed_cargo_ndk, the pinned output was verified with $PINNED_CARGO_NDK" >&2

# Find the pinned NDK by reading each candidate's own source.properties. ANDROID_NDK_HOME and
# friends are only hints: CI images and other projects leave them pointing at whatever NDK they
# bundle, and using it would produce a library that does not match the committed one.
find_ndk() {
    local root candidate
    for root in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}" \
                "${ANDROID_HOME:-}/ndk/$PINNED_NDK" "${ANDROID_SDK_ROOT:-}/ndk/$PINNED_NDK" \
                "$HOME/Android/Sdk/ndk/$PINNED_NDK" "/usr/local/lib/android/sdk/ndk/$PINNED_NDK"; do
        candidate="$root"
        [ -f "$candidate/source.properties" ] || continue
        if grep -q "^Pkg.Revision = $PINNED_NDK\$" "$candidate/source.properties"; then
            echo "$candidate"
            return 0
        fi
    done
    return 1
}
NDK="$(find_ndk)" || fail "Android NDK $PINNED_NDK not found (sdkmanager \"ndk;$PINNED_NDK\")"
export ANDROID_NDK_HOME="$NDK"

echo "== Building $LIB_NAME for $ABI with NDK $PINNED_NDK =="
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
cp -R "$SCRIPT_DIR/Cargo.toml" "$SCRIPT_DIR/Cargo.lock" "$SCRIPT_DIR/rust-toolchain.toml" "$SCRIPT_DIR/src" "$BUILD_DIR/"

# shellcheck source=repro-env.sh
source "$SCRIPT_DIR/repro-env.sh"

(cd "$BUILD_DIR" && cargo ndk --target "$ABI" --platform 26 --output-dir "$BUILD_DIR/jniLibs" build --release --locked)

built="$BUILD_DIR/jniLibs/$ABI/$LIB_NAME"
[ -f "$built" ] || fail "the build did not produce $built"

# The JNI symbol names carry the Kotlin package; a rename on either side breaks every call at runtime
# without any build error, so check them here.
expected=(nativeVersion nativeInitialize nativeStartSocks nativeStopSocks nativeIsBootstrapped
          nativeBootstrapProgress nativeSetDormant nativeStat nativePollLog nativeDestroy)
symbols="$("$NDK/toolchains/llvm/prebuilt/"*/bin/llvm-nm -D --defined-only "$built")"
for name in "${expected[@]}"; do
    echo "$symbols" | grep -q "Java_net_primal_core_networking_tor_engine_ArtiNative_$name" \
        || fail "missing JNI symbol $name in $built"
done

# 16 KB page alignment is required for the 64-bit ABIs on current Android.
"$NDK/toolchains/llvm/prebuilt/"*/bin/llvm-readelf -l "$built" | grep LOAD | awk '{print $NF}' | grep -qx "0x4000" \
    || echo "warning: the first LOAD segment is not 16 KB aligned" >&2

mkdir -p "$OUT_DIR/$ABI"
cp "$built" "$OUT_DIR/$ABI/$LIB_NAME"
echo "== Installed $OUT_DIR/$ABI/$LIB_NAME =="
sha256sum "$OUT_DIR/$ABI/$LIB_NAME"
ls -l "$OUT_DIR/$ABI/$LIB_NAME"
