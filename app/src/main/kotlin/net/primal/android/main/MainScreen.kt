package net.primal.android.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.paging.PagingData
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import net.primal.android.R
import net.primal.android.bookmarks.drawer.BookmarksDrawerSection
import net.primal.android.core.compose.PrimalDivider
import net.primal.android.core.compose.PrimalOverlay
import net.primal.android.core.compose.PrimalTopLevelAppBar
import net.primal.android.core.compose.PrimalTopLevelDestination
import net.primal.android.core.compose.SnackbarErrorHandler
import net.primal.android.core.compose.bubble.AnchorHandle
import net.primal.android.core.compose.bubble.AnchoredBubble
import net.primal.android.core.compose.bubble.BubblePlacement
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.LibreNavigationIcons
import net.primal.android.core.compose.icons.primaliconpack.Bookmarks
import net.primal.android.core.compose.fab.NewPostFloatingActionButton
import net.primal.android.core.compose.runtime.DisposableLifecycleObserverEffect
import net.primal.android.core.errors.resolveUiErrorMessage
import net.primal.android.drawer.DrawerScreenDestination
import net.primal.android.drawer.PrimalDrawer
import net.primal.android.drawer.PrimalMainScaffold
import net.primal.android.drawer.multiaccount.events.AccountSwitcherCallbacks
import net.primal.android.explore.search.ui.SearchScope
import net.primal.android.feeds.list.FeedListOverlayContent
import net.primal.android.feeds.list.ui.model.FeedUi
import net.primal.android.main.explore.section.ExploreSection
import net.primal.android.main.explore.section.ExploreSectionListOverlayContent
import net.primal.android.main.feeds.NoteFeedTopAppBar
import net.primal.android.main.feeds.NoteFeedsContent
import net.primal.android.main.feeds.NoteFeedsContract
import net.primal.android.main.feeds.NoteFeedsViewModel
import net.primal.android.main.notifications.NotificationsContent
import net.primal.android.main.notifications.NotificationsContract
import net.primal.android.main.notifications.NotificationsTopAppBar
import net.primal.android.main.notifications.NotificationsViewModel
import net.primal.android.theme.AppTheme
import net.primal.android.main.reads.ArticleFeedTopAppBar
import net.primal.android.main.reads.ReadsContent
import net.primal.android.main.reads.ReadsScreenContract
import net.primal.android.main.reads.ReadsViewModel
import net.primal.android.navigation.CURRENT_MAIN_TAB_KEY
import net.primal.android.navigation.accountSwitcherCallbacksHandler
import net.primal.android.navigation.primalSlideInHorizontallyFromEnd
import net.primal.android.navigation.primalSlideInHorizontallyFromStart
import net.primal.android.navigation.primalSlideOutHorizontallyToEnd
import net.primal.android.navigation.primalSlideOutHorizontallyToStart
import net.primal.android.navigation.navigateToAdvancedSearch
import net.primal.android.navigation.navigateToHome
import net.primal.android.navigation.navigateToNoteEditor
import net.primal.android.navigation.navigateToMessages
import net.primal.android.navigation.navigateToProfile
import net.primal.android.navigation.navigateToProfileQrCodeViewer
import net.primal.android.navigation.navigateToSearch
import net.primal.android.navigation.navigateToSettings
import net.primal.android.navigation.navigateToThread
import net.primal.android.navigation.noteCallbacksHandler
import net.primal.android.zaps.AndroidLightningWallet
import net.primal.android.notes.feed.note.ui.events.NoteCallbacks
import net.primal.android.notifications.list.ui.NotificationUi
import net.primal.domain.feeds.FeedSpecKind
import net.primal.domain.feeds.defaultLibreNostrNoteFeeds
import net.primal.android.feeds.list.ui.model.asFeedUi
import net.primal.domain.links.CdnImage
import net.primal.domain.notifications.NotificationGroup

internal const val REQUESTED_TAB_KEY = "requestedTab"

@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    navController: NavController,
    navBackStackEntry: NavBackStackEntry,
    onDrawerDestinationClick: (DrawerScreenDestination) -> Unit,
) {
    val uiScope = rememberCoroutineScope()

    // Tab state management
    var activeTab by rememberSaveable { mutableStateOf(PrimalTopLevelDestination.Feeds) }
    // Observe requestedTab from external navigation
    val requestedTab = navBackStackEntry.savedStateHandle
        .getStateFlow<String?>(REQUESTED_TAB_KEY, null)
        .collectAsState()

    LaunchedEffect(requestedTab.value) {
        val tabName = requestedTab.value ?: return@LaunchedEffect
        val destination = PrimalTopLevelDestination.entries.find { it.name == tabName }
        if (destination != null && destination != activeTab && destination != PrimalTopLevelDestination.Settings) {
            activeTab = destination
        }
        navBackStackEntry.savedStateHandle[REQUESTED_TAB_KEY] = null
    }

    // Unlike requestedTab above (a one-shot request, cleared right after being read), this
    // always reflects what's currently on screen — PrimalAppNavigation's transitions read it to
    // pick a slide direction when leaving/returning to "main" (see CURRENT_MAIN_TAB_KEY's doc).
    LaunchedEffect(activeTab) {
        navBackStackEntry.savedStateHandle[CURRENT_MAIN_TAB_KEY] = activeTab.name
    }

    // Shared callbacks
    val externalWallet = AndroidLightningWallet(LocalContext.current)
    val noteCallbacks = noteCallbacksHandler(
        navController = navController,
        onPayInvoice = { invoice -> externalWallet.payBolt11(invoice) },
    )
    val accountSwitcherCallbacks = accountSwitcherCallbacksHandler(navController)

    val mainViewModel = hiltViewModel<MainViewModel>(navBackStackEntry)
    val mainState by mainViewModel.state.collectAsState()

    MainScreenSharedEffects(mainViewModel, navController)

    val noteFeedsViewModel = hiltViewModel<NoteFeedsViewModel>(navBackStackEntry)
    val noteFeedsState by noteFeedsViewModel.state.collectAsState()

    val readsViewModel = hiltViewModel<ReadsViewModel>(navBackStackEntry)
    val readsState by readsViewModel.state.collectAsState()

    val notificationsViewModel = hiltViewModel<NotificationsViewModel>(navBackStackEntry)
    val notificationsState by notificationsViewModel.state.collectAsState()

    val homeTopAppBarState = rememberHomeTopAppBarState()
    val currentTopAppBarState = rememberPerTabTopAppBarState(activeTab, homeTopAppBarState)

    val sharedState = rememberMainScreenSharedState(
        noteFeedsState = noteFeedsState,
        readsState = readsState,
    )

    SnackbarErrorHandler(
        error = noteFeedsState.uiError,
        snackbarHostState = sharedState.snackbarHostState,
        errorMessageResolver = { it.resolveUiErrorMessage(context = LocalContext.current) },
        onErrorDismiss = { noteFeedsViewModel.setEvent(NoteFeedsContract.UiEvent.DismissError) },
    )

    val onActiveDestinationClick: () -> Unit = {
        handleActiveDestinationClick(activeTab, sharedState, uiScope)
    }

    val onTabChanged: (PrimalTopLevelDestination) -> Unit = { destination ->
        if (destination != activeTab) {
            activeTab = destination
        }
    }

    // The bottom navigation remains visible; only the Home header collapses with the feed.
    val focusModeEnabled = activeTab == PrimalTopLevelDestination.Feeds

    BackHandler(enabled = activeTab != PrimalTopLevelDestination.Feeds) {
        activeTab = PrimalTopLevelDestination.Feeds
    }

    MainScreenScaffold(
        activeTab = activeTab,
        mainState = mainState,
        mainEventPublisher = mainViewModel::setEvent,
        homeState = noteFeedsState,
        homeEventPublisher = noteFeedsViewModel::setEvent,
        readsState = readsState,
        readsEventPublisher = readsViewModel::setEvent,
        notificationsState = notificationsState,
        notificationsSeenProvider = notificationsViewModel::seenNotificationsForGroup,
        notificationsUnseenProvider = notificationsViewModel::unseenNotificationsForGroup,
        onNotificationsSeen = { group ->
            notificationsViewModel.setEvent(
                NotificationsContract.UiEvent.NotificationsSeen(group = group),
            )
            mainViewModel.setEvent(MainContract.UiEvent.NotificationsViewed)
        },
        currentTopAppBarState = currentTopAppBarState,
        sharedState = sharedState,
        noteCallbacks = noteCallbacks,
        accountSwitcherCallbacks = accountSwitcherCallbacks,
        focusModeEnabled = focusModeEnabled,
        onActiveDestinationClick = onActiveDestinationClick,
        onMessagesClick = { navController.navigateToMessages() },
        onTabChanged = onTabChanged,
        onDrawerDestinationClick = onDrawerDestinationClick,
        navController = navController,
    )
}

@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenTopAppBar(
    activeTab: PrimalTopLevelDestination,
    scrollBehavior: TopAppBarScrollBehavior?,
    onAvatarClick: () -> Unit,
    onAvatarSwipeDown: (() -> Unit)? = null,
    onAlgorithmMenuClick: (() -> Unit)? = null,
    onLongReadsClick: () -> Unit,
    onBookmarksClick: () -> Unit,
    onReadPickerRequest: () -> Unit,
    titleOverride: String? = null,
    subtitleOverride: String? = null,
    chevronExpanded: Boolean = false,
    avatarCdnImage: CdnImage?,
    avatarBlossoms: List<String>,
    homeActiveFeed: FeedUi?,
    readsActiveFeed: FeedUi?,
    homePagerState: PagerState,
    readsPagerState: PagerState,
    onExploreSearchClick: () -> Unit,
    onExploreSearchSubmit: (String) -> Unit,
    onExploreSearchProfileClick: (String) -> Unit,
    readsFeeds: List<FeedUi>,
) {
    when (activeTab) {
        PrimalTopLevelDestination.Feeds -> {
            NoteFeedTopAppBar(
                title = homeActiveFeed?.title ?: "",
                pagerState = homePagerState,
                activeFeed = homeActiveFeed,
                avatarCdnImage = avatarCdnImage,
                avatarBlossoms = avatarBlossoms,
                onAvatarClick = onAvatarClick,
                onAvatarSwipeDown = onAvatarSwipeDown,
                onMenuClick = onAlgorithmMenuClick,
                onSearchClick = onExploreSearchClick,
                scrollBehavior = scrollBehavior,
                titleOverride = titleOverride,
                subtitleOverride = subtitleOverride,
                chevronExpanded = chevronExpanded,
                showAvatar = true,
                onSearchSubmit = onExploreSearchSubmit,
                onSearchProfileClick = onExploreSearchProfileClick,
                homeActions = {
                    HomeQuickAccessRow(
                        onAlgorithmsClick = { onAlgorithmMenuClick?.invoke() },
                        onLongReadsClick = onLongReadsClick,
                        onBookmarksClick = onBookmarksClick,
                    )
                },
            )
        }

        PrimalTopLevelDestination.Reads -> {
            ArticleFeedTopAppBar(
                title = readsActiveFeed?.title ?: "",
                pagerState = readsPagerState,
                feeds = readsFeeds,
                activeFeed = readsActiveFeed,
                avatarCdnImage = avatarCdnImage,
                avatarBlossoms = avatarBlossoms,
                onAvatarClick = onAvatarClick,
                onAvatarSwipeDown = onAvatarSwipeDown,
                onFeedPickerRequest = onReadPickerRequest,
                scrollBehavior = scrollBehavior,
                titleOverride = titleOverride,
                subtitleOverride = subtitleOverride,
                chevronExpanded = chevronExpanded,
                showAvatar = false,
            )
        }

        PrimalTopLevelDestination.Explore -> {
            PrimalTopLevelAppBar(
                title = stringResource(id = R.string.algorithm_title),
                subtitle = stringResource(id = R.string.algorithm_subtitle),
                avatarCdnImage = avatarCdnImage,
                avatarBlossoms = avatarBlossoms,
                onAvatarClick = onAvatarClick,
                onAvatarSwipeDown = onAvatarSwipeDown,
                scrollBehavior = scrollBehavior,
                titleOverride = titleOverride,
                subtitleOverride = subtitleOverride,
                chevronExpanded = chevronExpanded,
                showAvatar = false,
            )
        }

        PrimalTopLevelDestination.Messages -> Unit

        PrimalTopLevelDestination.Alerts -> {
            NotificationsTopAppBar(
                avatarCdnImage = avatarCdnImage,
                avatarBlossoms = avatarBlossoms,
                scrollBehavior = scrollBehavior,
                onAvatarClick = onAvatarClick,
                onAvatarSwipeDown = onAvatarSwipeDown,
                titleOverride = titleOverride,
                subtitleOverride = subtitleOverride,
                pagerState = null,
                pages = emptyList(),
                showTitleChevron = false,
                chevronExpanded = false,
                onTitleClick = null,
                showAvatar = false,
            )
        }

        PrimalTopLevelDestination.Settings -> {}
    }
}

@Composable
private fun HomeQuickAccessRow(
    onAlgorithmsClick: () -> Unit,
    onLongReadsClick: () -> Unit,
    onBookmarksClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        HomeQuickAccessButton(
            modifier = Modifier.weight(1f),
            icon = LibreNavigationIcons.Algorithm,
            label = stringResource(id = R.string.home_quick_algorithms),
            onClick = onAlgorithmsClick,
        )
        HomeQuickAccessButton(
            modifier = Modifier.weight(1f),
            icon = LibreNavigationIcons.LongReads,
            label = stringResource(id = R.string.home_quick_reads),
            onClick = onLongReadsClick,
        )
        HomeQuickAccessButton(
            modifier = Modifier.weight(1f),
            icon = PrimalIcons.Bookmarks,
            label = stringResource(id = R.string.home_quick_bookmarks),
            onClick = onBookmarksClick,
        )
    }
}

@Composable
private fun HomeQuickAccessButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val tokens = AppTheme.libreNostrTokens
    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = tokens.accentSubtle,
        contentColor = tokens.accent,
        border = BorderStroke(1.dp, tokens.softOutline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                modifier = Modifier.size(21.dp),
                imageVector = icon,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = label,
                style = AppTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScaffoldTopAppBar(
    activeTab: PrimalTopLevelDestination,
    scrollBehavior: TopAppBarScrollBehavior?,
    mainState: MainContract.UiState,
    mainEventPublisher: (MainContract.UiEvent) -> Unit,
    accountDrawerVisible: Boolean,
    readPickerVisible: Boolean,
    exploreSectionPickerVisible: Boolean,
    sharedState: MainScreenSharedState,
    toggleOverlay: (ActiveOverlay) -> Unit,
    onAlgorithmMenuClick: () -> Unit,
    onLongReadsClick: () -> Unit,
    onBookmarksClick: () -> Unit,
    onExploreSearchClick: () -> Unit,
    onExploreSearchProfileClick: (String) -> Unit,
    readsFeeds: List<FeedUi>,
    navController: NavController,
) {
    val drawerTitle = if (accountDrawerVisible) stringResource(id = R.string.account_drawer_title) else null
    val drawerSubtitle = if (accountDrawerVisible) {
        stringResource(id = R.string.account_drawer_subtitle)
    } else {
        null
    }
    MainScreenTopAppBar(
        activeTab = activeTab,
        scrollBehavior = scrollBehavior,
        onAvatarClick = {
            if (mainState.activeAccountId.isNotEmpty()) {
                navController.navigateToProfile(profileId = mainState.activeAccountId)
            }
        },
        onAvatarSwipeDown = if (mainState.hasMultipleAccounts) {
            { mainEventPublisher(MainContract.UiEvent.SwitchToNextAccount) }
        } else {
            null
        },
        onAlgorithmMenuClick = onAlgorithmMenuClick,
        onLongReadsClick = onLongReadsClick,
        onBookmarksClick = onBookmarksClick,
        onReadPickerRequest = { toggleOverlay(ActiveOverlay.ReadPicker) },
        titleOverride = drawerTitle,
        subtitleOverride = drawerSubtitle,
        chevronExpanded = readPickerVisible ||
            exploreSectionPickerVisible,
        avatarCdnImage = mainState.activeAccountAvatarCdnImage,
        avatarBlossoms = mainState.activeAccountBlossoms,
        homeActiveFeed = sharedState.homeActiveFeed.value,
        readsActiveFeed = sharedState.readsActiveFeed.value,
        homePagerState = sharedState.homePagerState,
        readsPagerState = sharedState.readsPagerState,
        onExploreSearchClick = onExploreSearchClick,
        onExploreSearchSubmit = { query ->
            navController.navigateToSearch(
                searchScope = SearchScope.Notes,
                initialQuery = query,
            )
        },
        onExploreSearchProfileClick = onExploreSearchProfileClick,
        readsFeeds = readsFeeds,
    )
}

// Every other tab pair switches instantly (existing behavior, untouched) — Home and Notifications
// are the one pair reachable from the bottom bar with no navigation-graph transition of their own
// (MainScreen switches them via internal state), so they used to have no animation at all.
// Pulled out of MainScreenContent to keep this `when`'s complexity off of that already-large
// function rather than adding to it.
private val mainScreenTabTransitionSpec:
    AnimatedContentTransitionScope<PrimalTopLevelDestination>.() -> ContentTransform = {
    when {
        initialState == PrimalTopLevelDestination.Feeds && targetState == PrimalTopLevelDestination.Alerts ->
            primalSlideInHorizontallyFromEnd togetherWith primalSlideOutHorizontallyToStart

        initialState == PrimalTopLevelDestination.Alerts && targetState == PrimalTopLevelDestination.Feeds ->
            primalSlideInHorizontallyFromStart togetherWith primalSlideOutHorizontallyToEnd

        else -> EnterTransition.None togetherWith ExitTransition.None
    }
}

@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenContent(
    activeTab: PrimalTopLevelDestination,
    saveableStateHolder: androidx.compose.runtime.saveable.SaveableStateHolder,
    paddingValues: PaddingValues,
    sharedState: MainScreenSharedState,
    noteCallbacks: NoteCallbacks,
    homeState: NoteFeedsContract.UiState,
    homeEventPublisher: (NoteFeedsContract.UiEvent) -> Unit,
    readsState: ReadsScreenContract.UiState,
    readsEventPublisher: (ReadsScreenContract.UiEvent) -> Unit,
    notificationsState: NotificationsContract.UiState,
    notificationsSeenProvider: (NotificationGroup) -> Flow<PagingData<NotificationUi>>,
    notificationsUnseenProvider: (NotificationGroup) -> Flow<List<List<NotificationUi>>>,
    onNotificationsSeen: (NotificationGroup) -> Unit,
    navController: NavController,
    onTabChanged: (PrimalTopLevelDestination) -> Unit,
    onHomeNewNotesChanged: (Boolean) -> Unit,
) {
    Box {
        // Every other tab pair here switches instantly (existing behavior, untouched) — Home and
        // Notifications are the one pair reachable from the bottom bar with no navigation-graph
        // transition of their own (MainScreen switches them via this internal state), so they
        // used to have literally no animation at all switching between them directly.
        AnimatedContent(
            targetState = activeTab,
            transitionSpec = mainScreenTabTransitionSpec,
            label = "MainScreenContent",
        ) { tab ->
            saveableStateHolder.SaveableStateProvider(tab.name) {
                when (tab) {
                    PrimalTopLevelDestination.Feeds -> NoteFeedsContent(
                        state = homeState,
                        pagerState = sharedState.homePagerState,
                        noteCallbacks = noteCallbacks,
                        eventPublisher = homeEventPublisher,
                        onActiveFeedChanged = { sharedState.homeActiveFeed.value = it },
                        selectedFeed = sharedState.homeActiveFeed.value,
                        shouldAnimateScrollToTop = sharedState.homeShouldAnimateScrollToTop,
                        scrollToFeed = sharedState.homeScrollToFeed,
                        snackbarHostState = sharedState.snackbarHostState,
                        paddingValues = paddingValues,
                        onNewNotesStateChanged = onHomeNewNotesChanged,
                    )

                    PrimalTopLevelDestination.Reads -> ReadsContent(
                        state = readsState,
                        pagerState = sharedState.readsPagerState,
                        eventPublisher = readsEventPublisher,
                        onActiveFeedChanged = { sharedState.readsActiveFeed.value = it },
                        shouldAnimateScrollToTop = sharedState.readsShouldAnimateScrollToTop,
                        scrollToFeed = sharedState.readsScrollToFeed,
                        snackbarHostState = sharedState.snackbarHostState,
                        paddingValues = paddingValues,
                        navController = navController,
                    )

                    PrimalTopLevelDestination.Explore -> {
                        val active = sharedState.homeActiveFeed.value ?: homeState.feeds.firstOrNull()
                        if (active != null) {
                            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                                FeedListOverlayContent(
                                    activeFeed = active,
                                    feedSpecKind = FeedSpecKind.Notes,
                                    inlineActions = true,
                                    onFeedClick = { feed ->
                                        sharedState.homeActiveFeed.value = feed
                                        onTabChanged(PrimalTopLevelDestination.Feeds)
                                    },
                                    onDismiss = { onTabChanged(PrimalTopLevelDestination.Feeds) },
                                )
                            }
                        }
                    }

                    PrimalTopLevelDestination.Messages -> Unit

                    PrimalTopLevelDestination.Alerts -> NotificationsContent(
                        pagerState = sharedState.notificationsPagerState,
                        badges = notificationsState.badges,
                        seenNotificationsProvider = notificationsSeenProvider,
                        unseenNotificationsProvider = notificationsUnseenProvider,
                        onNotificationsSeen = onNotificationsSeen,
                        paddingValues = paddingValues,
                        noteCallbacks = noteCallbacks,
                        shouldAnimateScrollToTop = sharedState.notificationsShouldAnimateScrollToTop,
                    )

                    PrimalTopLevelDestination.Settings -> Unit
                }
            }
        }
    }
}

@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenScaffold(
    activeTab: PrimalTopLevelDestination,
    mainState: MainContract.UiState,
    mainEventPublisher: (MainContract.UiEvent) -> Unit,
    homeState: NoteFeedsContract.UiState,
    homeEventPublisher: (NoteFeedsContract.UiEvent) -> Unit,
    readsState: ReadsScreenContract.UiState,
    readsEventPublisher: (ReadsScreenContract.UiEvent) -> Unit,
    notificationsState: NotificationsContract.UiState,
    notificationsSeenProvider: (NotificationGroup) -> Flow<PagingData<NotificationUi>>,
    notificationsUnseenProvider: (NotificationGroup) -> Flow<List<List<NotificationUi>>>,
    onNotificationsSeen: (NotificationGroup) -> Unit,
    currentTopAppBarState: TopAppBarState,
    sharedState: MainScreenSharedState,
    noteCallbacks: NoteCallbacks,
    accountSwitcherCallbacks: AccountSwitcherCallbacks,
    focusModeEnabled: Boolean,
    onActiveDestinationClick: () -> Unit,
    onMessagesClick: () -> Unit,
    onTabChanged: (PrimalTopLevelDestination) -> Unit,
    onDrawerDestinationClick: (DrawerScreenDestination) -> Unit,
    navController: NavController,
) {
    val saveableStateHolder = rememberSaveableStateHolder()
    val exploreAnchor = remember { AnchorHandle() }
    var activeOverlay by rememberSaveable { mutableStateOf<ActiveOverlay?>(null) }
    var algorithmDrawerVisible by rememberSaveable { mutableStateOf(false) }
    var homeHasNewNotes by rememberSaveable { mutableStateOf(false) }
    val readPickerVisible = activeOverlay == ActiveOverlay.ReadPicker
    val exploreSectionPickerVisible = activeOverlay == ActiveOverlay.ExploreSectionPicker
    val accountDrawerVisible = activeOverlay == ActiveOverlay.AccountDrawer
    val exploreActiveSection = ExploreSection.entries
        .getOrElse(sharedState.explorePagerState.currentPage) { ExploreSection.Explore }

    // Keep the algorithm drawer available during the short interval in which the
    // feed pager is still restoring its active item from storage.
    val drawerActiveFeed = sharedState.homeActiveFeed.value
        ?: homeState.feeds.firstOrNull()
        ?: defaultLibreNostrNoteFeeds(mainState.activeAccountId).firstOrNull()?.asFeedUi()
    val drawerOffset by animateDpAsState(
        targetValue = if (algorithmDrawerVisible) ALGORITHM_DRAWER_WIDTH else 0.dp,
        label = "AlgorithmDrawerHomeOffset",
    )
    BackHandler(enabled = algorithmDrawerVisible) {
        algorithmDrawerVisible = false
    }

    fun toggleOverlay(overlay: ActiveOverlay) {
        activeOverlay = if (activeOverlay == overlay) null else overlay
    }

    PrimalMainScaffold(
        modifier = Modifier
            .offset(x = drawerOffset)
            .semantics { testTagsAsResourceId = true },
        activeDestination = activeTab,
        onActiveDestinationClick = onActiveDestinationClick,
        onMessagesClick = onMessagesClick,
        onPrimaryDestinationChanged = onTabChanged,
        onSettingsClick = { navController.navigateToSettings() },
        settingsSelected = false,
        badges = mainState.badges.copy(
            unreadFeedCount = if (homeHasNewNotes) 1 else 0,
        ),
        focusModeEnabled = focusModeEnabled,
        exploreAnchorHandle = exploreAnchor,
        topAppBarState = currentTopAppBarState,
        topAppBar = { scrollBehavior ->
            ScaffoldTopAppBar(
                activeTab = activeTab,
                scrollBehavior = scrollBehavior,
                mainState = mainState,
                mainEventPublisher = mainEventPublisher,
                accountDrawerVisible = accountDrawerVisible,
                readPickerVisible = readPickerVisible,
                exploreSectionPickerVisible = exploreSectionPickerVisible,
                sharedState = sharedState,
                toggleOverlay = ::toggleOverlay,
                onAlgorithmMenuClick = { algorithmDrawerVisible = true },
                onLongReadsClick = { onTabChanged(PrimalTopLevelDestination.Reads) },
                onBookmarksClick = {
                    onDrawerDestinationClick(
                        DrawerScreenDestination.Bookmarks(userId = mainState.activeAccountId),
                    )
                },
                onExploreSearchClick = {},
                onExploreSearchProfileClick = { profileId ->
                    navController.navigateToProfile(profileId = profileId)
                },
                readsFeeds = readsState.feeds,
                navController = navController,
            )
        },
        content = { paddingValues ->
            MainScreenContent(
                activeTab = activeTab,
                saveableStateHolder = saveableStateHolder,
                paddingValues = paddingValues,
                sharedState = sharedState,
                noteCallbacks = noteCallbacks,
                homeState = homeState,
                homeEventPublisher = homeEventPublisher,
                readsState = readsState,
                readsEventPublisher = readsEventPublisher,
                notificationsState = notificationsState,
                notificationsSeenProvider = notificationsSeenProvider,
                notificationsUnseenProvider = notificationsUnseenProvider,
                onNotificationsSeen = onNotificationsSeen,
                navController = navController,
                onTabChanged = onTabChanged,
                onHomeNewNotesChanged = { homeHasNewNotes = it },
            )
        },
        overlay = {
            MainScreenOverlays(
                accountDrawerVisible = accountDrawerVisible,
                readPickerVisible = readPickerVisible,
                exploreSectionPickerVisible = exploreSectionPickerVisible,
                algorithmDrawerVisible = algorithmDrawerVisible,
                exploreActiveSection = exploreActiveSection,
                sharedState = sharedState,
                homeFeeds = homeState.feeds,
                drawerActiveFeed = drawerActiveFeed,
                activeAccountId = mainState.activeAccountId,
                onDismissOverlay = { activeOverlay = null },
                onDismissAlgorithmDrawer = { algorithmDrawerVisible = false },
                onDrawerDestinationClick = onDrawerDestinationClick,
                accountSwitcherCallbacks = accountSwitcherCallbacks,
                navController = navController,
            )


            AnchoredBubble(
                anchor = exploreAnchor,
                text = stringResource(id = R.string.explore_double_tap_hint_text),
                visible = false,
                onDismiss = { mainEventPublisher(MainContract.UiEvent.DismissExploreHint) },
                placement = BubblePlacement.Above,
            )
        },
        overlayCoversTopBar = algorithmDrawerVisible,
        floatingActionButton = { MainScreenFab(navController = navController) },
        snackbarHost = {
            SnackbarHost(hostState = sharedState.snackbarHostState)
        },
    )
}

private val ALGORITHM_DRAWER_WIDTH = 320.dp

@Composable
private fun MainScreenFab(navController: NavController) {
    NewPostFloatingActionButton(
        onNewPostClick = { navController.navigateToNoteEditor(null) },
    )
}

@Suppress("LongMethod")
@Composable
private fun MainScreenOverlays(
    accountDrawerVisible: Boolean,
    readPickerVisible: Boolean,
    exploreSectionPickerVisible: Boolean,
    algorithmDrawerVisible: Boolean,
    exploreActiveSection: ExploreSection,
    sharedState: MainScreenSharedState,
    homeFeeds: List<FeedUi>,
    drawerActiveFeed: FeedUi?,
    activeAccountId: String,
    onDismissOverlay: () -> Unit,
    onDismissAlgorithmDrawer: () -> Unit,
    onDrawerDestinationClick: (DrawerScreenDestination) -> Unit,
    accountSwitcherCallbacks: AccountSwitcherCallbacks,
    navController: NavController,
) {
    PrimalOverlay(
        visible = accountDrawerVisible,
        onDismiss = onDismissOverlay,
    ) {
        PrimalDrawer(
            onDismiss = onDismissOverlay,
            onDrawerDestinationClick = onDrawerDestinationClick,
            onQrCodeClick = { navController.navigateToProfileQrCodeViewer() },
            accountSwitcherCallbacks = accountSwitcherCallbacks,
        )
    }

    val readsActiveFeed = sharedState.readsActiveFeed.value
    if (readsActiveFeed != null) {
        PrimalOverlay(
            visible = readPickerVisible,
            onDismiss = onDismissOverlay,
        ) {
            FeedListOverlayContent(
                activeFeed = readsActiveFeed,
                feedSpecKind = FeedSpecKind.Reads,
                onFeedClick = { feed ->
                    onDismissOverlay()
                    sharedState.readsScrollToFeed.value = feed
                },
                onDismiss = onDismissOverlay,
                onEditAdvancedSearchFeedClick = { feedSpec ->
                    onDismissOverlay()
                    navController.navigateToAdvancedSearch(editingFeedSpec = feedSpec)
                },
            )
        }
    }

    // Notification filters were intentionally removed: the ALL stream is always visible.

    ExploreSectionPickerOverlay(
        visible = exploreSectionPickerVisible,
        activeSection = exploreActiveSection,
        explorePagerState = sharedState.explorePagerState,
        onDismissOverlay = onDismissOverlay,
    )

    AlgorithmPickerDrawer(
        visible = algorithmDrawerVisible,
        activeFeed = drawerActiveFeed ?: homeFeeds.firstOrNull(),
        onDismiss = onDismissAlgorithmDrawer,
        onFeedSelected = { feed ->
            // Update the shared selection immediately so the active feed, title and
            // pager cannot fall back to the first (Latest) feed while the drawer closes.
            sharedState.homeActiveFeed.value = feed
            sharedState.homeScrollToFeed.value = feed
            onDismissAlgorithmDrawer()
        },
        onEditAdvancedSearch = { feedSpec ->
            onDismissAlgorithmDrawer()
            navController.navigateToAdvancedSearch(editingFeedSpec = feedSpec)
        },
        activeUserId = activeAccountId,
        onBookmarkedNoteClick = { noteId ->
            onDismissAlgorithmDrawer()
            navController.navigateToThread(noteId = noteId)
        },
        onSeeAllBookmarksClick = {
            onDismissAlgorithmDrawer()
            onDrawerDestinationClick(DrawerScreenDestination.Bookmarks(userId = activeAccountId))
        },
    )

}

@Composable
private fun AlgorithmPickerDrawer(
    visible: Boolean,
    activeFeed: FeedUi?,
    onDismiss: () -> Unit,
    onFeedSelected: (FeedUi) -> Unit,
    onEditAdvancedSearch: (String) -> Unit,
    activeUserId: String,
    onBookmarkedNoteClick: (noteId: String) -> Unit,
    onSeeAllBookmarksClick: () -> Unit,
) {
    if (activeFeed == null) return

    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .width(ALGORITHM_DRAWER_WIDTH)
                    .fillMaxHeight()
                    .background(AppTheme.extraColorScheme.surfaceVariantAlt2),
            ) {
                androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            modifier = Modifier.size(24.dp),
                            imageVector = LibreNavigationIcons.Algorithm,
                            contentDescription = null,
                            tint = AppTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Algorithms",
                            style = AppTheme.typography.titleLarge,
                            color = AppTheme.colorScheme.onSurface,
                        )
                    }
                    // The drawer is split in two equal halves: the algorithms above, the bookmarks
                    // below. The nav-bar inset belongs to the bottom edge of the drawer, so the
                    // upper half must not reserve it in the middle of the screen.
                    Box(modifier = Modifier.weight(1f)) {
                        FeedListOverlayContent(
                            activeFeed = activeFeed,
                            feedSpecKind = FeedSpecKind.Notes,
                            onFeedClick = onFeedSelected,
                            onDismiss = onDismiss,
                            onEditAdvancedSearchFeedClick = onEditAdvancedSearch,
                            applyNavigationBarsPadding = false,
                        )
                    }
                    PrimalDivider()
                    BookmarksDrawerSection(
                        modifier = Modifier.weight(1f),
                        activeUserId = activeUserId,
                        onNoteClick = onBookmarkedNoteClick,
                        onSeeAllClick = onSeeAllBookmarksClick,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(onClick = onDismiss),
            )
        }
    }
}

@Composable
private fun ExploreSectionPickerOverlay(
    visible: Boolean,
    activeSection: ExploreSection,
    explorePagerState: PagerState,
    onDismissOverlay: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    PrimalOverlay(visible = visible, onDismiss = onDismissOverlay) {
        ExploreSectionListOverlayContent(
            activeSection = activeSection,
            onSectionClick = { section ->
                scope.launch { explorePagerState.scrollToPage(section.ordinal) }
                onDismissOverlay()
            },
            onDismiss = onDismissOverlay,
        )
    }
}

@Composable
private fun MainScreenSharedEffects(mainViewModel: MainViewModel, navController: NavController) {
    DisposableLifecycleObserverEffect(mainViewModel) {
        when (it) {
            Lifecycle.Event.ON_START -> {
                mainViewModel.setEvent(MainContract.UiEvent.RequestUserDataUpdate)
            }

            else -> Unit
        }
    }

    LaunchedEffect(mainViewModel, mainViewModel.effects) {
        mainViewModel.effects.collect {
            when (it) {
                MainContract.SideEffect.AccountSwitched -> navController.navigateToHome()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberHomeTopAppBarState(): TopAppBarState {
    return rememberSaveable(saver = TopAppBarState.Saver) {
        TopAppBarState(
            initialHeightOffsetLimit = -Float.MAX_VALUE,
            initialHeightOffset = 0f,
            initialContentOffset = 0f,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberPerTabTopAppBarState(
    activeTab: PrimalTopLevelDestination,
    homeTopAppBarState: TopAppBarState,
): TopAppBarState {
    val readsTopAppBarState = rememberTopAppBarState()
    val exploreTopAppBarState = rememberTopAppBarState()
    val notificationsTopAppBarState = rememberTopAppBarState()

    return when (activeTab) {
        PrimalTopLevelDestination.Feeds -> homeTopAppBarState
        PrimalTopLevelDestination.Reads -> readsTopAppBarState
        PrimalTopLevelDestination.Explore -> exploreTopAppBarState
        PrimalTopLevelDestination.Messages -> homeTopAppBarState
        PrimalTopLevelDestination.Alerts -> notificationsTopAppBarState
        PrimalTopLevelDestination.Settings -> homeTopAppBarState
    }
}

private fun handleActiveDestinationClick(
    activeTab: PrimalTopLevelDestination,
    sharedState: MainScreenSharedState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val target = when (activeTab) {
        PrimalTopLevelDestination.Feeds -> sharedState.homeShouldAnimateScrollToTop
        PrimalTopLevelDestination.Reads -> sharedState.readsShouldAnimateScrollToTop
        PrimalTopLevelDestination.Alerts -> sharedState.notificationsShouldAnimateScrollToTop
        PrimalTopLevelDestination.Messages -> null
        else -> null
    }
    target?.let {
        it.value = true
        scope.launch {
            delay(500.milliseconds)
            it.value = false
        }
    }
}

private enum class ActiveOverlay {
    AccountDrawer,
    ReadPicker,
    ExploreSectionPicker,
}
