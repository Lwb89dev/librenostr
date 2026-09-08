package net.primal.android.thread.notes

import net.primal.android.notes.feed.model.FeedPostUi
import net.primal.android.notes.feed.model.asFeedPostUi
import net.primal.domain.posts.FeedPost
import net.primal.domain.posts.immediateParentId

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
        .buildReplyTree(rootAuthorId = rootAuthorId, rootId = highlightPostId)
        .map { it.post.asFeedPostUi().copy(replyLevel = it.level, hasUnresolvedParent = !it.hasKnownParent) }

    return ancestorsAndHighlighted + replies
}

/** One reply's place in the tree: how deep it sits, and whether that depth is trustworthy. */
internal data class ReplyPlacement(
    val post: FeedPost,
    val level: Int,
    /**
     * False for a reply whose named parent isn't the opened note and isn't anywhere in this
     * fetch either — its real depth is unknown, and level is a placeholder (1), not a fact. This
     * is deliberately *not* the same thing as "no named parent at all": a genuine direct reply to
     * the opened note has [hasKnownParent] `true` at level 1, same as always.
     */
    val hasKnownParent: Boolean,
)

/**
 * Orders the replies under an opened note into a proper tree instead of one flat pile.
 *
 * The parent/root relationship each post names for itself — NIP-10 or NIP-22 depending on kind —
 * is pure protocol interpretation and lives separately in `net.primal.domain.posts` (see
 * [immediateParentId]); there is no ambiguity there the way there is with timestamps, which some
 * clients get wrong. The thread screen used to ignore that and sort everything after the opened
 * note by timestamp alone, in two buckets (the thread author, then everyone else). A reply to a
 * reply looked identical to a reply to the opened note itself, both rendered at the same rank, and
 * the second one to arrive could appear ahead of the reply it was actually answering.
 *
 * The result pairs each reply with how deep it sits, for the UI to draw one vertical bar per
 * level, and orders the flat list as a depth-first walk: a reply is always immediately followed
 * by its own replies, before any sibling's. A reply whose named parent is neither [rootId] nor
 * anywhere in [this] — the parent exists somewhere, just not in what this fetch brought back — is
 * placed at level 1 with [ReplyPlacement.hasKnownParent] `false`, rather than pretending it is a
 * confirmed direct reply to what was opened. It still shows: the alternative is not "correctly
 * nested," it is "silently missing," which is worse.
 *
 * Within one parent's replies, [rootAuthorId]'s own are shown first, oldest first — a continued
 * thought reads top to bottom — and everyone else's after that, newest first.
 */
internal fun List<FeedPost>.buildReplyTree(rootAuthorId: String?, rootId: String? = null): List<ReplyPlacement> {
    val postsById = associateBy { it.eventId }
    val childrenByParentId = mutableMapOf<String, MutableList<FeedPost>>()
    val topLevel = mutableListOf<FeedPost>()
    val orphanIds = mutableSetOf<String>()

    forEach { post ->
        val parentId = post.immediateParentId()
        when {
            parentId == null || parentId == rootId -> topLevel += post
            parentId in postsById -> childrenByParentId.getOrPut(parentId) { mutableListOf() } += post
            else -> {
                topLevel += post
                orphanIds += post.eventId
            }
        }
    }

    val visited = mutableSetOf<String>()
    val ordered = mutableListOf<ReplyPlacement>()

    // Iterative pre-order walk, not recursive: a long chain of replies (a NIP-10 chain has no
    // depth limit) previously meant one JVM stack frame per level, risking a StackOverflowError
    // on a deep-enough thread. An explicit stack of (post, level) pairs stands in for the call
    // stack; each entry's children are pushed in reverse display order so the first one is popped
    // — and therefore visited — next, preserving the same "a post is immediately followed by its
    // own replies, before any sibling's" ordering the recursive walk produced.
    fun walk(roots: List<FeedPost>) {
        val stack = ArrayDeque<Pair<FeedPost, Int>>()
        roots.sortedForDisplay(rootAuthorId).asReversed().forEach { stack.addLast(it to 1) }

        while (stack.isNotEmpty()) {
            val (post, level) = stack.removeLast()
            // A cycle would mean two replies each named the other as parent — malformed input,
            // not a real conversation. Stop descending into it rather than looping forever.
            if (post.eventId in visited) continue
            visited += post.eventId

            ordered += ReplyPlacement(post = post, level = level, hasKnownParent = post.eventId !in orphanIds)
            childrenByParentId[post.eventId]
                .orEmpty()
                .sortedForDisplay(rootAuthorId)
                .asReversed()
                .forEach { stack.addLast(it to level + 1) }
        }
    }

    walk(topLevel)

    // A post can be unreachable from every top-level entry point without the graph having an
    // outright cycle: if every one of its ancestors also names an in-set parent, the walk above
    // never starts at any of them. A cycle is the narrowest case of that. Either way, dropping
    // the post would be worse than the timestamp-only sort this replaces — silently missing
    // content is a harder bug to notice than one merely out of order.
    forEach { post -> if (post.eventId !in visited) walk(listOf(post)) }

    return ordered
}

private fun List<FeedPost>.sortedForDisplay(rootAuthorId: String?): List<FeedPost> {
    val (fromRootAuthor, fromEveryoneElse) = partition {
        rootAuthorId != null && it.author.authorId == rootAuthorId
    }
    // Two relays can hand back events with identical `created_at` — a bare sortedBy is stable, so
    // ties would fall back to whatever order the query happened to return them in, which is not
    // guaranteed to be the same from one load to the next. Event id is arbitrary but fixed, so
    // ties resolve to the same order every time.
    return fromRootAuthor.sortedWith(compareBy({ it.timestamp }, { it.eventId })) +
        fromEveryoneElse.sortedWith(compareByDescending<FeedPost> { it.timestamp }.thenBy { it.eventId })
}
