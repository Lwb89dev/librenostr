package net.primal.android.feeds.list

import net.primal.android.feeds.dvm.ui.DvmFeedUi
import net.primal.android.feeds.list.ui.model.FeedUi
import net.primal.domain.feeds.FeedSpecKind

interface FeedListContract {
    data class FollowSetUi(
        val dTag: String,
        val title: String,
        val memberCount: Int,
    )

    data class UiState(
        val specKind: FeedSpecKind,
        val feeds: List<FeedUi> = emptyList(),
        val isEditMode: Boolean = false,
        val feedMarketplaceStage: FeedMarketplaceStage = FeedMarketplaceStage.FeedList,
        val fetchingDvmFeeds: Boolean = false,
        val dvmFeeds: List<DvmFeedUi> = emptyList(),
        val selectedDvmFeed: DvmFeedUi? = null,
        val followSets: List<FollowSetUi> = emptyList(),
    ) {
        enum class FeedMarketplaceStage {
            FeedList,
            FeedMarketplace,
            FeedDetails,
        }
    }

    sealed class UiEvent {
        data object OpenEditMode : UiEvent()
        data object CloseEditMode : UiEvent()
        data class UpdateFeedSpecEnabled(val feedSpec: String, val enabled: Boolean) : UiEvent()
        data class FeedReordered(val feeds: List<FeedUi>) : UiEvent()
        data object ShowFeedMarketplace : UiEvent()

        data object CloseFeedMarketplace : UiEvent()
        data class ShowFeedDetails(val dvmFeed: DvmFeedUi) : UiEvent()

        data object CloseFeedDetails : UiEvent()
        data class AddDvmFeedToUserFeeds(val dvmFeed: DvmFeedUi) : UiEvent()
        data class RemoveDvmFeedFromUserFeeds(val dvmFeed: DvmFeedUi) : UiEvent()
        data class EnableFeedInUserFeeds(val spec: String) : UiEvent()
        data class DisableFeedInUserFeeds(val spec: String) : UiEvent()
        data class RemoveFeedFromUserFeeds(val spec: String) : UiEvent()

        data object RestoreDefaultPrimalFeeds : UiEvent()
        data class AddFollowSetFeed(val followSet: FollowSetUi) : UiEvent()
    }
}
