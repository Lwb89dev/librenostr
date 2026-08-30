package net.primal.data.account.repository.repository

import net.primal.domain.account.pushnotifications.PushNotificationRepository
import net.primal.domain.nostr.NostrEvent

/**
 * Push registration intentionally has no remote implementation in LibreNostr.
 *
 * FCM/Primal registration would disclose account identifiers, signed events and
 * device tokens to a centralized service. Notifications are therefore sourced
 * from relays only; keeping this no-op implementation preserves the domain API
 * for callers while guaranteeing that no network request is made.
 */
class PushNotificationRepositoryImpl : PushNotificationRepository {

    override suspend fun updateNotificationsToken(authorizationEvents: List<NostrEvent>, token: String) = Unit

    override suspend fun updateNotificationTokenForNip46(authorizationEvent: NostrEvent, token: String) = Unit

    override suspend fun updateNotificationTokenForNip47(authorizationEvents: List<NostrEvent>, token: String) = Unit
}
