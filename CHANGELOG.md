# Changelog

All notable changes to LibreNostr are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
LibreNostr is a fork of [Primal](https://github.com/PrimalHQ/primal-android-app)
(MIT, Copyright (c) 2023 PRIMAL SYSTEMS INC.); this log covers changes made in
the LibreNostr fork on top of the imported `3.5.25` baseline.

## [0.1.0] - 2026-08-30

Initial LibreNostr release. Every active Android data path now talks to
Nostr relays directly instead of a Primal cache server.

### Added

- Relay-only data layer: feeds (**Latest**, **Latest with replies**),
  profiles, follow lists (NIP-65), threads, notifications, direct messages
  (NIP-04), bookmarks (kind 10003), mute lists (kind 10000/10555/30000) and
  live-stream lookup (kind 30311) are all read and written directly against
  configured relays.
- Relay-only Reads: article feed, article details/comments (NIP-22/NIP-23)
  and highlights (NIP-84), replacing the Primal Articles API.
- Relay-only Explore: profile search, popular people, trending topics/zaps
  and note reaction/repost action lists, with NIP-50 search where available.
- Relay-only polls (kind 1068/6969/1018) and NIP-57 zap receipts (kind 9735),
  including invoice-to-zap enrichment.
- `RelayPool` REQ/EOSE/CLOSE subscription API with dedupe, timeouts and
  bounded concurrent subscriptions (capped at 64).
- LibreNostr branding: app name, launcher icon, `nostrich.org` deep links
  (`/home`, `/reads`, `/notifications`, `/p/<npub>`) replacing `primal://`
  and `primal.net`.
- Project documentation: `docs/UPSTREAM.md`, `docs/BASELINE.md`,
  `docs/ARCHITECTURE_UPSTREAM.md`, `docs/PRIMAL_SERVER_DEPENDENCIES.md`,
  `docs/LIBRENOSTR_ROADMAP.md`, `docs/LIBRENOSTR_BACKLOG.md`,
  `docs/RELAY_ONLY_MIGRATION_STATUS.md`, `docs/SECURITY_AUDIT.md`.
- ABI-split release APK packaging (`armeabi-v7a`, `arm64-v8a`, `x86_64`) and
  an `alternative` signing config independent of Google Play signing.

### Changed

- `SocketModule` injects a fail-closed `RelayOnlyApiClient`; the legacy
  shared `PrimalApiClientFactory` / `PrimalHttpApiClientFactory` throw
  instead of silently reconnecting to a centralized cache.
- Wallet: NWC (NIP-47) is the only enabled in-app wallet transport. Legacy
  Primal-wallet payment/balance/invoice paths and centralized NWC
  provisioning are fail-closed; zaps and invoice links fall back to the
  Android `lightning:` intent when NWC isn't configured.
- Local credential storage now uses Android Keystore AES-GCM (versioned
  format v2, 128-bit auth tag); legacy CBC files are readable only for
  migration, with no plaintext fallback.
- Android backup is disabled (`allowBackup=false`) and backup/data-extraction
  rules exclude all app data; cleartext network traffic is disabled.
- Note counters, event stats and zap totals are now aggregated locally from
  relay-observed events instead of Primal's synthetic stats payload.

### Removed

- Firebase Cloud Messaging registration and `PrimalFirebaseMessagingService`;
  push token methods remain as local no-ops so no device token or signed
  authorization event reaches a central service.
- Primal well-known profile resolution, follow-pack remote paging, DVM
  featured-feed discovery, advanced-search parsing endpoint, and the
  Primal Articles/EventStats/Settings remote APIs on the active path.
- Primal deep links (`primal://`, `primal.net` app-link intents) and the
  `CONTRIBUTING.md` template inherited from upstream.

### Security

- Full audit results and verification evidence (build/test/lint runs, APK
  manifest and string scans, log review) are in `docs/SECURITY_AUDIT.md`.
- No `primal.net`, Firebase, `primal://`/`primalconnect://` or
  `nostrnwc+primal` references remain reachable from the active production
  code path; the compiled APK was scanned to confirm this.

### Known limitations

- Wallet/premium/external-signer UI and compatibility modules — including
  the optional Breez/Spark native wallet library — remain compiled in;
  their centralized transports are fail-closed but the modules themselves
  are scheduled for removal.
- The internal package namespace is still `net.primal.android`; renaming it
  is deferred until after the networking migration, per
  `docs/LIBRENOSTR_ROADMAP.md`.

[0.1.0]: https://github.com/Lwb89dev/librenostr/releases/tag/v0.1.0
