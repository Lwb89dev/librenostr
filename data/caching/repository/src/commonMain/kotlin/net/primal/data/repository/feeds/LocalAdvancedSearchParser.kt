package net.primal.data.repository.feeds

import net.primal.domain.feeds.AdvancedSearchParsedQuery

/** Parses the command emitted by advanced search without a remote parser. */
internal fun parseAdvancedSearchQueryLocally(query: String): AdvancedSearchParsedQuery {
    val normalized = query.trim()
    val kind = when {
        "filter:image" in normalized -> "Images"
        "filter:video" in normalized -> "Video"
        "filter:audio" in normalized -> "Audio"
        "repliestokind:1" in normalized -> "Note Replies"
        "repliestokind:30023" in normalized -> "Reads Comments"
        "kind:30023" in normalized -> "Reads"
        else -> "Notes"
    }

    fun valuesFor(prefix: String): List<String> =
        Regex("(?:^|\\s|\\()$prefix:?\\(?([A-Za-z0-9_]+)")
            .findAll(normalized)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .toList()

    val since = Regex("(?:^|\\s)since:([^\\s]+)").find(normalized)?.groupValues?.get(1).orEmpty()
    val until = Regex("(?:^|\\s)until:([^\\s]+)").find(normalized)?.groupValues?.get(1).orEmpty()
    val timeframe = when (since.lowercase()) {
        "yesterday" -> "Today"
        "lastweek" -> "This Week"
        "lastmonth" -> "This Month"
        "lastyear" -> "This Year"
        else -> "Anytime"
    }

    fun number(name: String): Int =
        Regex("(?:^|\\s)$name:(\\d+)").find(normalized)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    val hashtags = Regex("(?:^|\\s)#([A-Za-z0-9_]+)")
        .findAll(normalized)
        .map { it.groupValues[1] }
        .distinct()
        .joinToString(" ")

    val control = Regex(
        "(?:^|\\s)(?:kind|filter|repliestokind|since|until|scope|orderby|orientation|" +
            "minwords|maxwords|minduration|maxduration|minscore|mininteractions|minlikes|" +
            "minzaps|minreplies|minreposts|pas):[^\\s]+",
    )
    val profileGroups = Regex("\\([^)]*\\)")
    fun cleanWords() = profileGroups.replace(control.replace(normalized, ""), " ")
        .split(Regex("\\s+"))
        .map { it.trim('"', '\'', ',', '.', '(', ')') }
        .filter { it.isNotBlank() }
    val includes = cleanWords().filter { !it.startsWith("-") && !it.startsWith("#") }.joinToString(" ")
    val excludes = cleanWords().filter { it.startsWith("-") }.joinToString(" ") { it.removePrefix("-") }

    val scope = when (Regex("(?:^|\\s)scope:([^\\s]+)").find(normalized)?.groupValues?.get(1)) {
        "myfollows" -> "My Follows"
        "mynetwork" -> "My Network"
        "myfollowsinteractions" -> "My Follows Interactions"
        "mynetworkinteractions" -> "My Network Interactions"
        "notmyfollows" -> "Not My Follows"
        "mynotifications" -> "My Notifications"
        else -> "Global"
    }
    val sortBy = when (Regex("(?:^|\\s)orderby:([^\\s]+)").find(normalized)?.groupValues?.get(1)) {
        "score" -> "Content Score"
        "replies" -> "Number of Replies"
        "satszapped" -> "Sats Zapped"
        "likes" -> "Number of Interactions"
        else -> "Time"
    }
    val orientation = when (Regex("(?:^|\\s)orientation:([^\\s]+)").find(normalized)?.groupValues?.get(1)) {
        "horizontal" -> "Horizontal"
        "vertical" -> "Vertical"
        else -> "Any"
    }

    return AdvancedSearchParsedQuery(
        includes = includes,
        excludes = excludes,
        hashtags = hashtags,
        kind = kind,
        postedBy = valuesFor("from"),
        replyingTo = valuesFor("to"),
        zappedBy = valuesFor("zappedby"),
        timeframe = timeframe,
        customTimeframeSince = if (since.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) "${since}T00:00:00Z" else "",
        customTimeframeUntil = if (until.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) "${until}T23:59:59Z" else "",
        scope = scope,
        sortBy = sortBy,
        orientation = orientation,
        minWords = number("minwords"),
        maxWords = number("maxwords"),
        minDuration = number("minduration"),
        maxDuration = number("maxduration"),
        minScore = number("minscore"),
        minInteractions = number("mininteractions"),
        minLikes = number("minlikes"),
        minZaps = number("minzaps"),
        minReplies = number("minreplies"),
        minReposts = number("minreposts"),
        following = emptyList(),
        userMentions = emptyList(),
        sentiment = "Neutral",
    )
}
