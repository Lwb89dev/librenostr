package net.primal.android.core.updater

import io.github.aakira.napier.Napier
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.onFailure
import net.primal.core.utils.runCatching
import net.primal.domain.messages.ChatRepository
import net.primal.domain.notifications.NotificationRepository

/**
 * Pulls what the app needs at session start instead of waiting for a screen to be opened.
 *
 * Notifications and direct messages were only ever fetched by their own paging mediators, which
 * run when their tab appears. That meant the unread dots could not appear until the user had
 * already gone looking, and older messages and notifications simply did not exist locally until
 * someone scrolled far enough to ask for them.
 *
 * Notes are deliberately left alone: the feed's paging already decides how much to load, and the
 * live subscription keeps it current. Duplicating that here would fight it.
 *
 * Everything runs per account and is cancelled on a switch, so a sync started for one identity
 * cannot land in another's tables.
 */
@Singleton
class SessionSyncCoordinator @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val activeAccountStore: ActiveAccountStore,
    private val notificationRepository: NotificationRepository,
    private val chatRepository: ChatRepository,
) {

    private val scope = CoroutineScope(dispatcherProvider.io() + SupervisorJob())
    private var syncJob: Job? = null

    init {
        observeActiveAccount()
    }

    private fun observeActiveAccount() =
        scope.launch {
            activeAccountStore.activeUserId.collect { userId ->
                syncJob?.cancel()
                syncJob = if (userId.isNotEmpty()) startSync(userId) else null
            }
        }

    private fun startSync(userId: String): Job =
        scope.launch {
            // Independent of each other, so a relay that is slow for one does not hold the other.
            launch {
                runCatching { notificationRepository.syncNotifications(userId = userId) }
                    .onFailure { Napier.w(throwable = it) { "Notification session sync failed." } }
            }
            launch {
                runCatching { chatRepository.syncConversations(userId = userId) }
                    .onFailure { Napier.w(throwable = it) { "Conversation session sync failed." } }
            }
        }
}
