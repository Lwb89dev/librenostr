# Changelog

All notable changes to LibreNostr are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
LibreNostr is a fork of [Primal](https://github.com/PrimalHQ/primal-android-app)
(MIT, Copyright (c) 2023 PRIMAL SYSTEMS INC.); this log covers changes made in
the LibreNostr fork on top of the imported `3.5.25` baseline.

## [0.5.6] - 2026-09-10

### Fixed

- The recipient of a NIP-17 direct message or private reply now actually receives it. Relay
  resolution was asymmetric between the two sides of a send: a sender resolving a remote
  recipient with no kind-10050 fell back to the public bootstrap pool, while that recipient
  polling their own inbox stopped at their own configured relays and never checked the bootstrap
  pool at all — the two sides agreed on nothing, so the wrap was delivered exactly where nobody
  was listening. Inbox polling now reads the union of every relay a sender could plausibly have
  used, and an account's own kind-10050 is announced on first poll rather than only after a send.
- The Messages badge unread count is no longer hardcoded to zero. It is now derived from what
  actually arrived past the last known message, and opening a conversation clears it locally.
- A private reply notification now shows the lock indicator and opens the thread it answers when
  tapped, instead of missing both because the notification screen built its note preview by hand
  and dropped the fields that carry them.
- Private-reply sender profiles are now cached the same way direct-message senders already were,
  so a private-reply notification shows the sender's name instead of a raw npub.

## [0.5.5] - 2026-09-10

### Fixed

- Private replies sent over NIP-17 now appear in their own thread instead of being sorted above
  the conversation root: the thread's topological sort reads the gift-wrapped reply's normalized
  root/parent relationship directly, not only public NIP-10 tags, which a private reply never
  carries.
- The recipient of a private reply now gets a local notification for it — nothing else can, since
  the reply exists only inside an encrypted Gift Wrap and no relay ever serves one. Tapping the
  notification opens the thread the reply belongs to.
- The sent-reply lock indicator on a private reply's note card is now an icon and label together,
  matching Amethyst/Damus, instead of label text alone.
- NIP-17 relay resolution for both direct messages and private replies now follows the recipient's
  kind-10050 inbox, then their NIP-65 read relays, then a bootstrap pool, mirroring Amethyst's own
  fallback policy. Previously a missing kind-10050 could make an otherwise deliverable message fail
  outright.
- A legacy NIP-04 direct message (the fallback used when a contact has no kind-10050) is now also
  published to the recipient's own relays, not only the sender's write relays, so accounts with
  disjoint relay sets can actually reach each other.
- A private reply naming only a thread root (a NIP-10-legal shape, and the one Amethyst produces
  most often) is no longer silently dropped; a duplicate or malformed marker no longer discards an
  otherwise valid reply either.
- Announcing an account's own NIP-17 DM relay list no longer runs before every send and can no
  longer make an otherwise deliverable message fail; it is now a best-effort step after delivery.

## [0.5.4] - 2026-09-09

### Fixed

- Private replies are now invoked directly from a thread's reply toolbar via a lock icon, instead
  of being hidden in a note overflow menu. The recipient picker accepts either a searched username
  or an `npub`; only that selected account can decrypt the resulting NIP-17 reply.
- Normal thread replies remain public by default. Private delivery is enabled only after the lock
  action and recipient selection, and can be switched back off before publishing.
- NIP-17 conversations now continue to refresh and send through the compatible legacy path when a
  contact has not yet published a kind-10050 DM relay list, rather than failing the whole inbox.

## [0.5.3] - 2026-09-09

### Added

- Modern private messages now use the NIP-17 pipeline: NIP-44 encryption, NIP-59 seals and Gift
  Wraps, recipient kind-10050 DM relay discovery, a sender copy, and a central inbox subscription.
  Newly sent messages no longer create legacy NIP-04 events.
- Public threads support private replies to either a public note or an already-private reply. The
  encrypted root and parent markers are normalized into the existing thread tree only after
  decryption, persisted in encrypted local storage, and shown to authorized users with a small
  private badge. Gift Wraps reveal neither the thread relationship nor the plaintext to relays.

### Fixed

- Scrolling the Home feed now moves the complete LibreNostr header off-screen and gives its full
  height back to the timeline; scrolling in the opposite direction restores it.
- The center compose (`+`) action remains attached to the bottom navigation while the Home header
  is collapsed.
- Zap notifications now display the amount for every supported zap-notification grouping.
- Video attachments can decode and display an actual preview frame when no explicit thumbnail is
  supplied, instead of leaving a flat grey placeholder.
- Setting descriptions use readable foreground colours across the Dark Pixel, Dark Green Pixel,
  Dark Fire, Light Pixel, Green Pixel, and Fire Light themes.
- The expanded Tor settings section no longer leaves an oversized black area below its content.

## [0.5.2] - 2026-09-09

### Fixed

- A thread with an unusually long chain of nested replies could crash the app outright — the
  code that lays out the reply tree walked one level at a time recursively, and a deep enough
  chain overflowed the call stack. Rewritten to walk iteratively instead, with no protocol-level
  depth limit.
- Deeply nested replies no longer push the note itself further and further off the edge of the
  screen — indentation now caps out after a handful of levels while the actual nesting is still
  tracked correctly underneath.
- A reply whose parent note this app doesn't have used to render at the same visual weight as a
  confirmed direct reply to what you opened, with nothing distinguishing the two. It now draws
  with a fainter connector instead, so it's still there — never hidden — but doesn't read as more
  certain than it actually is.
- Reply ordering could be unstable when two replies shared the exact same timestamp (common when
  several relays hand back events for the same second); ties now resolve the same way every time.

## [0.5.1] - 2026-09-08

### Fixed

- Typing `@name` to mention someone could take several seconds before any
  result appeared. The search first tries a relay-side NIP-50 query, then
  fell back to a second, unfiltered query only if that failed — but the two
  ran strictly one after the other, so on a relay pool without NIP-50
  support (the common case here) every search paid both queries' full
  timeouts back to back. They now run at the same time; the fallback is
  cancelled the moment the NIP-50 query alone comes back with something, so
  a relay that does support NIP-50 is unaffected.
- A note that quoted or mentioned another note sometimes showed "Mentioned
  event not found" even though the referenced note existed and rendered
  fine elsewhere (the home feed, an opened thread). Search results,
  notifications, and article comments never fetched the notes a note
  quoted, only the note itself — now they do, matching the feed and
  thread screens. The "not found" card also has a working retry button
  now, instead of being a dead end after a single failed attempt.
- Some quoted or mentioned notes rendered as raw `nostr:nevent1…` text
  instead of a card — a reference that matched the expected format but
  failed to decode (a corrupted copy-paste, a checksum another client
  mangled) used to be silently dropped with no trace, leaving the literal
  text on screen forever. It's now shown as an unresolved-reference card
  like any other note that can't be found, instead of leaking as text.
- Video attachments almost never showed a preview frame, even when the
  note carried one. The video's own metadata tag can include a preview
  image, but it was never read — only Primal's own (now-unused) preview
  service was checked, so it fell back to nothing.
- The app sometimes loaded only your own notes and only your own
  notifications for a while after a cold start — notifications now retry
  a genuinely empty first response instead of treating it as "no
  notifications, ever," matching a fix already in place for the note feed.
- Zap notifications showed the sats-zapped amount only for a direct zap on
  your own post — a zap on a post you were mentioned in, or on a post that
  mentioned one of yours, showed the zap icon with no amount at all.
- The feed could visibly jump mid-scroll as more notes loaded. A background
  refresh of like/repost/zap counts for the newly-loaded page could land
  after you'd already scrolled further, and the resulting reflow used the
  scroll position's on-screen index rather than the note actually under
  it — turning off index-based placeholders in favor of per-note identity
  fixes the reflow to track the right note instead of the wrong index.
- On the Dark Pixel theme, the follow/unfollow button on a profile could be
  partly hidden behind the avatar once you already followed that person —
  the button's monospace font ran wider than the space reserved for it.

### Removed

- On-device note translation. It added a large, always-on cost to every
  build (native toolchain, a ~70-language detection model) for a feature
  that saw essentially no use. The code isn't gone, just disconnected from
  the build, in case it's worth reviving in a more selective form later.

## [0.5.0] - 2026-09-07

### Added

- **Tor support via Orbot**: a new "Tor" entry in Settings routes relay
  connections, zap/lightning requests, media uploads, image loading, media
  downloads, video/audio playback, and embedded link previews through
  Orbot's SOCKS proxy instead of connecting directly. Off by default; the
  SOCKS port is configurable (defaults to Orbot's own default, 9050). If
  Orbot isn't detected as installed, the settings screen says so plainly.
  There is no fallback to a direct connection if the proxy isn't reachable
  — connections fail instead of silently leaking outside Tor. The setting
  takes effect on the next full restart, not immediately, since every
  network client in the app is a long-lived singleton built once at
  startup; the app is explicit about this rather than pretending otherwise.
  A new onboarding step (shown once, for both new and existing accounts)
  explains the feature and links to Orbot on Zapstore.

### Fixed

- Tapping the zap button could crash the app outright. The zap sheet's
  amount-preset grid was a `LazyVerticalGrid` sitting inside a `Column`
  that scrolls to make room for the keyboard — a lazy grid inside a
  scrollable container with no bounded height is disallowed and threw
  immediately. Replaced with a plain, non-lazy grid; the preset count is
  small and fixed, so there was never anything to virtualize.

## [0.4.0] - 2026-09-06

### Added

- A redesigned home header — LibreNostr wordmark and tagline, an integrated
  search bar, a swipeable avatar — and a floating, pill-shaped navigation
  dock, replacing the previous Primal-styled top bar and bottom navigation.
  The biggest visual departure from Primal yet.
- Two more themes, Green Pixel and Dark Green Pixel, joining the existing
  six; the theme picker is now a 4×2 grid instead of 3×3 with a gap.
- A second button next to the publish countdown's "Don't send" — "I've read
  it, send now" — for skipping the rest of the wait once you've actually
  reviewed the note, translated into every supported language.
- Zap notifications now show the sat amount as its own bold, colored badge
  instead of leaving it buried inside the sentence text.

### Changed

- The navigation dock no longer has a separate account button — tapping the
  avatar already in the home header does the same thing, so this was a
  redundant second way to reach it. The dock's five remaining buttons
  (Feeds, Messages, compose, Notifications, Settings) are now evenly spaced
  with compose in the middle, instead of six unevenly packed ones.
- The home header no longer shows a hamburger icon or a feed-name chip
  under the search bar for opening the algorithm picker. Onboarding already
  explains that a swipe does this, so both buttons duplicated a gesture
  that was already the primary way in.

### Fixed

- A handful of small regressions from the redesign above: an off-center
  theme swatch icon, a hardcoded English "Profile" label where every other
  string is translated, and a navigation-bar height constant that no longer
  matched the dock's real height, which could misalign layout sitting below
  it (e.g. the wallet dashboard footer).
- A broad Italian mistranslation pass: "account" no longer shows as "conti"
  (bank accounts) anywhere, and dozens of other terms across feeds, mute/
  follow, wallet, sats, Lightning, reporting, and search are now consistent
  with what is, at its core, a social app.
- The same class of corruption that caused the Italian bugs above turned
  out to affect nearly every other language: `nsec`/`npub` replaced with
  unrelated words, login and logout both reading as "Sign", "backed up"
  mistranslated as "supported" in the wallet's fund-loss warning, and
  "share" mistranslated as a stock/equity term instead of the social
  action. Fixed across Bulgarian, Czech, Danish, German, Greek, Spanish,
  Estonian, French, Croatian, Hungarian, Lithuanian, Latvian, Maltese,
  Dutch, Polish, Romanian, Russian, Slovak, Slovenian, Swedish, and
  Chinese — around 140 strings in total.
- Japanese was corrupted far beyond a few bad strings: dozens of unrelated
  buttons and labels throughout the app had all been collapsed onto the
  same handful of wrong phrases (every "Retry", "Close", and "Done" button
  in the app, among others, showed the same unrelated word). Fully
  retranslated — over 130 strings corrected.

### Removed

- Irish (`ga`) is no longer a supported UI language. Its translation was
  corrupted beyond a reasonable patch — the app's own name, `nsec`/`npub`,
  and dozens of unrelated buttons were all random, unrelated phrases. It
  will come back once someone can redo it properly.

## [0.3.3] - 2026-09-06

### Added

- **Four new themes**: Dark Pixel, Light Pixel, Fire, and Fire Light join
  Midnight and Ice — six in total, shown in a grid with each theme's own
  name and color swatch instead of a plain "Dark"/"Light" pair. The two
  Pixel themes also use a different typeface (Fira Mono) and sharper
  corners, the first themes here to vary anything beyond color.

### Fixed

- The feed could show only your own notes after a cold start, sometimes
  needing the app force-closed and reopened to recover. A relay query
  racing ahead of the app's own relay connections on startup returned an
  empty follow list, and that empty result got cached for 5 minutes —
  turning a one-off timing issue into a long-lived one. A fresh feed load
  now retries instead of trusting a remembered "you follow nobody" answer.
- Scrolling a feed to load more notes could still make it visibly jump —
  the 0.3.0 fix for this addressed one of two places that were forcing a
  reload on every page, not both.
- The zap comment field could end up hidden behind the on-screen keyboard,
  with no way to see what you were typing. The zap sheet's amount-preset
  grid is also a bit more compact now.
- Switching between Home, Messages, Notifications, and Settings used
  inconsistent, sometimes directionless animations — tabs now consistently
  slide toward whichever side they actually sit on.
- Several Italian strings translated in 0.3.0 didn't make sense in context
  ("account" as "conto" — a bank account, "muted" as "morto" — dead,
  "note" as "biglietto" — a ticket, among others).

## [0.3.2] - 2026-09-06

Note translation is now fully on-device — no server, no configuration,
nothing ever leaves your phone.

### Changed

- **Note translation no longer uses a remote LibreTranslate server.** The
  "Translate" action introduced in 0.3.0 now runs entirely on-device via
  Bergamot Translator (Mozilla's own production NMT engine — the same one
  built into Firefox) with real neural translation models, covering every
  EU official language plus Chinese, Japanese, and Maltese in both
  directions (Irish has no published model anywhere, so it isn't
  supported). Models are small (mostly 14-18MB per language pair) and
  download on demand, with an explicit confirmation showing the real size
  and an on-device/offline disclosure before anything is fetched — never
  bundled into the app itself. "Translate notes" is on by default now that
  there's no server address or API key to configure; the now-pointless
  server URL and API key fields have been removed from Settings > Content
  Display.
- Raised the minimum supported Android version from 9.0 to Pie (API 28,
  Android 9) — the on-device translation engine's `iconv` usage requires it.
- Release APKs are now built for `arm64-v8a` only (previously also
  `armeabi-v7a` and `x86_64`). A real 32-bit-only phone isn't a realistic
  target any more, and native x86_64 Android phones are effectively
  nonexistent today — the rare x86 devices (Chromebooks) already run arm64
  apps through their own translation layer.

### Fixed

- Translating several notes in a scrolling session could grow the app's
  memory use without bound until it ran out of memory and crashed —
  language detection was loading its full offline model independently for
  every note instead of once, shared.
- Scrolling a note out of view while it was translating (or downloading a
  language pack) silently abandoned the operation — the spinner just kept
  spinning forever, with no error and no result even if you scrolled back.
  Translation now runs independently of what's on screen, so it keeps
  going, and finishes, regardless.
- Translation could take upwards of 15-20 seconds per note due to two
  compounding issues: the debug build of the native engine ran unoptimized
  (a side effect of how Android normally compiles debug builds), and the
  one-time language-detection setup was doing several times more work than
  it needed to. Both are fixed — a typical translation now takes about two
  seconds.

## [0.3.1] - 2026-09-05

A privacy/security pass across the codebase, prompted by an audit looking
specifically for data leaks and unnecessary attack surface.

### Fixed

- **The media-upload HTTP client could write a signed auth header to disk.**
  Enabling the in-app diagnostic logging (off by default, Settings) and then
  uploading a photo or video wrote the request's `Authorization` header — a
  signed Nostr event carrying your pubkey — into the exportable app logs.
  Anyone using "Share Logs" to send diagnostics to support would have sent
  that along with it, unknowingly. Logging is now fully disabled for that
  client, matching every other network client in the app; an additional,
  separate line of code that printed the same headers unconditionally
  (independent of the logging setting) was removed outright.
- **The note-translate server address wasn't required to be HTTPS.** The
  server URL you configure in Settings > Content Display > Translate notes
  is now checked before every request; a plain `http://` address is refused
  rather than relying on Android's network security config to catch it as
  a side effect. Note text should never leave the device other than
  encrypted, on purpose, not by accident of a different setting.
- Removed an unused, unwired no-op logger class left over from an earlier
  logging setup — dead code, no behavior change.

## [0.3.0] - 2026-09-05

The app now speaks 27 languages, and notes in a language you don't understand
can be translated with a tap.

### Added

- **Translate notes.** A small "Translate" action can now appear below a
  note's text — styled and placed apart from the reply/zap/like/repost row
  so it reads as something the app is offering, not something the author
  wrote. Tapping it calls a LibreTranslate-compatible server (self-hosted or
  any instance you trust) and shows the result in place, with a
  "Translated · See original" toggle to switch back. Off by default: enable
  it and set your server's URL under Settings > Content Display >
  Translate notes. No default third-party server is bundled — note text is
  only ever sent somewhere you've explicitly configured.
- **Full translation into 26 languages.** Every EU official language, plus
  Chinese, Japanese, and Russian, joins English as a complete UI translation
  (24 via local, offline neural machine translation; Croatian and Maltese,
  which that engine doesn't support, via a second offline model). Every
  translated string was checked for correct placeholder handling, valid
  XML, and consistent phrasing with the rest of each locale; a number of
  machine-translation artifacts (corrupted formatting placeholders,
  duplicated words on short labels, one flipped negation, one truncated
  sentence) were caught and hand-fixed along the way.

### Fixed

- **Scrolling down a feed could make it randomly jump or reload.** Loading
  more notes at the bottom of a feed also triggers a fetch of their
  engagement counts (likes, zaps, reposts) from relays; that fetch used to
  invalidate and reload the *entire* already-scrolled feed once it
  finished, however long that took. On a slow relay, this landed well into
  a later scroll and looked like the feed refreshing itself for no reason.
  The reload is now scoped to actual refreshes (pull-to-refresh, opening
  the feed) — scrolling for more notes no longer touches what's already on
  screen.

## [0.2.8] - 2026-09-04

Four small polish fixes from a real-device pass: a slightly clipped splash
logo, a jarring tab switch, a missing swipe gesture, and a dead Home button.

### Fixed

- **The splash screen logo was clipped in a corner.** The icon's artwork
  extended past the safe zone the system's splash screen uses when masking
  and scaling it, clipping a sliver off one edge. Re-centered with proper
  margin.
- **Switching from Messages to Notifications flashed and rebuilt the whole
  screen.** Messages lives in its own navigation destination rather than
  the app's in-place tab system, so switching tabs was popping back to a
  fully torn-down screen and replaying a "returning from a detail screen"
  scale animation. That transition is now instant, matching every other
  tab.
- **The DM Follows/Other tabs only responded to taps, not swipes.** Rebuilt
  on a `HorizontalPager` (the same pattern already used elsewhere in the
  app), so swiping left/right now switches between them, with the tab
  indicator and underlying data following along either way.
- **Tapping Home from the Profile screen did nothing.** The persistent
  bottom bar mislabeled Feeds as the active tab while viewing a profile,
  so tapping Home read as "you're already here" and silently no-opped.

## [0.2.7] - 2026-09-03

The new Accounts entry in Settings only offered to add another account,
with no way to see which accounts were already signed in, switch between
them, or sign out — a second account you added looked like it had replaced
the first, since there was nowhere to find it again.

### Fixed

- **Settings > Accounts now shows account switching and logout, not just
  "add account."** The app already had a fully working account switcher —
  list of signed-in accounts, tap to switch, an edit mode with a logout
  button, add/create actions — but it only lived behind a small avatar
  icon tucked next to the account name in the drawer, easy to miss.
  Settings now opens that same switcher directly, so a second account
  doesn't feel like it swallowed the first.

## [0.2.6] - 2026-09-03

A quoted note that wasn't already cached for some other reason showed
"Mentioned event not found", the new Accounts entry in Settings had nothing
inside it, and a bunker connection could leak a socket if you backed out
mid-login.

### Fixed

- **Quoted notes often rendered as "Mentioned event not found."** A note
  quoting another note — via a `nostr:note1…`/`nevent1…` reference or a
  NIP-18 `q` tag — used to only render correctly by coincidence, if the
  quoted note happened to already be cached from something else. The field
  that used to carry it was Primal's centralized cache server's job, and
  was never replaced with a relay fetch after the fork. The thread screen
  and the main feed now ask relays for a quoted note (and its author's
  profile) whenever it's missing.
- **The new "Accounts" entry in Settings showed nothing when tapped.**
  Settings rows render their content inline rather than navigating away,
  and this one had no inline content wired up, so it just expanded to an
  empty box. It now shows the "Add an existing account" action.
- **A bunker (NIP-46) account could be asked to approve leftover event
  kinds it should never see**, the same "leftover cache/wallet kind" set
  Amber accounts were already shielded from — the filter only checked for
  Amber, not for a bunker connection.
- **A cancelled bunker connection (backing out mid-login, or cancelling a
  pending publish) could leak an open socket.** The connection's cleanup
  ran as a normal step after the work, not in a `finally`, so a
  cancellation skipped it.

## [0.2.5] - 2026-09-03

Adding a second account was only possible from a switcher tucked inside the
drawer; there was also no way at all to sign in with a remote signer
("bunker") instead of a raw key or Amber.

### Added

- **An "Accounts" entry in Settings for adding another account.** Reuses the
  existing sign-in screen — nsec/npub paste or Amber — which was already
  safe to use for a second account (it never touches an already-logged-in
  one). As before, multiple accounts on this device share the same relay
  connections and local database: this is convenience, not identity
  separation, and a relay can trivially tell they belong to the same
  device. Keeping two identities genuinely unlinkable is a separate,
  larger piece of work, not something this screen provides.
- **Sign in with a bunker (NIP-46 remote signer).** Paste a `bunker://`
  connection string from Amber's bunker mode, nsec.app, or any other NIP-46
  signer to add an account without ever handing LibreNostr your private
  key. Each note published from that account is signed by a round trip to
  the bunker over its own relay channel, using a throwaway keypair
  generated just for that connection — never the account's real key.

## [0.2.4] - 2026-09-03

Two accounts on the same device already shared their relay connections and
local cache; the cleanup and search-feed code paths had not caught up with
that.

### Fixed

- **Logging out one account discarded shared cache other logged-in accounts
  were still using.** The coordinator's follow-list cache and hot event
  layer are one process-wide instance, shared by every account signed in on
  the device — logging out account A used to wipe both unconditionally,
  even with account B still logged in and relying on them. It is now reset
  only when the account being removed was the last one signed in.
- **Advanced search's `myfollows` scope asked relays directly instead of
  going through the fetch coordinator.** This was the case named when the
  coordinator was introduced — the note feed, article feed and advanced
  search all want the same follow list, often within the same burst of tab
  loads — but this call site was never actually wired to it. It now shares
  the same coalesced, briefly-cached request as everything else.

## [0.2.3] - 2026-09-03

Proofreading a note before it went out meant trusting the countdown alone,
and tagging someone by name only worked if a relay happened to answer.

### Added

- **The publish countdown now shows a preview of the note.** The last few
  seconds before a note goes out were a bare timer with no way to see what
  was actually about to be published. The countdown screen now renders the
  note's text and any attached image or GIF the same way a published note
  would look, so a mistake is caught by reading it, not by guessing.
- **Mention search now checks profiles you already have before asking a
  relay.** Typing `@` followed by a name only searched relays — a NIP-50
  `search` filter if the relay supported it, otherwise a scan of the last
  500 arbitrary profile events, meaning a followed or previously-seen
  profile could still fail to show up. Profiles already cached locally now
  match instantly by name prefix, offline included; a relay is only asked
  to fill whatever the local cache didn't already cover.

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
