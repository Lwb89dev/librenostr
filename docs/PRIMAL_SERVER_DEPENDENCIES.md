# Historical Primal server dependency inventory

This is a migration record, not a list of live endpoints. LibreNostr's Android
graph no longer creates the cache, wallet or push clients described below.
`SocketModule` supplies a fail-fast no-network compatibility client and all
active feeds, notifications, DMs, search, stats and subscriptions use Nostr
relays. Historical URLs are retained only to explain what was removed.

Every `PrimalVerb` currently sent to Primal cache/wallet servers, plus related HTTP well-known endpoints.

Source of verbs: `data/caching/remote/src/commonMain/kotlin/net/primal/data/remote/PrimalVerb.kt` (**59 verbs**).

Transport:

- WebSocket `REQ` with a Primal filter object (`primalVerb` + options JSON) via `PrimalApiClient` / `BasePrimalApiClient`.
- Former cache/wallet/upload URLs are no longer configured or opened.
- Dynamic endpoint discovery is disabled; the app uses user-configured relays
  and local Blossom settings.

Status values: `REPLACE` (relay/NIP equivalent), `REDUCE` (local/partial), `REMOVE` (no LibreNostr equivalent), `INVESTIGATE`, `KEEP-LOCAL` (device only, not this inventory).

Standard Nostr can reproduce a capability only when a NIP or kind already carries the data. Aggregation, ranking, and Primal-specific metadata (legend, primalName, CDN variants, server paging) have **no** 1:1 relay equivalent.

## Endpoints (not verbs)

| Feature | Source | Request | Purpose | Replacement | Standard Nostr? | Performance | Status |
|---|---|---|---|---|---|---|---|
| Cache/wallet/upload URLs | `core/app-config` `WellKnownApi` | `GET https://primal.net/.well-known/primal-endpoints.json` | Discover Primal servers | Delete; use user relays | n/a | n/a | REMOVE |
| Default cache | `AppConfigFactory` | `wss://cache1.primal.net/v1` | All cache verbs | Relay pool | no | worse latency, incomplete data | REPLACE |
| Default upload | same | `wss://uploads.primal.net/v1` | Media upload | Blossom servers from kind 10063 / user config | partial | similar if a blossom is reachable | REPLACE |
| Default wallet | same | `wss://wallet.primal.net/v1` | Primal wallet | NWC / LNURL | no | n/a | REMOVE / INVESTIGATE |
| primalName → pubkey | `UserWellKnownApi` | HTTP on primal.net | Resolve Primal names | NIP-05 | yes (NIP-05, not primalName) | extra HTTP | REPLACE |
| Default blossom | `MediaUploadsSettingsViewModel` | `https://blossom.primal.net` | Media host | User-configured blossom | NIP-B7 / blossom | depends on host | REFACTOR |

## Feeds, threads, events

| Feature | Source | Verb | Purpose | Replacement | Standard Nostr? | Performance | Status |
|---|---|---|---|---|---|---|---|
| Home / mega feeds | `FeedApiImpl` | `mega_feed_directive`, `multi_kind_mega_feed_directive` | Paged notes/articles by Primal feed spec JSON | Relay REQ on follow list / kinds | partial (no server ranking/paging) | much slower; duplicates; missing profiles | REPLACE |
| Following feed spec | `buildLatestNotesUserFeedSpec` | same as above | `{"id":"feed","kind":"notes","pubkey":user}` | kind 3 → authors filter kind 1 | yes, crude | first page only unless overlapping relays | REPLACE |
| Note thread | `FeedApiImpl.getThread` | `thread_view` | Root + replies + enrichment | NIP-10 `#e` across relays | partial | missing ancestors common | REPLACE |
| Multi-kind thread | `FeedApiImpl` | `multi_kind_thread_view` | Thread including polls/pictures | same + extra kinds | partial | same | REPLACE |
| Article thread | removed (`ArticlesApiImpl`) | `long_form_content_thread_view` | Kind 30023 + comments | NIP-23 + NIP-22 | partial | same | REPLACED (relay-only) |
| Highlights | removed (`ArticlesApiImpl`) | `get_highlights` | Article highlights | kind 9802 + tagged replies/zaps | partial | sparse | REPLACED (relay-only) |
| Event fetch | events API | `events` | Hydrate ids with enrichment | REQ ids | yes for events; no for stats/CDN | extra round-trips for metadata | REPLACE |
| Replaceable events | events API | `replaceable_event`, `parametrized_replaceable_event(s)` | Latest kind 0/3/10002/30000… | standard replaceable REQ | yes | need NIP-65 hints | REPLACE |
| Import published event | `PrimalImportApi` / `NostrPublisher` | `import_events` | Push event into Primal cache so UI sees it immediately | write Room locally; relays already got EVENT | n/a | local write is enough | REMOVE (LN-013: publish path writes Room; verb may remain unused) |
| Broadcast via cache | `RelayPool` (removed) | `broadcast_events` | Publish through Primal instead of user relays | always EVENT to write relays | yes | depends on relay set | REMOVE (LN-012: publish path gone; verb may remain for other callers) |

## Profiles, contacts, relays

| Feature | Source | Verb | Purpose | Replacement | Standard Nostr? | Performance | Status |
|---|---|---|---|---|---|---|---|
| Profile | `UsersApiImpl` | `user_profile` | Kind 0 + stats + premium + CDN | kind 0 + local cache | kind 0 yes; stats/premium no | metadata often delayed | REPLACE |
| User infos batch | same | `user_infos` | Many kind 0 + enrichment | batched kind 0 REQ | partial | many relays | REPLACE |
| Followed by | same | `user_profile_followed_by` | Social proof | kind 3 graphs (expensive) | weak | poor | REDUCE / REMOVE |
| Followers | same | `user_followers` | Follower list | not on protocol | no | n/a | REMOVE (or optional NIP-50) |
| Contact list | same | `contact_list` | Kind 3 + metadata | kind 3 | yes | one replaceable event | REPLACE |
| Is following | same | `is_user_following` | Boolean | inspect local kind 3 | yes | local | REPLACE |
| User relays | same | `get_user_relays_2` | Relay list | kind 10002 NIP-65 | yes | | REPLACED (LN-011: current-user and batch paths query relays; local edits use Room) |
| Default relays | same | `get_default_relays` | Primal-recommended relays | LibreNostr static defaults + user config | n/a | n/a | REPLACE (LN-011: `FALLBACK_RELAYS` used for bootstrap/onboarding) |
| Bookmarks | same | `get_bookmarks` | Bookmark list | kind 10003 / 30001 | yes | | REPLACE |
| Mutes | `SettingsApiImpl` | `mutelist`, `mutelists` | Mute lists | kind 10000 / 30000 | yes | | REPLACE |

## Search and explore

| Feature | Source | Verb | Purpose | Replacement | Standard Nostr? | Performance | Status |
|---|---|---|---|---|---|---|---|
| Profile search | `ExploreApiImpl` | `user_search` | Search people | NIP-50 + local metadata fallback | partial | depends on NIP-50 relays | REPLACED (relay-only) |
| Parse/execute advanced search | `FeedsApiImpl` / `FeedApiImpl` | `parse_advanced_search_query`, `get_multi_kind_feed_by_spec` | Primal query language and feed results | local parser + relay NIP-50 filters | partial | relay-dependent | REPLACED (relay-first) |
| Explore people | `ExploreApiImpl` | `explore_people` | Trending people | kind 3 follow lists + kind 0 metadata | partial | relay-dependent | REPLACED (relay ranking) |
| Explore topics | same | `explore_topics` | Trending hashtags | recent kind 1/30023 hashtag counting | partial | relay-dependent | REPLACED (local ranking) |
| Explore zaps | same | `explore_zaps` | Trending zaps | kind 9735 + kind 1 relay queries | partial | relay-dependent | REPLACED (local ranking) |
| Recommended users | same | `get_recommended_users` | Onboarding suggestions | none / user-provided npubs | no | n/a | REMOVE |
| Follow lists | same | `follow_lists`, `follow_list` | Follow packs | kind 30000 if we keep packs | partial | | INVESTIGATE then REDUCE |
| Featured DVM feeds | `FeedsApiImpl` | `get_featured_dvm_feeds` | Curated DVMs | NIP-89 kind-31990 AppHandler events | yes | relay-dependent | REPLACED (relay discovery; no featured-user ranking) |
| App sub-settings / default feeds | `FeedsApiImpl` | `get_default_app_subsettings`, `get_app_subsettings`, `set_app_subsettings` | Per-app feed tabs stored on Primal | local DataStore / kind 30078 | partial | | REPLACE with local |

## Notifications and DMs

| Feature | Source | Verb | Purpose | Replacement | Standard Nostr? | Performance | Status |
|---|---|---|---|---|---|---|---|
| Notification list | `NotificationsApiImpl` | `get_notifications` | Server inbox | subscribe mentions/reactions/zaps on relays | partial | easy to miss | REDUCE |
| Last seen get/set | same | `get_notifications_seen`, `set_notifications_seen` | Read cursor | local | yes (local) | | REPLACE local |
| Unread count | same | `notification_counts_2` | Badge | local derive | partial | | REDUCE |
| Push token | | `update_push_notification_token` | FCM → Primal | drop or own later | no | | REMOVE |
| Push token NIP-46 | | `update_push_notification_token_for_nip46` | Signer push | drop with signer | no | | REMOVE |
| DM contacts | `MessagesApiImpl` | `get_directmsg_contacts` | Conversation list | NIP-17/04 from relays + local | partial | | INVESTIGATE |
| DMs | same | `get_directmsgs` | Messages | same | partial | | INVESTIGATE |
| Mark read | same | `reset_directmsg_count(s)` | Read state | local | yes | | REPLACE local |
| DM unread | same | `directmsg_count_2` | Badge | local | partial | | REDUCE |

## Interactions, zaps, polls, live

| Feature | Source | Verb | Purpose | Replacement | Standard Nostr? | Performance | Status |
|---|---|---|---|---|---|---|---|
| Event zaps / feed zap counters | removed (`EventStatsApiImpl`) | `event_zaps_by_satszapped` | Ranked zap list and note counters | kind 9735 REQ | partial (no ranking guarantee) | relay-derived counters | REPLACED (relay-only; no ranking guarantee) |
| Event actions / feed counters | removed (`EventStatsApiImpl`) | `event_actions` | Who liked/reposted and note counters | kinds 7 / 6 | partial | relay-derived counters | REPLACED (relay-only) |
| Poll votes | `PollsApiImpl` | `poll_votes` | Aggregated poll stats | NIP-69 responses | partial | | REDUCE |
| Invoice → zap receipt | removed (`EventStatsApiImpl`) | `invoices_to_zap_receipts` | Map BOLT11 to kind 9735 | relay `#bolt11` filter + local matching | partial | | REPLACED (relay-only) |
| Live feed | `LiveStreamApiImpl` | `live_feed` | Live chat/stream | kind 30311 / 1311 relay subscription | partial | relay-dependent | REPLACED (relay subscription) |
| Live from follows | same | `live_events_from_follows` | Live list | REQ kind 30311 authors | partial | relay-dependent | REPLACED (relay query) |
| Find live | same | `find_live_events` | Lookup | kind 30311 relay query | partial | relay-dependent | REPLACED (relay query) |

## Settings, client config, media upload

| Feature | Source | Verb | Purpose | Replacement | Standard Nostr? | Performance | Status |
|---|---|---|---|---|---|---|---|
| App settings get/set | `SettingsApiImpl` | `get_app_settings`, `set_app_settings`, `get_default_app_settings` | Primal-synced settings | local DataStore; optional kind 30078 | partial | | REPLACE local |
| Client config | | `client_config` | Feature flags from Primal | static / local | no | | REMOVE |
| Upload chunk/complete | | `upload_chunk`, `upload_complete` | Primal upload API | blossom | blossom yes | | REPLACE |
| Membership media stats/uploads/delete | local compatibility adapter | `membership_media_management_*` | Paid media hosting | user blossom / empty local state | no | | REMOVED (no-op) |

## Wallet and membership

| Feature | Source | Verb | Purpose | Replacement | Standard Nostr? | Performance | Status |
|---|---|---|---|---|---|---|---|
| Primal wallet | wallet remote | `wallet`, `wallet_monitor_2` | Custodial/primal wallet | NWC / Breez (phase 10) | NWC yes | | REMOVE primal path |
| Membership name/status/products/purchase/cancel/history/monitor | app premium adapter (no-op); wallet module retained | `membership_*` / `wallet_membership_*` | Primal Premium | disabled in active app | no | | ACTIVE PATH REMOVED; wallet migration remains |
| Legend customization | local compatibility adapter | `membership_legend_customization` | Paid cosmetics | disabled | no | | REMOVED (no-op) |
| Contact list recovery | same | `membership_recovery_contact_lists` | Paid recovery | kind 3 backups on relays | partial | | REMOVE |
| Content rebroadcast | removed (`PremiumBroadcastApi`) | `membership_content_*` / `rebroadcasting_status` | Paid rebroadcast | unavailable/local no-op | no | | REMOVED |
| Leaderboards | | `membership_legends_leaderboard`, `membership_premium_leaderboard` | Social ranking | none | no | | REMOVE |
| Promo codes | | `promo_code_get_details`, `promo_codes_redeem` | Marketing | none | no | | REMOVE |

## Signer-specific cache

| Feature | Source | Verb | Purpose | Replacement | Standard Nostr? | Performance | Status |
|---|---|---|---|---|---|---|---|
| NIP-46 events | events API | `events_nip46` | Hydrate signer sessions | drop with NIP-46 | n/a | | REMOVE |

## UI/schema coupling (hidden dependency)

Cache responses inject Primal-only kinds/fields that Room and Compose currently expect:

- `PrimalPaging`, `PrimalEventStats`, `PrimalEventUserStats`
- `PrimalCdnResource`, `PrimalLinkPreview`, `PrimalRelayHint`
- `PrimalUserNames`, `PrimalLegendProfiles`, `PrimalPremiumInfo`
- `PrimalPollStats`

Relay-native feeds will not produce these. The first migrated screens must tolerate missing enrichment (progressive rendering, local derivation, empty stats) rather than assuming the cache payload shape.

## Counts by status (verbs + endpoints)

Approximate, verbs only (84):

| Status | Approx. count | Examples |
|---|---|---|
| REPLACE | ~30 | feeds, profiles, contacts, relays, threads, bookmarks, mutes |
| REDUCE | ~10 | search, notifications, polls, followers |
| REMOVE | ~35 | membership, explore, promo, primal wallet, push, client_config |
| INVESTIGATE | ~9 | DMs, live, DVM, zap-receipt mapping, highlights |

## Hidden string search (runtime)

Besides verbs, runtime hosts that must not remain as required infrastructure:

- `primal.net`
- `cache1.primal.net` / `cache.primal.net`
- `uploads.primal.net`
- `wallet.primal.net`
- `blossom.primal.net` (acceptable only as an optional default, not a hard dependency)
- `relay.primal.net` (acceptable as one default relay among others)

Do not delete every hit blindly. Tests, comments, and historical URLs in fixtures are not runtime dependencies.
