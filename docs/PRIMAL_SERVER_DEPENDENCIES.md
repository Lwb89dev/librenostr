# Primal server dependency inventory

Every `PrimalVerb` currently sent to Primal cache/wallet servers, plus related HTTP well-known endpoints.

Source of verbs: `data/caching/remote/src/commonMain/kotlin/net/primal/data/remote/PrimalVerb.kt` (**84 verbs**).

Transport:

- WebSocket `REQ` with a Primal filter object (`primalVerb` + options JSON) via `PrimalApiClient` / `BasePrimalApiClient`.
- Default cache URL: `wss://cache1.primal.net/v1` (`core/app-config`).
- Mapped HTTP API: `https://cache1.primal.net/api`.
- Dynamic discovery: `GET https://primal.net/.well-known/primal-endpoints.json`.
- Wallet socket: `wss://wallet.primal.net/v1`.
- Upload socket: `wss://uploads.primal.net/v1`.
- REST helper: `data/caching/remote/.../PrimalApiServiceFactory` uses `https://primal.net/`.

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
| Article thread | `ArticlesApiImpl` | `long_form_content_thread_view` | Kind 30023 + comments | NIP-23 + NIP-22 | partial | same | REPLACE |
| Highlights | `ArticlesApiImpl` | `get_highlights` | Article highlights | kind 9802 if used | partial | sparse | INVESTIGATE |
| Event fetch | events API | `events` | Hydrate ids with enrichment | REQ ids | yes for events; no for stats/CDN | extra round-trips for metadata | REPLACE |
| Replaceable events | events API | `replaceable_event`, `parametrized_replaceable_event(s)` | Latest kind 0/3/10002/30000… | standard replaceable REQ | yes | need NIP-65 hints | REPLACE |
| Import published event | `PrimalImportApi` / `NostrPublisher` | `import_events` | Push event into Primal cache so UI sees it immediately | write Room locally; relays already got EVENT | n/a | local write is enough | REMOVE after local persist |
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
| User relays | same | `get_user_relays_2` | Relay list | kind 10002 NIP-65 | yes | | REPLACE (LN-011: current-user path is relay-first; cache fallback; batch still cache) |
| Default relays | same | `get_default_relays` | Primal-recommended relays | LibreNostr static defaults + user config | n/a | n/a | REPLACE (LN-011: `FALLBACK_RELAYS` used for bootstrap/onboarding) |
| Bookmarks | same | `get_bookmarks` | Bookmark list | kind 10003 / 30001 | yes | | REPLACE |
| Mutes | `SettingsApiImpl` | `mutelist`, `mutelists` | Mute lists | kind 10000 / 30000 | yes | | REPLACE |

## Search and explore

| Feature | Source | Verb | Purpose | Replacement | Standard Nostr? | Performance | Status |
|---|---|---|---|---|---|---|---|
| Profile search | `ExploreApiImpl` | `user_search` | Search people | NIP-50 + local DB | partial | depends on NIP-50 relays | REDUCE |
| Parse advanced search | `FeedsApiImpl` | `parse_advanced_search_query` | Primal query language | local parser or drop | no | n/a | REMOVE / REDUCE |
| Explore people | `ExploreApiImpl` | `explore_people` | Trending people | none | no | n/a | REMOVE |
| Explore topics | same | `explore_topics` | Trending hashtags | none | no | n/a | REMOVE |
| Explore zaps | same | `explore_zaps` | Trending zaps | none | no | n/a | REMOVE |
| Recommended users | same | `get_recommended_users` | Onboarding suggestions | none / user-provided npubs | no | n/a | REMOVE |
| Follow lists | same | `follow_lists`, `follow_list` | Follow packs | kind 30000 if we keep packs | partial | | INVESTIGATE then REDUCE |
| Featured DVM feeds | `FeedsApiImpl` | `get_featured_dvm_feeds` | Curated DVMs | NIP-89 discovery later | partial | | REMOVE featured list |
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
| Event zaps | `EventStatsApiImpl` | `event_zaps_by_satszapped` | Ranked zap list | kind 9735 REQ | partial (no ranking guarantee) | | REPLACE |
| Event actions | same | `event_actions` | Who liked/reposted | kinds 7 / 6 | partial | | REPLACE |
| Poll votes | `PollsApiImpl` | `poll_votes` | Aggregated poll stats | NIP-69 responses | partial | | REDUCE |
| Invoice → zap receipt | events API | `invoices_to_zap_receipts` | Map BOLT11 to kind 9735 | local matching | partial | | INVESTIGATE |
| Live feed | `LiveStreamApiImpl` | `live_feed` | Live chat/stream | kind 30311 / 1311 | partial | | INVESTIGATE |
| Live from follows | same | `live_events_from_follows` | Live list | REQ kind 30311 authors | partial | | INVESTIGATE |
| Find live | same | `find_live_events` | Lookup | same | partial | | INVESTIGATE |

## Settings, client config, media upload

| Feature | Source | Verb | Purpose | Replacement | Standard Nostr? | Performance | Status |
|---|---|---|---|---|---|---|---|
| App settings get/set | `SettingsApiImpl` | `get_app_settings`, `set_app_settings`, `get_default_app_settings` | Primal-synced settings | local DataStore; optional kind 30078 | partial | | REPLACE local |
| Client config | | `client_config` | Feature flags from Primal | static / local | no | | REMOVE |
| Upload chunk/complete | | `upload_chunk`, `upload_complete` | Primal upload API | blossom | blossom yes | | REPLACE |
| Membership media stats/uploads/delete | | `membership_media_management_*` | Paid media hosting | user blossom | no | | REMOVE |

## Wallet and membership

| Feature | Source | Verb | Purpose | Replacement | Standard Nostr? | Performance | Status |
|---|---|---|---|---|---|---|---|
| Primal wallet | wallet remote | `wallet`, `wallet_monitor_2` | Custodial/primal wallet | NWC / Breez (phase 10) | NWC yes | | REMOVE primal path |
| Membership name/status/products/purchase/cancel/history/monitor | wallet + premium | `membership_*` / `wallet_membership_*` | Primal Premium | none | no | | REMOVE |
| Legend customization | same | `membership_legend_customization` | Paid cosmetics | none | no | | REMOVE |
| Contact list recovery | same | `membership_recovery_contact_lists` | Paid recovery | kind 3 backups on relays | partial | | REMOVE |
| Content rebroadcast | `PremiumBroadcastApi` | `membership_content_*` / `rebroadcasting_status` | Paid rebroadcast | user republish to relays | partial | | REMOVE |
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
