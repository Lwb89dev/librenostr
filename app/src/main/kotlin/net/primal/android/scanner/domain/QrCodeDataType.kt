package net.primal.android.scanner.domain

import net.primal.android.nostrconnect.utils.isNostrConnectUrl
import net.primal.domain.nostr.utils.isNAddr
import net.primal.domain.nostr.utils.isNAddrUri
import net.primal.domain.nostr.utils.isNEvent
import net.primal.domain.nostr.utils.isNEventUri
import net.primal.domain.nostr.utils.isNProfile
import net.primal.domain.nostr.utils.isNProfileUri
import net.primal.domain.nostr.utils.isNPub
import net.primal.domain.nostr.utils.isNPubUri
import net.primal.domain.nostr.utils.isNote
import net.primal.domain.nostr.utils.isNoteUri
import net.primal.core.utils.isBitcoinAddress
import net.primal.core.utils.isBitcoinUri
import net.primal.core.utils.isLightningUri
import net.primal.core.utils.isLnInvoice
import net.primal.core.utils.isLnUrl

enum class QrCodeDataType(val validator: (String) -> Boolean) {
    NPUB_URI(validator = { it.isNPubUri() }),
    NPUB(validator = { it.isNPub() }),
    NPROFILE_URI(validator = { it.isNProfileUri() }),
    NPROFILE(validator = { it.isNProfile() }),
    NADDR_URI(validator = { it.isNAddrUri() }),
    NADDR(validator = { it.isNAddr() }),
    NEVENT_URI(validator = { it.isNEventUri() }),
    NEVENT(validator = { it.isNEvent() }),
    NOTE_URI(validator = { it.isNoteUri() }),
    NOTE(validator = { it.isNote() }),
    LIGHTNING_URI(validator = { it.isLightningUri() }),
    LNBC(validator = { it.isLnInvoice() }),
    LNURL(validator = { it.isLnUrl() }),
    BITCOIN_URI(validator = { it.isBitcoinUri() }),
    BITCOIN_ADDRESS(validator = { it.isBitcoinAddress() }),
    NOSTR_CONNECT(validator = { it.isNostrConnectUrl() }),
    ;

    companion object {
        fun from(value: String): QrCodeDataType? {
            return entries.firstOrNull { it.validator(value) }
        }
    }
}
