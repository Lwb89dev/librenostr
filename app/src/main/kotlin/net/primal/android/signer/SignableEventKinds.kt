package net.primal.android.signer

import net.primal.domain.nostr.NostrEventKind

/**
 * Every event kind LibreNostr signs with the user's identity key.
 *
 * This is the single source of truth for two things that must agree:
 *
 *  - the NIP-55 `permissions` set sent to an external signer at connect time, so the user grants
 *    them once instead of being prompted per kind;
 *  - the gate in `NostrNotary` that decides whether a request is worth forwarding to the signer.
 *
 * They used to be separate lists and they drifted: kind 9802 was in neither, so a NIP-84 highlight
 * was rejected locally and never reached Amber at all. Anything the app can sign belongs here, and
 * adding a new signed kind means adding it here — not in two places.
 *
 * Deliberately absent:
 *  - NWC events (23194/23195/13194), signed with the wallet connection secret rather than the
 *    user's key, so they never reach the external signer;
 *  - kind 30078 application-specific data, which carried Primal's app settings and is rejected
 *    outright by `NostrNotary`.
 */
val SIGNABLE_EVENT_KINDS: List<NostrEventKind> = listOf(
    NostrEventKind.Metadata,                // profile edits
    NostrEventKind.ShortTextNote,           // notes, replies, article comments
    NostrEventKind.FollowList,              // follow and unfollow
    NostrEventKind.EncryptedDirectMessages, // NIP-04 direct messages
    NostrEventKind.EventDeletion,           // deleting a note or a highlight
    NostrEventKind.ShortTextNoteRepost,
    NostrEventKind.Reaction,                // likes
    NostrEventKind.GenericRepost,
    NostrEventKind.PictureNote,
    NostrEventKind.ChatMessage,             // live stream chat
    NostrEventKind.PollResponse,
    NostrEventKind.Reporting,
    NostrEventKind.ZapRequest,
    NostrEventKind.Highlight,               // NIP-84
    NostrEventKind.MuteList,
    NostrEventKind.RelayListMetadata,
    NostrEventKind.BookmarksList,
    NostrEventKind.BlossomServerList,
    NostrEventKind.StreamMuteList,
    NostrEventKind.ClientAuthentication,    // NIP-42 relay auth
    NostrEventKind.BlossomUploadBlob,       // BUD-01 upload authorisation
    NostrEventKind.CategorizedPeopleList,   // follow sets
    NostrEventKind.LongFormContent,         // NIP-23 articles
)

val SIGNABLE_EVENT_KIND_VALUES: Set<Int> = SIGNABLE_EVENT_KINDS.map { it.value }.toSet()
