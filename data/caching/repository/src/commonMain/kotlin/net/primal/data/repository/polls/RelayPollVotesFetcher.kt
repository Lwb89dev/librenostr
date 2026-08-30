package net.primal.data.repository.polls

import net.primal.data.local.dao.polls.PollData
import net.primal.data.local.dao.polls.PollType
import net.primal.data.local.dao.polls.PollVoteData
import net.primal.data.repository.mappers.remote.asProfileDataPOFromRelay
import net.primal.data.repository.mappers.remote.latestMetadataByPubkey
import net.primal.data.repository.mappers.remote.mapAsPollResponseVotes
import net.primal.data.repository.mappers.remote.mapAsZapPollVotes
import net.primal.data.repository.mappers.remote.mapNotNullAsPollDataPO
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/** Reads poll definitions, votes and voter metadata directly from the configured relays. */
internal class RelayPollVotesFetcher(
    private val querier: RelayEventQuerier,
) {

    suspend fun fetch(
        postId: String,
        optionId: String? = null,
        limit: Int = DEFAULT_LIMIT,
    ): PollVotesResult {
        val poll = querier.query(
            RelayFilter(
                ids = listOf(postId),
                kinds = listOf(NostrEventKind.Poll.value, NostrEventKind.ZapPoll.value),
                limit = 1,
            ),
        ).mapNotNullAsPollDataPO().firstOrNull()

        val voteEvents = when (poll?.pollType) {
            PollType.Zap -> querier.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.Zap.value),
                    eventTags = listOf(postId),
                    limit = limit.coerceAtLeast(DEFAULT_LIMIT),
                ),
            )

            else -> querier.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.PollResponse.value),
                    eventTags = listOf(postId),
                    limit = limit.coerceAtLeast(DEFAULT_LIMIT),
                ),
            )
        }.distinctBy { it.id }

        val allVotes = when (poll?.pollType) {
            PollType.Zap -> voteEvents.mapAsZapPollVotes()
            else -> voteEvents.mapAsPollResponseVotes()
        }.filter { it.postId == postId }

        val selectedVotes = optionId?.let { selectedOption ->
            allVotes.filter { it.optionId == selectedOption }
        } ?: allVotes

        val voterIds = allVotes.map { it.voterId }.distinct()
        val profiles = if (voterIds.isEmpty()) {
            emptyList()
        } else {
            querier.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.Metadata.value),
                    authors = voterIds,
                    limit = voterIds.size,
                ),
            ).latestMetadataByPubkey().map { it.asProfileDataPOFromRelay() }
        }

        val pollWithCounts = poll?.let { pollData ->
            val votesByOption = allVotes.groupBy { it.optionId }
            pollData.copy(
                options = pollData.options.map { option ->
                    val optionVotes = votesByOption[option.id].orEmpty()
                    option.copy(
                        voteCount = optionVotes.size,
                        satsZapped = optionVotes.sumOf { it.amountInSats ?: 0L },
                    )
                },
            )
        }

        return PollVotesResult(
            poll = pollWithCounts,
            votes = selectedVotes,
            profiles = profiles,
        )
    }

    data class PollVotesResult(
        val poll: PollData?,
        val votes: List<PollVoteData>,
        val profiles: List<net.primal.data.local.dao.profiles.ProfileData>,
    )

    private companion object {
        const val DEFAULT_LIMIT = 100
    }
}
