# Upstream architecture audit

Classification of the imported Primal 3.5.25 tree (`efb88b5af`) **before** destructive deletion.

Statuses:

| Status | Meaning |
|---|---|
| KEEP | Useful as-is, or with light renaming later |
| REFACTOR | Keep the idea, change the data source or branding |
| REPLACE | Current implementation cannot stay; write a relay-native equivalent |
| REMOVE | Primal-specific product feature with no LibreNostr place |
| INVESTIGATE | Need a later dedicated audit before deletion |

## Module map

Gradle root name is still `Primal` (`settings.gradle.kts`).

```text
app/                          Android UI, DI, relay publish pool, auth, settings
core/
  app-config/                 Primal well-known endpoint discovery
  caching/                    local media cache helpers
  networking-http/            Ktor HTTP client
  networking-lightning/       LNURL / lightning address
  networking-primal/          Primal cache WebSocket + HTTP API client
  networking-upload/          blossom / upload
  nips/                       NIP-04 / NIP-44 via Quartz
  testing/                    test helpers
  utils/
data/
  account/{local,remote,signer,repository}
  caching/{local,remote,repository}   Room cache + Primal cache APIs + repos
  shared/local
  wallet/{local,remote-primal,remote-nwc,repository}
domain/
  account/ nostr/ primal/ wallet/

macrobenchmark/
detekt-rules/
```

Application id / namespace: `net.primal.android`.
User-facing name: `Primal` (`app/src/main/res/values/strings.xml`).

## Current data path (as imported)

```text
Compose UI
    ↓
ViewModels
    ↓
domain interfaces (FeedRepository, ProfileRepository, ...)
    ↓
data/caching/repository  (Room + RemoteMediator)
    ↓
data/caching/remote      (PrimalVerb JSON)
    ↓
core/networking-primal   (REQ frames to wss://cache*.primal.net)
    ↓
Primal cache servers
    ↓
Nostr (server-side)
```

Publish path is already closer to the target:

```text
NostrNotary (local nsec / Amber / UI prompt)
    ↓
NostrPublisher
    ↓
RelaysSocketManager / RelayPool
    ↓
standard relay EVENT
    + optional CachingImportRepository.importEvents (Primal cache ingest)
```

`RelayPool` publishes and now exposes reconnect-safe subscriptions for live
stream events. Production feed/profile/thread reads and live subscriptions use
the configured user relays; remaining compatibility APIs are isolated to
follow-pack synchronization and optional wallet/membership modules.

## Subsystem classification

### Compose UI / navigation

| Area | Path | Status | Notes |
|---|---|---|---|
| Note feeds, threads, profiles, editor | `app/.../notes`, `thread`, `profile`, `editor` | REFACTOR | Keep UX. Data sources change later. |
| Explore / trending / follow packs | `app/.../main/explore`, `explore/` | REMOVE | Driven by Primal explore verbs. |
| Premium / legend / membership | `app/.../premium` | REMOVE | Primal paid product. |
| Reads (long-form) | `app/.../articles`, `main/reads` | REFACTOR | Protocol exists (kind 30023). Fetch path is cache. |
| DMs | `app/.../messages` | INVESTIGATE | Protocol NIPs exist; fetch is Primal DM verbs. |
| Live streams | `app/.../stream` | REPLACED | Kind 30311/1311 relay queries and subscriptions. |
| Wallet UI | `app/.../wallet`, `settings/wallet` | INVESTIGATE | Mixed NWC / Primal / Breez. |
| Onboarding / login | `app/.../auth` | REFACTOR | Keep local nsec + npub-only. Strip signer/premium upsell. |
| Settings / network / relays | `app/.../settings` | REFACTOR | Relay editor exists. Cache URL settings must go. |
| Theming / compose primitives | `app/.../theme`, `core/compose` | KEEP | Rebrand later; do not redesign. |
| Primal branding strings/icons | `res/`, README, store metadata | REMOVE (phase 12) | Keep LICENSE attribution. |

### Dependency injection / app wiring

| Area | Status | Notes |
|---|---|---|
| Hilt modules in `app/.../core/di` | REFACTOR | Bindings currently inject `PrimalApiClient`. |
| Flavor split `google` / `aosp` | KEEP | AOSP is the LibreNostr default. |
| Firebase / FCM (`google`) | INVESTIGATE | Push currently talks to Primal verbs. |
| Play Billing | REMOVE | Membership purchases. |

### Networking

| Area | Path | Status | Notes |
|---|---|---|---|
| `NostrSocketClient` | `core/networking-primal/.../sockets` | KEEP | REQ/EVENT/CLOSE/AUTH/COUNT, reconnect, NOTICE. Reuse this. |
| `RelayPool` / `RelaysSocketManager` | `app/.../networking/relays` | REFACTOR | Add read subscriptions; stop cache-proxy publish. |
| `FallbackRelays` | same | REFACTOR | Includes `wss://relay.primal.net`. Keep as one default, not the only one. |
| `PrimalApiClient` / `BasePrimalApiClient` | `core/networking-primal` | REPLACE then REMOVE | Cache protocol. Strangler: keep until each consumer migrates. |
| `AppConfig` well-known | `core/app-config` | REMOVE | Fetches `https://primal.net/.well-known/primal-endpoints.json`. Defaults: `cache1.primal.net`, `uploads.primal.net`, `wallet.primal.net`. |
| HTTP client | `core/networking-http` | KEEP | |
| Lightning address checker | `core/networking-lightning` | KEEP | LNURL, not Primal cache. |
| Blossom upload | `core/networking-upload` | REFACTOR | Default blossom host is `blossom.primal.net`. Protocol can stay. |
| Klipy GIF API | `data/caching/remote/.../klipy` | INVESTIGATE | Third party, not Primal cache. |

### Local cache (device)

| Area | Status | Notes |
|---|---|---|
| Room `CachingDatabase` | KEEP | Local cache is allowed and desirable. Schema currently stores Primal enrichment columns (legend, premium, CDN). |
| `UsersDatabase` | KEEP | Accounts, user relays. |
| DataStore credentials/accounts | KEEP | Encrypted in release (`AESEncryption` + SQLCipher). **Debug uses `NoEncryption()`.** |
| Media cacher | KEEP | |

### Nostr protocol / cryptography

| Area | Status | Notes |
|---|---|---|
| Event models, kinds, tags | `domain/nostr` | KEEP | Do not rewrite. |
| Signing (`signOrThrow`, secp256k1) | `domain/nostr` + Acinq secp256k1 | KEEP | |
| NIP-04 / NIP-44 | Quartz `1.04.2` via `core/nips` | KEEP | Do not write crypto. |
| Bech32 / event id | existing utils | KEEP | |
| `NostrNotary` | `app/.../nostr/notary` | REFACTOR | Keep local nsec signing. Remove Amber/external branches when signers go. |
| `NostrPublisher` | `app/.../nostr/publish` | REFACTOR | Keep relay publish. Drop `importEvents` to Primal cache. |

### Account / identity / signers

`CredentialType`:

| Type | Meaning | LibreNostr phase 1 |
|---|---|---|
| `PrivateKey` | local `nsec` in DataStore | KEEP |
| `PublicKey` | read-only npub login | KEEP (read-only is explicit) |
| `ExternalSigner` | NIP-55 Amber / external Android signer | REMOVE (phase 9) |
| `InternalSigner` | app-as-signer keypair for NIP-46/NIP-55 provider | REMOVE with signer product |

| Area | Path | Status |
|---|---|---|
| `CredentialsStore` | `app/.../user/credentials` | KEEP (local nsec) |
| `AuthRepository` / `LoginHandler` / `CreateAccountHandler` | `app/.../auth` | REFACTOR |
| NIP-46 remote signer **provider** UI | `app/.../nostrconnect` | REMOVE |
| NIP-46 remote client | `data/account/signer/.../remote` | REMOVE |
| NIP-55 Amber client | `app/.../signer/client` | REMOVE |
| NIP-55 content provider | `app/.../signer/provider`, `data/account/signer/.../local` | REMOVE |
| `data/account/*` | mixed | REFACTOR then shrink |

Phase 1 identity: keep `PrivateKey` (and optionally `PublicKey` read-only). Confirm publish still works after signer code is gone. Never log or commit private keys. Do not weaken release encryption.

### Feeds / threads / profiles

| Area | Path | Status | Replacement |
|---|---|---|---|
| `FeedRepositoryImpl` + `NoteFeedRemoteMediator` | `data/caching/repository/feed` | REPLACE | Relay REQ for kind 1 (+ reposts) from follow list. |
| Following-feed spec | `buildLatestNotesUserFeedSpec` → `{"id":"feed","kind":"notes","pubkey":...}` | REPLACE | This JSON is a Primal cache directive, not a Nostr filter. |
| Thread APIs | `FeedApiImpl.getThread` / `MULTI_KIND_THREAD_VIEW` | REPLACE | `#e` / `#a` filters, NIP-10 / NIP-22 as applicable. |
| `ProfileRepositoryImpl` | `data/caching/repository/profile` | REPLACE | Kind 0 + local Room. Drop `primalName` well-known. |
| Contact list | `UsersApiImpl` `CONTACT_LIST` | REPLACE | Kind 3 from relays. |
| User relays | `USER_RELAYS_2` | REPLACE | Kind 10002 (NIP-65) from relays. |
| Event lookup | `EVENTS`, replaceable-event verbs | REPLACE | REQ by id / `#d`. |

### Search / discovery

| Area | Status | Notes |
|---|---|---|
| Profile search (`USER_SEARCH`) | REPLACE | NIP-50 where relays support it; else local DB. |
| Advanced search (`PARSE_ADVANCED_SEARCH_QUERY`) | REMOVE or REDUCE | Cache query language. |
| Explore people/topics/zaps | REPLACED | Relay-derived local ranking/counting. |
| Follow lists / packs | INVESTIGATE | Some may map to kind 30000; current API is cache. |
| Featured DVM feeds | INVESTIGATE | NIP-89/DVM is protocol; featured list is Primal. |

### Notifications / DMs / media

| Area | Status | Notes |
|---|---|---|
| Notification inbox verbs | REPLACE or REDUCE | Kind 9735 / reactions / replies from relays; no server-side inbox. |
| Push token verbs | REMOVE unless a later non-Primal push path exists. |
| DM conversation verbs | INVESTIGATE | Prefer NIP-17 / NIP-04 from relays + local cache. |
| CDN resources in cache responses | REPLACE | Clients resolve media URLs from events; optional blossom. |

### Wallet / lightning / zaps

| Area | Status | Notes |
|---|---|---|
| NWC (`data/wallet/remote-nwc`) | KEEP | Standard protocol, already uses `sendREQ` on wallet relays. |
| LNURL / lightning address | KEEP | |
| Zap event publish (kind 9734/9735) | KEEP if it does not need Primal wallet | |
| `data/wallet/remote-primal` | REMOVE or REPLACE | Primal wallet socket. |
| Breez Spark SDK | INVESTIGATE | Vendor wallet, not cache. Phase 10. |
| Membership Play products | REMOVE | |
| `INVOICES_TO_ZAP_RECEIPTS` | REPLACED | Relay `#bolt11` lookup and local zap-receipt matching. |

### Membership / premium

Entire `app/.../premium` tree, membership verbs, promo codes, legend customization, content rebroadcast, premium feed paywall (`pas:1`): **REMOVE**.

### Recommended relay library / path

Do **not** add a new Nostr stack.

Reuse, in this order:

1. `core/networking-primal/.../sockets/NostrSocketClient` — already speaks standard frames.
2. `app/.../networking/relays/RelayPool` — already multiplexes relays for publish.
3. `domain/nostr` models + Acinq secp256k1 + Quartz for NIP-04/44.

Extend `RelayPool` with subscription lifecycle (REQ, EOSE, CLOSE, dedupe by event id, timeout, merge). Point `FeedRepository` / `ProfileRepository` at that, one feature at a time.

`core/networking-primal` as a **cache client** goes away. The socket types can move to a neutral module name later (`core/networking-nostr`) without rewriting crypto.

## KEEP / REFACTOR / REPLACE / REMOVE counts (coarse)

These are subsystem judgements, not file counts.

- KEEP: crypto, local Room, Compose UX, HTTP, LNURL, NWC, socket framing.
- REFACTOR: RelayPool, publisher, auth, settings, blossom defaults, branding.
- REPLACE: every read repository that calls `PrimalVerb`.
- REMOVE: premium, explore/trending, cache app-config, external signer product.
- INVESTIGATE: DMs, live, built-in wallet, DVM feeds, push, Klipy.

## First feature to migrate

Smallest useful read path that is not publish-only:

**contact list (kind 3) → kind 0 for those pubkeys → kind 1 following feed.**

That is the first production data flow that can stop talking to `cache1.primal.net`.
