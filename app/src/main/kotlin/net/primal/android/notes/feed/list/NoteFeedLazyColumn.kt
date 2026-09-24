package net.primal.android.notes.feed.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import io.github.aakira.napier.Napier
import net.primal.android.R
import net.primal.android.core.compose.ListLoadingError
import net.primal.android.core.compose.ListNoContent
import net.primal.android.core.compose.LibreNostrLoadingSpinner
import net.primal.android.core.compose.PremiumFeedPaywall
import net.primal.android.core.compose.heightAdjustableLoadingLazyListPlaceholder
import net.primal.android.core.compose.isEmpty
import net.primal.android.core.compose.isNotEmpty
import net.primal.android.core.compose.zaps.FeedNoteTopZapsSection
import net.primal.android.core.errors.UiError
import net.primal.android.nostr.mappers.asFeedPostUi
import net.primal.android.notes.feed.model.EventStatsUi
import net.primal.android.notes.feed.model.FeedPostUi
import net.primal.android.notes.feed.note.FeedNoteCard
import net.primal.android.notes.feed.note.MediaFeedCard
import net.primal.android.notes.feed.note.ui.events.NoteCallbacks
import net.primal.android.theme.AppTheme
import net.primal.domain.nostr.ReactionType

internal const val FEED_NESTED_NOTES_CUT_OFF_LIMIT = 2

@Composable
fun NoteFeedLazyColumn(
    modifier: Modifier = Modifier,
    pagingItems: LazyPagingItems<FeedPostUi>,
    listState: LazyListState,
    showPaywall: Boolean,
    noteCallbacks: NoteCallbacks,
    statsOverrides: State<Map<String, EventStatsUi>> = remember { mutableStateOf(emptyMap<String, EventStatsUi>()) },
    useMediaCards: Boolean = false,
    showTopZaps: Boolean = false,
    showCentralLoadingSpinner: Boolean = false,
    shouldShowLoadingState: Boolean = true,
    shouldShowNoContentState: Boolean = true,
    showReplyTo: Boolean = true,
    noContentVerticalArrangement: Arrangement.Vertical = Arrangement.Center,
    noContentPaddingValues: PaddingValues = PaddingValues(all = 0.dp),
    noContentText: String = stringResource(id = R.string.feed_no_content),
    contentPadding: PaddingValues = PaddingValues(all = 0.dp),
    header: @Composable (LazyItemScope.() -> Unit)? = null,
    stickyHeader: @Composable (LazyItemScope.() -> Unit)? = null,
    onUiError: ((UiError) -> Unit)? = null,
    onRetryAppend: (() -> Unit)? = null,
) {
    val pagingItemsOffset = (if (stickyHeader != null) 1 else 0) + (if (header != null) 1 else 0) + 1
    val firstVisibleVideoPlayingIndex = rememberFirstVisibleVideoPlayingItemIndex(
        listState = listState,
        itemIndexOffset = pagingItemsOffset,
        hasVideo = { index ->
            if (index < 0 || index >= pagingItems.itemCount) {
                false
            } else {
                hasAutoPlayVideo(pagingItems.peek(index))
            }
        },
    )
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        state = listState,
    ) {
        if (stickyHeader != null) {
            stickyHeader {
                stickyHeader()
            }
        }

        if (header != null) {
            item {
                header()
            }
        }

        when (val prependMediatorLoadState = pagingItems.loadState.mediator?.prepend) {
            is LoadState.Error -> {
                item(contentType = "PrependError") {
                    val error = prependMediatorLoadState.error
                    Napier.w(throwable = error) { "Error loading prev page" }
                    ListLoadingError(
                        text = stringResource(R.string.app_error_loading_prev_page) + "\n${error.message}",
                    )
                }
            }

            else -> Unit
        }

        items(
            count = pagingItems.itemCount,
            key = pagingItems.itemKey(key = { "${it.postId}${it.repostId}" }),
            contentType = pagingItems.itemContentType(),
        ) { index ->
            val item = pagingItems[index]
            val couldAutoPlay by remember(index) {
                derivedStateOf { index == firstVisibleVideoPlayingIndex.value }
            }
            // Scoped to this one item's postId so a stats update for note A never recomposes
            // note B's row — reading statsOverrides.value directly here (a whole-map State)
            // would recompose every visible row on every update, the same class of over-broad
            // redraw the mediator's own invalidate() used to cause, just via Compose instead.
            val overrideStats by remember(item?.postId) {
                derivedStateOf { item?.postId?.let { statsOverrides.value[it] } }
            }
            val renderedItem = overrideStats?.let { item?.copy(stats = it) } ?: item

            when {
                renderedItem != null -> Column {
                    if (useMediaCards) {
                        MediaFeedCard(
                            data = renderedItem,
                            noteCallbacks = noteCallbacks,
                            couldAutoPlay = couldAutoPlay,
                            onUiError = onUiError,
                        )
                    } else {
                        FeedNoteCard(
                            data = renderedItem,
                            shape = AppTheme.shapes.large,
                            border = BorderStroke(1.dp, AppTheme.libreNostrTokens.softOutline),
                            cardPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                            fullWidthContent = true,
                            forceContentIndent = true,
                            nestingCutOffLimit = FEED_NESTED_NOTES_CUT_OFF_LIMIT,
                            showReplyTo = showReplyTo,
                            couldAutoPlay = couldAutoPlay,
                            noteCallbacks = noteCallbacks,
                            onUiError = onUiError,
                            contentFooter = {
                                if (showTopZaps && renderedItem.eventZaps.isNotEmpty()) {
                                    FeedNoteTopZapsSection(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp)
                                            .padding(top = 4.dp, end = 2.dp),
                                        zaps = renderedItem.eventZaps,
                                        onClick = if (noteCallbacks.onEventReactionsClick != null) {
                                            {
                                                noteCallbacks.onEventReactionsClick
                                                    .invoke(renderedItem.postId, ReactionType.ZAPS, null)
                                            }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            },
                        )
                    }

                }

                else -> {}
            }
        }

        if (pagingItems.isEmpty()) {
            when (val refreshLoadState = pagingItems.loadState.refresh) {
                LoadState.Loading -> {
                    if (shouldShowLoadingState) {
                        if (showCentralLoadingSpinner) {
                            item(contentType = "SearchLoading") {
                                Box(
                                    modifier = Modifier.fillParentMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    LibreNostrLoadingSpinner()
                                }
                            }
                        } else {
                            heightAdjustableLoadingLazyListPlaceholder()
                        }
                    }
                }

                is LoadState.NotLoading -> {
                    if (shouldShowNoContentState && pagingItems.loadState.isIdle) {
                        item(contentType = "NoContent") {
                            ListNoContent(
                                modifier = Modifier.fillParentMaxSize(),
                                noContentText = noContentText,
                                refreshButtonVisible = false,
                                onRefresh = { pagingItems.refresh() },
                                verticalArrangement = noContentVerticalArrangement,
                                contentPadding = noContentPaddingValues,
                            )
                        }
                    }
                }

                is LoadState.Error -> {
                    val error = refreshLoadState.error
                    Napier.w(throwable = error) { "Error loading feed" }
                    item(contentType = "RefreshError") {
                        ListNoContent(
                            modifier = Modifier.fillParentMaxSize(),
                            noContentText = stringResource(id = R.string.feed_error_loading),
                            onRefresh = { pagingItems.refresh() },
                            verticalArrangement = noContentVerticalArrangement,
                            contentPadding = noContentPaddingValues,
                        )
                    }
                }
            }
        }

        when (val appendMediatorLoadState = pagingItems.loadState.mediator?.append) {
            LoadState.Loading -> {
                heightAdjustableLoadingLazyListPlaceholder(
                    contentType = { "LoadingPrepend" },
                    repeat = 1,
                )
            }

            is LoadState.Error -> {
                item(contentType = "AppendError") {
                    val error = appendMediatorLoadState.error
                    Napier.w(throwable = error) { "Error loading next page" }
                    ListLoadingError(
                        text = stringResource(R.string.app_error_loading_next_page) + "\n${error.message}",
                    )
                }
            }

            is LoadState.NotLoading -> {
                // NoteFeedRemoteMediator's APPEND direction now only ends deliberately, after a
                // bounded run of empty relay batches — not necessarily "no more history exists",
                // just nothing found in the spans probed so far. Offer a way to keep going
                // instead of a silent, permanent wall.
                val retryAppend = onRetryAppend
                val isExhausted = appendMediatorLoadState.endOfPaginationReached
                if (isExhausted && pagingItems.isNotEmpty() && retryAppend != null) {
                    item(contentType = "AppendExhausted") {
                        AppendExhaustedFooter(onClick = retryAppend)
                    }
                }
            }

            else -> Unit
        }

        if (pagingItems.isNotEmpty() && showPaywall) {
            item(contentType = "Paywall") {
                PremiumFeedPaywall(
                    onClick = {
                        noteCallbacks.onGetPrimalPremiumClick?.invoke()
                    },
                )
            }
        }

        if (pagingItems.isNotEmpty()) {
            item(contentType = "Footer") {
                Spacer(modifier = Modifier.height(104.dp))
            }
        }
    }
}

@Composable
private fun AppendExhaustedFooter(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 32.dp),
            text = stringResource(id = R.string.feed_append_exhausted_message),
            textAlign = TextAlign.Center,
        )
        TextButton(
            modifier = Modifier.padding(vertical = 8.dp),
            onClick = onClick,
        ) {
            Text(text = stringResource(id = R.string.feed_append_exhausted_button).uppercase())
        }
    }
}

private fun hasAutoPlayVideo(post: FeedPostUi?): Boolean =
    post != null && hasAutoPlayVideoRecursive(post, mutableSetOf())

private fun hasAutoPlayVideoRecursive(post: FeedPostUi, visitedIds: MutableSet<String>): Boolean {
    var hasVideo = false

    if (visitedIds.add(post.postId)) {
        val attachmentCount = post.uris.count { uri ->
            uri.mimeType?.let { it.startsWith("video") || it.startsWith("image") } == true
        }
        if (attachmentCount <= 1) {
            hasVideo = post.uris.any { it.mimeType?.startsWith("video") == true }

            if (!hasVideo) {
                hasVideo = post.nostrUris.any { nostrUri ->
                    nostrUri.referencedNote
                        ?.asFeedPostUi()
                        ?.let { hasAutoPlayVideoRecursive(it, visitedIds) }
                        ?: false
                }
            }
        }
    }

    return hasVideo
}
