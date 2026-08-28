package net.primal.android.wallet.zaps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidLightningWallet @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun payBolt11(invoice: String) {
        val bolt11 = invoice.removePrefix("lightning:")
        val intent = Intent(Intent.ACTION_VIEW, "lightning:$bolt11".toUri())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            throw LightningWalletMissingException()
        }
    }
}

class LightningWalletMissingException : IllegalStateException(
    "No Lightning wallet is installed to pay the invoice.",
)
