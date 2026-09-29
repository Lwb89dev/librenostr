package net.primal.android.deck

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import net.primal.android.core.compose.UniversalAvatarThumbnail
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.primaliconpack.ArrowBack
import net.primal.android.notes.feed.list.NoteFeedList
import net.primal.android.notes.feed.note.ui.events.NoteCallbacks
import net.primal.domain.feeds.buildProfileAuthoredNotesFeedSpec

/**
 * A lightweight profile view for deck columns: avatar/name/NIP-05/bio header above that profile's
 * note feed. Not the full [net.primal.android.profile.details.ProfileDetailsScreen] (its scrolling
 * parallax header is built for full-screen width, not a 380dp column) — this covers what tapping a
 * profile from inside a column needs without leaving deck mode.
 */
@Composable
fun DeckProfileContent(profileId: String, noteCallbacks: NoteCallbacks, onBack: () -> Unit) {
    val viewModel = hiltViewModel<DeckProfileViewModel, DeckProfileViewModel.Factory>(
        key = "DeckProfileViewModel_$profileId",
    ) { factory -> factory.create(profileId = profileId) }
    val profile by viewModel.profile.collectAsState()

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = PrimalIcons.ArrowBack, contentDescription = null)
            }
            UniversalAvatarThumbnail(
                avatarCdnImage = profile?.avatarCdnImage,
                avatarBlossoms = profile?.blossoms.orEmpty(),
                avatarSize = 40.dp,
            )
            Column(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
                Text(
                    text = profile?.displayName ?: profile?.handle ?: profileId,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                profile?.internetIdentifier?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
        NoteFeedList(feedSpec = buildProfileAuthoredNotesFeedSpec(profileId), noteCallbacks = noteCallbacks)
    }
}
