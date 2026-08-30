# LibreNostr

<p align="center">
  <img src="assets/icons/icon.png" alt="LibreNostr" width="128" height="128">
</p>

<p align="center">
  <strong>A relay-first Nostr client for Android.</strong><br>
  Connects to Nostr relays directly and keeps local state on the device, without proprietary infrastructure.
</p>

<p align="center">
  <a href="https://github.com/Lwb89dev/librenostr/blob/main/LICENSE"><img src="https://img.shields.io/badge/license-MIT-6430D5?style=for-the-badge" alt="MIT License"></a>
  <a href="https://github.com/Lwb89dev/librenostr"><img src="https://img.shields.io/badge/status-active%20development-222?style=for-the-badge" alt="Active development"></a>
</p>

LibreNostr is a fork of an open-source Android client ([upstream repository](https://github.com/PrimalHQ/primal-android-app)). The project keeps the mature Android/Compose foundation while replacing the remote cache dependency with ordinary Nostr relay queries wherever the relay path is ready.

The target architecture is:

```text
client  <->  Nostr relays  +  local Room/DataStore
```

The imported codebase still contains upstream namespaces and compatibility services. Those are being removed incrementally, path by path, instead of hiding the remaining dependencies behind a rebrand.

## Current state

**100% of active Android data paths are relay-native.** Feeds, profiles, threads,
notifications, direct messages, search, bookmarks, mute lists, articles and
zaps are all read and written directly against Nostr relays; no Primal cache
or aggregation server is required for any of them. Full detail and
verification evidence: [`docs/RELAY_ONLY_MIGRATION_STATUS.md`](docs/RELAY_ONLY_MIGRATION_STATUS.md)
and [`docs/SECURITY_AUDIT.md`](docs/SECURITY_AUDIT.md).

- direct relay read/write through `RelayPool`, including EOSE handling, timeouts, deduplication and clean subscription shutdown;
- relay-first feeds (**Latest**, **Latest with replies**), profiles, follow lists, threads, notifications, direct messages (NIP-04), bookmarks, mute lists and live-stream lookup;
- relay-first Reads: article feed, article details/comments (NIP-22/NIP-23) and highlights (NIP-84);
- relay-first Explore: profile search, popular people, trending topics/zaps and note reaction/repost action lists;
- relay-first polls (kind 1068/6969/1018) and zap receipts (NIP-57, kind 9735), including invoice-to-zap enrichment;
- local-key note publishing to write relays, with the published event persisted locally;
- NWC (NIP-47) as the only in-app wallet transport; legacy centralized/embedded wallet, premium and FCM push services are removed or fail closed;
- Android Keystore AES-GCM local credential storage, disabled Android backup, HTTPS-only/cleartext-disabled networking;
- LibreNostr-branded Compose UI with home, notifications, profile, algorithm and settings navigation;
- profile sharing through `nostrich.org/p/<npub>` and Android deep-link handling;
- inline search: profile results appear below the search field and note/read results render in the same screen, without an intermediate results page;
- persistent recent searches and recent profiles, capped at five entries per group and shown with profile images;
- note composer attachments, camera/gallery selection, polls and a GIF picker backed by Wikimedia Commons previews.

The app name and launcher icon are LibreNostr. The Android application ID is
`com.librenostr.android`; the internal Kotlin/Java package namespace is still
`net.primal.android` and is renamed separately from the networking migration.

## Remaining boundaries

LibreNostr is not yet a complete removal of every upstream-specific component. In particular:

- wallet/premium/external-signer UI and compatibility modules remain in the imported tree; their centralized transports are fail-closed but the modules themselves (including the optional Breez/Spark native library) are still compiled in and scheduled for removal;
- some upstream class, package and module names (e.g. `networking-primal`, `domain-primal`) still reflect the original codebase;
- historical test fixtures and event text may still reference the upstream domain; these are not live endpoints.

Remaining paths and phased removal are tracked in [`docs/LIBRENOSTR_ROADMAP.md`](docs/LIBRENOSTR_ROADMAP.md).

## Project layout

| Directory | Role |
|---|---|
| `app/` | Android application, Compose screens, navigation and feature wiring |
| `core/` | Networking, caching, media, cryptography and shared UI primitives |
| `data/` | Local databases, remote APIs and repository implementations |
| `domain/` | Platform-independent Nostr, feed, profile and wallet models |
| `docs/` | Architecture notes, dependency inventory and migration roadmap |

## Building

Requires JDK 21 and an Android SDK with compile SDK 37. The project uses AGP 9.2.1, Kotlin 2.4.0 and min SDK 26.

Flavors are `aosp` (F-Droid/Zapstore-oriented) and `google` (Play services). Use `aospDebug` for local development unless Play secrets are configured.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # or another JDK 21 path
export ANDROID_HOME="$HOME/Android/Sdk"

# sdk.dir is written to gitignored local.properties
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :app:compileAospDebugKotlin
./gradlew :app:assembleAospDebug
./gradlew :app:installAospDebug
```

The AOSP debug APK has been compiled, installed and exercised through ADB during the current development cycle. Debug builds do not encrypt stored keys (`NoEncryption`); do not use a debug APK with a valuable real `nsec`.

### Release builds

Create a gitignored `config.properties` in the repository root:

```properties
localStorage.keyAlias={KeystoreAliasForEncryption}
```

Signing properties are optional and use the `playStore` or `alternative` signing block. Then run the appropriate release task, for example:

```bash
./gradlew :app:installAospAltRelease
```

## Releases

Signed release APKs (`aospAltRelease`, split per ABI: `armeabi-v7a`,
`arm64-v8a`, `x86_64`) are published on the
[GitHub Releases](https://github.com/Lwb89dev/librenostr/releases) page,
starting with `v0.1.0`. Verify the APK signature before installing:

```bash
apksigner verify --print-certs app-aosp-altRelease-<abi>.apk
```

## Documentation

| File | Description |
|---|---|
| [`docs/UPSTREAM.md`](docs/UPSTREAM.md) | Origin, remotes and MIT obligations |
| [`docs/BASELINE.md`](docs/BASELINE.md) | Toolchain and imported baseline |
| [`docs/ARCHITECTURE_UPSTREAM.md`](docs/ARCHITECTURE_UPSTREAM.md) | KEEP / REFACTOR / REPLACE / REMOVE map |
| [`docs/PRIMAL_SERVER_DEPENDENCIES.md`](docs/PRIMAL_SERVER_DEPENDENCIES.md) | Inventory of remaining server verbs |
| [`docs/LIBRENOSTR_ROADMAP.md`](docs/LIBRENOSTR_ROADMAP.md) | Migration phases and stop conditions |
| [`docs/LIBRENOSTR_BACKLOG.md`](docs/LIBRENOSTR_BACKLOG.md) | Atomic implementation tasks |
| [`docs/RELAY_ONLY_MIGRATION_STATUS.md`](docs/RELAY_ONLY_MIGRATION_STATUS.md) | Relay-only migration status per data path |
| [`docs/SECURITY_AUDIT.md`](docs/SECURITY_AUDIT.md) | Security and relay-only audit results and verification evidence |

## Git remotes

```text
origin    https://github.com/Lwb89dev/librenostr.git
upstream  https://github.com/PrimalHQ/primal-android-app.git   (fetch only)
```

Do not push to `upstream`.

## Contributing

Open issues and pull requests on this repository. Discuss non-trivial changes
first, and check [`docs/LIBRENOSTR_ROADMAP.md`](docs/LIBRENOSTR_ROADMAP.md)
and [`docs/LIBRENOSTR_BACKLOG.md`](docs/LIBRENOSTR_BACKLOG.md) for the current
migration phase before starting work on the networking layer.

## License

MIT. Upstream copyright: Copyright (c) 2023 PRIMAL SYSTEMS INC. See [LICENSE](LICENSE). LibreNostr does not claim authorship of unmodified upstream code and uses LibreNostr as its product identity.

## Acknowledgments

- [Upstream Android client](https://github.com/PrimalHQ/primal-android-app) — the client this fork starts from
- [Quartz](https://github.com/vitorpamplona/quartz) — NIP-04 / NIP-44
- [Acinq](https://acinq.co) — secp256k1 and Lightning foundations
- [Breez SDK](https://breez.technology) — inherited optional Lightning integration under audit
