package net.primal.android.deck

import androidx.compose.runtime.saveable.Saver

/**
 * What a single deck column is showing right now — local, in-memory navigation that never
 * touches the app's root [androidx.navigation.NavController], so drilling into a note/profile/DM
 * from inside a column never leaves deck mode. See [DeckColumnView].
 */
sealed class DeckColumnDestination {
    data object Feed : DeckColumnDestination()
    data class Thread(val noteId: String) : DeckColumnDestination()
    data class Profile(val profileId: String) : DeckColumnDestination()
    data class Chat(val profileId: String) : DeckColumnDestination()
}

/**
 * Without this, `rememberSaveable` has no way to persist a sealed class, so a drilled-into
 * thread/profile/chat would reset to Feed both on rotation and whenever `LazyRow` recycles the
 * column's composable after it scrolls out of the viewport.
 */
val DeckColumnDestinationSaver = Saver<DeckColumnDestination, List<String>>(
    save = { destination ->
        when (destination) {
            DeckColumnDestination.Feed -> listOf("Feed")
            is DeckColumnDestination.Thread -> listOf("Thread", destination.noteId)
            is DeckColumnDestination.Profile -> listOf("Profile", destination.profileId)
            is DeckColumnDestination.Chat -> listOf("Chat", destination.profileId)
        }
    },
    restore = { saved ->
        when (saved.getOrNull(0)) {
            "Thread" -> DeckColumnDestination.Thread(saved[1])
            "Profile" -> DeckColumnDestination.Profile(saved[1])
            "Chat" -> DeckColumnDestination.Chat(saved[1])
            else -> DeckColumnDestination.Feed
        }
    },
)
