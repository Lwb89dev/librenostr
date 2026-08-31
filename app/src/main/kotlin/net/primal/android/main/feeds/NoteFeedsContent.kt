package net.primal.android.main.feeds

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.primal.android.R
import net.primal.android.core.compose.FeedsErrorColumn
import net.primal.android.core.compose.HeightAdjustableLoadingLazyListPlaceholder
import net.primal.android.core.compose.PrimalTopLevelAppBar
import net.primal.android.core.errors.resolveUiErrorMessage
import net.primal.android.feeds.list.ui.model.FeedUi
import net.primal.android.main.feeds.NoteFeedsContract.UiEvent
import net.primal.android.notes.feed.list.NoteFeedList
import net.primal.android.notes.feed.note.ui.events.NoteCallbacks
import net.primal.android.premium.legend.domain.LegendaryCustomization
import net.primal.domain.links.CdnImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteFeedsContent(
    state: NoteFeedsContract.UiState,
    pagerState: PagerState,
    noteCallbacks: NoteCallbacks,
    eventPublisher: (UiEvent) -> Unit,
    onActiveFeedChanged: (FeedUi?) -> Unit,
    selectedFeed: FeedUi? = null,
    topAppBarCollapsedFraction: Float,
    shouldAnimateScrollToTop: MutableState<Boolean>,
    scrollToFeed: MutableState<FeedUi?> = remember { mutableStateOf(null) },
    snackbarHostState: SnackbarHostState,
    paddingValues: PaddingValues,
    onGoToWallet: () -> Unit,
    onNewNotesStateChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()

    var activeFeed by remember { mutableStateOf<FeedUi?>(null) }

    val pollingStates by remember(activeFeed, state.feeds) {
        derivedStateOf {
            state.feeds.associateWith { feed ->
                activeFeed?.spec == feed.spec
            }
        }
    }

    LaunchedEffect(state.feeds, selectedFeed?.spec) {
        val selected = selectedFeed?.let { requested ->
            state.feeds.firstOrNull { it.spec == requested.spec }
        }
        val current = activeFeed
        val feed = selected ?: current?.let { currentFeed ->
            state.feeds.firstOrNull { it.spec == currentFeed.spec }
        } ?: state.feeds.firstOrNull()
        if (feed?.spec != current?.spec) {
            activeFeed = feed
            onActiveFeedChanged(feed)
        }
    }

    LaunchedEffect(scrollToFeed.value) {
        val feed = scrollToFeed.value ?: return@LaunchedEffect
        val resolvedFeed = state.feeds.firstOrNull { it.spec == feed.spec }
        val pageIndex = state.feeds.indexOfFirst { it.spec == feed.spec }
        if (pageIndex >= 0) {
            pagerState.scrollToPage(page = pageIndex)
        }
        if (resolvedFeed != null && activeFeed?.spec != resolvedFeed.spec) {
            activeFeed = resolvedFeed
            onActiveFeedChanged(resolvedFeed)
        }
        scrollToFeed.value = null
    }

    val visibleFeed = activeFeed ?: state.feeds.firstOrNull()
    if (visibleFeed != null) {
        NoteFeedList(
            feedSpec = visibleFeed.spec,
            pollingEnabled = pollingStates[visibleFeed] ?: true,
            noteCallbacks = noteCallbacks,
            showTopZaps = true,
            bigPillStreams = if (state.showLiveStreams) state.streams else emptyList(),
            showStreamsInNewPill = state.showLiveStreams,
            onGoToWallet = onGoToWallet,
            contentPadding = paddingValues,
            shouldAnimateScrollToTop = shouldAnimateScrollToTop.value,
            onNewNotesStateChanged = onNewNotesStateChanged,
            onUiError = { uiError ->
                uiScope.launch {
                    snackbarHostState.showSnackbar(
                        message = uiError.resolveUiErrorMessage(context),
                        duration = SnackbarDuration.Short,
                    )
                }
            },
        )
    } else if (state.loading) {
        HeightAdjustableLoadingLazyListPlaceholder(
            height = 128.dp,
            contentPaddingValues = paddingValues,
            itemPadding = PaddingValues(horizontal = 16.dp),
        )
    } else {
        FeedsErrorColumn(
            modifier = Modifier.fillMaxSize(),
            text = stringResource(id = R.string.feeds_error_loading_user_feeds),
            onRefresh = { eventPublisher(UiEvent.RefreshNoteFeeds) },
            onRestoreDefaultFeeds = { eventPublisher(UiEvent.RestoreDefaultNoteFeeds) },
        )
    }
}

@ExperimentalMaterial3Api
@Composable
internal fun NoteFeedTopAppBar(
    title: String,
    pagerState: PagerState,
    feeds: List<FeedUi>,
    avatarCdnImage: CdnImage?,
    onAvatarClick: () -> Unit,
    onMenuClick: (() -> Unit)? = null,
    onAvatarSwipeDown: (() -> Unit)? = null,
    onSearchClick: () -> Unit,
    activeFeed: FeedUi?,
    avatarLegendaryCustomization: LegendaryCustomization? = null,
    avatarBlossoms: List<String> = emptyList(),
    scrollBehavior: TopAppBarScrollBehavior? = null,
    titleOverride: String? = null,
    subtitleOverride: String? = null,
    chevronExpanded: Boolean = false,
    showAvatar: Boolean = true,
    onSearchSubmit: ((String) -> Unit)? = null,
    onSearchProfileClick: ((String) -> Unit)? = null,
) {
    PrimalTopLevelAppBar(
        title = title,
        subtitle = activeFeed?.description?.ifBlank { null },
        titleOverride = titleOverride,
        subtitleOverride = subtitleOverride,
        showTitleChevron = false,
        chevronExpanded = chevronExpanded,
        pagerState = pagerState,
        pages = emptyList(),
        avatarCdnImage = avatarCdnImage,
        avatarBlossoms = avatarBlossoms,
        avatarLegendaryCustomization = avatarLegendaryCustomization,
        onAvatarClick = onAvatarClick,
        onMenuClick = onMenuClick,
        onAvatarSwipeDown = onAvatarSwipeDown,
        scrollBehavior = scrollBehavior,
        onSearchClick = onSearchClick,
        searchPlaceholder = stringResource(id = R.string.home_search_placeholder),
        showAvatar = showAvatar,
        onSearchSubmit = onSearchSubmit,
        onSearchProfileClick = onSearchProfileClick,
    )
}
