package net.primal.data.remote.factory

import net.primal.core.networking.primal.PrimalApiClient
import net.primal.core.networking.primal.PrimalHttpApiClient
import net.primal.data.remote.api.broadcast.BroadcastApi
import net.primal.data.remote.api.broadcast.BroadcastApiImpl
import net.primal.data.remote.api.feed.FeedApi
import net.primal.data.remote.api.feed.FeedApiImpl
import net.primal.data.remote.api.importing.PrimalImportApi
import net.primal.data.remote.api.importing.PrimalImportApiImpl
import net.primal.data.remote.api.messages.MessagesApi
import net.primal.data.remote.api.messages.MessagesApiImpl
import net.primal.data.remote.api.notifications.NotificationsApi
import net.primal.data.remote.api.notifications.NotificationsApiImpl
import net.primal.data.remote.api.polls.PollsApi
import net.primal.data.remote.api.polls.PollsApiImpl
import net.primal.data.remote.api.settings.SettingsApi
import net.primal.data.remote.api.settings.SettingsApiImpl
import net.primal.data.remote.api.stream.LiveStreamApi
import net.primal.data.remote.api.stream.LiveStreamApiImpl
import net.primal.data.remote.api.users.UsersApi
import net.primal.data.remote.api.users.UsersApiImpl
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayEventSubscriber

object PrimalApiServiceFactory {

    fun createBroadcastApi(primalApiClient: PrimalApiClient): BroadcastApi = BroadcastApiImpl(primalApiClient)

    fun createFeedApi(primalApiClient: PrimalApiClient): FeedApi = FeedApiImpl(primalApiClient)

    fun createImportApi(primalHttpApiClient: PrimalHttpApiClient): PrimalImportApi =
        PrimalImportApiImpl(primalHttpApiClient)

    fun createMessagesApi(
        primalApiClient: PrimalApiClient,
        relayEventQuerier: RelayEventQuerier? = null,
    ): MessagesApi = MessagesApiImpl(primalApiClient, relayEventQuerier)

    fun createNotificationsApi(primalApiClient: PrimalApiClient): NotificationsApi =
        NotificationsApiImpl(primalApiClient)

    fun createSettingsApi(primalApiClient: PrimalApiClient): SettingsApi = SettingsApiImpl(primalApiClient)

    fun createPollsApi(primalApiClient: PrimalApiClient): PollsApi = PollsApiImpl(primalApiClient)

    fun createUsersApi(primalApiClient: PrimalApiClient): UsersApi = UsersApiImpl(primalApiClient)

    fun createStreamMonitor(
        relayEventSubscriber: RelayEventSubscriber,
    ): LiveStreamApi = LiveStreamApiImpl(
        relayEventSubscriber = relayEventSubscriber,
    )
}
