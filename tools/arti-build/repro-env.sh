# Deterministic build environment for libnostr_arti.so, sourced by build-arti.sh.
#
# A Rust cdylib is only byte-for-byte reproducible when every input that reaches the binary is fixed:
#
#   1. compiler version      -> rust-toolchain.toml (rustup installs it)
#   2. dependency versions   -> the committed Cargo.lock plus `cargo --locked`
#   3. host paths in strings -> --remap-path-prefix, below
#   4. codegen/link ORDER    -> a canonical build directory (see build-arti.sh). Path remapping only
#                               rewrites embedded strings; rustc still derives the order it lays out
#                               functions and data from the real on-disk artifact paths, so two
#                               builds at different paths differ even with everything else equal.
#   5. the NDK               -> ANDROID_NDK_VERSION: its clang compiles Arti's C dependencies (ring,
#                               zstd, sqlite) and its lld links the library, and both stamp their
#                               versions into the binary.
#
# The caller must have set SCRIPT_DIR (this directory) and BUILD_DIR (the canonical build directory).

# Incremental compilation caches can perturb codegen order.
export CARGO_INCREMENTAL=0

export CARGO_HOME="${CARGO_HOME:-$HOME/.cargo}"

REPRO_RUSTFLAGS="--remap-path-prefix=${CARGO_HOME}=/cargo"
REPRO_RUSTFLAGS="${REPRO_RUSTFLAGS} --remap-path-prefix=${BUILD_DIR}=/nostr-arti"
export RUSTFLAGS="${RUSTFLAGS:-} ${REPRO_RUSTFLAGS}"

# Pinned to the commit that last changed the lockfile: deterministic for a given committed input and
# independent of when the build runs. The constant is only a fallback for a checkout without history.
_epoch="$(git -C "${SCRIPT_DIR}" log -1 --format=%ct -- Cargo.lock 2>/dev/null || true)"
export SOURCE_DATE_EPOCH="${_epoch:-1790000000}"
unset _epoch
