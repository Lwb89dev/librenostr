# LibreNostr audit — 2026-08-31 (v0.1.1)

Scope: correctness bugs, attack surface, relay load, privacy leaks and dead
code across the Android application, the shared networking/data modules, the
build configuration and the shipped release APK.

Baseline: `08c3e465d`, plus the uncommitted 0.1.1 work (language settings,
unread badges, feed ordering, notification previews).

Verification performed for this pass:

- `./gradlew :app:compileAospDebugKotlin` — BUILD SUCCESSFUL
- `./gradlew testAospDebugUnitTest desktopTest` — BUILD SUCCESSFUL
- `./gradlew detekt` — FAILED, 108 weighted issues (pre-existing; itemized below)
- Release APK inspected directly: `unzip -l`, `aapt2 dump permissions`,
  `strings` over `classes*.dex`

---

## Fixed in 0.1.1

### F1. An empty relay snapshot wiped the cached feed

`NoteFeedList` dispatches `AutoUpdateFeed` on every `ON_START`;
`NoteFeedViewModel.refreshFeedForVisibility` then calls `fetchFeedPageSnapshot`
followed by `replaceFeed`, and `FeedRepositoryImpl.replaceFeed` passes
`clearFeed = true` into `FeedProcessor`, which deleted the feed's remote keys
and cross-refs *before* inserting the new page.

The snapshot can legitimately be empty: `RelaysSocketManager.queryEvents`
returns `RelayPoolQueryResult()` on timeout instead of throwing, so the
`catch (error: NetworkException)` guard in the view model never fires. Offline
or slow relays therefore emptied the local feed on every foreground, with no
cached content left to fall back on.

Fix: `FeedProcessor` never clears a feed for a response that carries no feed
events. Regression test: `FeedProcessorEmptySnapshotTest` — verified to fail
against the previous behaviour and pass against the fix.

### F2. Relay connection status was never cleared on a server-initiated close

`NostrSocketClientImpl.receiveSocketMessages` called `close()` from inside the
receiver coroutine. `close()` starts with `wsReceiverJob?.cancel()` — cancelling
the very coroutine it is running in — and then suspends on the session close
handshake. The project's `runCatching` (`core/utils/.../Result.kt`) deliberately
rethrows `CancellationException`, so `wsSession = null` and the
`onSocketConnectionClosed` callback were both skipped and `relayPoolStatus` kept
reporting a dead relay as connected.

Fix: a non-suspending `handleSocketTornDown` used from the receiver, which
resets the session reference only if it still points at the same session,
cancels it, and always invokes the callback.

### F3. Direct-message and mute-list queries were broadcast to public relays

`RelaysSocketManager.queryEvents` queried the hardcoded fallback pool
unconditionally, in parallel with the account's own relays. Because
`MessagesApiImpl.queryDirectMessages` builds `kinds=[4]` filters with
`authors=[userId]` / `#p=[userId]`, opening the messages tab announced the
user's pubkey and the fact that they were reading DMs to relay.damus.io,
nos.lol, nostr.wine, relay.nostr.band, purplepag.es, relay.snort.social and
relay.nostr.net — regardless of the user's own relay configuration.

Fix: filters that reference a private-scope kind (NIP-04 DMs, mute lists,
stream mute lists) stay on the account's relays whenever any are configured.
The fallback pool is still used when the account has no relays at all, since
there is no alternative there.

### F4. Cancellation swallowed by stdlib `runCatching`

`MessageConversationListViewModel.markAllConversationAsRead` (new in this
release) and `RelayOnboardingViewModel` used `kotlin.runCatching`, which
converts `CancellationException` into a logged failure inside an already
cancelled scope. Both now use `net.primal.core.utils.runCatching`. This is the
project's own `RequireCustomRunCatching` detekt rule.

---

## Open findings

### O1. No backoff or circuit breaker on relay reconnection

`RelayPool.queryOneRelay` calls `ensureSocketConnectionOrThrow()` on every
query, so an unreachable relay is re-handshaked (TCP + TLS) on every attempt,
indefinitely. Combined with O2 and the 30 s feed poll in `NoteFeedViewModel`, a
single scrolling session can produce a sustained request rate against relays
that are already failing.

### O2. Every query is duplicated across two relay pools

`queryEvents` fans out to both the account pool and the seven-relay fallback
pool. With 30 account relays that is up to 37 REQ per user action. Deduplicating
by relay URL across the two pools, or querying fallback only when the account
pool returns nothing, would roughly halve relay load.

### O3. 17.8 MB of native code for a disabled wallet backend

```
lib/arm64-v8a/libbreez_sdk_spark_bindings.so   17,814,608 bytes
```

68 % of the APK's native payload, for a backend the code itself documents as
"not an approved payment backend in the relay-only app"
(`DisabledPrimalWalletService`). Total APK: 78.5 MB.

### O4. `MainActivity` routes intents from any local app

`MainActivity` is `exported="true"` as the launcher. Manifest intent filters
constrain only *implicit* resolution, so any installed app can start it
explicitly with attacker-chosen `data` and reach
`navigateToNostrConnectBottomSheet` (NIP-46 pairing with an attacker-controlled
relay and secret) or `navController.handleDeepLink` for arbitrary in-app
navigation. User confirmation is the only barrier; the deep link's origin is
not validated.

### O5. `decompressMessage` cannot succeed

`NostrSocketClientImpl.decompressMessage` calls `source.readByteArray(1 MiB + 1)`,
and okio requires *exactly* that many bytes, throwing `EOFException` otherwise.
Unreachable today (`incomingCompressionEnabled` is always `false`), but one
flag away from breaking every compressed frame.

### O6. Android per-app language is overridden by the in-app preference

The manifest declares `android:localeConfig`, which surfaces the system per-app
language picker, while `PrimalActivity.attachBaseContext` always routes through
`AppLanguageManager.wrap`. When no in-app language is set, `wrap` forces
`Locale.setDefault(Resources.getSystem().configuration.locales[0])` — the
*device* locale, not the per-app locale the platform applied. Two competing
sources of truth; the system picker silently loses.

### O7. Publish result race

`RelayPool.handlePublishEventToRelays` emits into a `MutableSharedFlow()` with
`replay = 0` and no buffer, from coroutines launched before the collector
subscribes. Values emitted with no subscriber are dropped, so `responseCount`
never reaches `relayConnections.size` and a failed publish surfaces only after
the 10 s timeout instead of immediately.

### O8. Smaller items

- `NoEncryption` is present but wired nowhere — a plaintext credential
  serializer one DI edit away from being reachable.
- `proguard-rules.pro` keeps `-keepnames class net.primal.** { *; }` together
  with `SourceFile,LineNumberTable`: release builds are not obfuscated.
- `autoVerify` deep links point at `nostrich.org`, a third-party domain.
- `LocalSignerContentProvider` and `SignerActivity` are `exported="false"`, so
  the whole NIP-55 provider implementation is compiled but unreachable.
- `PrimalDatabasePasswordProvider` uses `runBlocking` on the DataStore read at
  database-open time (ANR risk); a corrupted `db_key.txt` makes AES-GCM throw
  with no recovery path.
- The embedded WebView allowlist (YouTube/Spotify/Tidal, HTTPS only,
  `allowFileAccess`/`allowContentAccess` off) is sound, but third-party cookies
  are not blocked, so embeds correlate views across notes.
- Release logging (`RecordingAntilog` → `AppLogRecorder`, exported through the
  FileProvider `logs/` path) defaults to **off**, but the 699 Napier call sites
  include relay URLs, event ids and NOTICE payloads when it is enabled.

---

## Dead code (detekt, 108 weighted issues)

`PrimalAppNavigation.kt` — eight unreferenced premium/legend functions
(344, 357, 1671, 1714, 1746, 1766, 1794, 1821) ·
`GifPickerViewModel.fetchTrending:100` ·
`WalletNoticeSheetViewModel.fetchAndUpdateNoticeType:109` ·
`MediaFeedCard.ActionSpacing:106` · 19 unused parameters (nine in
`MainScreen.kt`, three in `NostrNotary.kt`, four in `PurchaseMonitor.kt`) ·
`PrimalCrashReporter` (its upload is already a no-op) ·
`NostrSocketClientImpl.compressMessage`, marked `@Suppress("unused")`.

---

## Confirmed sound

- Every incoming event is validated for both the NIP-01 id and the Schnorr
  signature, with content and tag-count caps applied before the hash
  (`NostrEventValidation`, `NostrIncomingMessageParser.asVerifiedNostrEventOrNull`).
- Credential storage is Android Keystore AES-GCM with a versioned format and a
  read-only migration path from the legacy CBC files; there is no plaintext
  fallback.
- Android backup and cleartext traffic are both disabled; extraction rules
  exclude all domains.
- `RelayOnlyApiClient` is genuinely fail-closed and opens no socket.
- **No `primal.net` endpoint appears in the release dex** (verified with
  `strings` over `classes*.dex`). The only embedded WebSocket endpoints are the
  seven fallback relays and `wss://mempool.space/api/v1/ws`.
- Signing material (`config.properties`, `.env`, keystores) is gitignored and
  has never been committed — verified against the full history.
