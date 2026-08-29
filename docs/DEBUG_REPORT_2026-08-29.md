# LibreNostr debug report — 2026-08-29

## Blocking defect

`RelayPool.queryOneRelay` sent `REQ` from `Flow.onStart`. The socket response stream is a
non-replaying `SharedFlow`, so a fast relay could emit `EVENT` or `EOSE` before the collector
was subscribed. Those frames were then lost and the query appeared disconnected until timeout.

The request is now sent from `onSubscription`, both for pooled one-shot queries and persistent
socket subscriptions. A regression test makes a fake relay answer immediately and verifies that
the event is retained.

## Android-only WebSocket defect

The first Pixel 10 run exposed a second blocker that JVM mocks did not reproduce. Ktor's
`WebSockets.maxFrameSize` option is unsupported by the Android OkHttp engine, so every socket
failed before the HTTP upgrade with `Max frame size switch is not supported in OkHttp engine`.
The unsupported option has been removed. Incoming text and binary frames remain bounded by the
application-level checks in `NostrSocketClientImpl`.

Legacy cache and wallet proxy clients also connected eagerly during dependency initialization.
They now connect only if a legacy API is actually called, so launching LibreNostr no longer opens
`cache1.primal.net` or `wallet.primal.net` on its own.

## Home feed loading defect

The Home screen could remain on its skeleton forever before creating a `NoteFeedViewModel` or
sending any timeline `REQ`. `NoteFeedsViewModel` persisted the local default feeds and returned,
waiting for a second Room emission before publishing them to the UI. The merged local/default
feeds are now exposed immediately and persistence follows afterward. This unblocks construction
of the pager and the relay-native following-feed fetch.

## Related corrections

- User relay pools are cleared even when the new relay list is empty, avoiding account-to-account
  relay leakage.
- A failed eager connection no longer permanently excludes user relays from later queries.
- Login creates and activates the local account immediately; profile hydration is background work.
- NIP-65 relay discovery and batch lookups no longer fall back to `get_user_relays_2`.
- Direct profile fetches use Room and kind 0 relay events without `user_profile` fallback.
- The dead Primal-wallet migration branch was removed from `UserDataUpdater`.
- Query completion waits a short grace period after first EOSE so other relays can contribute,
  without adding multi-second latency to every request.

## Validation

- Unit regression coverage: immediate relay response, EOSE, timeout, deduplication, CLOSE and
  relay repository local/relay-only behavior.
- A live WebSocket smoke request against `wss://nos.lol` returned a kind 1 `EVENT`.
- Pixel 10 / Android 17 cold and warm launches completed without crash or ANR.
- On-device following-feed refresh queried five user relays, received real kind 1/6 events,
  persisted them, rendered the timeline, and completed subsequent APPEND pages.
- The notification, algorithm and network-settings screens were exercised on device; background
  relay failures degraded to partial pool results instead of blocking the UI.
- Full AOSP debug unit tests and APK assembly are the release gates for this change set.

## Remaining migration boundary

The repository still contains legacy Primal cache and wallet modules and several non-core screens
still call their APIs (ranking feeds, notification aggregation, search/statistics and premium
features). The basic identity, relay list, profile metadata and following-feed path no longer need
those services, but deleting all legacy modules remains a separate high-risk migration.
