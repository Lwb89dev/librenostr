package net.primal.android.premium.legend.subscription

import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/** Premium purchase monitoring was backed by a Primal wallet socket. */
class PurchaseMonitor @Inject constructor() {
    fun startMonitor(
        scope: CoroutineScope,
        quoteId: String,
        onComplete: () -> Unit,
    ) {
        // Intentionally no-op: LibreNostr has no centralized premium purchase flow.
    }

    fun stopMonitor(scope: CoroutineScope) {
        // Intentionally no-op.
    }
}
