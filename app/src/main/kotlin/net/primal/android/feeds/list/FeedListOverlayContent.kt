package net.primal.android.feeds.list

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import net.primal.android.R
import net.primal.android.core.compose.PrimalOverlayBottomBar
import net.primal.android.core.compose.PrimalOverlayCloseButton
import net.primal.android.feeds.list.FeedListContract.FollowSetUi
import net.primal.android.feeds.list.FeedListContract.UiState.FeedMarketplaceStage
import net.primal.android.theme.AppTheme
import net.primal.android.feeds.list.ui.DvmFeedDetails
import net.primal.android.feeds.list.ui.DvmFeedMarketplace
import net.primal.android.feeds.list.ui.FeedList
import net.primal.android.feeds.list.ui.model.FeedUi
import net.primal.domain.feeds.FeedSpecKind
import net.primal.domain.feeds.buildSpec

@Composable
fun FeedListOverlayContent(
    activeFeed: FeedUi,
    feedSpecKind: FeedSpecKind,
    onFeedClick: (FeedUi) -> Unit,
    onDismiss: () -> Unit,
    onGoToWallet: (() -> Unit)? = null,
    onEditAdvancedSearchFeedClick: ((feedSpec: String) -> Unit)? = null,
) {
    val viewModel = hiltViewModel<FeedListViewModel, FeedListViewModel.Factory>(
        key = "FeedListViewModel_$feedSpecKind",
        creationCallback = { it.create(specKind = feedSpecKind) },
    )
    val uiState = viewModel.state.collectAsState()

    FeedListOverlayContent(
        activeFeed = activeFeed,
        state = uiState.value,
        onFeedClick = onFeedClick,
        onDismiss = onDismiss,
        eventPublisher = viewModel::setEvent,
        onGoToWallet = onGoToWallet,
        onEditAdvancedSearchFeedClick = onEditAdvancedSearchFeedClick,
    )
}

@Composable
private fun FeedListOverlayContent(
    activeFeed: FeedUi,
    state: FeedListContract.UiState,
    onFeedClick: (FeedUi) -> Unit,
    onDismiss: () -> Unit,
    eventPublisher: (FeedListContract.UiEvent) -> Unit,
    onGoToWallet: (() -> Unit)? = null,
    onEditAdvancedSearchFeedClick: ((feedSpec: String) -> Unit)? = null,
) {
    BackHandler {
        when (state.feedMarketplaceStage) {
            FeedMarketplaceStage.FeedList -> if (state.isEditMode) {
                eventPublisher(FeedListContract.UiEvent.CloseEditMode)
            } else {
                onDismiss()
            }
            FeedMarketplaceStage.FeedMarketplace -> eventPublisher(FeedListContract.UiEvent.CloseFeedMarketplace)
            FeedMarketplaceStage.FeedDetails -> eventPublisher(FeedListContract.UiEvent.CloseFeedDetails)
        }
    }

    AnimatedContent(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 16.dp)
            .navigationBarsPadding(),
        targetState = state.feedMarketplaceStage,
        transitionSpec = { transitionSpecBetweenStages() },
        label = "FeedsOverlay",
    ) { target ->
        when (target) {
            FeedMarketplaceStage.FeedList -> FeedListStage(
                activeFeed = activeFeed,
                state = state,
                onFeedClick = onFeedClick,
                onCloseClick = onDismiss,
                eventPublisher = eventPublisher,
                onEditAdvancedSearchFeedClick = onEditAdvancedSearchFeedClick,
            )

            FeedMarketplaceStage.FeedMarketplace -> FollowSetPicker(
                followSets = state.followSets,
                onFollowSetClick = { eventPublisher(FeedListContract.UiEvent.AddFollowSetFeed(it)) },
                onClose = { eventPublisher(FeedListContract.UiEvent.CloseFeedMarketplace) },
            )

            FeedMarketplaceStage.FeedDetails -> FeedDetailsStage(
                state = state,
                eventPublisher = eventPublisher,
                onGoToWallet = onGoToWallet,
            )
        }
    }
}

@Composable
private fun FeedListStage(
    activeFeed: FeedUi,
    state: FeedListContract.UiState,
    onFeedClick: (FeedUi) -> Unit,
    onCloseClick: () -> Unit,
    eventPublisher: (FeedListContract.UiEvent) -> Unit,
    onEditAdvancedSearchFeedClick: ((feedSpec: String) -> Unit)? = null,
) {
    FeedList(
        modifier = Modifier.fillMaxSize(),
        feeds = state.feeds,
        activeFeed = activeFeed,
        onFeedClick = { feed ->
            if (state.isEditMode) {
                eventPublisher(
                    FeedListContract.UiEvent.UpdateFeedSpecEnabled(
                        feedSpec = feed.spec,
                        enabled = !feed.enabled,
                    ),
                )
            } else {
                onFeedClick(feed)
            }
        },
        onEditFeedClick = { eventPublisher(FeedListContract.UiEvent.OpenEditMode) },
        onCloseClick = onCloseClick,
        enableEditMode = true,
        isEditMode = state.isEditMode,
        onAddFeedClick = { eventPublisher(FeedListContract.UiEvent.ShowFeedMarketplace) },
        onEditDoneClick = { eventPublisher(FeedListContract.UiEvent.CloseEditMode) },
        onFeedReordered = { eventPublisher(FeedListContract.UiEvent.FeedReordered(feeds = it)) },
        onFeedEnabled = { feed, enabled ->
            eventPublisher(
                FeedListContract.UiEvent.UpdateFeedSpecEnabled(
                    feedSpec = feed.spec,
                    enabled = enabled,
                ),
            )
        },
        onFeedRemoved = {
            eventPublisher(FeedListContract.UiEvent.RemoveFeedFromUserFeeds(spec = it.spec))
        },
        onRestoreDefaultPrimalFeeds = {
            eventPublisher(FeedListContract.UiEvent.RestoreDefaultPrimalFeeds)
        },
        onEditAdvancedSearchFeedClick = onEditAdvancedSearchFeedClick,
    )
}

@Composable
private fun FollowSetPicker(
    followSets: List<FollowSetUi>,
    onFollowSetClick: (FollowSetUi) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            text = stringResource(id = R.string.follow_set_picker_title),
            style = AppTheme.typography.titleMedium,
            color = AppTheme.colorScheme.onSurface,
        )
        if (followSets.isEmpty()) {
            Text(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                text = stringResource(id = R.string.follow_set_picker_empty),
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.extraColorScheme.onSurfaceVariantAlt2,
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(followSets, key = { it.dTag }) { followSet ->
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFollowSetClick(followSet) },
                        headlineContent = {
                            Text(text = followSet.title, color = AppTheme.colorScheme.onPrimary)
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(
                                    id = R.string.follow_set_picker_members,
                                    followSet.memberCount,
                                ),
                            )
                        },
                    )
                }
            }
        }
        PrimalOverlayBottomBar(
            trailing = { PrimalOverlayCloseButton(onClick = onClose) },
        )
    }
}

@Composable
private fun FeedDetailsStage(
    state: FeedListContract.UiState,
    eventPublisher: (FeedListContract.UiEvent) -> Unit,
    onGoToWallet: (() -> Unit)?,
) {
    val localSelectedFeed by remember(state.selectedDvmFeed) {
        val spec = state.selectedDvmFeed?.data?.buildSpec(specKind = state.specKind)
        val foundFeed = state.feeds.firstOrNull { it.spec == spec }
        mutableStateOf(foundFeed)
    }
    DvmFeedDetails(
        modifier = Modifier.fillMaxSize(),
        dvmFeed = state.selectedDvmFeed,
        localFeed = localSelectedFeed,
        onGoToWallet = onGoToWallet,
        onClose = { eventPublisher(FeedListContract.UiEvent.CloseFeedDetails) },
        onAddOrRemoveFeed = {
            if (state.selectedDvmFeed != null) {
                val localFeed = localSelectedFeed
                when {
                    localFeed == null -> eventPublisher(
                        FeedListContract.UiEvent.AddDvmFeedToUserFeeds(dvmFeed = state.selectedDvmFeed),
                    )
                    localFeed.deletable -> eventPublisher(
                        FeedListContract.UiEvent.RemoveDvmFeedFromUserFeeds(dvmFeed = state.selectedDvmFeed),
                    )
                    localFeed.enabled -> eventPublisher(
                        FeedListContract.UiEvent.DisableFeedInUserFeeds(spec = localFeed.spec),
                    )
                    else -> eventPublisher(
                        FeedListContract.UiEvent.EnableFeedInUserFeeds(spec = localFeed.spec),
                    )
                }
            }
        },
    )
}

private fun AnimatedContentTransitionScope<FeedMarketplaceStage>.transitionSpecBetweenStages() =
    when (initialState) {
        FeedMarketplaceStage.FeedList -> {
            slideInHorizontally(initialOffsetX = { it })
                .togetherWith(slideOutHorizontally(targetOffsetX = { -it }))
        }

        FeedMarketplaceStage.FeedMarketplace -> {
            when (targetState) {
                FeedMarketplaceStage.FeedList -> {
                    slideInHorizontally(initialOffsetX = { -it })
                        .togetherWith(slideOutHorizontally(targetOffsetX = { it }))
                }

                FeedMarketplaceStage.FeedMarketplace, FeedMarketplaceStage.FeedDetails -> {
                    slideInHorizontally(initialOffsetX = { it })
                        .togetherWith(slideOutHorizontally(targetOffsetX = { -it }))
                }
            }
        }

        FeedMarketplaceStage.FeedDetails -> {
            slideInHorizontally(initialOffsetX = { -it })
                .togetherWith(slideOutHorizontally(targetOffsetX = { it }))
        }
    }
