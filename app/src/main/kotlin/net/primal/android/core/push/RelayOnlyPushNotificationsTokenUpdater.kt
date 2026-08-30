package net.primal.android.core.push

import javax.inject.Inject

/** Push registration is disabled: notifications are read from Nostr relays. */
class RelayOnlyPushNotificationsTokenUpdater @Inject constructor() : PushNotificationsTokenUpdater {
    override suspend fun updateTokenForAllUsers() = Unit
    override suspend fun updateTokenForRemoteSigner() = Unit
    override suspend fun updateTokenForNwcService() = Unit
}
