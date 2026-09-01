package net.primal.android.navigation

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import androidx.navigation.navOptions
import net.primal.android.audio.player.AudioPlayerStateProvider
import net.primal.android.auth.login.LoginContract
import net.primal.android.auth.login.LoginScreen
import net.primal.android.auth.login.LoginViewModel
import net.primal.android.auth.logout.LogoutContract
import net.primal.android.auth.logout.LogoutScreen
import net.primal.android.auth.logout.LogoutViewModel
import net.primal.android.auth.onboarding.account.OnboardingContract
import net.primal.android.auth.onboarding.account.OnboardingViewModel
import net.primal.android.auth.onboarding.account.ui.OnboardingScreen
import net.primal.android.auth.welcome.WelcomeContract
import net.primal.android.auth.welcome.GesturesOnboardingScreen
import net.primal.android.auth.welcome.RelayOnboardingScreen
import net.primal.android.auth.welcome.RelayOnboardingViewModel
import net.primal.android.auth.welcome.WelcomeScreen
import net.primal.android.bookmarks.list.BookmarksContract
import net.primal.android.bookmarks.list.BookmarksScreen
import net.primal.android.bookmarks.list.BookmarksViewModel
import net.primal.android.core.compose.ApplyEdgeToEdge
import net.primal.android.core.compose.LockToOrientationPortrait
import net.primal.android.core.compose.PrimalNavigationBar
import net.primal.android.core.compose.PrimalScaffold
import net.primal.android.core.compose.PrimalTopLevelDestination
import net.primal.android.core.compose.UnlockScreenOrientation
import net.primal.android.core.pip.PiPManagerProvider
import net.primal.android.drawer.DrawerScreenDestination
import net.primal.android.drawer.multiaccount.events.AccountSwitcherCallbacks
import net.primal.android.editor.NoteEditorContract
import net.primal.android.editor.NoteEditorScreen
import net.primal.android.editor.di.noteEditorViewModel
import net.primal.android.editor.domain.NoteEditorArgs
import net.primal.android.editor.domain.NoteEditorArgs.Companion.jsonAsNoteEditorArgs
import net.primal.android.events.gallery.EventMediaGalleryScreen
import net.primal.android.events.gallery.EventMediaGalleryViewModel
import net.primal.android.events.polls.votes.PollVotesContract
import net.primal.android.events.polls.votes.PollVotesScreen
import net.primal.android.events.polls.votes.PollVotesViewModel
import net.primal.android.events.reactions.ReactionsContract
import net.primal.android.events.reactions.ReactionsViewModel
import net.primal.android.events.reactions.ui.ReactionsScreen
import net.primal.android.explore.asearch.AdvancedSearchContract
import net.primal.android.explore.asearch.AdvancedSearchScreen
import net.primal.android.explore.asearch.AdvancedSearchViewModel
import net.primal.android.explore.feed.ExploreFeedContract
import net.primal.android.explore.feed.ExploreFeedScreen
import net.primal.android.explore.feed.ExploreFeedViewModel
import net.primal.android.explore.search.SearchContract
import net.primal.android.explore.search.SearchViewModel
import net.primal.android.explore.search.ui.SearchScope
import net.primal.android.explore.search.ui.SearchScreen
import net.primal.android.gifpicker.GifPickerContract
import net.primal.android.gifpicker.GifPickerScreen
import net.primal.android.gifpicker.GifPickerViewModel
import net.primal.android.main.MainScreen
import net.primal.android.main.MainViewModel
import net.primal.android.main.REQUESTED_TAB_KEY
import net.primal.android.main.explore.followpack.FollowPackContract
import net.primal.android.main.explore.followpack.FollowPackScreen
import net.primal.android.main.explore.followpack.FollowPackViewModel
import net.primal.android.media.MediaItemScreen
import net.primal.android.media.MediaItemViewModel
import net.primal.android.messages.chat.ChatScreen
import net.primal.android.messages.chat.ChatViewModel
import net.primal.android.messages.conversation.MessageConversationListContract
import net.primal.android.messages.conversation.MessageConversationListViewModel
import net.primal.android.messages.conversation.MessageListScreen
import net.primal.android.messages.conversation.create.NewConversationContract
import net.primal.android.messages.conversation.create.NewConversationScreen
import net.primal.android.nostrconnect.active.ActiveSessionsBottomSheet
import net.primal.android.nostrconnect.active.ActiveSessionsViewModel
import net.primal.android.nostrconnect.connect.NostrConnectBottomSheet
import net.primal.android.nostrconnect.connect.NostrConnectViewModel
import net.primal.android.nostrconnect.utils.NOSTR_CONNECT_SCHEME
import net.primal.android.notes.feed.model.asNeventString
import net.primal.android.notes.feed.note.ui.events.NoteCallbacks
import net.primal.android.profile.details.ProfileDetailsContract
import net.primal.android.profile.details.ProfileDetailsScreen
import net.primal.android.profile.details.ProfileDetailsViewModel
import net.primal.android.profile.domain.ProfileFollowsType
import net.primal.android.profile.editor.ProfileEditorContract
import net.primal.android.profile.editor.ProfileEditorViewModel
import net.primal.android.profile.editor.ui.ProfileEditorScreen
import net.primal.android.profile.follows.ProfileFollowsContract
import net.primal.android.profile.follows.ProfileFollowsScreen
import net.primal.android.profile.follows.ProfileFollowsViewModel
import net.primal.android.profile.qr.ProfileQrCodeContract
import net.primal.android.profile.qr.ProfileQrCodeViewModel
import net.primal.android.profile.qr.ui.ProfileQrCodeViewerScreen
import net.primal.android.scan.ScanCodeContract
import net.primal.android.scan.ScanCodeContract.ScanMode
import net.primal.android.scan.ScanCodeScreen
import net.primal.android.scan.ScanCodeViewModel
import net.primal.android.stream.LiveStreamOverlay
import net.primal.android.wallet.zaps.AndroidLightningWallet
import net.primal.android.stream.player.LocalStreamState
import net.primal.android.theme.AppTheme
import net.primal.android.theme.PrimalTheme
import net.primal.android.theme.domain.PrimalTheme
import net.primal.android.thread.articles.details.ArticleDetailsContract
import net.primal.android.thread.articles.details.ArticleDetailsScreen
import net.primal.android.thread.articles.details.ArticleDetailsViewModel
import net.primal.android.thread.notes.ThreadContract
import net.primal.android.thread.notes.ThreadScreen
import net.primal.android.thread.notes.ThreadViewModel
import net.primal.core.utils.map
import net.primal.core.utils.runCatching
import net.primal.core.utils.serialization.encodeToJsonString
import net.primal.domain.feeds.buildAdvancedSearchNotesFeedSpec
import net.primal.domain.feeds.buildAdvancedSearchNotificationsFeedSpec
import net.primal.domain.feeds.buildAdvancedSearchReadsFeedSpec
import net.primal.domain.feeds.buildReadsTopicFeedSpec
import net.primal.domain.nostr.ReactionType

private fun NavController.navigateToWelcome() =
    navigate(
        route = "welcome",
        navOptions = navOptions { clearBackStack() },
    )

private fun NavController.navigateToLogin() = navigate(route = "login")

private fun NavController.navigateToRelayOnboarding() = navigate(route = "relayOnboarding")

private fun NavController.navigateToGesturesOnboarding() = navigate(route = "gesturesOnboarding")

private fun NavController.navigateToOnboarding() = navigate(route = "onboarding")

private fun NavController.navigateToLogout(profileId: String) = navigate(route = "logout?$PROFILE_ID=$profileId")

internal fun NavController.navigateToSearch(searchScope: SearchScope, initialQuery: String? = null) =
    navigate(route = "search?$SEARCH_SCOPE=$searchScope&$INITIAL_QUERY=$initialQuery")

internal fun NavController.navigateToAdvancedSearch(
    initialQuery: String? = null,
    initialPostedBy: List<String>? = null,
    initialSearchKind: AdvancedSearchContract.SearchKind? = null,
    initialSearchScope: AdvancedSearchContract.SearchScope? = null,
    editingFeedSpec: String? = null,
) = navigate(
    route = "asearch" +
        "?$INITIAL_QUERY=$initialQuery" +
        "&$POSTED_BY=${initialPostedBy.encodeToJsonString()}" +
        "&$SEARCH_KIND=$initialSearchKind" +
        "&$ADV_SEARCH_SCOPE=$initialSearchScope" +
        "&$EDITING_FEED_SPEC=${editingFeedSpec?.asBase64Encoded()}",
)

internal fun NavController.navigateToNoteEditor(args: NoteEditorArgs? = null) {
    navigate(route = "noteEditor?$NOTE_EDITOR_ARGS=${args?.toJson()?.asBase64Encoded()}")
}

private fun NavController.navigateToGifPicker() = navigate(route = "gifPicker")

fun NavController.navigateToMain(tab: PrimalTopLevelDestination? = null) {
    if (tab != null) {
        runCatching { getBackStackEntry("main") }
            .getOrNull()
            ?.savedStateHandle
            ?.set(REQUESTED_TAB_KEY, tab.name)
    }
    if (!popBackStack(route = "main", inclusive = false)) {
        navigate(route = "main", navOptions = navOptions { clearBackStack() })
    }
}

fun NavController.navigateToHome() {
    navigate(route = "main", navOptions = navOptions { clearBackStack() })
}

fun NavController.navigateToWallet() = Unit

fun NavController.navigateToFollowPack(profileId: String, followPackId: String) =
    navigate(route = "explore/followPack/$profileId/$followPackId")

fun NavController.navigateToScanCode(scanMode: ScanMode, promoCode: String? = null) =
    navigate(route = "scanCode?$SCAN_MODE=$scanMode&$PROMO_CODE=$promoCode")

internal fun NavController.navigateToMessages() = navigate(route = "messages")

fun NavController.navigateToChat(profileId: String) = navigate(route = "messages/$profileId")

private fun NavController.navigateToNewMessage() = navigate(route = "messages/new")

fun NavController.navigateToProfile(profileId: String) = navigate(route = "profile?$PROFILE_ID=$profileId")

fun NavController.navigateToProfileQrCodeViewer(profileId: String? = null) =
    when {
        profileId != null -> navigate(route = "profileQrCodeViewer?$PROFILE_ID=$profileId")
        else -> navigate(route = "profileQrCodeViewer")
    }

fun NavController.navigateToProfileFollows(profileId: String, followsType: ProfileFollowsType) =
    navigate(route = "profile/$profileId/follows?$FOLLOWS_TYPE=$followsType")

fun NavController.navigateToProfileEditor() = navigate(route = "profileEditor")

internal fun NavController.navigateToSettings() = navigate(route = "settings")

fun NavController.navigateToThread(noteId: String) = navigate(route = "thread/$noteId")

fun NavController.navigateToArticleDetails(naddr: String) = navigate(route = "article?$ARTICLE_NADDR=$naddr")

fun NavController.navigateToReactions(
    eventId: String,
    initialTab: ReactionType = ReactionType.ZAPS,
    articleATag: String?,
) = navigate("reactions/$eventId?$INITIAL_REACTION_TYPE=${initialTab.name}&$ARTICLE_A_TAG=$articleATag")

fun NavController.navigateToMediaGallery(
    noteId: String,
    mediaUrl: String,
    mediaPositionMs: Long = 0,
) = navigate(
    route = "media/$noteId" +
        "?$MEDIA_URL=$mediaUrl" +
        "&$MEDIA_POSITION_MS=$mediaPositionMs",
)

fun NavController.navigateToMediaItem(mediaUrl: String) {
    val encodedUrl = mediaUrl.asUrlEncoded()
    navigate(route = "mediaItem?$MEDIA_URL=$encodedUrl")
}

fun NavController.navigateToExploreFeed(
    feedSpec: String,
    renderType: ExploreFeedContract.RenderType = ExploreFeedContract.RenderType.List,
    feedTitle: String? = null,
    feedDescription: String? = null,
    editingFeedSpec: String? = null,
) = navigate(
    route = "explore/note?$EXPLORE_FEED_SPEC=${feedSpec.asBase64Encoded()}" +
        "&$EXPLORE_FEED_TITLE=${feedTitle?.asBase64Encoded()}" +
        "&$EXPLORE_FEED_DESCRIPTION=${feedDescription?.asBase64Encoded()}" +
        "&$RENDER_TYPE=$renderType" +
        "&$EDITING_FEED_SPEC=${editingFeedSpec?.asBase64Encoded()}",
)

private fun NavController.navigateToBookmarks() = navigate(route = "bookmarks")

private fun NavController.navigateToUpgradeToPrimalPro() =
    navigate(route = "premium/buying?$UPGRADE_TO_PRIMAL_PRO=true")

internal fun NavController.navigateToNostrConnectBottomSheet(url: String) {
    val safeUrl = url.asUrlEncoded()
    navigate(route = "nostrConnectBottomSheet?$NOSTR_CONNECT_URI=$safeUrl")
}

fun accountSwitcherCallbacksHandler(navController: NavController) =
    AccountSwitcherCallbacks(
        onActiveAccountChanged = { navController.navigateToHome() },
        onAddExistingAccountClick = { navController.navigateToLogin() },
        onCreateNewAccountClick = { navController.navigateToOnboarding() },
    )

fun noteCallbacksHandler(
    navController: NavController,
    onPayInvoice: (String) -> Unit = { navController.navigateToWalletCreateTransaction(lnbc = it) },
) =
    NoteCallbacks(
        onNoteClick = { noteId -> navController.navigateToThread(noteId = noteId) },
        onNoteReplyClick = { referencedNoteEvent ->
            navController.navigateToNoteEditor(NoteEditorArgs(referencedNoteNevent = referencedNoteEvent))
        },
        onNoteQuoteClick = { noteNevent ->
            navController.navigateToNoteEditor(
                args = NoteEditorArgs(
                    referencedNoteNevent = noteNevent,
                    isQuoting = true,
                ),
            )
        },
        onStreamQuoteClick = { streamNaddr ->
            navController.navigateToNoteEditor(
                args = NoteEditorArgs(
                    referencedStreamNaddr = streamNaddr,
                    isQuoting = true,
                ),
            )
        },
        onHighlightReplyClick = { highlightNevent, articleNaddr ->
            navController.navigateToNoteEditor(
                args = NoteEditorArgs(
                    referencedHighlightNevent = highlightNevent,
                    referencedArticleNaddr = articleNaddr,
                ),
            )
        },
        onHighlightQuoteClick = { nevent, naddr ->
            navController.navigateToNoteEditor(
                args = NoteEditorArgs(
                    referencedArticleNaddr = naddr,
                    referencedHighlightNevent = nevent,
                    isQuoting = true,
                ),
            )
        },
        onArticleClick = { naddr -> navController.navigateToArticleDetails(naddr = naddr) },
        onArticleReplyClick = { naddr ->
            navController.navigateToNoteEditor(
                NoteEditorArgs(referencedArticleNaddr = naddr),
            )
        },
        onArticleQuoteClick = { naddr ->
            navController.navigateToNoteEditor(
                args = NoteEditorArgs(
                    referencedArticleNaddr = naddr,
                    isQuoting = true,
                ),
            )
        },
        onProfileClick = { profileId -> navController.navigateToProfile(profileId = profileId) },
        onHashtagClick = { hashtag ->
            navController.navigateToExploreFeed(feedSpec = buildAdvancedSearchNotesFeedSpec(query = hashtag))
        },
        onMediaClick = {
            navController.navigateToMediaGallery(
                noteId = it.noteId,
                mediaUrl = it.mediaUrl,
                mediaPositionMs = it.positionMs,
            )
        },
        onPayInvoiceClick = { onPayInvoice(it.lnbc) },
        onEventReactionsClick = { eventId, initialTab, articleATag ->
            navController.navigateToReactions(eventId = eventId, initialTab = initialTab, articleATag = articleATag)
        },
        onPollVotesClick = { eventId -> navController.navigateToPollVotes(eventId = eventId) },
        onGetPrimalPremiumClick = {},
        onPrimalLegendsLeaderboardClick = {},
    )

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PrimalAppNavigation(navController: NavHostController, startDestination: String) {
    val context = LocalContext.current
    val openExternalWallet: (String) -> Unit = { invoice ->
        AndroidLightningWallet(context).payBolt11(invoice)
    }
    val drawerDestinationHandler: (DrawerScreenDestination) -> Unit = {
        when (it) {
            is DrawerScreenDestination.Profile -> navController.navigateToProfile(profileId = it.userId)
            DrawerScreenDestination.Messages -> navController.navigateToMessages()
            is DrawerScreenDestination.Bookmarks -> navController.navigateToBookmarks()
            DrawerScreenDestination.ScanCode -> navController.navigateToScanCode(scanMode = ScanMode.Anything)
            DrawerScreenDestination.RemoteLogin -> navController.navigateToScanCode(scanMode = ScanMode.RemoteLogin)
            DrawerScreenDestination.Settings -> navController.navigateToSettings()
            is DrawerScreenDestination.SignOut -> navController.navigateToLogout(profileId = it.userId)
        }
    }

    SharedTransitionLayout {
        AppOverlays {
            PiPManagerProvider {
                LiveStreamOverlay(
                    navController = navController,
                    noteCallbacks = noteCallbacksHandler(
                        navController = navController,
                        onPayInvoice = openExternalWallet,
                    ),
                ) {
                    AudioPlayerStateProvider {
                        PrimalAppNavigation(
                            navController = navController,
                            startDestination = startDestination,
                            drawerDestinationHandler = drawerDestinationHandler,
                            onPayInvoice = openExternalWallet,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PrimalAppNavigation(
    navController: NavHostController,
    startDestination: String,
    drawerDestinationHandler: (DrawerScreenDestination) -> Unit,
    onPayInvoice: (String) -> Unit,
) {
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStackEntry?.destination
    val isMessages = currentDestination?.hierarchy?.any { it.route == "messages" } == true
    val isSettings = currentDestination?.hierarchy?.any { it.route == "settings" } == true
    val isProfile = currentDestination?.hierarchy?.any {
        it.route?.startsWith("profile?") == true || it.route?.startsWith("profile/") == true
    } == true
    val persistentBarVisible = isMessages || isSettings || isProfile
    val mainViewModel: MainViewModel = hiltViewModel()
    val mainState by mainViewModel.state.collectAsState()

    PrimalScaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        bottomBar = if (persistentBarVisible) {
            {
                PrimalNavigationBar(
                    activeDestination = when {
                        isMessages -> PrimalTopLevelDestination.Messages
                        isSettings -> PrimalTopLevelDestination.Settings
                        else -> PrimalTopLevelDestination.Feeds
                    },
                    onTopLevelDestinationChanged = { destination ->
                        when (destination) {
                            PrimalTopLevelDestination.Feeds -> navController.navigateToMain(PrimalTopLevelDestination.Feeds)
                            PrimalTopLevelDestination.Alerts -> navController.navigateToMain(PrimalTopLevelDestination.Alerts)
                            else -> Unit
                        }
                    },
                    onMessagesClick = {
                        if (!isMessages) navController.navigateToMessages()
                    },
                    onSettingsClick = {
                        if (!isSettings) navController.navigateToSettings()
                    },
                    onProfileClick = {
                        mainState.activeAccountId.takeIf { it.isNotEmpty() }?.let { profileId ->
                            if (!isProfile) navController.navigateToProfile(profileId = profileId)
                        }
                    },
                    profileAvatarCdnImage = mainState.activeAccountAvatarCdnImage,
                    profileSelected = isProfile,
                    settingsSelected = isSettings,
                    badges = mainState.badges,
                )
            }
        } else {
            null
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            NavHost(
                modifier = Modifier.background(AppTheme.colorScheme.background),
                navController = navController,
                startDestination = startDestination,
            ) {
        pollVotes(
            route = "poll_votes/{$EVENT_ID}",
            arguments = listOf(
                navArgument(EVENT_ID) { type = NavType.StringType },
            ),
            navController = navController,
        )
        welcome(route = "welcome", navController = navController)

        login(route = "login", navController = navController)

        relayOnboarding(route = "relayOnboarding", navController = navController)

        gesturesOnboarding(route = "gesturesOnboarding", navController = navController)

        onboarding(
            route = "onboarding",
            navController = navController,
        )

        scanCode(
            route = "scanCode?$SCAN_MODE={$SCAN_MODE}&$PROMO_CODE={$PROMO_CODE}",
            arguments = listOf(
                navArgument(SCAN_MODE) {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument(PROMO_CODE) {
                    type = NavType.StringType
                    nullable = true
                },
            ),
            deepLinks = listOf(
                navDeepLink {
                    uriPattern = "https://nostrich.org/rc/{$PROMO_CODE}"
                },
            ),
            navController = navController,
        )

        nostrConnectDialog(
            route = "nostrConnectBottomSheet?$NOSTR_CONNECT_URI={$NOSTR_CONNECT_URI}",
            arguments = listOf(
                navArgument(NOSTR_CONNECT_URI) {
                    type = NavType.StringType
                    nullable = true
                },
            ),
            navController = navController,
        )

        activeSessions(
            route = "activeSessions",
            navController = navController,
        )

        logout(
            route = "logout?$PROFILE_ID={$PROFILE_ID}",
            arguments = listOf(
                navArgument(PROFILE_ID) {
                    type = NavType.StringType
                    nullable = false
                },
            ),
            navController = navController,
        )

        main(
            route = "main",
            navController = navController,
            onDrawerDestinationClick = drawerDestinationHandler,
            deepLinks = listOf(
                navDeepLink { uriPattern = "https://nostrich.org" },
                navDeepLink { uriPattern = "https://nostrich.org/home" },
                navDeepLink { uriPattern = "https://nostrich.org/reads" },
                navDeepLink { uriPattern = "https://nostrich.org/explore" },
                navDeepLink { uriPattern = "https://nostrich.org/notifications" },
                navDeepLink { uriPattern = "https://nostrich.org/p/{$PROFILE_NPUB}/live/{$IDENTIFIER}" },
                navDeepLink { uriPattern = "https://nostrich.org/{$PRIMAL_NAME}/live/{$IDENTIFIER}" },
                navDeepLink { uriPattern = "$NOSTR_CONNECT_SCHEME://.*" },
            ),
            arguments = listOf(
                navArgument(PROFILE_NPUB) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(IDENTIFIER) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(PRIMAL_NAME) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(STREAM_NADDR) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        )

        followPack(
            route = "explore/followPack/{$PROFILE_ID}/{$FOLLOW_PACK_ID}",
            navController = navController,
            arguments = listOf(
                navArgument(PROFILE_ID) {
                    type = NavType.StringType
                    nullable = false
                },
                navArgument(FOLLOW_PACK_ID) {
                    type = NavType.StringType
                    nullable = false
                },
            ),
        )

        bookmarks(
            route = "bookmarks",
            navController = navController,
            deepLinks = listOf(
                navDeepLink {
                    uriPattern = "https://nostrich.org/bookmarks"
                },
            ),
        )

        exploreFeed(
            route = "explore/note?" +
                "$EXPLORE_FEED_SPEC={$EXPLORE_FEED_SPEC}&" +
                "$ADVANCED_SEARCH_FEED_SPEC={$ADVANCED_SEARCH_FEED_SPEC}&" +
                "$EXPLORE_FEED_TITLE={$EXPLORE_FEED_TITLE}&" +
                "$EXPLORE_FEED_DESCRIPTION={$EXPLORE_FEED_DESCRIPTION}&" +
                "$RENDER_TYPE={$RENDER_TYPE}&" +
                "$EDITING_FEED_SPEC={$EDITING_FEED_SPEC}",
            arguments = listOf(
                navArgument(EXPLORE_FEED_SPEC) {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument(ADVANCED_SEARCH_FEED_SPEC) {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument(EXPLORE_FEED_TITLE) {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument(EXPLORE_FEED_DESCRIPTION) {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument(RENDER_TYPE) {
                    type = NavType.StringType
                    nullable = false
                    defaultValue = ExploreFeedContract.RenderType.List.toString()
                },
                navArgument(EDITING_FEED_SPEC) {
                    type = NavType.StringType
                    nullable = true
                },
            ),
            deepLinks = listOf(
                navDeepLink {
                    uriPattern = "https://nostrich.org/search/{$ADVANCED_SEARCH_FEED_SPEC}"
                },
            ),
            navController = navController,
        )

        search(
            route = "search?$SEARCH_SCOPE={$SEARCH_SCOPE}&$INITIAL_QUERY={$INITIAL_QUERY}",
            arguments = listOf(
                navArgument(SEARCH_SCOPE) {
                    type = NavType.StringType
                },
                navArgument(INITIAL_QUERY) {
                    type = NavType.StringType
                    nullable = true
                },
            ),
            navController = navController,
        )

        advancedSearch(
            route = "asearch" +
                "?$INITIAL_QUERY={$INITIAL_QUERY}" +
                "&$POSTED_BY={$POSTED_BY}" +
                "&$SEARCH_KIND={$SEARCH_KIND}" +
                "&$ADV_SEARCH_SCOPE={$ADV_SEARCH_SCOPE}" +
                "&$EDITING_FEED_SPEC={$EDITING_FEED_SPEC}",
            navController = navController,
            arguments = listOf(
                navArgument(INITIAL_QUERY) {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument(POSTED_BY) {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument(SEARCH_KIND) {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument(ADV_SEARCH_SCOPE) {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument(EDITING_FEED_SPEC) {
                    type = NavType.StringType
                    nullable = true
                },
            ),
        )












        messages(
            route = "messages",
            navController = navController,
            deepLinks = listOf(
                navDeepLink {
                    uriPattern = "https://nostrich.org/dms"
                },
            ),
        )

        chat(
            route = "messages/{$PROFILE_ID}",
            arguments = listOf(
                navArgument(PROFILE_ID) {
                    type = NavType.StringType
                },
            ),
            navController = navController,
        )

        newMessage(route = "messages/new", navController = navController)

        noteEditor(
            route = "noteEditor?$NOTE_EDITOR_ARGS={$NOTE_EDITOR_ARGS}",
            arguments = listOf(
                navArgument(NOTE_EDITOR_ARGS) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
            deepLinks = listOf(
                navDeepLink {
                    action = "ACTION_SEND"
                    mimeType = "image/*"
                },
                navDeepLink {
                    action = "ACTION_SEND_MULTIPLE"
                    mimeType = "image/*"
                },
                navDeepLink {
                    action = "ACTION_SEND"
                    mimeType = "video/*"
                },
                navDeepLink {
                    action = "ACTION_SEND_MULTIPLE"
                    mimeType = "video/*"
                },
            ),
            navController = navController,
        )

        gifPicker(
            route = "gifPicker",
            navController = navController,
        )

        thread(
            route = "thread/{$NOTE_ID}",
            arguments = listOf(
                navArgument(NOTE_ID) {
                    type = NavType.StringType
                },
            ),
            deepLinks = listOf(
                navDeepLink {
                    uriPattern = "https://nostrich.org/e/{$NOTE_ID}"
                },
            ),
            navController = navController,
        )

        articleDetails(
            route = "article?$ARTICLE_NADDR={$ARTICLE_NADDR}&$PRIMAL_NAME={$PRIMAL_NAME}&$ARTICLE_ID={$ARTICLE_ID}",
            arguments = listOf(
                navArgument(ARTICLE_NADDR) {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument(PRIMAL_NAME) {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument(ARTICLE_ID) {
                    type = NavType.StringType
                    nullable = true
                },
            ),
            deepLinks = listOf(
                navDeepLink {
                    uriPattern = "https://nostrich.org/a/{$ARTICLE_NADDR}"
                },
                navDeepLink {
                    uriPattern = "https://nostrich.org/{$PRIMAL_NAME}/{$ARTICLE_ID}"
                },
            ),
            navController = navController,
        )

        reactions(
            route = "reactions/{$EVENT_ID}" +
                "?$INITIAL_REACTION_TYPE={$INITIAL_REACTION_TYPE}&$ARTICLE_A_TAG={$ARTICLE_A_TAG}",
            arguments = listOf(
                navArgument(EVENT_ID) { type = NavType.StringType },
                navArgument(INITIAL_REACTION_TYPE) {
                    type = NavType.StringType
                    defaultValue = ReactionType.ZAPS.name
                },
                navArgument(ARTICLE_A_TAG) {
                    type = NavType.StringType
                    nullable = true
                },
            ),
            navController = navController,
        )

        media(
            route = "media/{$NOTE_ID}" +
                "?$MEDIA_URL={$MEDIA_URL}" +
                "&$MEDIA_POSITION_MS={$MEDIA_POSITION_MS}",
            arguments = listOf(
                navArgument(NOTE_ID) {
                    type = NavType.StringType
                },
                navArgument(MEDIA_URL) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(MEDIA_POSITION_MS) {
                    type = NavType.LongType
                    nullable = false
                    defaultValue = 0
                },
            ),
            navController = navController,
        )

        mediaItem(
            route = "mediaItem?$MEDIA_URL={$MEDIA_URL}",
            arguments = listOf(
                navArgument(MEDIA_URL) {
                    type = NavType.StringType
                    nullable = false
                },
            ),
            navController = navController,
        )

        profile(
            route = "profile?$PROFILE_ID={$PROFILE_ID}&$PRIMAL_NAME={$PRIMAL_NAME}",
            arguments = listOf(
                navArgument(PROFILE_ID) {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument(PRIMAL_NAME) {
                    type = NavType.StringType
                    nullable = true
                },
            ),
            deepLinks = listOf(
                navDeepLink {
                    uriPattern = "https://nostrich.org/p/{$PROFILE_ID}"
                },
                navDeepLink {
                    uriPattern = "https://nostrich.org/p/{$PROFILE_ID}"
                },
                navDeepLink {
                    uriPattern = "https://nostrich.org/profile/{$PROFILE_ID}"
                },
                navDeepLink {
                    uriPattern = "https://nostrich.org/{$PRIMAL_NAME}"
                },
            ),
            navController = navController,
        )

        profileFollows(
            route = "profile/{$PROFILE_ID}/follows?$FOLLOWS_TYPE={$FOLLOWS_TYPE}",
            arguments = listOf(
                navArgument(PROFILE_ID) {
                    type = NavType.StringType
                },
                navArgument(FOLLOWS_TYPE) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = ProfileFollowsType.Following.name
                },
            ),
            navController = navController,
        )

        profileEditor(route = "profileEditor", navController = navController)

        profileQrCodeViewer(
            route = "profileQrCodeViewer?$PROFILE_ID={$PROFILE_ID}",
            arguments = listOf(
                navArgument(PROFILE_ID) {
                    type = NavType.StringType
                    nullable = true
                },
            ),
            navController = navController,
        )

        settingsNavigation(route = "settings", navController = navController)

        walletScreens(navController = navController)
            }
        }
    }
}

private fun Intent?.parseMediaUris(): List<String> =
    when (this?.action) {
        Intent.ACTION_SEND -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                this.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                this.getParcelableExtra(Intent.EXTRA_STREAM)
            }?.run { listOf(toString()) }
        }

        Intent.ACTION_SEND_MULTIPLE -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                this.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                this.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }?.map { it.toString() }
        }

        else -> emptyList()
    } ?: emptyList()

private fun NavGraphBuilder.welcome(route: String, navController: NavController) =
    composable(
        route = route,
        enterTransition = {
            val initialRoute = initialState.destination.route

            when {
                initialRoute == "login" ||
                    initialRoute?.startsWith("onboarding") == true -> slideInHorizontally(initialOffsetX = { -it })

                else -> null
            }
        },
        exitTransition = {
            val targetRoute = targetState.destination.route
            when {
                targetRoute == "login" ||
                    targetRoute?.startsWith("onboarding") == true -> slideOutHorizontally(targetOffsetX = { -it })

                else -> null
            }
        },
    ) {
        LockToOrientationPortrait()
        PrimalTheme(PrimalTheme.Midnight) {
            ApplyEdgeToEdge(isDarkTheme = false)
            WelcomeScreen(
                callbacks = WelcomeContract.ScreenCallbacks(
                    onSignInClick = { navController.navigateToLogin() },
                ),
            )
        }
    }

private fun NavGraphBuilder.login(route: String, navController: NavController) =
    composable(
        route = route,
        enterTransition = {
            when (initialState.destination.route) {
                "welcome" -> slideInHorizontally(initialOffsetX = { it })
                else -> null
            }
        },
        exitTransition = {
            when (targetState.destination.route) {
                "welcome" -> slideOutHorizontally(targetOffsetX = { it })
                else -> null
            }
        },
    ) {
        val viewModel: LoginViewModel = hiltViewModel(it)
        LockToOrientationPortrait()
        PrimalTheme(PrimalTheme.Midnight) {
            ApplyEdgeToEdge(isDarkTheme = false)
            LoginScreen(
                viewModel = viewModel,
                callbacks = LoginContract.ScreenCallbacks(
                    onLoginSuccess = { navController.navigateToRelayOnboarding() },
                    onClose = { navController.popBackStack() },
                ),
            )
        }
    }

private fun NavGraphBuilder.relayOnboarding(route: String, navController: NavController) =
    composable(
        route = route,
        enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
        exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) },
    ) {
        val viewModel: RelayOnboardingViewModel = hiltViewModel(it)
        LockToOrientationPortrait()
        PrimalTheme(PrimalTheme.Midnight) {
            ApplyEdgeToEdge(isDarkTheme = false)
            RelayOnboardingScreen(
                viewModel = viewModel,
                onComplete = { navController.navigateToGesturesOnboarding() },
            )
        }
    }

private fun NavGraphBuilder.gesturesOnboarding(route: String, navController: NavController) =
    composable(
        route = route,
        enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
        exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) },
    ) {
        LockToOrientationPortrait()
        PrimalTheme(PrimalTheme.Midnight) {
            ApplyEdgeToEdge(isDarkTheme = false)
            GesturesOnboardingScreen(onComplete = { navController.navigateToHome() })
        }
    }

private fun NavGraphBuilder.onboarding(route: String, navController: NavController) =
    composable(
        route = route,
        enterTransition = {
            val initialRoute = initialState.destination.route

            when {
                initialRoute == "welcome" -> slideInHorizontally(initialOffsetX = { it })
                else -> null
            }
        },
        exitTransition = {
            val targetRoute = targetState.destination.route

            when {
                targetRoute == "welcome" -> slideOutHorizontally(targetOffsetX = { it })
                else -> null
            }
        },
    ) {
        val viewModel: OnboardingViewModel = hiltViewModel(it)

        LockToOrientationPortrait()
        PrimalTheme(PrimalTheme.Midnight) {
            ApplyEdgeToEdge(isDarkTheme = false)
            OnboardingScreen(
                viewModel = viewModel,
                callbacks = OnboardingContract.ScreenCallbacks(
                    onClose = { navController.popBackStack() },
                    onOnboarded = { navController.navigateToHome() },
                ),
            )
        }
    }

private fun NavGraphBuilder.scanCode(
    route: String,
    arguments: List<NamedNavArgument>,
    deepLinks: List<NavDeepLink>,
    navController: NavController,
) = composable(
    route = route,
    arguments = arguments,
    deepLinks = deepLinks,
    enterTransition = {
        val initialRoute = initialState.destination.route
        when {
            initialRoute == "welcome" || initialRoute.isMainScreenRoute() ->
                slideInHorizontally(initialOffsetX = { it })

            initialRoute?.startsWith("onboarding") == true ->
                slideInHorizontally(initialOffsetX = { -it })

            else -> null
        }
    },
    exitTransition = {
        val targetRoute = targetState.destination.route
        when {
            targetRoute == "welcome" || targetRoute.isMainScreenRoute() ->
                slideOutHorizontally(targetOffsetX = { it })

            targetRoute?.startsWith("onboarding") == true ->
                slideOutHorizontally(targetOffsetX = { -it })

            else -> null
        }
    },
) {
    val viewModel = hiltViewModel<ScanCodeViewModel>()
    val streamState = LocalStreamState.current
    ApplyEdgeToEdge()
    LockToOrientationPortrait()
    ScanCodeScreen(
        viewModel = viewModel,
        callbacks = ScanCodeContract.ScreenCallbacks(
            onClose = navController::navigateUp,
            navigateToOnboarding = { navController.navigateToOnboarding() },
            navigateToWalletOnboarding = { },
            onNostrConnectRequest = { url ->
                navController.popBackStack()
                navController.navigateToNostrConnectBottomSheet(url = url)
            },
            onDraftTransactionReady = { draft ->
                navController.popBackStack()
                navController.navigateToWalletCreateTransaction(draftTransaction = draft)
            },
            onProfileScan = { profileId ->
                navController.popBackStack()
                navController.navigateToProfile(profileId)
            },
            onNoteScan = { noteId ->
                navController.popBackStack()
                navController.navigateToThread(noteId)
            },
            onArticleScan = { naddr ->
                navController.popBackStack()
                navController.navigateToArticleDetails(naddr)
            },
            onLiveStreamScan = { naddr ->
                navController.popBackStack()
                streamState.start(naddr)
            },
        ),
    )
}

private fun NavGraphBuilder.main(
    route: String,
    deepLinks: List<NavDeepLink>,
    arguments: List<NamedNavArgument>,
    navController: NavController,
    onDrawerDestinationClick: (DrawerScreenDestination) -> Unit,
) = composable(
    route = route,
    deepLinks = deepLinks,
    arguments = arguments,
    enterTransition = { null },
    exitTransition = {
        if (targetState.destination.route.isMainScreenRoute()) {
            null
        } else {
            primalScaleOut
        }
    },
    popEnterTransition = {
        if (initialState.destination.route.isMainScreenRoute()) {
            null
        } else {
            primalScaleIn
        }
    },
    popExitTransition = {
        if (targetState.destination.route.isMainScreenRoute()) {
            null
        } else {
            primalScaleOut
        }
    },
) { navBackEntry ->
    ApplyEdgeToEdge()
    LockToOrientationPortrait()

    MainScreen(
        navController = navController,
        navBackStackEntry = navBackEntry,
        onDrawerDestinationClick = onDrawerDestinationClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
private fun NavGraphBuilder.nostrConnectDialog(
    route: String,
    arguments: List<NamedNavArgument>,
    navController: NavController,
) {
    dialog(
        route = route,
        arguments = arguments,
        dialogProperties = DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        val viewModel = hiltViewModel<NostrConnectViewModel>()
        ApplyEdgeToEdge()
        LockToOrientationPortrait()
        NostrConnectBottomSheet(
            viewModel = viewModel,
            onDismissRequest = { navController.popBackStack() },
        )
    }
}

private fun NavGraphBuilder.activeSessions(route: String, navController: NavController) {
    dialog(
        route = route,
        dialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val sessionsViewModel = hiltViewModel<ActiveSessionsViewModel>()
        ApplyEdgeToEdge()
        LockToOrientationPortrait()
        ActiveSessionsBottomSheet(
            viewModel = sessionsViewModel,
            onDismissRequest = { navController.popBackStack() },
            onSettingsClick = { clientPubKey ->
                navController.popBackStack()
                if (clientPubKey != null) {
                    navController.navigateToConnectedAppDetails(clientPubKey = clientPubKey)
                } else {
                    navController.navigateToConnectedApps()
                }
            },
        )
    }
}

private fun NavGraphBuilder.noteEditor(
    route: String,
    deepLinks: List<NavDeepLink>,
    arguments: List<NamedNavArgument>,
    navController: NavController,
) = composable(
    route = route,
    deepLinks = deepLinks,
    arguments = arguments,
    enterTransition = {
        // The editor grows from the FAB corner, giving the post composer a diagonal reveal.
        scaleIn(initialScale = 0.08f, transformOrigin = TransformOrigin(1f, 1f)) + fadeIn()
    },
    exitTransition = {
        scaleOut(targetScale = 0.08f, transformOrigin = TransformOrigin(1f, 1f)) + fadeOut()
    },
) {
    val activity = LocalActivity.current
    val mediaUrls = activity?.intent.parseMediaUris()

    val args = it.arguments?.getString(NOTE_EDITOR_ARGS)
        ?.asBase64Decoded()
        ?.jsonAsNoteEditorArgs()
        ?: NoteEditorArgs()
            .copy(mediaUris = mediaUrls)

    val viewModel = noteEditorViewModel(args = args)

    val gifUrlResult = it.savedStateHandle
        .getStateFlow<String?>(GIF_URL_RESULT, null)
        .collectAsState()

    LaunchedEffect(gifUrlResult.value) {
        gifUrlResult.value?.let { gifUrl ->
            viewModel.setEvent(NoteEditorContract.UiEvent.InsertGif(gifUrl))
            it.savedStateHandle[GIF_URL_RESULT] = null
        }
    }

    ApplyEdgeToEdge()
    LockToOrientationPortrait()
    NoteEditorScreen(
        viewModel = viewModel,
        callbacks = NoteEditorContract.ScreenCallbacks(
            onClose = {
                activity?.intent?.removeExtra(Intent.EXTRA_STREAM)
                navController.navigateUp()
            },
            onGifPickerClick = { navController.navigateToGifPicker() },
        ),
    )
}

private fun NavGraphBuilder.gifPicker(route: String, navController: NavController) =
    composable(
        route = route,
    ) {
        val viewModel = hiltViewModel<GifPickerViewModel>()

        ApplyEdgeToEdge()
        LockToOrientationPortrait()
        GifPickerScreen(
            viewModel = viewModel,
            callbacks = GifPickerContract.ScreenCallbacks(
                onClose = { navController.navigateUp() },
                onGifSelected = { gifUrl ->
                    navController.previousBackStackEntry?.savedStateHandle?.set(GIF_URL_RESULT, gifUrl)
                    navController.popBackStack()
                },
            ),
        )
    }

private fun NavGraphBuilder.followPack(
    route: String,
    arguments: List<NamedNavArgument>,
    navController: NavController,
) = composable(
    route = route,
    arguments = arguments,
    enterTransition = { primalSlideInHorizontallyFromEnd },
    exitTransition = { primalScaleOut },
    popEnterTransition = { primalScaleIn },
    popExitTransition = { primalSlideOutHorizontallyToEnd },
) {
    val viewModel = hiltViewModel<FollowPackViewModel>()
    ApplyEdgeToEdge()
    LockToOrientationPortrait()
    FollowPackScreen(
        viewModel = viewModel,
        callbacks = FollowPackContract.ScreenCallbacks(
            onShowFeedClick = { feed, title, description ->
                navController.navigateToExploreFeed(
                    feedSpec = feed,
                    feedTitle = title,
                    feedDescription = description,
                )
            },
            onProfileClick = { navController.navigateToProfile(profileId = it) },
            onClose = { navController.navigateUp() },
        ),
    )
}

private fun NavGraphBuilder.exploreFeed(
    route: String,
    deepLinks: List<NavDeepLink>,
    arguments: List<NamedNavArgument>,
    navController: NavController,
) = composable(
    route = route,
    deepLinks = deepLinks,
    arguments = arguments,
    enterTransition = { primalSlideInHorizontallyFromEnd },
    exitTransition = { primalScaleOut },
    popEnterTransition = { primalScaleIn },
    popExitTransition = { primalSlideOutHorizontallyToEnd },
) {
    val viewModel = hiltViewModel<ExploreFeedViewModel>(it)
    ApplyEdgeToEdge()
    LockToOrientationPortrait()
    ExploreFeedScreen(
        viewModel = viewModel,
        noteCallbacks = noteCallbacksHandler(navController),
        callbacks = ExploreFeedContract.ScreenCallbacks(
            onClose = { navController.navigateUp() },
            onGoToWallet = { navController.navigateToWallet() },
            onFeedEditCompleted = {
                navController.previousBackStackEntry?.savedStateHandle?.set(FEED_SAVED_RESULT, true)
                navController.popBackStack()
            },
        ),
    )
}

private fun NavGraphBuilder.search(
    route: String,
    arguments: List<NamedNavArgument>,
    navController: NavController,
) = composable(
    route = route,
    arguments = arguments,
    enterTransition = { primalSlideInHorizontallyFromEnd },
    exitTransition = { primalScaleOut },
    popEnterTransition = { primalScaleIn },
    popExitTransition = { primalSlideOutHorizontallyToEnd },
) {
    val viewModel = hiltViewModel<SearchViewModel>(it)
    ApplyEdgeToEdge()
    LockToOrientationPortrait()
    val searchScope = it.searchScopeOrThrow
    SearchScreen(
        viewModel = viewModel,
        searchScope = searchScope,
        callbacks = SearchContract.ScreenCallbacks(
            onClose = { navController.navigateUp() },
            onAdvancedSearchClick = { query ->
                navController.popBackStack()
                when (searchScope) {
                    SearchScope.Notes -> navController.navigateToAdvancedSearch(
                        initialQuery = query,
                    )

                    SearchScope.Reads -> navController.navigateToAdvancedSearch(
                        initialQuery = query,
                        initialSearchKind = AdvancedSearchContract.SearchKind.Reads,
                    )

                    SearchScope.MyNotifications -> navController.navigateToAdvancedSearch(
                        initialQuery = query,
                        initialSearchScope = AdvancedSearchContract.SearchScope.MyNotifications,
                    )
                }
            },
            onProfileClick = { profileId -> navController.navigateToProfile(profileId) },
            onNaddrClick = { naddr -> navController.navigateToArticleDetails(naddr) },
            noteCallbacks = noteCallbacksHandler(navController),
            onGoToWallet = { navController.navigateToWallet() },
        ),
    )
}

private fun NavGraphBuilder.advancedSearch(
    route: String,
    arguments: List<NamedNavArgument>,
    navController: NavController,
) = composable(
    route = route,
    arguments = arguments,
    enterTransition = { primalSlideInHorizontallyFromEnd },
    exitTransition = { primalScaleOut },
    popEnterTransition = { primalScaleIn },
    popExitTransition = { primalSlideOutHorizontallyToEnd },
) {
    val viewModel = hiltViewModel<AdvancedSearchViewModel>()

    val feedSavedResult = it.savedStateHandle
        .getStateFlow<Boolean?>(FEED_SAVED_RESULT, null)
        .collectAsState()

    LaunchedEffect(feedSavedResult.value) {
        if (feedSavedResult.value == true) {
            it.savedStateHandle[FEED_SAVED_RESULT] = null
            navController.navigateUp()
        }
    }

    ApplyEdgeToEdge()
    LockToOrientationPortrait()

    AdvancedSearchScreen(
        viewModel = viewModel,
        callbacks = AdvancedSearchContract.ScreenCallbacks(
            onClose = { navController.navigateUp() },
            onNavigateToExploreNoteFeed = { feedSpec, renderType, editingFeedSpec ->
                navController.navigateToExploreFeed(
                    feedSpec = feedSpec,
                    renderType = renderType,
                    editingFeedSpec = editingFeedSpec,
                )
            },
            onNavigateToExploreArticleFeed = { feedSpec, editingFeedSpec ->
                navController.navigateToExploreFeed(
                    feedSpec = feedSpec,
                    editingFeedSpec = editingFeedSpec,
                )
            },
        ),
    )
}

private fun NavGraphBuilder.messages(
    route: String,
    deepLinks: List<NavDeepLink>,
    navController: NavController,
) = composable(
    route = route,
    deepLinks = deepLinks,
    enterTransition = { primalSlideInHorizontallyFromEnd },
    exitTransition = { primalScaleOut },
    popEnterTransition = { primalScaleIn },
    popExitTransition = { primalSlideOutHorizontallyToEnd },
) { navBackEntry ->
    val viewModel = hiltViewModel<MessageConversationListViewModel>(navBackEntry)
    ApplyEdgeToEdge()
    LockToOrientationPortrait()
    MessageListScreen(
        viewModel = viewModel,
        callbacks = MessageConversationListContract.ScreenCallbacks(
            onConversationClick = { profileId -> navController.navigateToChat(profileId) },
            onProfileClick = { profileId -> navController.navigateToProfile(profileId) },
            onNewMessageClick = { navController.navigateToNewMessage() },
            onClose = { navController.navigateUp() },
        ),
    )
}

private fun NavGraphBuilder.bookmarks(
    route: String,
    deepLinks: List<NavDeepLink>,
    navController: NavController,
) = composable(
    route = route,
    deepLinks = deepLinks,
    enterTransition = { primalSlideInHorizontallyFromEnd },
    exitTransition = { primalScaleOut },
    popEnterTransition = { primalScaleIn },
    popExitTransition = { primalSlideOutHorizontallyToEnd },
) {
    val viewModel: BookmarksViewModel = hiltViewModel()
    ApplyEdgeToEdge()
    LockToOrientationPortrait()
    BookmarksScreen(
        viewModel = viewModel,
        noteCallbacks = noteCallbacksHandler(navController),
        callbacks = BookmarksContract.ScreenCallbacks(
            onClose = { navController.navigateUp() },
            onGoToWallet = { navController.navigateToWallet() },
        ),
    )
}

private fun NavGraphBuilder.chat(
    route: String,
    arguments: List<NamedNavArgument>,
    navController: NavController,
) = composable(
    route = route,
    arguments = arguments,
    enterTransition = { primalSlideInHorizontallyFromEnd },
    exitTransition = { primalScaleOut },
    popEnterTransition = { primalScaleIn },
    popExitTransition = { primalSlideOutHorizontallyToEnd },
) { navBackEntry ->
    val viewModel = hiltViewModel<ChatViewModel>(navBackEntry)
    ApplyEdgeToEdge()
    LockToOrientationPortrait()
    ChatScreen(
        viewModel = viewModel,
        onClose = { navController.navigateUp() },
        noteCallbacks = noteCallbacksHandler(navController),
    )
}

private fun NavGraphBuilder.newMessage(route: String, navController: NavController) =
    composable(
        route = route,
        enterTransition = { primalSlideInHorizontallyFromEnd },
        exitTransition = { primalScaleOut },
        popEnterTransition = { primalScaleIn },
        popExitTransition = { primalSlideOutHorizontallyToEnd },
    ) { navBackEntry ->
        val viewModel = hiltViewModel<SearchViewModel>(navBackEntry)
        ApplyEdgeToEdge()
        LockToOrientationPortrait()
        NewConversationScreen(
            viewModel = viewModel,
            callbacks = NewConversationContract.ScreenCallbacks(
                onClose = { navController.navigateUp() },
                onProfileClick = { profileId ->
                    navController.popBackStack()
                    navController.navigateToChat(profileId)
                },
            ),
        )
    }

private fun NavGraphBuilder.thread(
    route: String,
    deepLinks: List<NavDeepLink>,
    arguments: List<NamedNavArgument>,
    navController: NavController,
) = composable(
    route = route,
    deepLinks = deepLinks,
    arguments = arguments,
    enterTransition = { primalSlideInHorizontallyFromEnd },
    exitTransition = { primalScaleOut },
    popEnterTransition = { primalScaleIn },
    popExitTransition = { primalSlideOutHorizontallyToEnd },
) { navBackEntry ->
    val viewModel = hiltViewModel<ThreadViewModel>(navBackEntry)

    val gifUrlResult = navBackEntry.savedStateHandle
        .getStateFlow<String?>(GIF_URL_RESULT, null)
        .collectAsState()

    LaunchedEffect(gifUrlResult.value) {
        gifUrlResult.value?.let { gifUrl ->
            val pendingArgsJson = navBackEntry.savedStateHandle.get<String>(PENDING_GIF_REPLY_ARGS)
            val pendingArgs = pendingArgsJson?.jsonAsNoteEditorArgs()
            val state = viewModel.state.value
            navController.navigateToNoteEditor(
                NoteEditorArgs(
                    referencedNoteNevent = state.highlightNote?.asNeventString(),
                    gifUrl = gifUrl,
                    content = pendingArgs?.content ?: "",
                    contentSelectionStart = pendingArgs?.contentSelectionStart ?: 0,
                    contentSelectionEnd = pendingArgs?.contentSelectionEnd ?: 0,
                    taggedUsers = pendingArgs?.taggedUsers ?: emptyList(),
                ),
            )
            navBackEntry.savedStateHandle[GIF_URL_RESULT] = null
            navBackEntry.savedStateHandle[PENDING_GIF_REPLY_ARGS] = null
        }
    }

    ApplyEdgeToEdge()
    LockToOrientationPortrait()
    ThreadScreen(
        viewModel = viewModel,
        callbacks = ThreadContract.ScreenCallbacks(
            onClose = { navController.navigateUp() },
            onGoToWallet = { navController.navigateToWallet() },
            onExpandReply = { args -> navController.navigateToNoteEditor(args) },
            onGifReply = { args ->
                navBackEntry.savedStateHandle[PENDING_GIF_REPLY_ARGS] = args.toJson()
                navController.navigateToGifPicker()
            },
        ),
        noteCallbacks = noteCallbacksHandler(navController),
    )
}

private fun NavGraphBuilder.articleDetails(
    route: String,
    deepLinks: List<NavDeepLink>,
    arguments: List<NamedNavArgument>,
    navController: NavController,
) = composable(
    route = route,
    arguments = arguments,
    deepLinks = deepLinks,
    enterTransition = { primalSlideInHorizontallyFromEnd },
    exitTransition = { primalScaleOut },
    popEnterTransition = { primalScaleIn },
    popExitTransition = { primalSlideOutHorizontallyToEnd },
) { navBackEntry ->
    val viewModel = hiltViewModel<ArticleDetailsViewModel>(navBackEntry)
    ApplyEdgeToEdge()
    LockToOrientationPortrait()
    ArticleDetailsScreen(
        viewModel = viewModel,
        callbacks = ArticleDetailsContract.ScreenCallbacks(
            onClose = { navController.navigateUp() },
            onArticleHashtagClick = { hashtag ->
                navController.navigateToExploreFeed(feedSpec = buildReadsTopicFeedSpec(hashtag = hashtag))
            },
            onGoToWallet = { navController.navigateToWallet() },
        ),
        noteCallbacks = noteCallbacksHandler(navController),
    )
}

private fun NavGraphBuilder.reactions(
    route: String,
    arguments: List<NamedNavArgument>,
    navController: NavController,
) = composable(
    route = route,
    arguments = arguments,
    enterTransition = { primalSlideInHorizontallyFromEnd },
    exitTransition = { primalScaleOut },
    popEnterTransition = { primalScaleIn },
    popExitTransition = { primalSlideOutHorizontallyToEnd },
) { navBackEntry ->
    val viewModel = hiltViewModel<ReactionsViewModel>(navBackEntry)
    ApplyEdgeToEdge()
    LockToOrientationPortrait()
    ReactionsScreen(
        viewModel = viewModel,
        callbacks = ReactionsContract.ScreenCallbacks(
            onClose = { navController.navigateUp() },
            onProfileClick = { profileId -> navController.navigateToProfile(profileId) },
        ),
    )
}

private fun NavGraphBuilder.media(
    route: String,
    arguments: List<NamedNavArgument>,
    navController: NavController,
) = composable(
    route = route,
    arguments = arguments,
    enterTransition = { fadeIn() },
    exitTransition = { fadeOut() },
    popEnterTransition = { EnterTransition.None },
    popExitTransition = { fadeOut() },
) { navBackEntry ->
    val viewModel = hiltViewModel<EventMediaGalleryViewModel>(navBackEntry)
    PrimalTheme(primalTheme = PrimalTheme.Midnight) {
        ApplyEdgeToEdge(isDarkTheme = true)
        UnlockScreenOrientation()
        EventMediaGalleryScreen(
            onClose = { navController.navigateUp() },
            viewModel = viewModel,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.mediaItem(
    route: String,
    arguments: List<NamedNavArgument>,
    navController: NavController,
) = composable(
    route = route,
    arguments = arguments,
) {
    val viewModel = hiltViewModel<MediaItemViewModel>()

    PrimalTheme(primalTheme = PrimalTheme.Midnight) {
        ApplyEdgeToEdge(isDarkTheme = true)
        UnlockScreenOrientation()
        MediaItemScreen(
            onClose = { navController.navigateUp() },
            viewModel = viewModel,
        )
    }
}

private fun NavGraphBuilder.profile(
    route: String,
    arguments: List<NamedNavArgument>,
    deepLinks: List<NavDeepLink>,
    navController: NavController,
) = composable(
    route = route,
    arguments = arguments,
    deepLinks = deepLinks,
    enterTransition = { primalSlideInHorizontallyFromEnd },
    exitTransition = { primalScaleOut },
    popEnterTransition = { primalScaleIn },
    popExitTransition = { primalSlideOutHorizontallyToEnd },
) {
    val streamState = LocalStreamState.current
    val viewModel = hiltViewModel<ProfileDetailsViewModel>(it)

    ApplyEdgeToEdge()
    LockToOrientationPortrait()
    ProfileDetailsScreen(
        viewModel = viewModel,
        callbacks = ProfileDetailsContract.ScreenCallbacks(
            onClose = { navController.navigateUp() },
            onEditProfileClick = { navController.navigateToProfileEditor() },
            onMessageClick = { profileId -> navController.navigateToChat(profileId = profileId) },
            onSendWalletTx = { transaction -> navController.navigateToWalletCreateTransaction(transaction) },
            onDrawerQrCodeClick = { profileId -> navController.navigateToProfileQrCodeViewer(profileId) },
            onFollowsClick = { profileId, followsType ->
                navController.navigateToProfileFollows(
                    profileId = profileId,
                    followsType = followsType,
                )
            },
            onMediaItemClick = { navController.navigateToMediaItem(it) },
            onGoToWallet = { navController.navigateToWallet() },
            onSearchClick = { navController.navigateToAdvancedSearch(initialPostedBy = listOf(it)) },
            onNewPostClick = { navController.navigateToNoteEditor(null) },
            onLiveStreamClick = { naddr -> streamState.start(naddr) },
        ),
        noteCallbacks = noteCallbacksHandler(navController),
    )
}

private fun NavGraphBuilder.profileEditor(route: String, navController: NavController) =
    composable(
        route = route,
        enterTransition = { primalSlideInHorizontallyFromEnd },
        exitTransition = { primalScaleOut },
        popEnterTransition = { primalScaleIn },
        popExitTransition = { primalSlideOutHorizontallyToEnd },
    ) {
        val viewModel = hiltViewModel<ProfileEditorViewModel>()
        ApplyEdgeToEdge()
        LockToOrientationPortrait()
        ProfileEditorScreen(
            viewModel = viewModel,
            callbacks = ProfileEditorContract.ScreenCallbacks(
                onClose = { navController.navigateUp() },
            ),
        )
    }

private fun NavGraphBuilder.profileFollows(
    route: String,
    arguments: List<NamedNavArgument>,
    navController: NavController,
) = composable(
    route = route,
    arguments = arguments,
    enterTransition = { primalSlideInHorizontallyFromEnd },
    exitTransition = { primalScaleOut },
    popEnterTransition = { primalScaleIn },
    popExitTransition = { primalSlideOutHorizontallyToEnd },
) {
    val viewModel = hiltViewModel<ProfileFollowsViewModel>(it)
    ApplyEdgeToEdge()
    LockToOrientationPortrait()
    ProfileFollowsScreen(
        viewModel = viewModel,
        callbacks = ProfileFollowsContract.ScreenCallbacks(
            onProfileClick = { profileId -> navController.navigateToProfile(profileId) },
            onClose = { navController.navigateUp() },
        ),
    )
}

private fun NavGraphBuilder.profileQrCodeViewer(
    route: String,
    navController: NavController,
    arguments: List<NamedNavArgument>,
) = composable(
    route = route,
    arguments = arguments,
    enterTransition = { primalSlideInHorizontallyFromEnd },
    exitTransition = { primalScaleOut },
    popEnterTransition = { primalScaleIn },
    popExitTransition = { primalSlideOutHorizontallyToEnd },
) {
    val streamState = LocalStreamState.current
    val viewModel = hiltViewModel<ProfileQrCodeViewModel>()
    PrimalTheme(primalTheme = PrimalTheme.Midnight) {
        ApplyEdgeToEdge(isDarkTheme = false)
        LockToOrientationPortrait()
        ProfileQrCodeViewerScreen(
            viewModel = viewModel,
            callbacks = ProfileQrCodeContract.ScreenCallbacks(
                onClose = { navController.navigateUp() },
                onProfileScan = { profileId ->
                    navController.popBackStack()
                    navController.navigateToProfile(profileId)
                },
                onNoteScan = { noteId ->
                    navController.popBackStack()
                    navController.navigateToThread(noteId)
                },
                onLiveStreamScan = { naddr ->
                    streamState.start(naddr)
                    navController.popBackStack()
                },
                onArticleScan = { naddr ->
                    navController.popBackStack()
                    navController.navigateToArticleDetails(naddr)
                },
                onDraftTxScan = { draftTx ->
                    navController.popBackStack()
                    navController.navigateToWalletCreateTransaction(draftTx)
                },
                onPromoCodeScan = {
                    navController.popBackStack()
                    navController.navigateToScanCode(scanMode = ScanMode.Anything, promoCode = it)
                },
            ),
        )
    }
}

private fun NavGraphBuilder.logout(
    route: String,
    arguments: List<NamedNavArgument>,
    navController: NavController,
) = dialog(
    route = route,
    arguments = arguments,
) {
    val viewModel: LogoutViewModel = hiltViewModel(it)
    LockToOrientationPortrait()
    LogoutScreen(
        viewModel = viewModel,
        callbacks = LogoutContract.ScreenCallbacks(
            onClose = { navController.popBackStack() },
            navigateToHome = { navController.navigateToHome() },
            navigateToWelcome = { navController.navigateToWelcome() },
        ),
    )
}

fun NavController.navigateToPollVotes(eventId: String) = navigate(route = "poll_votes/$eventId")

private fun NavGraphBuilder.pollVotes(
    route: String,
    arguments: List<NamedNavArgument>,
    navController: NavController,
) = composable(
    route = route,
    arguments = arguments,
    enterTransition = { primalSlideInHorizontallyFromEnd },
    exitTransition = { primalScaleOut },
    popEnterTransition = { primalScaleIn },
    popExitTransition = { primalSlideOutHorizontallyToEnd },
) {
    ApplyEdgeToEdge()
    val viewModel = hiltViewModel<PollVotesViewModel>()
    PollVotesScreen(
        viewModel = viewModel,
        callbacks = PollVotesContract.ScreenCallbacks(
            onClose = { navController.navigateUp() },
            onProfileClick = { profileId -> navController.navigateToProfile(profileId = profileId) },
        ),
    )
}
