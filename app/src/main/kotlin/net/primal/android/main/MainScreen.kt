package net.primal.android.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
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
import net.primal.android.articles.feed.ArticleFeedList
import net.primal.android.core.compose.AppBarPage
import net.primal.android.core.compose.PrimalOverlay
import net.primal.android.core.compose.PrimalTopLevelAppBar
import net.primal.android.core.compose.PrimalTopLevelDestination
import net.primal.android.core.compose.SnackbarErrorHandler
import net.primal.android.core.compose.bubble.AnchorHandle
import net.primal.android.core.compose.bubble.AnchoredBubble
import net.primal.android.core.compose.bubble.BubblePlacement
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.LibreNavigationIcons
import net.primal.android.core.compose.icons.primaliconpack.Close
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
import net.primal.android.main.explore.ExploreHomeContent
import net.primal.android.main.explore.ExploreTopAppBar
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
import net.primal.android.main.wallet.WalletDashboardContent
import net.primal.android.main.wallet.WalletDashboardTopAppBar
import net.primal.android.navigation.accountSwitcherCallbacksHandler
import net.primal.android.navigation.navigateToAdvancedSearch
import net.primal.android.navigation.navigateToArticleDetails
import net.primal.android.navigation.navigateToExploreFeed
import net.primal.android.navigation.navigateToFollowPack
import net.primal.android.navigation.navigateToHome
import net.primal.android.navigation.navigateToNoteEditor
import net.primal.android.navigation.navigateToMessages
import net.primal.android.navigation.navigateToProfile
import net.primal.android.navigation.navigateToProfileQrCodeViewer
import net.primal.android.navigation.navigateToSearch
import net.primal.android.navigation.navigateToSettings
import net.primal.android.navigation.noteCallbacksHandler
import net.primal.android.wallet.zaps.AndroidLightningWallet
import net.primal.android.notes.feed.note.ui.events.NoteCallbacks
import net.primal.android.notifications.list.ui.NotificationUi
import net.primal.android.stream.player.LocalStreamState
import net.primal.android.wallet.picker.WalletPickerOverlayContent
import net.primal.domain.feeds.FeedSpecKind
import net.primal.domain.feeds.buildAdvancedSearchNotesFeedSpec
import net.primal.domain.feeds.buildAdvancedSearchReadsFeedSpec
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

    MainScreenHomeEffects(noteFeedsViewModel)

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
        if (destination != PrimalTopLevelDestination.Wallet && destination != activeTab) {
            activeTab = destination
        }
    }

    // The bottom navigation is persistent, so the former full-screen feed mode is disabled.
    val focusModeEnabled = false

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
        homeTopAppBarState = homeTopAppBarState,
        currentTopAppBarState = currentTopAppBarState,
        sharedState = sharedState,
        noteCallbacks = noteCallbacks,
        accountSwitcherCallbacks = accountSwitcherCallbacks,
        focusModeEnabled = focusModeEnabled,
        profileAvatarCdnImage = mainState.activeAccountAvatarCdnImage,
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
    onFeedPickerRequest: () -> Unit,
    onReadPickerRequest: () -> Unit,
    onWalletPickerRequest: () -> Unit,
    titleOverride: String? = null,
    subtitleOverride: String? = null,
    chevronExpanded: Boolean = false,
    avatarCdnImage: CdnImage?,
    avatarBlossoms: List<String>,
    homeActiveFeed: FeedUi?,
    readsActiveFeed: FeedUi?,
    homePagerState: PagerState,
    readsPagerState: PagerState,
    explorePagerState: PagerState,
    notificationsPagerState: PagerState,
    notificationsPages: List<AppBarPage>,
    exploreActiveSection: ExploreSection,
    onExploreSectionPickerRequest: () -> Unit,
    onExploreSearchClick: () -> Unit,
    onExploreSearchSubmit: (String) -> Unit,
    onExploreSearchProfileClick: (String) -> Unit,
    onExploreAdvancedSearchClick: () -> Unit,
    homeFeeds: List<FeedUi>,
    readsFeeds: List<FeedUi>,
) {
    when (activeTab) {
        PrimalTopLevelDestination.Feeds -> {
            NoteFeedTopAppBar(
                title = homeActiveFeed?.title ?: "",
                pagerState = homePagerState,
                feeds = homeFeeds,
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
                showAvatar = false,
                onSearchSubmit = onExploreSearchSubmit,
                onSearchProfileClick = onExploreSearchProfileClick,
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

        PrimalTopLevelDestination.Wallet -> WalletDashboardTopAppBar(
            scrollBehavior = scrollBehavior,
            onAvatarClick = onAvatarClick,
            onAvatarSwipeDown = onAvatarSwipeDown,
            onWalletPickerRequest = onWalletPickerRequest,
            titleOverride = titleOverride,
            subtitleOverride = subtitleOverride,
            chevronExpanded = chevronExpanded,
        )
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
    feedPickerVisible: Boolean,
    readPickerVisible: Boolean,
    walletPickerVisible: Boolean,
    exploreSectionPickerVisible: Boolean,
    sharedState: MainScreenSharedState,
    toggleOverlay: (ActiveOverlay) -> Unit,
    onAlgorithmMenuClick: () -> Unit,
    onExploreSearchClick: () -> Unit,
    onExploreSearchSubmit: (String) -> Unit,
    onExploreSearchProfileClick: (String) -> Unit,
    onExploreAdvancedSearchClick: () -> Unit,
    exploreActiveSection: ExploreSection,
    homeFeeds: List<FeedUi>,
    readsFeeds: List<FeedUi>,
    navController: NavController,
) {
    val drawerTitle = if (accountDrawerVisible) stringResource(id = R.string.account_drawer_title) else null
    val drawerSubtitle = if (accountDrawerVisible) {
        stringResource(id = R.string.account_drawer_subtitle)
    } else {
        null
    }
    val notificationsPages = emptyList<AppBarPage>()

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
        onFeedPickerRequest = { toggleOverlay(ActiveOverlay.FeedPicker) },
        onReadPickerRequest = { toggleOverlay(ActiveOverlay.ReadPicker) },
        onWalletPickerRequest = { toggleOverlay(ActiveOverlay.WalletPicker) },
        titleOverride = drawerTitle,
        subtitleOverride = drawerSubtitle,
        chevronExpanded = feedPickerVisible ||
            readPickerVisible ||
            walletPickerVisible ||
            exploreSectionPickerVisible,
        avatarCdnImage = mainState.activeAccountAvatarCdnImage,
        avatarBlossoms = mainState.activeAccountBlossoms,
        homeActiveFeed = sharedState.homeActiveFeed.value,
        readsActiveFeed = sharedState.readsActiveFeed.value,
        homePagerState = sharedState.homePagerState,
        readsPagerState = sharedState.readsPagerState,
        explorePagerState = sharedState.explorePagerState,
        notificationsPagerState = sharedState.notificationsPagerState,
        notificationsPages = notificationsPages,
        exploreActiveSection = exploreActiveSection,
        onExploreSectionPickerRequest = { toggleOverlay(ActiveOverlay.ExploreSectionPicker) },
        onExploreSearchClick = onExploreSearchClick,
        onExploreSearchSubmit = { query -> navController.navigateToSearch(searchScope = SearchScope.Notes, initialQuery = query) },
        onExploreSearchProfileClick = onExploreSearchProfileClick,
        onExploreAdvancedSearchClick = onExploreAdvancedSearchClick,
        homeFeeds = homeFeeds,
        readsFeeds = readsFeeds,
    )
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
    homeTopAppBarState: TopAppBarState,
    navController: NavController,
    onTabChanged: (PrimalTopLevelDestination) -> Unit,
    onHomeNewNotesChanged: (Boolean) -> Unit,
) {
    val onGoToWallet = {}
    Box {
        saveableStateHolder.SaveableStateProvider(activeTab.name) {
            when (activeTab) {
                PrimalTopLevelDestination.Feeds -> NoteFeedsContent(
                    state = homeState,
                    pagerState = sharedState.homePagerState,
                    noteCallbacks = noteCallbacks,
                    eventPublisher = homeEventPublisher,
                    onActiveFeedChanged = { sharedState.homeActiveFeed.value = it },
                    selectedFeed = sharedState.homeActiveFeed.value,
                    topAppBarCollapsedFraction = homeTopAppBarState.collapsedFraction,
                    shouldAnimateScrollToTop = sharedState.homeShouldAnimateScrollToTop,
                    scrollToFeed = sharedState.homeScrollToFeed,
                    snackbarHostState = sharedState.snackbarHostState,
                    paddingValues = paddingValues,
                    onGoToWallet = onGoToWallet,
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
                                onGoToWallet = onGoToWallet,
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
                    onGoToWallet = onGoToWallet,
                    shouldAnimateScrollToTop = sharedState.notificationsShouldAnimateScrollToTop,
                )

                PrimalTopLevelDestination.Settings -> Unit

                PrimalTopLevelDestination.Wallet -> WalletDashboardContent(
                    currencyMode = sharedState.walletCurrencyMode.value,
                    onCurrencyModeToggle = { sharedState.walletCurrencyMode.value = it },
                    onScrolledToTopChanged = { sharedState.walletIsScrolledToTop.value = it },
                    shouldAnimateScrollToTop = sharedState.walletShouldAnimateScrollToTop,
                    paddingValues = paddingValues,
                    navController = navController,
                )
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
    homeTopAppBarState: TopAppBarState,
    currentTopAppBarState: TopAppBarState,
    sharedState: MainScreenSharedState,
    noteCallbacks: NoteCallbacks,
    accountSwitcherCallbacks: AccountSwitcherCallbacks,
    focusModeEnabled: Boolean,
    profileAvatarCdnImage: CdnImage?,
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
    var longReadVisible by rememberSaveable { mutableStateOf(false) }
    var homeHasNewNotes by rememberSaveable { mutableStateOf(false) }
    val feedPickerVisible = activeOverlay == ActiveOverlay.FeedPicker
    val readPickerVisible = activeOverlay == ActiveOverlay.ReadPicker
    val walletPickerVisible = activeOverlay == ActiveOverlay.WalletPicker
    val exploreSectionPickerVisible = activeOverlay == ActiveOverlay.ExploreSectionPicker
    val accountDrawerVisible = activeOverlay == ActiveOverlay.AccountDrawer
    val showPullToRefreshHint = mainState.showPullToRefreshHint &&
        activeTab == PrimalTopLevelDestination.Feeds &&
        activeOverlay == null &&
        !algorithmDrawerVisible &&
        !longReadVisible
    val exploreActiveSection = ExploreSection.entries
        .getOrElse(sharedState.explorePagerState.currentPage) { ExploreSection.Explore }

    val streamState = LocalStreamState.current
    // Keep the algorithm drawer available during the short interval in which the
    // feed pager is still restoring its active item from storage.
    val drawerActiveFeed = sharedState.homeActiveFeed.value
        ?: homeState.feeds.firstOrNull()
        ?: defaultLibreNostrNoteFeeds(mainState.activeAccountId).firstOrNull()?.asFeedUi()
    val drawerOffset by animateDpAsState(
        targetValue = if (algorithmDrawerVisible) ALGORITHM_DRAWER_WIDTH else 0.dp,
        label = "AlgorithmDrawerHomeOffset",
    )
    BackHandler(enabled = longReadVisible) {
        longReadVisible = false
    }
    BackHandler(enabled = algorithmDrawerVisible && !longReadVisible) {
        algorithmDrawerVisible = false
    }
    LaunchedEffect(activeOverlay, algorithmDrawerVisible, longReadVisible) {
        if (activeOverlay != null || algorithmDrawerVisible || longReadVisible) streamState.acquireHide() else streamState.releaseHide()
    }

    fun toggleOverlay(overlay: ActiveOverlay) {
        activeOverlay = if (activeOverlay == overlay) null else overlay
    }

    LaunchedEffect(mainState.showPullToRefreshHint) {
        if (mainState.showPullToRefreshHint) {
            delay(PULL_TO_REFRESH_HINT_DURATION_MS)
            mainEventPublisher(MainContract.UiEvent.DismissPullToRefreshHint)
        }
    }

    PrimalMainScaffold(
        modifier = Modifier
            .offset(x = drawerOffset)
            .semantics { testTagsAsResourceId = true }
            .pointerInput(activeTab) {
                var trackingEdgeSwipe = false
                var dragDistance = 0f
                detectHorizontalDragGestures(
                    onDragStart = { start ->
                        // The drawer is a home affordance. Keep the gesture zone wide
                        // enough for a real finger (raw px vary with device density),
                        // while leaving the rest of the timeline available for normal
                        // horizontal interactions. Android's system-back edge still
                        // takes precedence when the gesture starts at the very edge.
                        val centerStart = size.width * 0.25f
                        val centerEnd = size.width * 0.75f
                        trackingEdgeSwipe = activeTab == PrimalTopLevelDestination.Feeds &&
                            (start.x <= 280f || start.x in centerStart..centerEnd)
                        dragDistance = 0f
                    },
                    onHorizontalDrag = { change, amount ->
                        if (trackingEdgeSwipe) {
                            val opensAlgorithm = amount > 0f
                            val opensLongReads = amount < 0f && !algorithmDrawerVisible
                            if (opensAlgorithm || opensLongReads) {
                                dragDistance += kotlin.math.abs(amount)
                                if (dragDistance >= 56f) {
                                    if (opensAlgorithm) {
                                        algorithmDrawerVisible = true
                                    } else {
                                        longReadVisible = true
                                    }
                                    trackingEdgeSwipe = false
                                }
                            }
                        }
                    },
                    onDragEnd = {
                        trackingEdgeSwipe = false
                        dragDistance = 0f
                    },
                    onDragCancel = {
                        trackingEdgeSwipe = false
                        dragDistance = 0f
                    },
                )
            },
        activeDestination = activeTab,
        onActiveDestinationClick = onActiveDestinationClick,
        onMessagesClick = onMessagesClick,
        onPrimaryDestinationChanged = onTabChanged,
        onSettingsClick = { navController.navigateToSettings() },
        onProfileClick = {
            if (mainState.activeAccountId.isNotEmpty()) {
                navController.navigateToProfile(profileId = mainState.activeAccountId)
            }
        },
        settingsSelected = false,
        badges = mainState.badges.copy(
            unreadFeedCount = if (homeHasNewNotes) 1 else 0,
        ),
        focusModeEnabled = focusModeEnabled,
        profileAvatarCdnImage = profileAvatarCdnImage,
        exploreAnchorHandle = exploreAnchor,
        topAppBarState = currentTopAppBarState,
        topAppBar = { scrollBehavior ->
            ScaffoldTopAppBar(
                activeTab = activeTab,
                scrollBehavior = scrollBehavior,
                mainState = mainState,
                mainEventPublisher = mainEventPublisher,
                accountDrawerVisible = accountDrawerVisible,
                feedPickerVisible = feedPickerVisible,
                readPickerVisible = readPickerVisible,
                walletPickerVisible = walletPickerVisible,
                exploreSectionPickerVisible = exploreSectionPickerVisible,
                sharedState = sharedState,
                toggleOverlay = ::toggleOverlay,
                onAlgorithmMenuClick = { algorithmDrawerVisible = true },
                onExploreSearchClick = {},
                onExploreSearchSubmit = { query -> navController.navigateToSearch(searchScope = SearchScope.Notes, initialQuery = query) },
                onExploreSearchProfileClick = { profileId -> navController.navigateToProfile(profileId = profileId) },
                onExploreAdvancedSearchClick = { navController.navigateToAdvancedSearch() },
                exploreActiveSection = exploreActiveSection,
                homeFeeds = homeState.feeds,
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
                homeTopAppBarState = homeTopAppBarState,
                navController = navController,
                onTabChanged = onTabChanged,
                onHomeNewNotesChanged = { homeHasNewNotes = it },
            )
        },
        overlay = {
            MainScreenOverlays(
                accountDrawerVisible = accountDrawerVisible,
                feedPickerVisible = feedPickerVisible,
                readPickerVisible = readPickerVisible,
                walletPickerVisible = walletPickerVisible,
                exploreSectionPickerVisible = exploreSectionPickerVisible,
                algorithmDrawerVisible = algorithmDrawerVisible,
                longReadVisible = longReadVisible,
                exploreActiveSection = exploreActiveSection,
                sharedState = sharedState,
                homeFeeds = homeState.feeds,
                drawerActiveFeed = drawerActiveFeed,
                onDismissOverlay = { activeOverlay = null },
                onDismissAlgorithmDrawer = { algorithmDrawerVisible = false },
                onDismissLongRead = { longReadVisible = false },
                onDrawerDestinationClick = onDrawerDestinationClick,
                accountSwitcherCallbacks = accountSwitcherCallbacks,
                navController = navController,
                onTabChanged = onTabChanged,
            )

            PullToRefreshHint(
                visible = showPullToRefreshHint,
                onUpdate = { mainEventPublisher(MainContract.UiEvent.UpdateFeedFromHint) },
                onDismiss = { mainEventPublisher(MainContract.UiEvent.DismissPullToRefreshHint) },
            )

            AnchoredBubble(
                anchor = exploreAnchor,
                text = stringResource(id = R.string.explore_double_tap_hint_text),
                visible = false,
                onDismiss = { mainEventPublisher(MainContract.UiEvent.DismissExploreHint) },
                placement = BubblePlacement.Above,
            )
        },
        overlayCoversTopBar = algorithmDrawerVisible || longReadVisible,
        floatingActionButton = { MainScreenFab(activeTab = activeTab, navController = navController) },
        snackbarHost = {
            SnackbarHost(hostState = sharedState.snackbarHostState)
        },
    )
}

private const val PULL_TO_REFRESH_HINT_DURATION_MS = 6_000L
private val ALGORITHM_DRAWER_WIDTH = 320.dp
private val LONG_READS_FEED_SPEC = buildAdvancedSearchReadsFeedSpec(query = "")

@Composable
private fun LongReadOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    onArticleClick: (String) -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppTheme.colorScheme.background),
        ) {
            ArticleFeedList(
                feedSpec = LONG_READS_FEED_SPEC,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 72.dp),
                noContentText = stringResource(id = R.string.long_reads_no_content),
                onArticleClick = onArticleClick,
                onGetPremiumClick = {},
                pullToRefreshEnabled = true,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(AppTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = PrimalIcons.Close,
                        contentDescription = stringResource(id = R.string.accessibility_close),
                        tint = AppTheme.colorScheme.onSurface,
                    )
                }
                Icon(
                    modifier = Modifier.size(25.dp),
                    imageVector = LibreNavigationIcons.LongReads,
                    contentDescription = stringResource(id = R.string.accessibility_long_reads),
                    tint = AppTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(id = R.string.long_reads_title),
                    style = AppTheme.typography.titleLarge,
                    color = AppTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PullToRefreshHint(
    visible: Boolean,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = slideInVertically(initialOffsetY = { -it / 2 }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it / 2 }) + fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 12.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            val shape = CircleShape
            Row(
                modifier = Modifier
                    .shadow(elevation = 10.dp, shape = shape)
                    .clip(shape)
                    .clickable(onClick = onUpdate)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                AppTheme.colorScheme.primary,
                                AppTheme.colorScheme.secondary,
                            ),
                        ),
                        shape = shape,
                    )
                    .border(
                        width = 1.dp,
                        color = AppTheme.colorScheme.onPrimary.copy(alpha = 0.28f),
                        shape = shape,
                    )
                    .padding(start = 16.dp, top = 9.dp, bottom = 9.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(id = R.string.pull_down_to_update),
                    color = AppTheme.colorScheme.onPrimary,
                    style = AppTheme.typography.bodyMedium,
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.padding(start = 4.dp).size(28.dp),
                ) {
                    Icon(
                        imageVector = PrimalIcons.Close,
                        contentDescription = stringResource(id = R.string.accessibility_close),
                        tint = AppTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MainScreenFab(activeTab: PrimalTopLevelDestination, navController: NavController) {
    when (activeTab) {
        PrimalTopLevelDestination.Feeds,
        PrimalTopLevelDestination.Alerts,
        -> NewPostFloatingActionButton(
            onNewPostClick = { navController.navigateToNoteEditor(null) },
        )

        else -> {}
    }
}

@Suppress("LongMethod")
@Composable
private fun MainScreenOverlays(
    accountDrawerVisible: Boolean,
    feedPickerVisible: Boolean,
    readPickerVisible: Boolean,
    walletPickerVisible: Boolean,
    exploreSectionPickerVisible: Boolean,
    algorithmDrawerVisible: Boolean,
    longReadVisible: Boolean,
    exploreActiveSection: ExploreSection,
    sharedState: MainScreenSharedState,
    homeFeeds: List<FeedUi>,
    drawerActiveFeed: FeedUi?,
    onDismissOverlay: () -> Unit,
    onDismissAlgorithmDrawer: () -> Unit,
    onDismissLongRead: () -> Unit,
    onDrawerDestinationClick: (DrawerScreenDestination) -> Unit,
    accountSwitcherCallbacks: AccountSwitcherCallbacks,
    navController: NavController,
    onTabChanged: (PrimalTopLevelDestination) -> Unit,
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

    val homeActiveFeed = sharedState.homeActiveFeed.value
    if (homeActiveFeed != null) {
        PrimalOverlay(
            visible = feedPickerVisible,
            onDismiss = onDismissOverlay,
        ) {
            FeedListOverlayContent(
                activeFeed = homeActiveFeed,
                feedSpecKind = FeedSpecKind.Notes,
                onFeedClick = { feed ->
                    onDismissOverlay()
                    sharedState.homeScrollToFeed.value = feed
                },
                onDismiss = onDismissOverlay,
                onGoToWallet = {},
                onEditAdvancedSearchFeedClick = { feedSpec ->
                    onDismissOverlay()
                    navController.navigateToAdvancedSearch(editingFeedSpec = feedSpec)
                },
            )
        }
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

    PrimalOverlay(
        visible = walletPickerVisible,
        onDismiss = onDismissOverlay,
    ) {
        WalletPickerOverlayContent(
            onDismiss = onDismissOverlay,
        )
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
    )

    LongReadOverlay(
        visible = longReadVisible,
        onDismiss = onDismissLongRead,
        onArticleClick = { naddr -> navController.navigateToArticleDetails(naddr) },
    )
}

@Composable
private fun AlgorithmPickerDrawer(
    visible: Boolean,
    activeFeed: FeedUi?,
    onDismiss: () -> Unit,
    onFeedSelected: (FeedUi) -> Unit,
    onEditAdvancedSearch: (String) -> Unit,
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
                    Box(modifier = Modifier.weight(1f)) {
                        FeedListOverlayContent(
                            activeFeed = activeFeed,
                            feedSpecKind = FeedSpecKind.Notes,
                            onFeedClick = onFeedSelected,
                            onDismiss = onDismiss,
                            onGoToWallet = {},
                            onEditAdvancedSearchFeedClick = onEditAdvancedSearch,
                        )
                    }
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

@Composable
private fun MainScreenHomeEffects(noteFeedsViewModel: NoteFeedsViewModel) {
    val streamState = LocalStreamState.current
    LaunchedEffect(noteFeedsViewModel, noteFeedsViewModel.effects) {
        noteFeedsViewModel.effects.collect {
            when (it) {
                is NoteFeedsContract.SideEffect.StartStream -> streamState.start(naddr = it.naddr)
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
    val walletTopAppBarState = rememberTopAppBarState()

    return when (activeTab) {
        PrimalTopLevelDestination.Feeds -> homeTopAppBarState
        PrimalTopLevelDestination.Reads -> readsTopAppBarState
        PrimalTopLevelDestination.Explore -> exploreTopAppBarState
        PrimalTopLevelDestination.Messages -> homeTopAppBarState
        PrimalTopLevelDestination.Alerts -> notificationsTopAppBarState
        PrimalTopLevelDestination.Wallet -> walletTopAppBarState
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
        PrimalTopLevelDestination.Wallet -> sharedState.walletShouldAnimateScrollToTop
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
    FeedPicker,
    ReadPicker,
    WalletPicker,
    ExploreSectionPicker,
}
