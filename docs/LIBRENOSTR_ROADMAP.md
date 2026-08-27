# LibreNostr roadmap

Incremental strangler migration. Do not delete the cache client until a replacement is wired and the app still compiles.

Ordering below follows repository reality: **read-side RelayPool must exist before any cache verb can be turned off**, and local `nsec` identity must stay publish-capable before signers are removed.

## Phase 0 — Baseline and audit

Status: **done locally** (import + `aospDebug` compile + unit/`allTests` + these docs). GitHub `origin` is `Lwb89dev/librenostr`.

- Import Primal 3.5.25 with history.
- Configure `origin` / `upstream` (push to upstream disabled).
- Build `aospDebug`.
- Document license, architecture, server inventory, backlog.

Stop condition: no mass deletion.

## Phase 1 — Inventory freeze

Status: **this document set**.

- Treat `docs/PRIMAL_SERVER_DEPENDENCIES.md` as the living inventory.
- Do not add new Primal verb callers.

## Phase 2 — Relay infrastructure

Status: **done** (`RelayPool.query`, EOSE/timeout/dedupe/CLOSE, `lastQueryStats`). Publish unchanged.

- Extend `RelayPool` with REQ / EOSE / CLOSE / timeout / event-id dedupe / merge.
- Expose connection state and safe debug counts (no secrets).
- Keep publish path working.
- Optional: move socket types toward a neutral module name later.

## Phase 3 — Profile / contact data from relays

Status: **partial** — kind 3 and kind 0 are relay-first with cache fallback. NIP-65 still cache.

- Kind 3 contact list → Room.
- Kind 0 metadata for those pubkeys → Room (respect local cache; do not re-query unchanged profiles forever).
- Kind 10002 (NIP-65) into `UsersDatabase` relays.

## Phase 4 — Following feed from relays

- Authors from kind 3, REQ kind 1 (+ 6/16 if cheap).
- Merge, dedupe, chronological sort.
- Progressive UI: notes first, profiles later.
- Do not clone Primal ranking.

## Phase 5 — Thread / event retrieval

- REQ by id and `#e` / `#a`.
- Tolerate partial threads and out-of-order events.
- Open a note without `thread_view`.

## Phase 6 — Publish path validation

- Local nsec can still sign via `NostrNotary`.
- EVENT to user/write relays without `cachingProxyEnabled`.
- Persist published events locally instead of `import_events`.
- Restart still shows the note from Room.

## Phase 7 — Remove caching-server implementation

- Switch remaining read repositories only after replacements exist.
- Delete unused `PrimalVerb` callers, then the cache client, then `core/app-config` well-known.

## Phase 8 — Remove trending / discovery

- Explore people/topics/zaps, recommended users, premium paywalled feeds.
- Close navigation holes with a simpler home.

## Phase 9 — Remove external signer functionality

- After phase 6 is proven: NIP-46 UI/client, NIP-55 Amber client, NIP-55 provider, `ExternalSigner` / `InternalSigner` login.
- Leave `PrivateKey` (and optional read-only `PublicKey`).

## Phase 10 — Audit wallet / zaps

- KEEP: NWC, LNURL, protocol zaps if independent.
- REMOVE: Primal wallet verbs, membership IAP.
- INVESTIGATE: Breez Spark built-in wallet.

## Phase 11 — Remove Primal-specific services

- Premium, legends, promo codes, primalName, membership media, rebroadcast, client_config.

## Phase 12 — Rebranding

Separate commits from networking.

- App name LibreNostr, launcher, icons, splash, about, store metadata, README.
- Package rename only after networking is stable (not in the same commit).
- Keep MIT copyright.

## Phase 13 — Performance / local caching

- Profile/note/thread caches, relay hints, subscription hygiene, progressive rendering.

## Phase 14 — Tests / stabilization

- Unit tests for relay merge/dedupe.
- Regression: with Primal domains blocked, following feed / profile / thread / publish still work.
- ktlint, detekt, `testAospDebugUnitTest`.

## Phase 1 end-state (success criteria)

From the project brief. Mapping:

1. App launches — phase 0.
2. Nostr identity (local nsec) — already present; protect through phase 9.
3. Direct relay connections — phase 2.
4. Follow list without Primal — phase 3.
5. Following feed from relays — phase 4.
6. Profiles from Nostr — phase 3.
7. Notes/threads open — phase 5.
8. Publish via local key — phase 6.
9. Restart works — phase 6/13.
10. Blocking primal.net / cache does not break the basic client — phase 7 + 14.

## Next implementation step after this audit

Phase 2 only: subscription API on `RelayPool`. No cache deletion yet.
