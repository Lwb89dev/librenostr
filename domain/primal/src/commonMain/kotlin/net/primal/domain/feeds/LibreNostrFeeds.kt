package net.primal.domain.feeds

fun defaultLibreNostrNoteFeeds(userId: String): List<PrimalFeed> =
    listOf(
        PrimalFeed(
            ownerId = userId,
            spec = "{\"id\":\"latest\",\"kind\":\"notes\"}",
            specKind = FeedSpecKind.Notes,
            feedKind = FEED_KIND_USER,
            title = "Latest",
            description = "Notes from people you follow",
            enabled = true,
            position = 0,
        ),
        PrimalFeed(
            ownerId = userId,
            spec = "{\"id\":\"latest\",\"include_replies\":true,\"kind\":\"notes\"}",
            specKind = FeedSpecKind.Notes,
            feedKind = FEED_KIND_USER,
            title = "Latest with replies",
            description = "Notes and replies from people you follow",
            enabled = true,
            position = 1,
        ),
    )

fun defaultLibreNostrReadFeeds(userId: String): List<PrimalFeed> =
    listOf(
        PrimalFeed(
            ownerId = userId,
            spec = "{\"id\":\"latest\",\"kind\":\"reads\"}",
            specKind = FeedSpecKind.Reads,
            feedKind = FEED_KIND_USER,
            title = "Latest reads",
            description = "Articles from people you follow",
            enabled = true,
            position = 0,
        ),
    )

fun mergeDefaultNoteFeeds(userId: String, existing: List<PrimalFeed>): List<PrimalFeed> {
    val defaults = defaultLibreNostrNoteFeeds(userId)
    if (existing.isEmpty()) return defaults
    val bySpec = existing.associateBy { it.spec }
    val defaultSpecs = defaults.map { it.spec }.toSet()
    val orderedDefaults = defaults.mapIndexed { index, default ->
        bySpec[default.spec]?.copy(
            title = default.title,
            description = default.description,
            position = index,
        ) ?: default
    }
    val rest = existing.filter { it.spec !in defaultSpecs }
        .mapIndexed { index, feed -> feed.copy(position = defaults.size + index) }
    return orderedDefaults + rest
}

fun defaultNoteFeedsNeedSync(existing: List<PrimalFeed>, merged: List<PrimalFeed>): Boolean {
    if (existing.size != merged.size) return true
    return existing.zip(merged).any { (current, next) ->
        current.spec != next.spec || current.position != next.position
    }
}
