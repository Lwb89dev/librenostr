package net.primal.android.user.subscriptions

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.flowWithLifecycle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.user.domain.Badges
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.runCatching
import net.primal.domain.streams.StreamRepository

@Singleton
class SubscriptionsManager @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val activeAccountStore: ActiveAccountStore,
    private val streamRepository: StreamRepository,
) {

    private val lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle
    private val scope = CoroutineScope(dispatcherProvider.io() + SupervisorJob())
    private var subscriptionsActive = false

    private var streamsFromFollowsSubscription: Job? = null

    private val _badges = MutableSharedFlow<Badges>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val badges = _badges.asSharedFlow().distinctUntilChanged()

    init {
        observeActiveAccount()
    }

    private var latestBadge: Badges = Badges()

    private suspend fun emitBadgesUpdate(updateReducer: (Badges) -> Badges) {
        val updatedBadges = updateReducer(latestBadge)
        latestBadge = updatedBadges
        _badges.emit(updatedBadges)
    }

    private fun observeActiveAccount() =
        scope.launch {
            activeAccountStore.activeUserId
                .flowWithLifecycle(lifecycle = lifecycle, minActiveState = Lifecycle.State.STARTED)
                .collect { newActiveUserId ->
                    emitBadgesUpdate { Badges() }
                    unsubscribeAll()
                    when {
                        newActiveUserId.isEmpty() -> {
                            withContext(Dispatchers.Main) {
                                lifecycle.removeObserver(lifecycleEventObserver)
                            }
                        }

                        else -> {
                            subscribeAll(userId = newActiveUserId)
                            withContext(Dispatchers.Main) {
                                lifecycle.addObserver(lifecycleEventObserver)
                            }
                        }
                    }
                }
        }

    private val lifecycleEventObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> scope.launch {
                resumeSubscriptions()
            }

            Lifecycle.Event.ON_PAUSE -> scope.launch {
                pauseSubscriptions()
            }

            else -> Unit
        }
    }

    private suspend fun resumeSubscriptions() {
        if (!subscriptionsActive) {
            unsubscribeAll()
            subscribeAll(userId = activeAccountStore.activeUserId())
        }
    }

    private suspend fun pauseSubscriptions() = unsubscribeAll()

    private fun subscribeAll(userId: String) {
        subscriptionsActive = true
        streamsFromFollowsSubscription = launchStreamsFromFollowsSubscription(userId = userId)
    }

    private suspend fun unsubscribeAll() {
        subscriptionsActive = false
        streamsFromFollowsSubscription?.cancel()
    }

    private fun launchStreamsFromFollowsSubscription(userId: String) =
        scope.launch {
            runCatching { streamRepository.fetchLiveEventsFromFollows(userId = userId) }
            streamRepository.startLiveEventsFromFollowsSubscription(userId = userId)
        }
}
