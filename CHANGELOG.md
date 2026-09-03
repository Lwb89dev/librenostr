# Changelog

All notable changes to LibreNostr are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
LibreNostr is a fork of [Primal](https://github.com/PrimalHQ/primal-android-app)
(MIT, Copyright (c) 2023 PRIMAL SYSTEMS INC.); this log covers changes made in
the LibreNostr fork on top of the imported `3.5.25` baseline.

## [0.2.2] - 2026-09-02

A privacy-preserving relay stopped handing back direct messages at all, and
thread replies rendered as one flat pile regardless of who was actually
answering whom.

### Fixed

- **A relay's NIP-42 challenge for direct messages was never answered.**
  Some relays require authentication before returning kind-4 events
  specifically, so nobody can read your DMs off them without proving who
  they are — a real relay-side privacy protection. LibreNostr's socket
  layer already had the wire message to answer that challenge, unused
  since the day it was written; nothing upstream ever called it, so every
  relay enforcing this policy silently returned nothing, forever, for
  every conversation whose only copy lived there. Every relay pool now
  answers a challenge with a signed event when it has a signer — only the
  account's own relay pool gets one, never the fallback pool or the
  wallet-connect pool, since authenticating on either would disclose more
  than that relationship calls for.
- **A reply to a reply rendered at the same rank as every other reply.**
  Everything after the opened note in a thread was sorted by timestamp
  alone, in two flat buckets, with no notion of who was actually replying
  to whom. A NIP-10 tag names a reply's parent exactly, and the thread
  screen was not using it. Replies are now walked into a proper tree from
  that tag, and the screen draws one vertical bar per level — a reply to a
  reply visibly nests under the comment it answers instead of sitting at
  the same rank as an unrelated reply that merely arrived nearby in time.

## [0.2.1] - 2026-09-02

Two follow-up fixes to the Follows/Requests split shipped in 0.2.0, found
by using the app rather than by reading the code.

### Fixed

- **A reply landing in the same relay page as the stranger's first message
  was still filed as a request.** The classification read who you had
  written to from the local database, and the page that just discovered
  the conversation had not been saved yet — persisting happens after
  classification runs. A conversation whose reply and first-seen message
  arrive together got judged before its own evidence existed, and nothing
  would ever revisit it: a quiet conversation's events do not reappear in
  a later sync once they fall outside its window. Fixed by folding the
  page's own messages into the same check, so a reply counts the moment
  it is seen instead of waiting for a future sync to notice it already
  happened.
- **A conversation misclassified before the fix existed stayed that way
  forever**, even carrying a reply the database had held all along. A
  stored conversation's relation only changed when its events reappeared
  in a fresh relay fetch — which an old, quiet conversation never does, so
  its very first classification became permanent. Every sync now sweeps
  every stored conversation against current local data once, correcting
  rows like this without needing anything back from the relays.

## [0.2.0] - 2026-09-02

A request coordinator so the app stops asking relays the same question
twice, and three bugs it turned up along the way: direct messages showing
raw npubs, profile tabs that never loaded, and a Requests/Follows split
that was not actually splitting anything.

### Added

- **A fetch coordinator sits between every repository and the relays.**
  Before this, each repository asked on its own and nothing knew what
  anything else had already requested. The active user's follow list was
  fetched independently by the note feed, the article feed, advanced
  search, explore and the profile screen — five requests for one kind-3
  event, all at once on app start. Profile metadata was worse: two screens
  showing the same author each asked for kind 0 separately, and the
  profile screen bypassed the existing cache entirely. A concurrent request
  for something already in flight now attaches to it instead of opening a
  second one; two screens asking for overlapping sets of authors or notes
  now share whatever overlaps and only ask separately for what does not.
  The follow list additionally gets a short time-to-live, since the
  screens that want it open seconds apart rather than at the same instant.

### Fixed

- **Feeds, notifications and DMs lost events past about four relays.** The
  incoming socket flow was unbuffered, so one slow collector blocked the
  read loop for every relay behind it, and a query could finish on the
  first EOSE while other relays still had events in flight. Buffered now,
  and a query waits for a quorum instead of a winner.
- **Direct messages showed a raw npub instead of a name for many
  conversations.** A relay hands back kind-4 events and nothing else, so
  the conversation list never asked for the participants' profiles — a
  name appeared only when another screen happened to have fetched that
  profile first. The people you have exchanged messages with are the last
  ones who should read as a hex string.
- **A profile's Notes and Replies tabs never loaded**, showing "unable to
  load content" every time. Somebody's own notes are a plain author filter
  a relay can answer directly, but the feed mediator only recognised
  following feeds and follow sets, so a profile tab fell through to the
  centralized API this build doesn't have. Dead since the relay migration,
  not introduced by this release.
- **The Requests and Follows message tabs showed the same conversations.**
  Every conversation was written under one relation regardless of which
  tab it was fetched for, and the list query ignored the column besides.
  A relation is now decided locally, the way Amethyst does it: a
  conversation counts as accepted if you follow the other person or you
  have written back to them, and everything else is a request. Answering
  somebody is what accepts them, so a request does not sit there forever.
- **Follow/unfollow loops filled the notifications tab.** A follow list is
  republished in full on every change, so an account cycling follow and
  unfollow emits a new event id each time; one account produced seven
  identical "followed you" rows inside a minute. Follows are now keyed by
  who did it and what day, and are grouped per day in the notifications
  list itself rather than only until the tab is opened.
- One failed profile-metadata request used to leave an author rendered as
  a raw npub for the rest of the session, because the request was marked
  done before anything came back. It is released and asked again now.

### Changed

- **A live note arriving no longer re-fetches the whole feed.** The
  refresh triggered by the live subscription asked for a full page across
  the entire follow list on every burst; it now asks the relays only for
  what is newer than the newest note already held, with an inclusive
  boundary so a note sharing a timestamp with it is not silently dropped.
- **Note interaction counts and DM-referenced profiles are shared through
  the coordinator.** Likes, replies, reposts and zaps for a note were
  re-fetched by every feed that displayed it; profiles referenced inside a
  conversation sat outside every existing dedupe. Both now coalesce with
  whatever else is already asking.
- Settings > Notifications gained a "Show new followers" switch, for
  turning follow notifications off entirely.

## [0.1.5] - 2026-09-01

Reliability with more than a handful of relays, a session that fetches before
you go looking, and a notifications tab that stops shouting.

### Added

- **A countdown before a note goes out.** Posting holds the note for a few
  seconds behind a countdown that can be tapped to call it off, because a note
  published to relays is effectively permanent. Configurable in
  Settings > Content display: a switch, and a slider from one to seven seconds.
  Replies go out immediately unless asked otherwise, since they are usually
  short and deliberate.
- **Notifications and direct messages are fetched at session start**, per
  account and cancelled on a switch. They used to be fetched only by their own
  paging mediators, which run when their tab is first shown, so the unread dot
  could not appear until you had already gone looking.
- **Older direct messages are pulled in on start.** The conversation request
  sent no limit and no `until`, so whatever a relay chose to return was the
  whole of local DM history and nothing would ever go back for the rest. It now
  walks backwards a few pages, stopping when a page comes back short.
- **A fourth onboarding screen** naming the two gestures that are otherwise
  undiscoverable: drag right from the middle of Home for the algorithm picker,
  drag left for the long-form reader.
- **Settings > Notifications > Show new followers**, to keep follows out of the
  notifications feed entirely. A follow is the one notification that carries
  nothing to read, and a bot loop can bury everything else.

### Fixed

- **Feeds, notifications and DMs lost events once the pool grew past about four
  relays.** The incoming socket flow was unbuffered, so a slow collector blocked
  the read loop for every relay behind it, and a query could finish on the first
  EOSE while other relays still had events in flight. The flow is buffered, the
  read loop no longer sleeps before EOSE, and a query now waits for a quorum
  rather than for whoever answers first.
- **Follow and unfollow loops filled the notifications tab.** A follow list is
  republished in full on every change, so accounts that follow and unfollow
  repeatedly emitted a new event id each cycle; keying rows by event id turned
  one account into seven identical rows inside a minute. Follows are keyed by
  who did it and on what day now.
- **Follows were grouped per day only until the tab was opened.** The seen feed
  is paged and mapped rows one to one, so marking everything seen brought every
  follow back as its own row. The grouping happens in the query now, where a
  page boundary cannot split a day, and the count is of people rather than of
  events.
- **One failed profile request left an author as a raw npub for the rest of the
  session.** Metadata requests were marked as done before knowing whether
  anything came back, and nothing would ask a second time.

### Changed

- **The default relay set was rebuilt by measurement.** Every candidate was
  asked for its NIP-11 document and then opened for a real REQ; the ones that
  answered with events and an EOSE on repeated attempts were kept.
  `relay.nostr.band` and `nostr.wine` were dropped from the defaults — the first
  answered nothing unauthenticated, the second requires payment and restricted
  writes; it is still offered during onboarding, unticked. `purplepag.es` moved
  to metadata-only, where it is unusually good and where it stops costing a
  round trip in note queries. Nothing is ticked by default: a pre-ticked list
  reads like an endorsement.
- **The event cache gained an in-memory hot layer and is now shared.** A note
  recurring across feed pages, a thread and a notification preview was read from
  SQLite and parsed from its raw JSON every time — about 378us per lookup of 40
  ids, against about 15us once hot. The cache was also constructed per
  repository and per paging mediator while being described as session-scoped, so
  every notifications tab started empty and re-asked the relays for authors the
  feed had already resolved. There is one instance now.
- The manual feed-refresh button is gone; the live subscription and the
  five-minute refresh underneath it make it redundant.

## [0.1.4] - 2026-09-01

Speed: fewer round trips, nothing re-downloaded, and a live subscription in
place of polling.

### Changed

- **New notes arrive over a live subscription instead of a 30-second poll.** The
  feed used to ask the relays for a fresh snapshot every thirty seconds whether
  or not anything had happened, so a new note appeared somewhere between
  instantly and half a minute late and the request went out either way. It now
  opens a live REQ scoped to the same authors as the feed, carrying only what is
  published from that moment on. The delay drops to about a second and nothing is
  sent while nothing happens. A five-minute refresh stays underneath, because a
  subscription can die quietly and a feed that silently stops updating is worse
  than one that updates late.
- **Events and profiles already in the database are no longer re-requested.** A
  thread's ancestors and the notes a notification points at are usually already
  stored by the feed; Nostr events are immutable and content-addressed, so a
  locally held id is the same event. Profile metadata is deduplicated per session
  rather than permanently, so a changed display name still comes through.
- **A full page no longer waits for the slowest relay.** Every query paid a grace
  period after the first EOSE and, when that EOSE carried no events, waited for
  the slowest relay up to the full timeout — even when the first relay had
  already delivered everything asked for. The early exit is gated on a *full*
  page, never a partial one, so a fast relay with a single event still cannot
  hide the rest of the network.
- **The follow list is no longer refetched before every page**, and author
  chunks are wider with more in flight, which brings the common case down to one
  sequential wave instead of two.

### Fixed

- Opening a reply from the notification list walked the ancestor chain one relay
  round trip at a time, up to five, then made three or four more in sequence.
  NIP-10 already names a reply's root and parent in its `e` tags, so the whole
  ancestor set fits in one filter: ten sequential round trips become three, and
  the first is the opened note together with its replies.
- A tagged user rendered as an ellipsized npub instead of the name they chose.
  The feed and thread fetchers requested metadata only for the authors of the
  events they loaded, never for the profiles mentioned inside them.
- Follow notifications were grouped under a single key that covered every follow
  the account had ever received. They are bucketed by day now.

## [0.1.3] - 2026-08-31

Notifications, Reads scoping, highlights and external-signer permissions.

### Fixed

- **Notifications were slow and returned a truncated page.** A Nostr filter takes
  a list of kinds, so one REQ is enough; the fetcher issued five — replies,
  reposts, reactions, zaps, follow lists — and each fanned out to both relay
  pools, so opening the tab cost ten pool queries with their own EOSE grace and
  timeouts. The split also truncated the result: every kind got the full `limit`
  independently and the union was cut back to `limit`, so a page was whichever
  kind happened to be busiest and the rest fell off the end. Each tab now
  requests only the kinds it can display, and referenced notes and actor
  metadata are fetched in parallel instead of chained.
- **Notification paging stopped after one page** on sparse tabs, because the end
  of the list was decided by the group-filtered row count rather than by what the
  relays returned.
- **Zaps were credited to the wrong person.** A NIP-57 receipt is signed by the
  recipient's LNURL server, not by the zapper; the sender is the author of the
  kind 9734 request embedded in the `description` tag.
- **Long-form Reads pulled from the global firehose.** The author list was passed
  as "no constraint" when empty, which happened for topic feeds, search feeds and
  any unrecognised spec — and the public long-form firehose is mostly spam. Every
  query is now scoped to an explicit author set: the user's follows, widened once
  to the follows of those follows when follows alone cannot fill a page, capped
  at 1000 authors because relays reject very large filter arrays. When no scope
  can be resolved the feed returns empty instead of falling back to global.
- **Topic Reads queried the wrong tag**, putting the hashtag in `#e` (event ids)
  instead of `#t`.
- **NIP-84 highlights never loaded.** The article fetch and the highlights fetch
  ran sequentially inside one `try` that caught only `NetworkException`, so any
  failure of the first skipped the second. They now run in parallel and each
  handles its own failure.
- **Highlights could not be signed by an external signer.** The notary gated
  signer requests on a kind allowlist that omitted 9802, so a highlight was
  rejected locally and Amber was never asked; polls, reports and stream mute
  lists were blocked the same way. Separately, the NIP-55 connect request asked
  for `sign_event` on kind 1 only, so every other kind prompted on each use. Both
  now derive from a single list, and the connect request also asks for nip44
  encrypt/decrypt and decrypt_zap_event.

## [0.1.2] - 2026-08-31

De-Googled build, Primal Premium removed, and image metadata stripped before
upload.

> **This release is signed with a new key.** The previous certificate carried a
> personal name in its subject; it has been retired in favour of a pseudonymous
> one (`CN=Lwb89dev, O=LibreNostr`). Android refuses to upgrade an installed app
> across a signing-key change, so **0.1.0 and 0.1.1 must be uninstalled before
> installing 0.1.2**. Uninstalling clears local app data, including any key
> stored on the device — back up your nsec first. The 0.1.0 and 0.1.1 APK assets
> have been withdrawn.

### Added

- Image metadata is stripped before an upload leaves the device. A photo from a
  camera carries EXIF with GPS coordinates, capture time, device make/model and
  often the owner's name; all of it was previously published to the Blossom
  server alongside the picture. JPEG loses APP1 (Exif/XMP), APP13
  (Photoshop/IPTC) and COM; PNG loses eXIf and the textual and tIME chunks;
  WebP loses EXIF and XMP. Colour and rendering segments are kept, pixel data is
  copied verbatim so there is no re-encoding, and video streams through
  untouched.
- A long-form reads destination with its own navigation glyph.

### Removed

- **Google.** The `google` product flavor and everything that fed it: Play
  Billing, ML Kit barcode scanning, the Cronet player, the FCM token updater,
  the google-services and play-publishing Gradle plugins, the `playStore`
  signing config and `playRelease` build type. There is now a single build.
- **Google Play Services**, which survived the flavor removal because it entered
  transitively through the Breez Spark SDK's dependency on
  `androidx.credentials:credentials-play-services-auth`. The Spark wallet
  backend was already returning a disabled service and discarding its
  collaborators, so it cost 17.8 MB of native code per ABI and the whole
  play-services auth/fido stack for no runtime behaviour. NWC remains the only
  wallet transport; `Wallet.Spark` stays so the Room migrations keep resolving.
- **Primal Premium**: Legend/OG tiers, primal names, leaderboards, content
  rebroadcast, media management and the in-app purchase flow — 138 files and
  14,742 lines. The Legend avatar glow, coloured verification badge and profile
  premium badge go with it; the plain verified badge and live-stream ring stay.
  None of it could function without Primal's servers.

### Changed

- The release workflow was still upstream's: it filtered `ios-*` tags, ran PR
  checks on macOS runners inherited from a repository that also built an iOS
  XCFramework, published an AAB to Google Play, built a second APK for a crash
  reporter whose upload is a no-op, collapsed the three ABI splits onto one
  `primal-<tag>.apk` and opened a draft release called "Primal". Both workflows
  also decoded absent google-services secrets over committed files, which is why
  every tagged run failed with "Malformed root json". They now build the ABI
  splits, refuse to publish debug-signed APKs and take their body from this file.
- Highlights are fetched relay-only; the repository no longer takes a cache client.

### Fixed

- Two test fixtures left behind by the relay-only migration: the app-config
  handler test still asserted that well-known discovery reached the store, and
  the tags test still expected the Primal relay default.

Release APK: 78.6 MB at 0.1.1, 55.2 MB now.

## [0.1.1] - 2026-08-31

Localization, unread badges and feed ordering, plus the fixes from the
2026-08-31 audit ([`docs/SECURITY_AUDIT_2026-08-31.md`](docs/SECURITY_AUDIT_2026-08-31.md)).

### Added

- In-app language selection with 26 translations (Bulgarian, Croatian, Czech,
  Danish, Dutch, Estonian, Finnish, French, German, Greek, Hungarian, Irish,
  Italian, Japanese, Latvian, Lithuanian, Maltese, Polish, Portuguese,
  Romanian, Russian, Slovak, Slovenian, Spanish, Swedish, Chinese) and an
  Android `localeConfig`.
- Unread badges for messages and notifications, computed from the local
  database instead of a remote counter.
- A "mark all as read" action in the notifications list, and local
  mark-all-as-read for direct message conversations.
- A new-notes indicator on the home tab, and an optional automatic feed
  refresh when the app returns to the foreground.
- `wss://nostr.wine` in the fallback relay set.

### Changed

- Feeds are ordered by event timestamp rather than insertion position, so a
  relay reconnect can no longer shuffle the timeline. Notification ordering
  gained a stable tie-break on notification id.
- Interaction counters are resolved from relays for reposts and for the notes
  in an opened thread, and the feed is invalidated once they arrive so visible
  cards redraw without navigating away.
- Notification previews now fetch the events their `e` tag references, so
  likes, zaps and reposts render an actual note body.
- Paging loads 50 notes initially and 20 per subsequent page.
- A relay query whose first EOSE carries no events now waits for the remaining
  relays instead of returning empty.
- Added `avif`, `svg` and `ico` to the recognized media types.

### Security

- Direct-message and mute-list queries are no longer broadcast to the hardcoded
  public fallback relays when the account has its own relays configured.
  Previously, opening the messages tab disclosed the user's pubkey and reading
  activity to seven third-party relay operators regardless of configuration.
- An empty relay snapshot no longer clears the cached feed. Offline or slow
  relays used to wipe the local timeline on every foreground, leaving nothing
  to fall back on.
- Relay connection status is now cleared when a relay closes the socket. The
  teardown path cancelled its own coroutine, so the closed callback never ran
  and dead relays kept reporting as connected.
- Replaced two uses of `kotlin.runCatching` with the project's
  cancellation-safe `runCatching`, which no longer converts coroutine
  cancellation into a logged failure.

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

[0.2.2]: https://github.com/Lwb89dev/librenostr/releases/tag/v0.2.2
[0.2.1]: https://github.com/Lwb89dev/librenostr/releases/tag/v0.2.1
[0.2.0]: https://github.com/Lwb89dev/librenostr/releases/tag/v0.2.0
[0.1.5]: https://github.com/Lwb89dev/librenostr/releases/tag/v0.1.5
[0.1.4]: https://github.com/Lwb89dev/librenostr/releases/tag/v0.1.4
[0.1.3]: https://github.com/Lwb89dev/librenostr/releases/tag/v0.1.3
[0.1.2]: https://github.com/Lwb89dev/librenostr/releases/tag/v0.1.2
[0.1.1]: https://github.com/Lwb89dev/librenostr/releases/tag/v0.1.1
[0.1.0]: https://github.com/Lwb89dev/librenostr/releases/tag/v0.1.0
