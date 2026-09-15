package net.primal.data.repository.feed.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [FeedSpecInvalidationTracker]'s per-spec routing: invalidating one `(ownerId, feedSpec)`
 * must invalidate only the sources tracked under that key. This is the contract the note feeds
 * rely on after `FeedPostDataCrossRef` was removed from the Room-observed table set — a feed's
 * writes must never regenerate another feed's PagingSource.
 */
class FeedSpecInvalidationTrackerTest {

    @Test
    fun invalidate_invalidatesTrackedSourceForSameSpec() {
        val tracker = FeedSpecInvalidationTracker()
        val source = tracker.track(OWNER_ID, FEED_SPEC_A, FakePagingSource())

        tracker.invalidate(ownerId = OWNER_ID, feedSpec = FEED_SPEC_A)

        assertTrue(source.invalid, "source tracked under the invalidated spec must invalidate")
    }

    @Test
    fun invalidate_doesNotInvalidateSourceOfOtherSpec() {
        val tracker = FeedSpecInvalidationTracker()
        val sourceA = tracker.track(OWNER_ID, FEED_SPEC_A, FakePagingSource())
        val sourceB = tracker.track(OWNER_ID, FEED_SPEC_B, FakePagingSource())

        tracker.invalidate(ownerId = OWNER_ID, feedSpec = FEED_SPEC_B)

        assertFalse(sourceA.invalid, "source of an unrelated spec must NOT invalidate")
        assertTrue(sourceB.invalid)
    }

    @Test
    fun invalidate_doesNotInvalidateSameSpecOfOtherOwner() {
        val tracker = FeedSpecInvalidationTracker()
        val source = tracker.track(OWNER_ID, FEED_SPEC_A, FakePagingSource())

        tracker.invalidate(ownerId = "other-owner", feedSpec = FEED_SPEC_A)

        assertFalse(source.invalid, "same spec under another owner must NOT invalidate")
    }

    @Test
    fun invalidate_invalidatesAllSourcesTrackedUnderSameSpec() {
        val tracker = FeedSpecInvalidationTracker()
        val first = tracker.track(OWNER_ID, FEED_SPEC_A, FakePagingSource())
        val second = tracker.track(OWNER_ID, FEED_SPEC_A, FakePagingSource())

        tracker.invalidate(ownerId = OWNER_ID, feedSpec = FEED_SPEC_A)

        assertTrue(first.invalid)
        assertTrue(second.invalid)
    }

    @Test
    fun retryAppend_invokesTheRegisteredHandlerForThatSpec() {
        val tracker = FeedSpecInvalidationTracker()
        var handlerCalled = false
        tracker.registerAppendRetryHandler(OWNER_ID, FEED_SPEC_A) { handlerCalled = true }
        val source = tracker.track(OWNER_ID, FEED_SPEC_A, FakePagingSource())

        tracker.retryAppend(ownerId = OWNER_ID, feedSpec = FEED_SPEC_A)

        assertTrue(handlerCalled, "retryAppend must invoke the handler registered for that spec")
        assertTrue(source.invalid, "retryAppend must also invalidate the tracked source")
    }

    @Test
    fun retryAppend_doesNotInvokeAnotherSpecsHandler() {
        val tracker = FeedSpecInvalidationTracker()
        var wrongHandlerCalled = false
        tracker.registerAppendRetryHandler(OWNER_ID, FEED_SPEC_B) { wrongHandlerCalled = true }

        tracker.retryAppend(ownerId = OWNER_ID, feedSpec = FEED_SPEC_A)

        assertFalse(wrongHandlerCalled, "a handler registered for an unrelated spec must not fire")
    }

    @Test
    fun retryAppend_withNoRegisteredHandler_stillInvalidatesWithoutThrowing() {
        val tracker = FeedSpecInvalidationTracker()
        val source = tracker.track(OWNER_ID, FEED_SPEC_A, FakePagingSource())

        tracker.retryAppend(ownerId = OWNER_ID, feedSpec = FEED_SPEC_A)

        assertTrue(source.invalid, "a missing handler must not prevent the invalidate from running")
    }

    @Test
    fun invalidateAll_invalidatesEveryTrackedSource() {
        val tracker = FeedSpecInvalidationTracker()
        val sourceA = tracker.track(OWNER_ID, FEED_SPEC_A, FakePagingSource())
        val sourceB = tracker.track("other-owner", FEED_SPEC_B, FakePagingSource())

        tracker.invalidateAll()

        assertTrue(sourceA.invalid)
        assertTrue(sourceB.invalid)
    }

    private class FakePagingSource : PagingSource<Int, Int>() {
        override fun getRefreshKey(state: PagingState<Int, Int>): Int? = null
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Int> =
            LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
    }

    companion object {
        private const val OWNER_ID = "owner-pubkey"
        private const val FEED_SPEC_A = "feed-spec-a"
        private const val FEED_SPEC_B = "feed-spec-b"
    }
}
