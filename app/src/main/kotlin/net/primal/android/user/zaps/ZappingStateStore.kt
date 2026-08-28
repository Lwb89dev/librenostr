package net.primal.android.user.zaps

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.domain.notifications.DEFAULT_ZAP_CONFIG
import net.primal.domain.notifications.DEFAULT_ZAP_DEFAULT
import net.primal.domain.zaps.ZappingState

@Singleton
class ZappingStateStore @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    activeAccountStore: ActiveAccountStore,
) {

    private val scope = CoroutineScope(dispatcherProvider.io() + SupervisorJob())

    val zappingState: StateFlow<ZappingState> =
        activeAccountStore.activeUserAccount
            .map { account ->
                ZappingState(
                    walletConnected = true,
                    walletBalanceInBtc = null,
                    zapDefault = account.appSettings?.zapDefault ?: DEFAULT_ZAP_DEFAULT,
                    zapsConfig = account.appSettings?.zapsConfig ?: DEFAULT_ZAP_CONFIG,
                )
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), ZappingState())
}
