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
