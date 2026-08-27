# LibreNostr backlog

Atomic tasks. Status: `todo` | `in_progress` | `blocked` | `done`.

Complexity: S / M / L / XL.

## First five migration tasks

These are the next implementation slice after Phase 0. Do them in order.

### LN-001 — RelayPool subscriptions

- **Title:** Add REQ/EOSE/CLOSE subscription API to `RelayPool`
- **Files / modules:** `app/.../networking/relays/RelayPool.kt`, `RelaysSocketManager.kt`, `core/networking-primal/.../sockets/*`
- **Depends on:** Phase 0 docs
- **Acceptance:** Pool can subscribe a Nostr filter on N relays, emit unique events by id, complete or timeout per relay, close the sub, and leave publish intact
- **Tests:** Fake socket clients; dedupe; one relay failure does not block others; timeout; CLOSE sent
- **Risk:** High — every later read path depends on this
- **Complexity:** L
- **Status:** done

### LN-002 — Contact list from relays

- **Title:** Load kind 3 follow list without Primal `contact_list`
- **Files / modules:** `data/caching/repository` (new data source or branch in account/profile), `UsersApiImpl`, `UsersDatabase` / Room contacts
- **Depends on:** LN-001
- **Acceptance:** After login with nsec, kind 3 is fetched from user/read relays and stored locally; app still works if cache is unreachable
- **Tests:** Parse kind 3; prefer latest replaceable; empty list; relay timeout
- **Risk:** Medium — follow list is the input to the home feed
- **Complexity:** M
- **Status:** done (relay-first, Primal cache fallback)

### LN-003 — Profiles from relays

- **Title:** Load kind 0 metadata into Room without `user_profile` / `user_infos`
- **Files / modules:** `ProfileRepositoryImpl`, `CachingDatabase` profiles, `NostrNotary` unchanged
- **Depends on:** LN-001
- **Acceptance:** Display name/picture/nip05 render from kind 0; unchanged profiles are not re-queried forever; missing enrichment fields do not crash UI
- **Tests:** Upsert latest kind 0; cache hit; batch authors
- **Risk:** Medium — UI currently expects Primal premium/legend/CDN fields
- **Complexity:** M
- **Status:** done (relay-first kind 0, Room upsert, Primal cache fallback)

### LN-004 — Following feed from relays

- **Title:** Chronological kind 1 feed from followed pubkeys
- **Files / modules:** `FeedRepositoryImpl`, `NoteFeedRemoteMediator`, `FeedApiImpl`, `RelayPool`
- **Depends on:** LN-001, LN-002, LN-003 (LN-003 can be progressive)
- **Acceptance:** Home following feed shows kind 1 from contacts via relays; duplicates collapsed; sort by `created_at`; blocking cache does not empty the feed
- **Tests:** Merge/dedupe; author filter; paging/limit
- **Risk:** High — UX will be slower/incomplete vs Primal; paging model differs
- **Complexity:** L
- **Status:** todo

### LN-005 — Thread by event id from relays

- **Title:** Open a note and its replies without `thread_view`
- **Files / modules:** `FeedApiImpl.getThread`, thread ViewModels, `RelayPool`
- **Depends on:** LN-001
- **Acceptance:** Root loads; known replies attach; missing ancestors shown as gaps; no infinite wait on a dead relay
- **Tests:** Duplicate replies; out-of-order; partial tree
- **Risk:** Medium — NIP-10 graphs are messy across relays
- **Complexity:** L
- **Status:** todo

## Phase 0 — docs / baseline

| ID | Title | Files | Depends | Acceptance | Tests | Risk | Complexity | Status |
|---|---|---|---|---|---|---|---|---|
| LN-000 | Import Primal 3.5.25, remotes, license | git remotes, `LICENSE` | — | History present; `upstream` push disabled | n/a | Low | S | done |
| LN-006 | Record baseline build | `docs/BASELINE.md` | LN-000 | `assembleAospDebug` documented | APK produced | Low | S | done |
| LN-007 | Architecture + dependency inventory | `docs/ARCHITECTURE_UPSTREAM.md`, `docs/PRIMAL_SERVER_DEPENDENCIES.md` | LN-000 | 84 verbs classified | n/a | Low | M | done |
| LN-008 | Run `testAospDebugUnitTest` / `allTests` | CI commands | LN-006 | Failures listed in BASELINE | those tasks | Low | M | done (344 + 985 XML, 0 fail; ktlint/detekt/lint still open) |
| LN-009 | Create GitHub `Lwb89dev/librenostr` and push | remotes | user permission | `origin` fetch works | `git ls-remote origin` | Low | S | done |

## Phase 2 extras

| ID | Title | Files | Depends | Acceptance | Tests | Risk | Complexity | Status |
|---|---|---|---|---|---|---|---|---|
| LN-010 | Relay observability (debug) | `RelayPool`, logging | LN-001 | Connected relays, active subs, timeouts, event/dup counts; never log nsec | unit | Low | S | done |
| LN-011 | Default + user relay model | `FallbackRelays`, `settings/network`, NIP-65 | LN-002 | Add/remove/enable relays; read/write; not a single vendor | UI + unit | Medium | M | todo |
| LN-012 | Disable `cachingProxyEnabled` publish | `RelaysSocketManager`, `RelayPool` | LN-006 | EVENT only to relays | publish still OK | Medium | S | todo |

## Phase 6–7

| ID | Title | Files | Depends | Acceptance | Tests | Risk | Complexity | Status |
|---|---|---|---|---|---|---|---|---|
| LN-013 | Local persist instead of `import_events` | `NostrPublisher`, `CachingImportRepository` | LN-004 | Publish visible after restart without cache | unit | Medium | M | todo |
| LN-014 | Switch profile screens off UsersApi cache | `ProfileRepositoryImpl` | LN-003 | No `user_profile` in that path | grep + tests | High | L | todo |
| LN-015 | Switch following feed off mega_feed | `NoteFeedRemoteMediator` | LN-004 | No `multi_kind_mega_feed_directive` for that spec | grep + tests | High | L | todo |
| LN-016 | Switch thread off `thread_view` | thread repos | LN-005 | No `thread_view` | grep + tests | High | L | todo |
| LN-017 | Delete unused cache client | `core/networking-primal` cache types, `core/app-config` | LN-014..016 and remaining consumers | App compiles; primal cache URLs not required | assemble + tests | High | XL | todo |

## Phase 8–12 removals (do not start early)

| ID | Title | Files | Depends | Acceptance | Tests | Risk | Complexity | Status |
|---|---|---|---|---|---|---|---|---|
| LN-018 | Remove explore/trending UI | `app/.../explore`, `main/explore` | LN-004 (home still works) | No explore verbs at runtime | compile | Medium | L | todo |
| LN-019 | Remove premium/membership | `app/.../premium`, membership verbs | LN-004 | No paywall, no IAP membership | compile | Medium | L | todo |
| LN-020 | Remove NIP-46 / NIP-55 product | `nostrconnect`, `signer`, `data/account/signer` | LN-013 (publish proven with nsec) | Local nsec still publishes; no Amber/bunker login | login + publish tests | High | XL | todo |
| LN-021 | Wallet classification implementation | `data/wallet/*` | LN-010 audit | NWC kept; primal wallet gone or isolated | compile | High | XL | todo |
| LN-022 | Rebrand LibreNostr (no package rename) | strings, icons, README, about | after LN-018/019 | User-facing Primal marks gone; LICENSE kept | visual | Low | M | in_progress (name, icon, README, splash; package still net.primal.android) |
| LN-023 | Package rename | `net.primal.android` | LN-022 + stable networking | Own applicationId | full assemble | High | XL | todo |

## Phase 14

| ID | Title | Files | Depends | Acceptance | Tests | Risk | Complexity | Status |
|---|---|---|---|---|---|---|---|---|
| LN-024 | Hidden-dependency grep pass | whole tree | LN-017 | Each `primal.net` / signer hit classified | documented | Medium | M | todo |
| LN-025 | Block Primal domains integration check | device / emulator | LN-015, LN-016, LN-013 | Feed, profile, thread, publish work | manual | High | M | todo |

## Out of scope for Phase 1

- New LibreNostr aggregation server
- Recreating Primal trending
- Rewriting secp256k1 / NIP-44
- Full wallet rewrite
- iOS/desktop parity
