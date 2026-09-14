package net.primal.core.utils

import fr.acinq.bitcoin.Base58
import fr.acinq.bitcoin.Bech32

fun String.isLnInvoice() = startsWith(prefix = "lnbc", ignoreCase = true)

fun String.isLnUrl() = startsWith(prefix = "lnurl", ignoreCase = true)

fun String.isLightningAddress() = isEmailAddress()

fun String.isLightningUri(): Boolean {
    val isPrefixCorrect = startsWith(prefix = "lightning:", ignoreCase = true)
    val path = this.split(":").last()
    val isPathCorrect = path.isLightningAddress() || path.isLnUrl() || path.isLnInvoice()
    return isPrefixCorrect && isPathCorrect
}

fun String.isBitcoinUri() =
    startsWith(prefix = "bitcoin:", ignoreCase = true) &&
        this.split(":").lastOrNull()?.split("?")?.firstOrNull().isBitcoinAddress()

/**
 * Returns `true` when the receiver is _syntactically_ a valid Bitcoin address
 * (legacy Base58Check **or** SegWit Bech32/Bech32m).
 */
fun String?.isBitcoinAddress(): Boolean =
    when {
        this == null -> false
        // Legacy P2PKH / P2SH (Base58Check).
        startsWith("1") || startsWith("3") ->
            runCatching { Base58.decode(this) }.isSuccess
        // SegWit v0-16 and Taproot (Bech32 / Bech32m).
        startsWith("bc1", ignoreCase = true) ||
            startsWith("tb1", ignoreCase = true) ||
            startsWith("bcrt1", ignoreCase = true) ->
            runCatching { Bech32.decode(this.lowercase()) }.isSuccess

        else -> false
    }
