package net.primal.data.repository.utils

import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.domain.posts.FeedPost
import net.primal.domain.posts.immediateParentId
import net.primal.domain.posts.threadRootId

/**
 * Tries to perform topological sort calling [performTopologicalSort].  In case the sort fails,
 * returns the original list.
 */
fun List<FeedPost>.performTopologicalSortOrThis() = runCatching { performTopologicalSort() }.getOrDefault(this)

/**
 * Performs a topological sort based on depth-first search as described
 * [here](https://en.wikipedia.org/wiki/Topological_sorting#Depth-first_search).
 *
 * Used to deal with some clients generating bad timestamps on thread chains.
 * @throws IllegalStateException thrown in case a cycle is detected making sort impossible to complete.
 */
fun List<FeedPost>.performTopologicalSort(): List<FeedPost> {
    val postsMap = this.associateBy { it.eventId }
    val adjacencyMap = mutableMapOf<String, MutableSet<String>>()

    val permanentMark = mutableSetOf<String>()
    val temporaryMark = mutableSetOf<String>()
    val finalList = mutableListOf<FeedPost>()

    this.forEach { post ->
        // A post whose relationship is already normalized — a gift-wrapped private reply, whose
        // thread links live inside the encrypted rumor and never as public tags — states it
        // through threadRelation and carries no tags at all. Reading only tags gave those posts
        // no edges, so the sort put them at the very front of the thread, above its own root,
        // where the screen renders them as ancestors of the opened note instead of replies to it.
        val parentId = post.immediateParentId()
        val rootId = post.threadRootId()
        if (parentId != null || rootId != null) {
            parentId?.let { adjacencyMap.getOrPut(key = it) { mutableSetOf() }.add(post.eventId) }
            rootId?.takeIf { it != parentId }
                ?.let { adjacencyMap.getOrPut(key = it) { mutableSetOf() }.add(post.eventId) }
        }
    }

    fun visit(node: FeedPost) {
        if (permanentMark.contains(node.eventId)) return
        if (temporaryMark.contains(node.eventId)) error("Impossible. Graph has cycle.")

        temporaryMark.add(node.eventId)

        adjacencyMap.getOrElse(key = node.eventId) { emptySet<String>() }
            .forEach { id ->
                postsMap[id]?.let { visit(it) }
            }

        permanentMark.add(node.eventId)
        finalList.add(0, node)
    }

    this.forEach { visit(it) }
    return finalList
}
