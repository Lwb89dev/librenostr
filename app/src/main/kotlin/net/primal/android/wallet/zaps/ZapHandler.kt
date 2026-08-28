package net.primal.android.wallet.zaps

import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import net.primal.android.networking.relays.FALLBACK_RELAYS
import net.primal.android.nostr.notary.NostrNotary
import net.primal.android.user.domain.RelayKind
import net.primal.android.user.domain.mapToRelayDO
import net.primal.android.user.repository.RelayRepository
import net.primal.core.lightning.LightningPayHelper
import net.primal.core.utils.MSATS_IN_SATS
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.domain.nostr.cryptography.utils.getOrNull
import net.primal.domain.nostr.zaps.ZapError
import net.primal.domain.nostr.zaps.ZapResult
import net.primal.domain.nostr.zaps.ZapTarget
import net.primal.domain.notifications.DEFAULT_ZAP_DEFAULT

class ZapHandler @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
    private val relayRepository: RelayRepository,
    private val notary: NostrNotary,
    private val lightningPayHelper: LightningPayHelper,
    private val androidLightningWallet: AndroidLightningWallet,
) {

    suspend fun zap(
        userId: String,
        target: ZapTarget,
        amountInSats: ULong? = null,
        comment: String? = null,
        optionalTags: List<JsonArray> = emptyList(),
        @Suppress("UNUSED_PARAMETER") walletId: String = "",
    ) = withContext(dispatcherProvider.io()) {
        val zapComment = comment ?: DEFAULT_ZAP_DEFAULT.message
        val zapAmountInSats = amountInSats ?: DEFAULT_ZAP_DEFAULT.amount.toULong()

        val userRelays = relayRepository.findRelays(userId, RelayKind.UserRelay)
            .map { it.mapToRelayDO() }
            .ifEmpty { FALLBACK_RELAYS }

        val zapRequestEvent = notary.signZapRequestNostrEvent(
            userId = userId,
            comment = zapComment,
            target = target,
            relays = userRelays,
            optionalTags = optionalTags,
        ).getOrNull() ?: return@withContext ZapResult.Failure(error = ZapError.FailedToSignEvent)

        try {
            withTimeout(30.seconds) {
                val payRequest = lightningPayHelper.fetchPayRequest(target.recipientLnUrlDecoded)
                val invoice = lightningPayHelper.fetchInvoice(
                    payRequest = payRequest,
                    amountInMilliSats = zapAmountInSats * MSATS_IN_SATS.toULong(),
                    comment = zapComment,
                    zapEvent = zapRequestEvent.takeIf { payRequest.allowsNostr == true },
                )
                androidLightningWallet.payBolt11(invoice.invoice)
            }
            ZapResult.Success
        } catch (error: TimeoutCancellationException) {
            ZapResult.Failure(error = ZapError.Timeout(error))
        } catch (error: LightningWalletMissingException) {
            ZapResult.Failure(error = ZapError.FailedToPayZap(error))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ZapResult.Failure(error = ZapError.Unknown(error))
        }
    }
}
