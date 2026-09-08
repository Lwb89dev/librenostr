package net.primal.data.local.dao.threads

import androidx.room3.Entity
import androidx.room3.Index

/**
 * Marks [replyNoteId] as belonging to the conversation identified by [noteId] — membership, not a
 * structural parent/child edge, despite the field name. Two call sites write it for different
 * reasons and both are load-bearing:
 *  - `FeedResponseProcessor.persistToDatabase`: a genuine one-hop edge, `noteId` = the post's own
 *    direct parent (from its NIP-10 reply/root tag). Written for every post as it's seen anywhere
 *    (feed, notifications, …), so opening a note's thread later can already show something from
 *    data collected before that thread was ever opened.
 *  - `FeedResponseProcessor.persistNoteRepliesAndArticleCommentsToDatabase`: every post fetched
 *    for one thread-open gets `noteId` = the opened note, regardless of its real depth. This is
 *    what makes a reply-to-a-reply reachable at all: `ThreadConversationDao.observeNoteConversation`
 *    is a single join on this table, not a recursive one, so without this a grandchild reply
 *    would never come back from a query for the root's conversation.
 *
 * The actual reply structure (who is whose direct parent, how deep each post sits) is reconstructed
 * separately, client-side, from each post's own NIP-10 tags — see `ReplyTree.buildReplyTree` in the
 * app module. This table's only job is making sure every post that might belong to a conversation is
 * in the result set for that walk to work with; removing either write path breaks it (respectively:
 * losing the pre-fetch preview, or losing every reply nested more than one level deep).
 */
@Entity(
    primaryKeys = [
        "noteId",
        "replyNoteId",
    ],
    indices = [
        Index(value = ["noteId"]),
        Index(value = ["replyNoteId"]),
    ],
)
data class NoteConversationCrossRef(
    val noteId: String,
    val replyNoteId: String,
)
