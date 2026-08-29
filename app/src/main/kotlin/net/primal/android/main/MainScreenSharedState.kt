package net.primal.android.main

import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import net.primal.android.main.explore.section.ExploreSection
import net.primal.android.main.feeds.NoteFeedsContract
import net.primal.android.main.reads.ReadsScreenContract
import net.primal.android.feeds.list.ui.model.FeedUi
import net.primal.domain.feeds.FeedSpecKind
import net.primal.domain.notifications.NotificationGroup
import net.primal.domain.wallet.CurrencyMode

@Suppress("LongParameterList")
internal class MainScreenSharedState(
    val snackbarHostState: SnackbarHostState,
    val homeActiveFeed: MutableState<FeedUi?>,
    val readsActiveFeed: MutableState<FeedUi?>,
    val explorePagerState: PagerState,
    val homePagerState: PagerState,
    val readsPagerState: PagerState,
    val notificationsPagerState: PagerState,
    val homeShouldAnimateScrollToTop: MutableState<Boolean>,
    val homeScrollToFeed: MutableState<FeedUi?>,
    val readsShouldAnimateScrollToTop: MutableState<Boolean>,
    val readsScrollToFeed: MutableState<FeedUi?>,
    val walletCurrencyMode: MutableState<CurrencyMode>,
    val walletIsScrolledToTop: MutableState<Boolean>,
    val walletShouldAnimateScrollToTop: MutableState<Boolean>,
    val notificationsShouldAnimateScrollToTop: MutableState<Boolean>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberMainScreenSharedState(
    noteFeedsState: NoteFeedsContract.UiState,
    readsState: ReadsScreenContract.UiState,
): MainScreenSharedState {
    val homePagerState = rememberPagerState(pageCount = { noteFeedsState.feeds.size })
    val readsPagerState = rememberPagerState(pageCount = { readsState.feeds.size })
    return MainScreenSharedState(
        snackbarHostState = remember { SnackbarHostState() },
        homeActiveFeed = rememberSaveable(stateSaver = listSaver<FeedUi?, Any>(
            save = { feed: FeedUi? -> feed?.let { listOf<Any>(it.ownerId, it.spec, it.specKind.name, it.feedKind, it.title, it.description, it.enabled, it.deletable) } ?: emptyList() },
            restore = { values ->
                if (values.isEmpty()) null else values.let {
                    FeedUi(
                        ownerId = it[0] as String,
                        spec = it[1] as String,
                        specKind = FeedSpecKind.valueOf(it[2] as String),
                        feedKind = it[3] as String,
                        title = it[4] as String,
                        description = it[5] as String,
                        enabled = it[6] as Boolean,
                        deletable = it[7] as Boolean,
                    )
                }
            },
        )) { mutableStateOf(null) },
        readsActiveFeed = remember { mutableStateOf(null) },
        explorePagerState = rememberPagerState(pageCount = { ExploreSection.entries.size }),
        homePagerState = homePagerState,
        readsPagerState = readsPagerState,
        notificationsPagerState = rememberPagerState(pageCount = { 1 }),
        homeShouldAnimateScrollToTop = remember { mutableStateOf(false) },
        homeScrollToFeed = remember { mutableStateOf(null) },
        readsShouldAnimateScrollToTop = remember { mutableStateOf(false) },
        readsScrollToFeed = remember { mutableStateOf(null) },
        walletCurrencyMode = rememberSaveable { mutableStateOf(CurrencyMode.SATS) },
        walletIsScrolledToTop = remember { mutableStateOf(true) },
        walletShouldAnimateScrollToTop = remember { mutableStateOf(false) },
        notificationsShouldAnimateScrollToTop = remember { mutableStateOf(false) },
    )
}
