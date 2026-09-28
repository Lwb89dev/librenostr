package net.primal.data.local.queries

import androidx.room3.RoomRawQuery
import androidx.sqlite.SQLiteStatement

class ChronologicalFeedWithRepostsQueryBuilder(
    private val feedSpec: String,
    private val userPubkey: String,
    private val allowMutedThreads: Boolean,
    /**
     * Whether web-of-trust filtering should actually restrict this query: the LEFT JOIN below is
     * always present, but bypassed with `OR ?` exactly like [allowMutedThreads] bypasses the mute
     * check, so the same query works whether or not the caller has it turned on. See
     * `WebOfTrustRepository` for what "in the network" means and why the bypass has to exist:
     * without it, turning the setting on before a network has ever been computed would hide every
     * note in the feed instead of showing everything, unfiltered, until the first computation lands.
     */
    private val wotFilterActive: Boolean = false,
) : FeedQueryBuilder {

    companion object {
        private const val LATEST_BASIC_QUERY = """
            SELECT
                PostData.postId,
                PostData.authorId,
                PostData.createdAt,
                PostData.kind,
                PostData.content,
                PostData.raw,
                PostData.authorMetadataId,
                PostData.hashtags,
                NULL AS repostId,
                NULL AS repostAuthorId,
                NULL AS repostCreatedAt,
                EventUserStats.liked AS userLiked,
                EventUserStats.replied AS userReplied,
                EventUserStats.reposted AS userReposted,
                EventUserStats.zapped AS userZapped,
                EventUserStats.votedForOption AS userVotedForOption,
                CASE WHEN MutedUser.item IS NOT NULL THEN 1 ELSE 0 END AS isAuthorMuted,
                CASE WHEN MutedThread.item IS NOT NULL THEN 1 ELSE 0 END AS isThreadMuted,
                CASE WHEN Wot.pubkey IS NOT NULL THEN 0 ELSE 1 END AS isAuthorOutsideWot,
                FeedPostDataCrossRef.position AS position,
                PostData.createdAt AS sortCreatedAt,
                PostData.replyToPostId,
                PostData.replyToAuthorId
            FROM PostData
            JOIN FeedPostDataCrossRef ON FeedPostDataCrossRef.eventId = PostData.postId
            LEFT JOIN EventUserStats ON EventUserStats.eventId = PostData.postId AND EventUserStats.userId = ?
            LEFT JOIN MutedItemData AS MutedUser ON MutedUser.item = PostData.authorId AND MutedUser.ownerId = ?
            LEFT JOIN MutedItemData AS MutedThread ON MutedThread.item = PostData.postId AND MutedThread.ownerId = ?
            LEFT JOIN WotQualifiedPubkeyData AS Wot ON Wot.pubkey = PostData.authorId AND Wot.ownerId = ?
            WHERE FeedPostDataCrossRef.feedSpec = ? AND FeedPostDataCrossRef.ownerId = ?
                AND isAuthorMuted = 0 AND (isThreadMuted = 0 OR ?) AND (isAuthorOutsideWot = 0 OR ?)

            UNION ALL

            SELECT
                PostData.postId,
                PostData.authorId,
                PostData.createdAt,
                PostData.kind,
                PostData.content,
                PostData.raw,
                PostData.authorMetadataId,
                PostData.hashtags,
                RepostData.repostId AS repostId,
                RepostData.authorId AS repostAuthorId,
                RepostData.createdAt AS repostCreatedAt,
                EventUserStats.liked AS userLiked,
                EventUserStats.replied AS userReplied,
                EventUserStats.reposted AS userReposted,
                EventUserStats.zapped AS userZapped,
                EventUserStats.votedForOption AS userVotedForOption,
                CASE WHEN MutedUser.item IS NOT NULL THEN 1 ELSE 0 END AS isAuthorMuted,
                CASE WHEN MutedThread.item IS NOT NULL THEN 1 ELSE 0 END AS isThreadMuted,
                CASE WHEN Wot.pubkey IS NOT NULL THEN 0 ELSE 1 END AS isAuthorOutsideWot,
                FeedPostDataCrossRef.position AS position,
                RepostData.createdAt AS sortCreatedAt,
                PostData.replyToPostId,
                PostData.replyToAuthorId
            FROM RepostData
            JOIN PostData ON RepostData.postId = PostData.postId
            JOIN FeedPostDataCrossRef ON FeedPostDataCrossRef.eventId = RepostData.repostId
            LEFT JOIN EventUserStats ON EventUserStats.eventId = PostData.postId AND EventUserStats.userId = ?
            LEFT JOIN MutedItemData AS MutedUser ON MutedUser.item = PostData.authorId AND MutedUser.ownerId = ?
            LEFT JOIN MutedItemData AS MutedThread ON MutedThread.item = PostData.postId AND MutedThread.ownerId = ?
            LEFT JOIN WotQualifiedPubkeyData AS Wot ON Wot.pubkey = PostData.authorId AND Wot.ownerId = ?
            WHERE FeedPostDataCrossRef.feedSpec = ? AND FeedPostDataCrossRef.ownerId = ?
                AND isAuthorMuted = 0 AND (isThreadMuted = 0 OR ?) AND (isAuthorOutsideWot = 0 OR ?)
        """
    }

    // Position reflects insertion order and can be inconsistent after a relay reconnect.
    // Use the event timestamp as the source of truth so newest notes are always at the top.
    private val orderByClause = "ORDER BY sortCreatedAt"

    /** Binds the 8 `?` placeholders one branch of [LATEST_BASIC_QUERY] declares, in order. */
    private fun SQLiteStatement.bindBranch(startIndex: Int) {
        bindText(index = startIndex, value = userPubkey)
        bindText(index = startIndex + 1, value = userPubkey)
        bindText(index = startIndex + 2, value = userPubkey)
        bindText(index = startIndex + 3, value = userPubkey)
        bindText(index = startIndex + 4, value = feedSpec)
        bindText(index = startIndex + 5, value = userPubkey)
        bindBoolean(index = startIndex + 6, value = allowMutedThreads)
        bindBoolean(index = startIndex + 7, value = !wotFilterActive)
    }

    override fun feedQuery(): RoomRawQuery {
        return RoomRawQuery(
            sql = "$LATEST_BASIC_QUERY $orderByClause DESC, position DESC",
            onBindStatement = { query ->
                query.bindBranch(startIndex = 1)
                query.bindBranch(startIndex = 9)
            },
        )
    }

    override fun newestFeedPostsQuery(limit: Int): RoomRawQuery {
        return RoomRawQuery(
            sql = "$LATEST_BASIC_QUERY $orderByClause DESC, position DESC LIMIT ?",
            onBindStatement = { query ->
                query.bindBranch(startIndex = 1)
                query.bindBranch(startIndex = 9)
                query.bindInt(index = 17, value = limit)
            },
        )
    }

    override fun oldestFeedPostsQuery(limit: Int): RoomRawQuery {
        return RoomRawQuery(
            sql = "$LATEST_BASIC_QUERY $orderByClause ASC, position ASC LIMIT ?",
            onBindStatement = { query ->
                query.bindBranch(startIndex = 1)
                query.bindBranch(startIndex = 9)
                query.bindInt(index = 17, value = limit)
            },
        )
    }
}
