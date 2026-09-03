package net.primal.android.thread.notes

import net.primal.android.notes.feed.model.FeedPostUi
import net.primal.android.notes.feed.model.asFeedPostUi
import net.primal.domain.nostr.getTagValueOrNull
import net.primal.domain.nostr.hasMentionMarker
import net.primal.domain.nostr.hasReplyMarker
import net.primal.domain.nostr.hasRootMarker
import net.primal.domain.nostr.isEventIdTag
import net.primal.domain.posts.FeedPost

/**
 * Maps the ancestor chain leading to [highlightPostId] through unchanged, then rebuilds
 * whatever comes after it as a proper reply tree — see [buildReplyTree] for why that used to be
 * a flat, unnested sort by timestamp.
 *
 * The ancestor chain is left untouched deliberately: it is what the thread screen renders as a
 * linear breadcrumb above the opened note, and reordering it is not what was reported broken.
 * Only what comes after the opened note gets rebuilt.
 */
internal fun List<FeedPost>.asDisplayOrderedFeedPostUi(highlightPostId: String): List<FeedPostUi> {
    val highlightIndex = indexOfFirst { it.eventId == highlightPostId }
    if (highlightIndex == -1) return map { it.asFeedPostUi() }

    val ancestorsAndHighlighted = subList(0, highlightIndex + 1).map { it.asFeedPostUi() }
    val rootAuthorId = firstOrNull()?.author?.authorId
    val replies = subList(highlightIndex + 1, size)
        .buildReplyTree(rootAuthorId = rootAuthorId)
        .map { (post, level) -> post.asFeedPostUi().copy(replyLevel = level) }

    return ancestorsAndHighlighted + replies
}

/**
 * Orders the replies under an opened note into a proper tree instead of one flat pile.
 *
 * A NIP-10 `e` tag names a reply's parent exactly — there is no ambiguity here the way there is
 * with timestamps, which some clients get wrong. The thread screen used to ignore that and sort
 * everything after the opened note by timestamp alone, in two buckets (the thread author, then
 * everyone else). A reply to a reply looked identical to a reply to the opened note itself, both
 * rendered at the same rank, and the second one to arrive could appear ahead of the reply it was
 * actually answering.
 *
 * The result pairs each reply with how deep it sits, for the UI to draw one vertical bar per
 * level, and orders the flat list as a depth-first walk: a reply is always immediately followed
 * by its own replies, before any sibling's. A reply whose named parent is not in [this] — because
 * it targets the opened note itself, or an ancestor, or something outside this thread fetch
 * entirely — is treated as level 1, a direct reply to what was opened.
 *
 * Within one parent's replies, [rootAuthorId]'s own are shown first, oldest first — a continued
 * thought reads top to bottom — and everyone else's after that, newest first.
 */
internal fun List<FeedPost>.buildReplyTree(rootAuthorId: String?): List<Pair<FeedPost, Int>> {
    val postsById = associateBy { it.eventId }
    val childrenByParentId = mutableMapOf<String, MutableList<FeedPost>>()
    val topLevel = mutableListOf<FeedPost>()

    forEach { post ->
        val parentId = post.immediateParentId()?.takeIf { it in postsById }
        if (parentId == null) {
            topLevel += post
        } else {
            childrenByParentId.getOrPut(parentId) { mutableListOf() } += post
        }
    }

    val visited = mutableSetOf<String>()
    val ordered = mutableListOf<Pair<FeedPost, Int>>()

    fun visit(post: FeedPost, level: Int) {
        // A cycle would mean two replies each named the other as parent — malformed input, not a
        // real conversation. Stop descending into it rather than recursing forever.
        if (post.eventId in visited) return
        visited += post.eventId

        ordered += post to level
        childrenByParentId[post.eventId]
            .orEmpty()
            .sortedForDisplay(rootAuthorId)
            .forEach { visit(it, level = level + 1) }
    }

    topLevel.sortedForDisplay(rootAuthorId).forEach { visit(it, level = 1) }

    // A post can be unreachable from every top-level entry point without the graph having an
    // outright cycle: if every one of its ancestors also names an in-set parent, the walk above
    // never starts at any of them. A cycle is the narrowest case of that. Either way, dropping
    // the post would be worse than the timestamp-only sort this replaces — silently missing
    // content is a harder bug to notice than one merely out of order.
    forEach { post -> if (post.eventId !in visited) visit(post, level = 1) }

    return ordered
}

private fun List<FeedPost>.sortedForDisplay(rootAuthorId: String?): List<FeedPost> {
    val (fromRootAuthor, fromEveryoneElse) = partition {
        rootAuthorId != null && it.author.authorId == rootAuthorId
    }
    return fromRootAuthor.sortedBy { it.timestamp } + fromEveryoneElse.sortedByDescending { it.timestamp }
}

/**
 * The one post this reply answers, per NIP-10: the `e` tag marked `reply`, or — for a reply
 * directly to the thread root, which NIP-10 allows to omit the `reply` marker — the tag marked
 * `root`. Falls back to the last bare `e` tag for the deprecated positional convention some
 * clients still write.
 */
private fun FeedPost.immediateParentId(): String? {
    val replyTag = tags.find { it.hasReplyMarker() }
    val rootTag = tags.find { it.hasRootMarker() }
    (replyTag ?: rootTag)?.getTagValueOrNull()?.let { return it }

    return tags.filterNot { it.hasMentionMarker() }
        .lastOrNull { it.isEventIdTag() }
        ?.getTagValueOrNull()
}
