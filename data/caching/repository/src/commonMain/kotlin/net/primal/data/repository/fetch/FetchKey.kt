package net.primal.data.repository.fetch

/**
 * What a fetch is *for*, as opposed to how it is expressed as a relay filter.
 *
 * Two callers that want the same thing must produce the same key even when they would have built
 * slightly different filters for it, because the key is what lets the second caller discover that
 * the first is already asking. Keys are structured rather than a hash of the serialized filter so
 * they stay readable in logs and in test failures, and so a later version can reason about one key
 * covering another without parsing a filter back apart.
 */
internal sealed interface FetchKey {

    /** Kind 0 for one author. Per pubkey rather than per request, so overlapping sets coalesce. */
    data class ProfileMetadata(val pubkey: String) : FetchKey

    /** Kind 3 for one author. Requested independently by most feeds and by the profile screen. */
    data class FollowList(val pubkey: String) : FetchKey

    /** Likes, replies, reposts and zaps pointing at one note. Per note, so feeds share them. */
    data class EventInteractions(val eventId: String) : FetchKey

    /**
     * One page of direct-message conversations.
     *
     * Session start and the messages tab both ask for the newest page, and opening messages right
     * after launching the app runs them at the same time. These are kind 4 requests, which tell a
     * relay who is asking, so sending one instead of two is worth more here than elsewhere.
     *
     * [until] is part of the identity because it is part of the question. Pages of one backfill
     * run in sequence and never overlap, so no test today can tell the difference — but a key that
     * did not name its window could hand a caller somebody else's page, and that is not a property
     * to leave resting on scheduling.
     */
    data class Conversations(val ownerId: String, val until: Long?) : FetchKey

    /** Anything not yet worth its own case. The caller supplies a stable, self-describing name. */
    data class Custom(val name: String) : FetchKey
}
