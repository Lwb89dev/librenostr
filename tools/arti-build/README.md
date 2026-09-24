# nostr-arti: Arti (Tor in Rust) for LibreNostr

A thin JNI wrapper around the [`arti-client`](https://gitlab.torproject.org/tpo/core/arti) crate
that runs one Tor client inside the app and exposes it as a loopback SOCKS5 proxy. The design notes
and the reasoning behind every choice are in [`built_in_tor.md`](../../built_in_tor.md) at the
repository root; this file is about building and testing the crate.

## Status

| | State |
|---|---|
| Crate builds and its tests pass on the **Linux host** | done, see "Host tests" |
| Real Tor bootstrap and a request through the proxy, on the host | done (`{"IsTor":true}` from check.torproject.org) |
| Cross-compiled for `aarch64-linux-android` | done (2026-09-24, NDK `28.2.13676358`): 5.4 MB, 10 JNI symbols, 16 KB aligned, links `libdl`/`libm`/`libc` only |
| `build-arti.sh` / `verify-reproducible.sh` | run; two clean builds are byte-identical. `build-arti.sh` worked on its first run without changes |
| Prebuilt library committed under `app/src/main/jniLibs` | see `git log -- app/src/main/jniLibs` |

## Layout

```
Cargo.toml             crate manifest (arti-client 0.46 = Arti 2.6.0, MSRV 1.91)
Cargo.lock             committed; every build runs `cargo --locked`
rust-toolchain.toml    pinned compiler (a distro cargo without rustup ignores it)
ARTI_VERSION           the Arti release the lockfile was resolved for
ANDROID_NDK_VERSION    pinned NDK revision, read by build-arti.sh (and, later, by app/build.gradle.kts)
CARGO_NDK_VERSION      cargo-ndk release the build procedure was written against
repro-env.sh           deterministic build environment (sourced by build-arti.sh)
build-arti.sh          builds and installs libnostr_arti.so for arm64-v8a
verify-reproducible.sh builds twice and compares, then compares with the committed library
run-host-tests.sh      runs every test on the host
src/lib.rs             the wrapper
```

The NDK and cargo-ndk pins were taken from the values Amethyst verified its own reproducible build
with. They have not been exercised in this repository; treat them as a starting point.

## JNI surface

Class `net.primal.core.networking.tor.engine.ArtiNative` (Kotlin, in `core/networking-http`).
All functions are static. The class and package name are part of the native symbol names.

| Kotlin | Purpose |
|---|---|
| `nativeInitialize(dataDir): Int` | create the Tor client (once); returns as soon as it exists, the directory downloads in the background |
| `nativeStartSocks(port): Int` | bind `127.0.0.1:<port>` (`0` = OS-chosen) and return the bound port |
| `nativeStopSocks(): Int` | stop the listener, keep the client |
| `nativeIsBootstrapped(): Int` | `1` ready, `0` not yet, `-1` no client (asked live from Arti) |
| `nativeBootstrapProgress(): Int` | directory download progress in permille, `-1` without a client |
| `nativeSetDormant(soft)` | suspend or resume Arti's background work |
| `nativeStat(index): Long` | connect counters: ok, failed, consecutive "could not reach Tor" |
| `nativePollLog(): String?` | drain the queued log lines |
| `nativeDestroy(): Int` | drop the client and everything running on it; releases Arti's state-file lock |
| `nativeVersion(): String` | diagnostics |

## Host tests

The wrapper is plain Rust, so all of its logic can be tested on the development machine without
Android. `cargo test` runs the SOCKS5 protocol and mapping tests; the engine tests drive the real
client, share process-wide state and need outbound access to the Tor network, so they are ignored by
default:

```bash
./run-host-tests.sh
# or, keeping everything cargo writes out of your home directory:
CARGO_HOME=/scratch/cargo-home CARGO_TARGET_DIR=/scratch/target ./run-host-tests.sh
```

What the engine tests pin (each was a real bug somewhere else):

- `destroy()` releases Arti's state-file lock, so the same directory can be reused, over several
  cycles. A leaked client makes the next `initialize` fail and strands Tor after the first self-heal.
- stopping and restarting the proxy keeps the client, and `initialize` is a no-op while one runs.
- a SOCKS greeting delivered one byte at a time is accepted (a single-`read` parser rejects it).
- a request really goes through Tor: SOCKS connect by domain name to `check.torproject.org:443`,
  TLS through the tunnel, response contains `"IsTor":true`.
- `guards.json` appears at `state/state/guards.json` with a confirmed guard. The Kotlin side reads
  that exact path to detect a wedged guard sample. Measured here: the file is first written with an
  empty sample and the confirmed guard shows up roughly 30 seconds after the first successful
  stream, so "Tor worked here before" is not known for the first half minute of a fresh install.

Measured on the development machine (x86_64 Linux, distro rustc 1.95.0): cold bootstrap 12.5 to
27 seconds across runs; a release build with the profile in `Cargo.toml` takes about a minute and
produces a **6.25 MB** `libnostr_arti.so` for x86_64. The arm64 size has not been measured; the
Amethyst project reports 5 to 6 MB for its equivalent.

## Building for Android

Prerequisites (nothing here installs them for you):

1. `rustup`, which reads `rust-toolchain.toml` and installs the pinned compiler and the
   `aarch64-linux-android` standard library.
2. `cargo-ndk` at the version in `CARGO_NDK_VERSION`: `cargo install cargo-ndk --version "$(cat CARGO_NDK_VERSION)" --locked`.
3. The Android NDK revision in `ANDROID_NDK_VERSION`: `sdkmanager "ndk;$(cat ANDROID_NDK_VERSION)"`.

```bash
./build-arti.sh                # build and copy to app/src/main/jniLibs/arm64-v8a/
./verify-reproducible.sh       # two clean builds must be byte-identical
```

`build-arti.sh` checks the exported JNI symbols against the Kotlin declarations and warns when the
first `LOAD` segment is not 16 KB aligned (a Play requirement for 64-bit ABIs; rustc's Android
target spec provides it).

## Updating Arti

1. Pick a release tag: `git ls-remote --tags https://gitlab.torproject.org/tpo/core/arti.git | grep 'arti-v'`.
2. Update `ARTI_VERSION` and the crate versions in `Cargo.toml` to match that release's
   `crates/arti-client/Cargo.toml`.
3. `cargo update` once (without `--locked`) to re-resolve `Cargo.lock`, review the diff, then run the
   host tests.
4. Rebuild the Android library, run `verify-reproducible.sh`, and commit the library together with
   `Cargo.lock`. Arti has had security releases (`TROVE-*` advisories); do not let this lag.

## Credits

The structure of this wrapper (on-demand bootstrap, polled readiness, error mapping, guard-state
recovery) follows what Amethyst (MIT, github.com/vitorpamplona/amethyst, `tools/arti-build`) learned
in production. The code here is written from scratch for LibreNostr's package layout, with the
weaknesses noted in `built_in_tor.md` fixed. Arti itself is `MIT OR Apache-2.0`.
