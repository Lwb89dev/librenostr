package net.primal.android.bookmarks.drawer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import net.primal.android.R
import net.primal.android.core.compose.UniversalAvatarThumbnail
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.primaliconpack.Bookmarks
import net.primal.android.notes.feed.list.NoteFeedViewModel
import net.primal.android.notes.feed.model.FeedPostUi
import net.primal.android.notes.feed.model.rememberSharedEventLabels
import net.primal.android.notes.feed.model.replaceSharedEventsWithLabels
import net.primal.android.theme.AppTheme
import net.primal.domain.feeds.buildNotesBookmarksFeedSpec

/**
 * The lower half of the algorithm drawer: the notes the user bookmarked, newest first.
 *
 * It reads the same bookmarks feed the full Bookmarks screen does, through the same view model, so
 * the two can never disagree about what is saved; only the presentation differs. A full note card
 * does not fit a 320dp drawer, so each entry is a compact row: who wrote it and how it starts.
 * Tapping one opens the note, and [onSeeAllClick] goes to the full screen for everything else a
 * card can do.
 */
@Composable
fun BookmarksDrawerSection(
    activeUserId: String,
    onNoteClick: (noteId: String) -> Unit,
    onSeeAllClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        BookmarksDrawerHeader(onSeeAllClick = onSeeAllClick)
        BookmarksDrawerList(
            modifier = Modifier
                .weight(1f)
                .navigationBarsPadding(),
            activeUserId = activeUserId,
            onNoteClick = onNoteClick,
        )
    }
}

@Composable
private fun BookmarksDrawerHeader(onSeeAllClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            modifier = Modifier.size(HEADER_ICON_SIZE),
            imageVector = PrimalIcons.Bookmarks,
            contentDescription = null,
            tint = AppTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            modifier = Modifier.weight(1f),
            text = stringResource(id = R.string.bookmarks_drawer_title),
            style = AppTheme.typography.titleLarge,
            color = AppTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onSeeAllClick) {
            Text(text = stringResource(id = R.string.bookmarks_drawer_see_all))
        }
    }
}

@Composable
private fun BookmarksDrawerList(
    activeUserId: String,
    onNoteClick: (noteId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val feedSpec = remember(activeUserId) { buildNotesBookmarksFeedSpec(userId = activeUserId) }
    // Same key and arguments as the full screen's feed, so one view model serves both.
    val viewModel = hiltViewModel<NoteFeedViewModel, NoteFeedViewModel.Factory>(
        key = "NoteFeedViewModel_$feedSpec",
    ) { factory ->
        factory.create(feedSpec = feedSpec, allowMutedThreads = false)
    }
    val state by viewModel.state.collectAsState()
    val notes = state.notes.collectAsLazyPagingItems()
    val refreshState = notes.loadState.refresh

    Box(modifier = modifier.fillMaxSize()) {
        when {
            notes.itemCount > 0 -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(count = notes.itemCount, key = notes.itemKey { it.postId }) { index ->
                    notes[index]?.let { note ->
                        BookmarkDrawerItem(post = note, onClick = { onNoteClick(note.postId) })
                    }
                }
            }

            refreshState is LoadState.Loading -> CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp),
                strokeWidth = 2.dp,
            )

            refreshState is LoadState.Error -> DrawerMessage(
                text = stringResource(id = R.string.bookmarks_drawer_error),
                onClick = { notes.retry() },
            )

            else -> DrawerMessage(text = stringResource(id = R.string.bookmarks_drawer_empty))
        }
    }
}

@Composable
private fun DrawerMessage(text: String, onClick: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = AppTheme.typography.bodyMedium,
            color = AppTheme.extraColorScheme.onSurfaceVariantAlt2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BookmarkDrawerItem(post: FeedPostUi, onClick: () -> Unit) {
    val sharedEventLabel = rememberSharedEventLabels()
    val preview = remember(post.postId, post.content, sharedEventLabel) {
        post.content
            .replaceSharedEventsWithLabels(nostrUris = post.nostrUris, labelFor = sharedEventLabel)
            .asDrawerPreview()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UniversalAvatarThumbnail(
            avatarSize = AVATAR_SIZE,
            avatarCdnImage = post.authorAvatarCdnImage,
            avatarBlossoms = post.authorBlossoms,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = post.authorName,
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = preview.ifBlank { stringResource(id = R.string.bookmarks_drawer_media_only) },
                style = AppTheme.typography.bodySmall,
                color = AppTheme.extraColorScheme.onSurfaceVariantAlt2,
                maxLines = PREVIEW_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val UrlRegex = Regex("""https?://\S+""")
private val NostrUriRegex = Regex("""nostr:\S+""")
private val WhitespaceRegex = Regex("""\s+""")

/**
 * The text of a note as a one-glance summary. Links and `nostr:` references are what a note's own
 * renderer turns into cards and mentions; as raw text they are long unreadable strings that would
 * fill the whole preview, so they are dropped. A note left with nothing is media or a bare link.
 */
internal fun String.asDrawerPreview(): String =
    replace(UrlRegex, "")
        .replace(NostrUriRegex, "")
        .replace(WhitespaceRegex, " ")
        .trim()

private val HEADER_HEIGHT = 64.dp
private val HEADER_ICON_SIZE = 24.dp
private val AVATAR_SIZE = 32.dp
private const val PREVIEW_MAX_LINES = 2
