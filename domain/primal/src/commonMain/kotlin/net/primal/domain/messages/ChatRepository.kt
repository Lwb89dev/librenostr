package net.primal.domain.messages

import androidx.paging.PagingData
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.cryptography.MessageEncryptException
import net.primal.domain.nostr.cryptography.SignatureException
import net.primal.domain.nostr.publisher.NostrPublishException

interface ChatRepository {

    fun observeUnreadMessagesCount(userId: String): Flow<Int>

    fun newestConversations(userId: String, relation: ConversationRelation): Flow<PagingData<DMConversation>>

    fun newestMessages(userId: String, participantId: String): Flow<PagingData<DirectMessage>>

    /**
     * Pulls direct messages newest first, then walks back through [backfillPages] older pages.
     *
     * Session start asks for the backfill: without it a conversation only existed locally once its
     * tab had been opened and scrolled, so a fresh install showed an empty inbox even when the
     * relays held years of messages. A pull-to-refresh passes zero and just takes the newest page.
     *
     * The first page is the one the caller asked for and its failure is thrown; a backfill page
     * failing only means there is no more history to be had right now.
     */
    @Throws(NetworkException::class, CancellationException::class)
    suspend fun syncConversations(userId: String, backfillPages: Int = DEFAULT_BACKFILL_PAGES)

    @Throws(NetworkException::class, CancellationException::class)
    suspend fun fetchNonFollowsConversations(userId: String)

    @Throws(NetworkException::class, CancellationException::class)
    suspend fun fetchNewConversationMessages(userId: String, conversationUserId: String)

    @Throws(NetworkException::class, CancellationException::class)
    suspend fun markConversationAsRead(authorization: NostrEvent, conversationUserId: String)

    @Throws(NetworkException::class, CancellationException::class)
    suspend fun markAllMessagesAsRead(authorization: NostrEvent)

    /** Marks every locally stored conversation as read without a remote service. */
    suspend fun markAllMessagesAsReadLocally(userId: String)

    @Throws(
        MessageEncryptException::class,
        NostrPublishException::class,
        SignatureException::class,
        CancellationException::class,
    )
    suspend fun sendMessage(
        userId: String,
        receiverId: String,
        text: String,
    )

    companion object {
        /** Bounded on purpose: relays hold years of kind-4 events and every one must be decrypted. */
        const val DEFAULT_BACKFILL_PAGES = 3
    }
}
