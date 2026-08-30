# LibreNostr security and relay-only audit

Audit date: 2026-08-30. Scope: Android application, shared networking/data
modules, build configuration, manifest and local secret handling.

## Results

- Production reads and subscriptions use `RelaysSocketManager`/`RelayPool`.
  Relay configuration is deduplicated, URL-validated and capped at 30 relays.
  Queries are capped at 500 events, normalize the filter `limit` to 1..500,
  and clamp query timeouts to 500 ms..8 s. Publish and subscription operations
  have bounded 10 s and 8 s timeouts respectively; subscriptions are closed in
  `finally`/`awaitClose` paths, capped at 64 active subscriptions, and receive
  normalized event limits.
- The Android dependency graph no longer creates a Primal cache or wallet
  socket. `SocketModule` injects `RelayOnlyApiClient`, which never opens a
  connection and throws a clear `NetworkException` if an obsolete fallback is
  accidentally reached. The legacy shared `PrimalApiClientFactory` and
  `PrimalHttpApiClientFactory` are also fail-closed, so a future caller cannot
  silently recreate the centralized transport. The follow-pack compatibility
  API and its paging mediator were removed.
- Legacy Primal-wallet operations are now fail-closed at the service boundary:
  `Wallet.Primal` has no payment, balance or invoice implementation, and the
  old centralized NWC-provisioning repository returns no connections. NWC
  (NIP-47) remains the only in-app wallet transport. Zaps and invoice links
  use the Lightning protocol and the Android `lightning:` intent for an
  external wallet when NWC is not selected. LNURL and BOLT11 parsing happen
  locally or against the provider endpoint; they do not call a wallet server.
- FCM registration and the Firebase message service were removed. Push token
  methods remain as no-op domain compatibility methods, so account identifiers,
  signed authorization events and device tokens are not sent to a central
  service.
- Import/broadcast compatibility methods are local-only or fail closed; the
  production graph no longer constructs the centralized HTTP import client.
- Local credentials use Android Keystore AES-GCM (versioned format v2 with a
  128-bit authentication tag). Legacy CBC files can be read only for migration;
  there is no plaintext fallback and authentication/format failures throw.
- Android backup is disabled (`allowBackup=false`); backup/data-extraction rules
  exclude all app data. Cleartext traffic is disabled. Only the launcher and
  explicit `nostrich.org` HTTPS deep links are exported; the old `primal://`
  deep link was removed.
- Sensitive clipboard copies are marked sensitive on Android 13+ and cleared
  after 60 seconds only when the copied value is still present. A source scan
  found no logging of nsec/private-key values; only generic failure messages and
  public identifiers are logged.
- Preview data no longer contains real-looking nsec/private-key material. The
  remaining wallet preview key is generated as an all-zero deterministic value
  and cannot represent a user credential.
- Upload transport now tries the Blossom `/upload` endpoint first and the legacy
  `/media` route second, with cancellation-safe bounded error handling. Upload
  destinations are HTTPS-only and capped at six configured servers.
- Relay settings accept only validated `wss://` hosts; cleartext `ws://` input is
  rejected both by the UI and relay pool.
- Log review removed full NIP-46 response payloads, NIP-05 identifiers and raw
  malformed event JSON from logcat; cancellation is propagated through socket
  acquisition and NIP-05 verification instead of being converted to failures.

## Verification evidence

- `./gradlew :app:compileAospDebugKotlin --no-daemon` — **BUILD SUCCESSFUL**.
- `./gradlew :app:compileGoogleDebugKotlin --no-daemon` — **BUILD SUCCESSFUL**.
- `./gradlew :app:assembleAospDebug --no-daemon` — **BUILD SUCCESSFUL**.
- APK SHA-256: `752851b7abefdf47078270343f4269a3236d23d66b2bfd23e7e309af86e027a1`.
- `adb install -r app/build/outputs/apk/aosp/debug/app-aosp-debug.apk` — **Success**;
  package `com.librenostr.android` started with `monkey` and produced no
  `FATAL EXCEPTION`, `ANR` or process-death entries in an 8-second logcat smoke
  run. The only GCM warning observed in the final device log was attributed by
  `adb shell ps` to `com.brave.browser`, not the LibreNostr process; there was
  no Primal/FCM host access from the app.
- `apkanalyzer manifest print` on the installed APK shows only `nostrich.org`
  HTTPS authorities; no Firebase messaging service or `primal://` handler is
  exported. `strings classes.dex` contains no `primal.net`, Firebase or nsec
  literals. A source scan of active Android/shared production code found no
  `primal.net`, `cache1.primal`, Firebase, `primal://`, `primalconnect` or
  `nostrnwc+primal` endpoint/scheme references.
- The APK still contains the optional third-party Breez/Spark native library
  because wallet compatibility screens remain compiled. Its embedded fallback
  relay list contains `relay.primal.net`; Spark payment/receive services are
  now fail-closed and the library is not initialized by the relay-only startup
  path. Removing this binary completely requires removing/isolating the
  wallet UI and repository modules as a separate breaking change; it is not a
  cache client and is explicitly outside the active payment graph.
- `git diff --check` — clean.
- `./gradlew :app:testAospDebugUnitTest --no-daemon` — **BUILD SUCCESSFUL**;
  the unit-test suite is green after updating stale fixtures to `nostrich.org`
  and the relay-only constructors.
- `./gradlew :app:lintAospDebug --no-daemon` — **BUILD SUCCESSFUL** (363
  warnings remain in legacy/UI code; no lint errors).
- Legacy factory hardening was compiled together with the Android and shared
  networking sources. Compatibility factories may still construct typed
  adapters for migration/UI wiring, but their injected client is fail-closed
  and no centralized request can be emitted by the production graph.
- `adb devices` reports the connected test device; install and runtime smoke
  evidence is recorded in the handoff after the debug APK is assembled.
- `:core:app-config:detektMetadataCommonMain` — **BUILD SUCCESSFUL** after
  removing the obsolete dispatcher state. Targeted detekt still reports
  inherited complexity/style findings in the networking/repository modules
  (36 weighted findings in the final pass); the full `./gradlew detekt` baseline
  reports 92 such UI/style findings. These are non-zero quality findings, not
  Kotlin parse/type failures; both Android flavors and the unit tests compile.

## Deliberate compatibility boundaries

The modules and package names historically called `networking-primal`,
`domain-primal` and `remote-primal` are still present because they provide
shared interfaces, migrations and optional wallet compatibility types. No
active wallet service uses their centralized transport: legacy Primal and
Spark operations fail closed, while NWC and external Lightning intents remain
available. Historical event text and test fixtures may still contain the
upstream domain name; they are not endpoints or active network dependencies.
The MIT license retains the original copyright notice as required by its terms.
