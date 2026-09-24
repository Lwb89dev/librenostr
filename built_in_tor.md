# Built-in Tor for LibreNostr

Research and implementation plan for shipping an in-app Tor engine (Arti) next to the existing
Orbot support. Written on 2026-09-24 on branch `feature/built-in-tor`; the last section tracks what
has actually been built so far.

**Reference read for this document**

| What | Version | How it was read |
|---|---|---|
| Amethyst (`vitorpamplona/amethyst`) | `a8db8b8`, 2026-09-24, MIT | shallow clone, every Tor-related file read in full |
| Arti (Tor Project) | tag `arti-v2.6.0`, `arti-client 0.46.0`, MSRV 1.91, `MIT OR Apache-2.0` | shallow clone of the tag, API checked in source |
| LibreNostr | `86da26ca7` (v0.5.17 + unreleased work) | this repository |

Facts are tagged **[verified]** (read in source or measured here), **[claimed]** (stated by another
project's docs, not independently checked) or **[unverified]** (recollection, must be checked before
relying on it).

---

## 1. Where LibreNostr is today

Tor support is **Orbot only**: an external SOCKS proxy the user installs and runs separately.
**[verified]**

- `core/networking-http/src/androidMain/kotlin/net/primal/core/networking/tor/`
  - `TorProxySettings(enabled: Boolean, socksPort: Int = 9050)`, persisted as JSON in a DataStore
    (`TorProxySettingsStore`).
  - `TorProxyOkHttpConfigurer.applyTorProxyIfEnabled(settings)` sets
    `Proxy(SOCKS, 127.0.0.1:<port>)` and widens the timeouts to 30 s. There is **no fallback to a
    direct connection** on purpose: if the port does not answer the call fails instead of leaking.
- Settings are read **once, blocking, when a client is built**, so the toggle is documented as
  "requires an app restart". Consumers: the Hilt `OkHttpClient` (`NetworkingModule`), the Ktor
  engine used by every `HttpClientFactory` client (relays' WebSockets, NIP-05, Blossom, ...), Coil
  (`PrimalImageLoaderFactory`), ExoPlayer (`MediaOkHttpClientProvider`, `NoteAttachmentVideoPreview`),
  `MediaDownloader`, `VideoFrameExtractor`, and the WebView (`WebViewProxyConfigurer`).
- UI: `settings/tor/` (toggle + port field + "Orbot not installed" hint) and
  `auth/welcome/OrbotOnboardingScreen.kt`.
- The app is **arm64-v8a only** (`app/build.gradle.kts`, `splits`), distributed as signed
  `altRelease` APKs on GitHub releases and on Zapstore (`.github/workflows/tag-release.yml`,
  `publish-zapstore.yml`). Not on F-Droid or Google Play, so shipping a committed prebuilt binary
  is not blocked by a store policy. `minSdk 26`, `compileSdk 37`, `targetSdk 36`.
- There is **no native build infrastructure** (no NDK block, no `jniLibs`, no Rust). The previous
  native experiment (Bergamot translation) was shelved in `shelved/translation-engine/`.

What is missing compared with Amethyst is the engine, not the policy: the toggle is all-or-nothing
and needs Orbot.

---

## 2. What Amethyst actually does

Snapshot `a8db8b8`. Everything here was read in Amethyst's source and docs. Where a number is a
measurement (bootstrap times, sizes, failure rates), it is **their** measurement quoted from their
comments and READMEs, not something reproduced here.

### 2.1 Engine and history

- Since **v1.08.0 (2026-04-01)** Android uses **Arti** ("Migrates C Tor to Arti Tor, hopefully no
  more random crashes"). Before that it embedded C Tor via `tor-android` + `jtorctl`. Desktop still
  uses `kmp-tor` (C Tor binaries), a different engine behind the same `ITorManager` interface.
  (An older note of ours said Android was on `tor-android`; that is stale.)
- It is **not** an off-the-shelf Arti library. It is a small custom **Rust cdylib** (`tools/arti-build`)
  that wraps the `arti-client` crate and exposes ~8 JNI functions. It replaced the Guardian Project
  `arti-mobile-ex` AAR **[claimed]** because that was ~140 MB, had no 16 KB page alignment, and its
  stop/restart was broken by a state-file lock.
- Release history worth knowing: v1.15 "stops fresh installs stranding on a Tor bootstrap"
  (watchdog fired once per `Connecting` span, then went silent; fixed by bootstrapping on demand),
  v1.16 "updates Arti to 2.6.0 for two medium-severity fixes" (TROVE-2026-24 and TROVE-2026-27,
  both reachable in normal use). Arti is security-relevant software: expect a version bump every
  few weeks.

### 2.2 Native build (`tools/arti-build/`)

| Item | Value |
|---|---|
| Rust crate | `cdylib`, `arti-client 0.46` with `default-features = false` and features `tokio`, `rustls`, `compression`, `onion-service-client`, `static-sqlite`; plus `tor-rtcompat` (`tokio`, `rustls`), `rustls 0.23` (`ring` provider), `jni 0.22`, `tokio`, `anyhow` |
| Why `ring` | since Arti 2.3.0 `tor-rtcompat` no longer installs a rustls crypto provider; the default `aws-lc-rs` is painful on Android, so the wrapper installs `ring` itself |
| Release profile | `opt-level="z"`, `lto=true`, `codegen-units=1`, `strip=true`, `panic="abort"` |
| Targets | `aarch64-linux-android`, `x86_64-linux-android`, `armv7-linux-androideabi`, `i686-linux-android` |
| Toolchain pins | `rust-toolchain.toml` (1.98.1), `ANDROID_NDK_VERSION` (r30, `30.0.16248370`), `CARGO_NDK_VERSION` (4.1.2), `ARTI_VERSION` (`arti-v2.6.0`), committed `Cargo.lock`, builds run `cargo --locked` |
| Reproducibility | byte-for-byte: pinned rustc + NDK + lockfile + `--remap-path-prefix` + **a canonical build path** (`/tmp/amethyst-arti-build`; path remapping alone is not enough because rustc's codegen ordering follows the real on-disk path) + `SOURCE_DATE_EPOCH` from the Arti tag + `CARGO_INCREMENTAL=0`. `verify-reproducible.sh` builds twice and diffs |
| Size | ~22 MB for all four ABIs, **4–6 MB in the APK a device installs** (arm64 ~5–6 MB) |
| 16 KB pages | yes on the 64-bit ABIs (rustc's Android target spec sets `max-page-size=16384`) |
| How it ships | **prebuilt `.so` committed** under `amethyst/src/main/jniLibs/<abi>/`; a `verifyNativeAbis` Gradle task in `preBuild` checks every APK ABI split has an ELF of the right `e_machine`; `keepDebugSymbols += "**/libarti_android.so"` so AGP's `llvm-strip` does not rewrite it and the APK copy is byte-identical to the committed one; `ndkVersion` in Gradle is read from the same pin file |
| Build time | not stated; a cold Arti build is dozens of crates × 4 targets. **[unverified]** |

### 2.3 JNI surface (`src/lib.rs`, ~680 lines)

`getVersion`, `setLogCallback(cb)`, `initialize(dataDir) → int`, `startSocksProxy(port) → int`,
`stopSocksProxy()`, `isBootstrapped() → 1/0/-1`, `bootstrapProgressPermille() → 0..1000/-1`,
`destroy()`. The JNI symbol names embed the Kotlin package
(`Java_com_vitorpamplona_amethyst_ui_tor_ArtiNative_*`), so the wrapper cannot be reused as-is.

Design decisions that matter and that we should keep:

- **One `TorClient` for the process lifetime**, created by `initialize`. `stopSocksProxy` only stops
  the TCP listener; `destroy` aborts the listener, the bootstrap task and every in-flight handler
  (each holds an `Arc<TorClient>`) and drops the client, which is what releases Arti's **state file
  lock** so a later `initialize` can take it again.
- **`BootstrapBehavior::OnDemand` + `create_unbootstrapped_async`**: the client exists in
  milliseconds, the SOCKS port binds in ~130 ms, the directory download (12.6–34.4 s cold, ~6–7 s
  warm, measured by them) runs in the background and each stream waits for its own circuit. Before
  this they used `create_bootstrapped`, which blocked the JNI call *and* the Kotlin lifecycle lock
  for the whole download and stranded fresh installs.
- **Readiness is polled, not pushed**: `isBootstrapped()` asks Arti live
  (`bootstrap_status().ready_for_traffic()`), because a latched "bootstrap failed" would report a
  dead Tor forever against a client that recovered on a later stream. Driving state off log strings
  had already caused a Connecting→Active race.
- **Progress is exposed** (`bootstrapProgressPermille`) so the supervisor can tell a slow download
  from a stalled one; a fixed timeout cannot (cold downloads varied 12.6–34.4 s on the same device).
- **SOCKS errors are mapped from Arti's `ErrorKind`** instead of always answering "connection
  refused": `RemoteHostNotFound`/`RemoteHostResolutionFailed`→0x04, `RemoteConnectionRefused`→0x05,
  `ExitPolicyRejected`→0x02, `RemoteNetworkFailed`→0x03, timeouts→0x06, else 0x01. On a cold start
  639 of ~768 relay failures had arrived as one opaque "refused" string.
- The wrapper implements SOCKS5 itself on a `tokio::net::TcpListener` and forwards with
  `tokio::io::copy` in a `select!`.
- `fs-mistrust` (Arti's strict file-permission check) is only relaxed on non-Android targets, so the
  same crate can run in JVM host tests.

### 2.4 Kotlin lifecycle

Files under `amethyst/src/main/java/.../ui/tor/`.

- `ArtiNative`: the JNI object (`System.loadLibrary("arti_android")` in `init`).
- `TorBackend`: interface extracted so the manager is unit-testable without JNI.
- `TorService` (522 lines): implements `TorBackend`.
  - One `Mutex` serializes every native lifecycle transition (`start/stop/reset/resetWithCleanState`)
    because `initialize`/`destroy` are blocking JNI calls that ignore coroutine cancellation. Without
    it a self-heal `reset()` could `destroy()` mid-`initialize()` and leave a SOCKS listener with no
    client behind it.
  - Status flow: `Off → Connecting (no port) → Bootstrapping(port) → Active(port)`. The port is
    reported as soon as the proxy is bound (`Bootstrapping` counts as routable) so dials queue behind
    the download instead of falling back to a dead port.
  - `bootstrapInFlight` flag, so a watchdog can tell "still working" from "gave up".
  - Preserves the consensus cache across starts (wiping it turned a ~7 s warm start into ~24 s).
  - SOCKS port: default 17392, up to 10 retries on `bind` failure.
- `ArtiGuardState`: parses `<filesDir>/arti/state/state/guards.json`.
  - **The real production bug**: a guard sample of 60 with 59 `disabled` and 1 usable, all sixty
    rejected at runtime (`AllGuardsDown`), across restarts; ~87 % of relay connections failed
    indefinitely. A naive "wipe when `usable == 0`" never fired. Fix: wedged when a sample of ≥ 10
    has fewer than 1/10 usable. `hasConfirmedGuard` (a non-null `confirmed_at`) is durable proof that
    Tor worked here before, used to decide between a gentle reset and a state wipe.
  - A second, runtime detector: ≥ 40 `AllGuardsDown` log lines inside 60 s → `guardsDownSignal`.
- `TorManager` (590 lines): the policy brain. Reactive on `torType` (OFF / INTERNAL / EXTERNAL) +
  external SOCKS port + session bypass + a `resetEpoch`.
  - Self-heal watchdog every 45 s while trying to connect: skipped while a bootstrap call is in
    flight; skipped while the directory download is still making progress (stall = no progress for
    60 s); cooldown 30 s before the first success, 5 min after; **gentle reset** (drop client, keep
    state) before Tor ever worked, **wipe state** afterwards or after 3 gentle resets.
  - Bypass prompt after 60 s of not working ("use regular connection" for an hour, remembered).
  - `onNetworkChange()` → reset; `onTorCircuitsDead()` → drop client to rotate exits.
- `TorCircuitHealthTracker` (in `commons`): detects "status Active but every circuit dead" from the
  relay layer: an unbroken run of ≥ N Tor-routed failures lasting ≥ a sustained window with zero
  successes, gated on connectivity. The sustained floor is essential: right after `Active` the pool
  dials everything at once and a burst can fail before the first handshake completes.

### 2.5 Routing policy (not needed for a first cut)

`TorSettings` has a mode (Off / Internal / External) and per-traffic-class switches (onion relays,
DM relays, new relays, trusted relays, URL previews, profile pics, images, videos, money operations,
NIP-05, media uploads), grouped into five presets (Only When Needed / Default / Small Payloads /
Full Privacy / Custom). `TorRelayEvaluation.useTor(relay)` decides per relay; `.onion` always needs
Tor; localhost and overlay-mesh relays never go through it; money-operation relays follow their own
switch. An `Onion-Location` header on any response makes later requests use the `.onion` twin.
This is the "cheaper middle ground" from our earlier notes and is independent of the engine.

### 2.6 Tests

Four layers: `TorManagerTest` (fake backend, virtual time), `ArtiGuardStateTest` (captured
`guards.json` fixtures), `TorArtiNativeIntegrationTest` (real JNI against a **host** build of the
same crate, opt-in with `-Pamethyst.arti.integration=true`; covers destroy→initialize releasing the
lock, in-flight handlers not pinning it, stop/start reusing the client, many destroy/init cycles),
an instrumented test on a device, and `tools/tor-network-tests` (a device-driven suite that toggles
airplane mode / Wi-Fi ↔ cellular / app lifecycle and asserts on real bootstrap, run by hand as a
release gate for any Tor change).

### 2.7 Weak points in their wrapper (our improvement list) — my reading

1. The SOCKS handshake does **one** `read` into a 512-byte buffer and assumes the whole request
   arrived; a fragmented request (legal TCP) is rejected as "invalid". We should `read_exact` by
   the lengths in the protocol.
2. The listener is **unauthenticated** on `127.0.0.1:<fixed port>`: any local app can use the user's
   Tor client. Low severity (an app can already open Tor via Orbot's 9050 today) but avoidable with
   an ephemeral port and, later, RFC 1929 username/password.
3. **No stream isolation.** Arti isolates streams by default only per its own rules; SOCKS
   credentials are the standard way to ask for separate circuits (e.g. isolate media from relays).
   `StreamPrefs::set_isolation` / `TorClient::isolated_client()` exist in `arti-client 0.46`.
4. **`panic = "abort"` inside the app process**: a Rust panic takes the whole app down, where Orbot
   crashes in its own process. Worth a decision (see §5.3, "process model").
5. Unbounded handler tasks and no handshake timeout; default tokio worker count (= CPU cores).
6. **No dormant mode.** `TorClient::set_dormant(DormantMode::Soft)` suspends background tasks to save
   CPU/battery and wakes on first use; nothing in Amethyst calls it (checked in `arti-client` source).
7. The "all guards down" runtime signal is a **substring match on Arti's error text**. Arti already
   classifies it: `PickGuardError::AllGuardsDown | AllFallbacksDown` map to `ErrorKind::TorAccessFailed`
   (`tor-guardmgr/src/err.rs`), so counting consecutive `TorAccessFailed` connect errors is sturdier.

---

## 3. Options for the engine

| Option | Size (arm64) | Build burden | Maintenance | Verdict |
|---|---|---|---|---|
| **A. Arti + our own thin JNI wrapper** (Amethyst's approach) | ~5–6 MB **[claimed]** | Rust + cargo-ndk + NDK, pinned; prebuilt `.so` committed | Arti bumps (security), toolchain bumps, re-verify | **Recommended** |
| B. Guardian Project `arti-mobile-ex` AAR | ~140 MB across ABIs **[claimed]**; arm64 share not measured | none (Maven dependency) | depends on their release cadence; stop/restart broken by lock **[claimed]**; 16 KB alignment absent **[claimed]** | Possible as a throw-away spike only |
| C. C Tor via `tor-android` + `jtorctl` | **[unverified]** | none (AAR) | Amethyst left it citing crashes (v1.08) | Not recommended |
| D. `kmp-tor` runtime + Tor resources | **[unverified]** for Android | Gradle dependency | Used by Amethyst desktop | Not recommended for Android |
| E. Stay Orbot-only | 0 | none | none | Keep as the "External" mode regardless |

Recommendation: **A**, keeping Orbot as a mode, exactly the three-way model Amethyst has
(Off / Built-in / External). Licences are compatible: Arti is `MIT OR Apache-2.0`; Amethyst is MIT.
We write our own wrapper (different JNI package anyway) and credit Amethyst in a NOTICE for the
design lessons; if any file is copied verbatim its MIT header must be kept.

---

## 4. What we need, concretely

### 4.1 Toolchain and build infrastructure

| Need | State on this dev machine | Notes |
|---|---|---|
| `rustc`/`cargo` ≥ 1.91 (Arti MSRV) | `/usr/bin` **1.95.0** (distro package) | enough for a **host** build |
| `rustup` + the Android target's `rust-std` | **not installed** | the distro rustc has no `aarch64-linux-android` std; needs `rustup target add aarch64-linux-android` (installs under `~/.rustup`) |
| `cargo-ndk` | **not installed** | `cargo install cargo-ndk --locked` (installs under `~/.cargo`) |
| Android NDK | **not checked** (lives under the SDK, outside the repo) | pin one revision in a file read by both the build script and `app/build.gradle.kts` (`ndkVersion`); AGP's default NDK would otherwise drift with every AGP bump |
| Network to crates.io and `gitlab.torproject.org` | available | ~400 crates to fetch on first build |

Installing anything in the user's home is deliberately **not** done without an explicit yes; the
host-side work below uses a scratch `CARGO_HOME`/`CARGO_TARGET_DIR` outside `~`.

Decisions:

1. **Single ABI, `arm64-v8a`.** Matches the app's splits. Emulator (x86_64) runs would get "built-in
   Tor unavailable in this build" instead of a crash (the engine must survive `UnsatisfiedLinkError`).
2. **Commit the prebuilt `.so`** under `app/src/main/jniLibs/arm64-v8a/` (like Amethyst), built by a
   script with pinned inputs, plus a script that rebuilds and compares hashes. Rationale: releases
   are cut by hand from a developer machine and CI has no Rust step today; a Gradle-integrated Rust
   build would slow every clean build and make the project depend on a toolchain most contributors
   do not have. Cost: ~5–6 MB added to git per rebuild, so rebuild only on Arti/wrapper bumps.
3. **Pin the inputs**: `ARTI_VERSION`, `ANDROID_NDK_VERSION`, `CARGO_NDK_VERSION`, `Cargo.lock`,
   `rust-toolchain.toml`, canonical build path. Same reasons as Amethyst.
4. `keepDebugSymbols += "**/libnostr_arti.so"` in `app/build.gradle.kts` so the APK copy is
   byte-identical to the committed one, and a Gradle check (`verifyNativeLibs`) that fails the build
   if the committed library is missing or is not an arm64 ELF.
5. **R8/ProGuard**: keep the JNI class and its `native` methods (and any callback interface). Missing
   rules fail only at runtime, on release builds.

### 4.2 The native crate (`tools/arti-build/`)

Keep Amethyst's decisions from §2.3 and fix §2.7:

- our own JNI package `net.primal.core.networking.tor.engine.ArtiNative`, library `libnostr_arti.so`;
- one `TorClient`, `OnDemand` bootstrap, live readiness and progress accessors, `destroy` that really
  releases the lock;
- SOCKS5 with exact reads, CONNECT only, handshake timeout, bounded concurrency, `ErrorKind`→reply
  mapping, `.onion` supported (`onion-service-client`);
- **ephemeral port** (`startSocksProxy(0)` returns the bound port);
- a bounded in-Rust log queue drained by Kotlin (`pollLog`) instead of calling back into the JVM from
  arbitrary threads (removes the JavaVM attach/global-ref machinery and its failure modes);
- counters for connect outcomes so Kotlin can detect "Active but all circuits dead" and
  `TorAccessFailed` streaks without parsing text;
- `setDormant(soft)` for backgrounding; worker threads capped at 2;
- optional later: SOCKS credentials → `isolated_client()` per isolation group.

### 4.3 Kotlin engine (`core/networking-http`, `androidMain`, package `...tor.engine`)

- `ArtiBridge` (interface) with `ArtiNative` as the JNI implementation and a fake in tests.
- `TorEngine` + `TorEngineState`:
  `Unavailable` (library missing) · `Off` · `Starting(progressPermille)` · `Ready(port)` · `Failed(reason)`.
  The port is published when the listener is bound (dials queue behind the download), and `Ready`
  when Arti reports traffic-ready — the same two facts Amethyst had to separate.
- `ArtiTorEngine`: single lifecycle `Mutex`; all JNI on `Dispatchers.IO`; data dir
  `<filesDir>/arti`; never wipes the cache on start.
- `ArtiGuardState` (JSON parse of `guards.json`, ratio rule) and `TorSupervisor` (watchdog with
  progress-aware stall detection, gentle-then-wipe escalation, cooldowns, network-change reset),
  both pure and unit-tested with virtual time.
- `TorProxySettings` gains an `engine` (`ORBOT` / `BUILT_IN`) next to the existing `enabled`; the
  persisted JSON from today (`enabled`, `socksPort`) keeps decoding as Orbot. (Built as an engine
  choice under one on/off switch rather than the three-way mode first sketched: it changes nothing
  for existing installs and keeps the toggle's meaning.)
- `applyTorProxyIfEnabled` becomes mode-aware and resolves the port through a `ProxySelector` that
  reads the engine state **at connect time**, so a restarted engine (new ephemeral port) does not
  require rebuilding every client. It must stay fail-closed: while the engine is not `Ready` the
  selector still returns the loopback SOCKS address, so calls wait or fail, never go direct.

### 4.4 Integration points and the leak surface

Everything that talks to the network must go through the proxy, and the list is long; each is a
place a "Tor is on" claim can silently be false:

| Traffic | Where | Note |
|---|---|---|
| Relay WebSockets, NIP-05, Blossom, generic Ktor | `ClientEngine.android.kt` | already goes through `applyTorProxyIfEnabled` |
| Hilt `OkHttpClient` | `NetworkingModule` | same |
| Images | `PrimalImageLoaderFactory` (Coil) | same |
| Video | `MediaOkHttpClientProvider`, `NoteAttachmentVideoPreview` | ExoPlayer has its own data source |
| Downloads, frame extraction | `MediaDownloader`, `VideoFrameExtractor` | same |
| **WebView** | `WebViewProxyConfigurer` | uses the port at configuration time; must be re-applied when the engine's port changes and must not load before `Ready` |
| Link previews, crash reporter, language packs | raw OkHttp sites | audit needed; the store's KDoc says "standalone raw-OkHttp sites" exist |
| DNS | — | with `Proxy.Type.SOCKS` OkHttp passes the hostname to the proxy (no local resolution); verify on device with a packet capture, do not assume |

Behavioural requirements:

- **Turning Tor on must drop every already-open direct socket** (relay pool, pooled HTTP
  connections), otherwise the app keeps talking directly until each connection happens to reconnect.
  Today the answer is "restart the app"; keep that for the first version and design the reconnect
  path later.
- The engine must start **early** (Application, before the first client is built) when mode is
  `BUILT_IN`, and the first dial must not race it.
- Fail-closed at cold start: relays will time out for 10–30 s on a fresh install while the directory
  downloads. The UI needs a "Tor is starting" state instead of "relays offline".
- First login is much slower over Tor (Amethyst measured feed on screen at login+18 s vs login+11 s,
  and half the relays open at 20 s, on a fresh install; their planned fix is not to Tor-route the
  bootstrap relays until the user's own relay list is known). Relevant to our "bootstrap relays".

### 4.5 UI and settings

- Three-way mode selector in `settings/tor/` (Off / Built-in / Orbot), a status line
  (`Starting 42 %`, `Connected`, `Not available in this build`), the port field only for Orbot.
- Onboarding screen: offer built-in Tor first; keep the Orbot path.
- No per-traffic-class policy in the first cut (see §2.5, independent work).
- Strings in `values/` and `values-it/` at minimum.

### 4.6 Size, battery, data

- Size: arm64-only, so roughly **+5–6 MB on a ~57 MB APK (~10 %)** **[claimed figure, single ABI
  extrapolated]**.
- Disk: Arti's directory cache is persisted; keep it across starts. Size on disk not measured here.
- CPU/battery: cap tokio workers, use dormant mode when backgrounded, and do not run a foreground
  service just for Tor.
- Data: a cold directory download is several MB; a warm start is small. Not measured here.

### 4.7 Security review checklist

- Loopback listener only, ephemeral port, no wildcard bind; consider credentials.
- No direct fallback anywhere, including "Tor failed, retry without" logic.
- `Cargo.lock` committed, `--locked`; review `cargo tree` for surprising crates; consider `cargo
  audit`/`cargo vet` in the update procedure; track Arti advisories (`TROVE-*`).
- Reproducible build script and hash check, so the committed `.so` can be audited.
- State directory permissions: `filesDir` is app-private; `fs-mistrust` stays strict on Android.
- Crash isolation: see the process-model question below.

### 4.8 Test strategy

1. Pure Kotlin unit tests with a fake `ArtiBridge` and virtual time: state machine, supervisor,
   guard-state heuristic against fixtures, settings migration, `ProxySelector` fail-closed behaviour.
2. **Host integration test** (opt-in Gradle property): build the same crate for the Linux host and run
   real JNI: bootstrap, a SOCKS round trip, `.onion`, destroy→initialize cycles, many restarts, and a
   fragmented SOCKS handshake. This is where the lock/lifecycle bugs were found upstream.
3. Instrumented / manual on the Pixel: cold and warm bootstrap timings, airplane-mode and
   Wi-Fi↔cellular transitions, DNS capture, WebView and video paths, battery over a few hours.

---

## 5. Open questions and risks

1. **Process model.** In-process (Amethyst's choice, simplest) vs a separate `:tor` Android process.
   A Rust panic with `panic = "abort"` kills the whole app in-process; a separate process isolates
   crashes and memory, and killing it is the cleanest way to release Arti's state lock — at the cost
   of a second `Application` init to guard against and a Binder/Messenger for status. Plan: start
   in-process behind the `TorEngine` interface, decide after measuring memory and crash behaviour.
2. **Permission to install the Android toolchain** (`rustup`, the target, `cargo-ndk`, the pinned NDK)
   in the user's home. Until then the Android `.so` cannot be produced or tested; only the host build
   can.
3. **Which NDK revision** to pin (the SDK on this machine was not inspected).
4. **Committed binary vs CI build.** Recommended above; revisit if a store that forbids binaries is
   ever targeted.
5. **Maintenance load.** Arti security releases, Rust and NDK bumps, re-verifying reproducibility.
   The earlier reasons for deferring this (see the Bergamot precedent) still apply; the difference
   now is that Amethyst has paid most of the discovery cost and their result is small and stable.
6. **Guard-state bug class.** Any embedded Tor needs the wedge detector and a wipe path before it is
   trusted in the field; do not ship without them.
7. **UX honesty.** "Tor" must mean everything, or the UI must say what is not covered.

---

## 6. Plan

1. **Native crate** (`tools/arti-build/`): wrapper + pins + build/verify scripts; builds and passes a
   host round-trip test.
2. **Kotlin engine** + settings model + proxy selector, unit tested with fakes.
3. **Gradle wiring**: `jniLibs`, `keepDebugSymbols`, `verifyNativeLibs`, R8 rules, `ndkVersion` pin.
4. **Android build** (needs §5.2): produce and commit the arm64 `.so`, verify 16 KB alignment and
   reproducibility.
5. **UI**: mode selector, status, onboarding.
6. **On-device validation** (§4.8.3), then a field trial before any release; only then decide about the
   traffic-class policy layer and dormant/battery tuning.

---

## 7. Status on this branch

Branch `feature/built-in-tor`. Everything below is committed; `git log feature/built-in-tor` has the
detail. **The Android library has not been built**, so a build of this branch reports built-in Tor as
"not part of this build" and behaves exactly like `main` otherwise.

### 7.1 Done and how it was verified

| Piece | Verification |
|---|---|
| `tools/arti-build`: the Rust wrapper (`src/lib.rs`), `Cargo.lock`, toolchain/NDK/Arti pins | `cargo test`: 9 protocol/mapping tests pass. 4 engine tests against the real Arti client pass on this machine (`--ignored`): `destroy()` releases the state-file lock over 4 cycles on one directory; stop/restart keeps the client; a SOCKS greeting sent one byte at a time is accepted; a TLS request through the tunnel to check.torproject.org returns `{"IsTor":true}` |
| Kotlin engine (`core/networking-http`, `...tor.engine`): `ArtiBridge`, `ArtiTorEngine`, `ArtiGuardState`, `TorSupervisor` | 34 unit tests with a fake bridge and virtual time, plus an opt-in JVM test (`-Pnostr.arti.hostLib=<dir>`) that loads the real library through JNI: the Kotlin `external` declarations match the exported symbols, a request through OkHttp exits via Tor, a gentle restart comes back and works again, stop returns to Off |
| `guards.json` heuristic | tested against a **real** file captured from Arti 2.6.0, and against synthetic wedged samples including the 59-of-60-disabled field failure |
| Settings model, `TorPortProxySelector`, WebView proxy, app wiring, settings UI | unit tests for JSON backward compatibility (a file written before the option existed decodes as Orbot; an unknown engine name falls back to Orbot), for the selector's fail-closed behaviour (never `NO_PROXY`), and for which proxy each engine installs. UI compiled but **not seen on a device** |
| Gradle: `keepDebugSymbols`, R8 keep rules, `verifyBuiltInTorLibrary` | R8 run on `altRelease`: the JNI class and all its native methods appear in `seeds.txt`. The task was exercised against a missing file (warns; fails with `-Plibrenostr.requireBuiltInTor=true`), an x86_64 library (fails, `e_machine=62`) and a 10-byte file (fails) |
| App checks | `:app:testDebugUnitTest` 402 pass; `:core:networking-http` 53 tests, 1 skipped (the opt-in one); app detekt at its baseline of 93 findings; the module's detekt is clean |

### 7.2 Numbers measured here (x86_64 Linux host, distro rustc 1.95.0)

- Release library: **6.25 MB** for x86_64 (`opt-level = "z"`, LTO, stripped). The arm64 figure is not
  measured; Amethyst reports 5–6 MB for its equivalent, which the x86_64 number is consistent with.
- Release build time: about **1 minute** from a warm dependency cache, 7.5 minutes of CPU time.
- **Cold bootstrap to "ready for traffic": 12.5 to 27 s** across five runs, with monotonic progress
  (0 → 1000 permille) that the supervisor can watch.
- Arti persists the guard sample lazily: `state/state/guards.json` exists after the first stream but
  holds an empty sample; a **confirmed guard appeared on disk about 31 s after** the first successful
  stream (measured once). So "Tor has worked on this install" is not known for the first half minute,
  and a process killed earlier loses it; the supervisor degrades to the first-start behaviour then,
  which is the safe direction.
- Real-Tor test of the whole Kotlin → JNI → Rust chain: 15–28 s including a restart.

### 7.3 Not done, and what each needs

1. **Android build of the library.** Needs `rustup`, the `aarch64-linux-android` target, `cargo-ndk`
   and the pinned NDK (§4.1). Installing them writes to the user's home directory, so it waits for an
   explicit yes. `build-arti.sh` and `verify-reproducible.sh` are written but have **never been run**.
   The NDK (`30.0.16248370`) and cargo-ndk (`4.1.2`) pins were copied from Amethyst's verified build
   and are unconfirmed here.
2. **Committing the library**, `-Plibrenostr.requireBuiltInTor=true` in the release workflow, and
   the reproducibility check. Depends on 1.
3. **Device validation** (§4.8.3): cold/warm bootstrap on the Pixel, airplane mode and Wi-Fi↔cellular,
   a packet capture to confirm there is no DNS leak, WebView and ExoPlayer paths, battery over hours.
4. **Onboarding screen** (`OrbotOnboardingScreen`) still talks only about Orbot.
5. **Turning Tor on still needs an app restart**, as today. Already-open direct sockets are not
   dropped by the toggle; designing that is separate work.
6. **Leak audit of the raw-OkHttp call sites** the store's KDoc mentions (crash reporter, language
   packs): they use `applyTorProxyIfEnabled`, so they follow the engine, but nobody has checked that
   list is complete.
7. **Process model** (§5.1) is undecided; the engine sits behind the `TorEngine` interface so it can
   move to a `:tor` process later.
8. **Italian strings**: the Italian locale has no Tor strings at all (the whole screen falls back to
   English), so the new ones are English only.

### 7.4 How to run what exists

```bash
# Rust: protocol tests, then the engine tests against the real Tor network (needs outbound access)
CARGO_HOME=/scratch/cargo-home CARGO_TARGET_DIR=/scratch/target tools/arti-build/run-host-tests.sh

# Kotlin: everything, including the tests that need no library
./gradlew :core:networking-http:testAndroidHostTest

# Kotlin -> JNI -> Rust -> Tor, on the host (build the library first: `cargo build --release --locked`)
./gradlew :core:networking-http:testAndroidHostTest \
    --tests "*ArtiNativeHostIntegrationTest*" -Pnostr.arti.hostLib=/scratch/target/release
```
