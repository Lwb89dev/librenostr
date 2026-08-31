# Relay-only migration status

This document tracks the migration from Primal-hosted APIs to native Nostr relay
queries/subscriptions. The percentage measures production data flows, not the
number of files changed.

## Current status

**Overall: 100% of active Android data paths**

| Block | Scope | Status | Weight |
| --- | --- | --- | ---: |
| 1 | Remove cache dependency from the active Blossom suggestions/upload setup | Complete | 5% |
| 2 | Relay-first account follow-list loading, with local-only offline fallback | Complete | 5% |
| 3 | Profiles and social graph (metadata, followers, following, search) | Complete for active profile/search paths; relay-derived ranking | 10% |
| 4 | Home feeds, algorithms and thread loading | Complete on the active Android path: notes and Reads feed/detail/highlights loading are relay-native, with no Primal Articles API in the active graph | 20% |
| 5 | Notifications and unread state | Complete for the active app path: derived from relay events; local unread state | 15% |
| 6 | Direct messages, NIP-46/NIP-47 and live streams | Complete on the active path: relay-native reads/subscriptions and local read state; centralized push transport removed | 15% |
| 7 | Interactions, event stats, polls, bookmarks and Explore/search | Complete on the active path: relay-derived data and bounded local ranking; follow-pack remote paging removed | 15% |
| 8 | Wallet/Premium removal and Primal endpoint cleanup | Complete for the active path: NWC and external Lightning wallets only; legacy centralized/embedded wallet services fail closed | 15% |

## Completed in this iteration

- Media upload server suggestions are local (`blossom.band` and
  `cdn.satellite.earth`); the settings screen no longer queries the Primal
  cache for recommendations.
- The unused Primal upload-client binding was removed from the app graph.
- Follow-list refresh uses the user's kind-3 event from relays and falls back
  only to the last locally persisted account value.
- Profile followers/following/followed-by and follow-state are derived from
  kind-3 relay events; metadata is fetched from kind-0 relay events.
- Profile NIP-05 and Lightning-address edits read existing metadata from local
  storage or relays before publishing the updated kind-0 event.
- Profile search queries relay kind-0 metadata and filters locally; it no longer
  falls back to the centralized search API when the relay manager is available.
- Notifications are now derived from standard relay events (kind 1 replies and
  mentions, kind 3 follows, kind 6 reposts, kind 7 reactions and kind 9735
  zap receipts). The active Android dependency graph injects the relay querier;
  notification read state is persisted locally and is never acknowledged via
  Primal AUTH endpoints.
- Direct messages now read NIP-04 kind-4 events directly from relays in both
  directions (sent and received), with local deduplication, chronological
  ordering and existing paging preserved. Conversation summaries are derived
  from the relay events when the centralized summary is unavailable; marking
  messages read is local-only on this path.
- Notes and profile metadata referenced inside decrypted DMs are also fetched
  directly by event id/kind-0 relay queries; the DM processor no longer calls
  the Primal feed/users APIs for link previews.
- Explore popular users is now computed from relay kind-3 follow lists and
  kind-0 metadata, with the ranked profiles persisted locally; it no longer
  calls the centralized popular-users endpoint when the relay manager is active.
- Explore profile search, popular people, trending topics and trending zaps now
  use relay kind-0/kind-1/kind-3/kind-9735 queries and local scoring. NIP-50 is
  used when available, with a bounded relay-only metadata fallback.
- Explore trending topics are now computed from recent kind-1/kind-30023 relay
  events by counting their hashtags over a seven-day window; the result is
  persisted locally instead of being requested from the Primal endpoint.
- Note reaction (kind 7) and repost (kind 6) action lists are now queried from
  relays, with the reacting authors' kind-0 metadata loaded and persisted
  locally. The UI no longer needs Primal's ranked event-actions response; the
  old score is treated as zero because relay events do not provide a central
  ranking score.
- Replaceable events (long-form articles and live activities) are now resolved
  from relay kind/authors filters, matched locally by their `d` identifier and
  persisted through the existing article/stream DAOs. No centralized
  `ReplaceableEventResponse` is requested on the active Android path.
- Zap receipt lists now query relay kind 9735 events, decode the embedded zap
  request, load sender metadata from kind 0 and reuse the local EventZap paging
  index. The zap RemoteMediator and one-shot zap fetch no longer call the
  centralized event-zaps endpoint on Android.
- Standalone reaction/repost lists and replaceable-event hydration are now
  relay-only. Invoice-to-zap enrichment resolves NIP-57 receipts with a
  standard `#bolt11` relay filter and local invoice matching; no event-stats
  cache API is involved.
- Poll definitions (kind 1068/6969), regular votes (kind 1018) and zap-poll
  receipts (kind 9735) are now loaded from relays. Vote totals and sats totals
  are derived locally from the events, and voter metadata comes from relay kind
  0 events; the poll voter pager uses the same relay snapshot without the
  centralized poll-votes endpoint.
- Live-stream lookup (`findLiveStream`) and the follows snapshot now query
  kind-30311 events directly from relays. The snapshot first resolves the
  account's kind-3 follow list, then matches current live activities locally;
  chat/zap live subscriptions also use relay socket subscriptions with
  reconnect-safe per-relay subscriptions.
- NIP-46 and NIP-47 event lookups now query the configured relays by event id
  and protocol kind; the active handlers have no centralized event fallback.
- Public bookmark lists now read the user's kind-10003 event directly from
  relays. An empty relay result is treated as an empty bookmark set; the old
  users endpoint is used only when the relay query itself fails.
- Mute lists now use standard relay events: kind 10000 for muted pubkeys,
  hashtags and threads, kind 10555 for stream mutes and kind 30000 for the
  followed mute list. Referenced profile metadata is resolved from relay kind-0
  events and persisted locally; the Settings API is retained only as a
  compatibility fallback when no relay querier is available.
- Event URI loading, event interactions and relay-hint lookup no longer carry a
  Primal cache client through their factories or Android dependency graph;
  these paths are local persistence and relay-publisher operations only.
- NIP-46 and NIP-47 event handlers now require the configured relay querier and
  resolve protocol events exclusively by relay filters; the legacy
  `EventStatsApi` fallback and its cache-client DI bindings were removed.
- The active settings repository is now local-only: unused Primal settings
  fetch/publish methods and the Android `SettingsApi` cache binding were
  removed. Settings synchronization over Nostr remains a separate future block.
- GIF discovery/download is no longer created through `PrimalApiServiceFactory`:
  the Wikimedia Commons client has its own HTTP factory and the picker talks
  directly to Commons for thumbnails and media.
- Profile-name resolution no longer calls `primal.net/.well-known`: metadata
  `kind 0` events are queried from configured relays and matched locally by
  `name` or NIP-05 local-part. The obsolete well-known API and DI module were
  removed.
- Default note and read-feed definitions are now application-owned LibreNostr specs;
  onboarding and feed restoration no longer request `get_default_app_subsettings`
  from the Primal cache. The `Latest` and `Latest with replies` specs are
  available immediately from local code, including on a fresh account; the
  Reads screen receives a local `Latest reads` seed and loads its pages from
  relay `kind:30023` events.
- Recommended DVM feeds are discovered from relay kind-31990 AppHandler events.
  Their metadata and recommendation order are persisted locally; the centralized
  `get_featured_dvm_feeds` API, request/response models, Primal DVM metadata and
  featured-user ranking mapper were removed from the active repository graph.
- User-feed synchronization no longer exposes Primal `get_app_subsettings` or
  `set_app_subsettings`: feed edits persist locally, and the obsolete remote
  API methods, payloads and settings-key identifiers were removed.
- Advanced-search command parsing is now local and deterministic. Editing an
  advanced feed no longer calls `parse_advanced_search_query`; the remote
  endpoint, response payloads, mapper and verb were removed.
- Advanced-search feed execution is relay-first as well: `advsearch` specs are
  mapped to NIP-50 search, kind/author/tag/time filters and local positive or
  negative text matching in the paging mediator. It no longer falls back to
  `get_multi_kind_feed_by_spec` when a relay manager is available.
- Reads/article feed pagination is relay-first: `kind:30023` events are queried
  from configured relays using follow lists, follow sets, topics and
  advanced-search terms, with temporal cursors and kind-0 author metadata. The
  existing Room paging/processing layer is reused. The active Android graph no
  longer creates or injects the Primal Articles API for this path.
- Reads article details are relay-first as well: the article is resolved by its
  NIP-23 address and comments by NIP-22 `#a`/`#e` references; author metadata
  comes from relay `kind:0` events and the existing local persistence path is
  reused. Article details and comments no longer have a centralized API
  fallback in the active repository path.
- Article highlights are relay-first: NIP-84 `kind:9802` events are loaded by
  the article address, with highlight replies and zap receipts queried by their
  event tags; profile metadata is resolved from relay `kind:0` events and the
  existing highlights processor persists everything locally.
- The Android Reads dependency graph now requires the configured relay querier
  for feed, detail, comments and highlights loading; `ArticlesApi` is no longer
  constructed or passed through the active repository factories.
- The obsolete Primal Articles API implementation, request payloads and unused
  article-feed response models were removed from the remote module; relay
  fetchers retain only the response models needed by local processors.
- Note counters are now derived from relay interaction events as each feed page
  is persisted: kind 7 reactions, kind 1 replies, kind 6 reposts and kind 9735
  zap receipts are aggregated into the local `EventStats`/`EventUserStats` tables.
  The UI therefore observes relay-derived counts instead of Primal's synthetic
  `PrimalEventStats` payload.
- The obsolete `EventStatsApi` layer and its Primal request/response DTOs were
  removed from the remote module; the Android event repository and zap pager
  require a relay querier.
- Premium content rebroadcast no longer signs or sends membership requests: the
  old broadcast API and verbs were removed, and the compatibility repository
  reports the feature as unavailable locally. The content-backup screen no
  longer owns a Primal cache client or a centralized status subscription.
- Premium membership/leaderboard and paid media-management adapters are now
  local compatibility implementations (empty/disabled results); their Android
  providers no longer receive either Primal cache or wallet clients.
- Event import now persists locally when invoked by compatibility code, while
  centralized broadcast/import transport is disabled; publishing is performed
  through the relay publisher. Proprietary `primal://` and `primalconnect://`
  deep links are no longer generated or accepted.
- Network settings now expose only user-configured Nostr relays; the former
  caching-service editor, defaults and preview were removed.
- Wallet payment paths no longer instantiate a legacy Primal wallet service or
  centralized NWC provisioning API. BOLT11 parsing is local, LNURL metadata is
  fetched from its provider, and Android invoice actions use the external
  `lightning:` wallet intent. NWC remains the only enabled in-app wallet
  transport; legacy Primal and Spark/Breez operations return an explicit
  unsupported result.
- `:app:compileDebugKotlin` passes.

## Important distinction

The local Room/DataStore databases are device-local caches and are not Primal
services. Blossom servers are HTTP media hosts, needed for file transport; they
are tracked separately from Nostr event transport. Nostr data must ultimately
come from relay queries/subscriptions or local persistence.

## Compatibility boundary

Legacy cache, wallet and premium interfaces remain compiled for migrations and
optional UI compatibility, but the Android providers inject a fail-fast
no-network client. Follow-pack remote paging and FCM push registration were
removed. See `docs/SECURITY_AUDIT.md` for the security evidence and the exact
scope of historical fixtures/license attribution that remains.
