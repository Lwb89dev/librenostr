# LibreNostr

<p align="center">
  <img src="assets/icons/icon.png" alt="LibreNostr" width="128" height="128">
</p>

<p align="center">
  <strong>A relay-first Nostr client for Android.</strong><br>
  Talks to ordinary relays. Stores data on the device. Does not need Primal’s cache servers.
</p>

<p align="center">
  <a href="https://github.com/Lwb89dev/librenostr/blob/main/LICENSE"><img src="https://img.shields.io/badge/license-MIT-6430D5?style=for-the-badge" alt="MIT License"></a>
  <a href="https://github.com/Lwb89dev/librenostr"><img src="https://img.shields.io/badge/status-early%20fork-222?style=for-the-badge" alt="Early fork"></a>
</p>

LibreNostr is a fork of the open-source [Primal Android app](https://github.com/PrimalHQ/primal-android-app). We kept the UI and the Nostr pieces that already work, and we are cutting the product free from Primal-specific infrastructure.

This is **not** a re-skinned Primal. The point of the fork is architectural:

```text
client  ↔  Nostr relays  +  local Room cache
```

not:

```text
client  ↔  primal.net cache  ↔  Nostr
```

Today the imported tree still speaks to Primal’s cache for most reads. That is documented, not hidden. Each migrated path is switched to relays, then the old caller is deleted. See [`docs/LIBRENOSTR_ROADMAP.md`](docs/LIBRENOSTR_ROADMAP.md).

## What stays, what goes

**Keep (and reuse)**

- Compose UI and navigation patterns
- Local Room / DataStore cache (on the device, not a remote aggregator)
- Local `nsec` identity
- Existing Nostr models, secp256k1, Quartz NIP-04/NIP-44
- Direct relay `EVENT` publish (`RelayPool`)
- NWC and LNURL where they do not depend on Primal

**Remove (incrementally)**

- Primal cache WebSocket/HTTP as the source of feeds, profiles, threads, search
- Trending / discovery that only exists because of that cache
- Primal Premium / membership / promo
- External signer product (NIP-46 / NIP-55) after local keys still publish
- Primal trademarks, logos, and store copy — MIT copyright stays

## Current status

Imported from Primal **3.5.25** (`efb88b5af`). Phase 0 baseline compiles.

| Check | Result |
|---|---|
| `./gradlew :app:assembleAospDebug` | success |
| `./gradlew :app:testAospDebugUnitTest` | 344 tests, 0 fail |
| `./gradlew allTests` | 985 XML tests, 0 fail |

The app name and launcher icon are LibreNostr. Package id is still `net.primal.android` until networking is stable — renaming it in the same breath as the data-layer work would make diffs unreadable.

Honest caveat: **blocking `primal.net` will still break most reads** until the relay migration (LN-001…) lands. Publish already goes to relays.

## Docs

| File | What it is |
|---|---|
| [`docs/UPSTREAM.md`](docs/UPSTREAM.md) | Origin, remotes, MIT obligations |
| [`docs/BASELINE.md`](docs/BASELINE.md) | Toolchain and the unmodified build |
| [`docs/ARCHITECTURE_UPSTREAM.md`](docs/ARCHITECTURE_UPSTREAM.md) | KEEP / REFACTOR / REPLACE / REMOVE |
| [`docs/PRIMAL_SERVER_DEPENDENCIES.md`](docs/PRIMAL_SERVER_DEPENDENCIES.md) | 84 cache/wallet verbs |
| [`docs/LIBRENOSTR_ROADMAP.md`](docs/LIBRENOSTR_ROADMAP.md) | Phased strangler plan |
| [`docs/LIBRENOSTR_BACKLOG.md`](docs/LIBRENOSTR_BACKLOG.md) | Atomic tasks (`LN-00x`) |

## Building

**Requires:** JDK 21, Android SDK (compileSdk 37, minSdk 26), Android Studio current enough for AGP 9.2.

Flavors: `aosp` (F-Droid / Zapstore) and `google` (Play). Use **aospDebug** unless you have Play secrets.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # or your JDK 21
export ANDROID_HOME="$HOME/Android/Sdk"

# sdk.dir is written to gitignored local.properties
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :app:assembleAospDebug
./gradlew :app:installAospDebug
```

Debug builds **do not encrypt** stored keys (`NoEncryption`). Release builds use AES + SQLCipher. Do not daily-drive a debug APK with a real `nsec`.

### Release

Create `config.properties` in the repo root (gitignored):

```properties
localStorage.keyAlias={KeystoreAliasForEncryption}
```

Optional signing:

```properties
{signingConfigName}.storeFile={PathToYourCertificate}
{signingConfigName}.storePassword={CertificatePassword}
{signingConfigName}.keyAlias={YourAlias}
{signingConfigName}.keyPassword={AliasPassword}
```

`{signingConfigName}` is `playStore` or `alternative`. Then `./gradlew :app:installAospAltRelease`.

## Git remotes

```text
origin    https://github.com/Lwb89dev/librenostr.git
upstream  https://github.com/PrimalHQ/primal-android-app.git   (fetch only)
```

Do not push to `upstream`.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Open issues on this repository, not on Primal’s.

## License

MIT. Upstream copyright: Copyright (c) 2023 PRIMAL SYSTEMS INC. See [LICENSE](LICENSE). LibreNostr does not claim authorship of unmodified upstream code and does not use the Primal trademark.

## Acknowledgments

- [PrimalHQ/primal-android-app](https://github.com/PrimalHQ/primal-android-app) — the client this fork starts from
- [Quartz](https://github.com/vitorpamplona/quartz) — NIP-04 / NIP-44
- [Acinq](https://acinq.co) — secp256k1
- [Breez SDK](https://breez.technology) — optional Lightning (still under audit for LibreNostr)
