package net.primal.domain.posts

import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.getTagValueOrNull
import net.primal.domain.nostr.hasMentionMarker
import net.primal.domain.nostr.hasReplyMarker
import net.primal.domain.nostr.hasRootMarker
import net.primal.domain.nostr.isATag
import net.primal.domain.nostr.isEventIdTag
import net.primal.domain.nostr.isUppercaseATag
import net.primal.domain.nostr.isUppercaseEventIdTag

/**
 * The normalized (rootId, parentId) relationship a reply/comment names for itself, per NIP-10 or
 * NIP-22 depending on [FeedPost.kind]. Kept as pure protocol interpretation, independent of how a
 * tree gets built from it or rendered — see `ReplyTree.buildReplyTree` (app module, thread screen)
 * for the tree builder that consumes [immediateParentId], and [threadRootId]'s own doc for why it
 * isn't needed there.
 */

/**
 * The one post this reply answers.
 *
 * NIP-22 (kind 1111 comments) says so explicitly: a lowercase `e`/`a` tag names the parent item —
 * another comment if nested, or the root content itself for a top-level one. There is no
 * positional fallback for this kind; the tags are mandatory by spec.
 *
 * Everything else is NIP-10: the `e` tag marked `reply`, or — for a reply directly to the thread
 * root, which NIP-10 allows to omit the `reply` marker — the tag marked `root`. Falls back to the
 * last bare `e` tag for the deprecated positional convention some clients still write.
 */
fun FeedPost.immediateParentId(): String? =
    threadRelation?.parentId ?: if (kind == NostrEventKind.Comment.value) {
        tags.find { it.isEventIdTag() }?.getTagValueOrNull()
            ?: tags.find { it.isATag() }?.getTagValueOrNull()
    } else {
        val replyOrRootTag = tags.find { it.hasReplyMarker() } ?: tags.find { it.hasRootMarker() }
        replyOrRootTag?.getTagValueOrNull()
            ?: tags.filterNot { it.hasMentionMarker() }.lastOrNull { it.isEventIdTag() }?.getTagValueOrNull()
    }

/**
 * The root of the whole conversation this reply belongs to.
 *
 * NIP-22: the uppercase `E`/`A` tag — root scope, always present regardless of nesting depth,
 * unlike [immediateParentId] which changes at every level. (An external, non-nostr root — an `I`
 * tag with no `E`/`A` at all, e.g. a comment thread under a URL — has no nostr event id to return
 * here; this resolves to null for that case, same as an event this fetch doesn't know about.)
 *
 * NIP-10: the `e` tag marked `root`, or — for the deprecated positional convention — the FIRST
 * bare `e` tag (the last is the immediate parent per [immediateParentId]; the first is the root).
 * A reply directly to the root has no separate parent, so this and [immediateParentId] resolve to
 * the same id for it.
 *
 * Not consumed by the tree builder itself — depth there comes from walking [immediateParentId]
 * edges, which needs no root at all. This exists as the other half of a normalized
 * (rootId, parentId) relationship for anything that needs to know which conversation a reply
 * belongs to without walking the whole chain, e.g. a "jump to root" action.
 */
fun FeedPost.threadRootId(): String? =
    threadRelation?.rootId ?: if (kind == NostrEventKind.Comment.value) {
        tags.find { it.isUppercaseEventIdTag() }?.getTagValueOrNull()
            ?: tags.find { it.isUppercaseATag() }?.getTagValueOrNull()
    } else {
        tags.find { it.hasRootMarker() }?.getTagValueOrNull()
            ?: tags.filterNot { it.hasMentionMarker() }.firstOrNull { it.isEventIdTag() }?.getTagValueOrNull()
    }
