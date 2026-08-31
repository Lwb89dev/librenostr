package net.primal.android.signer

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import net.primal.domain.nostr.NostrEventKind
import org.junit.Test

/**
 * `NostrNotary` refuses to forward a request to an external signer when its kind is absent from
 * [SIGNABLE_EVENT_KINDS], and the NIP-55 permission set sent to the signer is built from the same
 * list. When the two were separate lists they drifted, and kind 9802 was in neither: a highlight
 * was rejected locally and Amber never saw it.
 */
class SignableEventKindsTest {

    @Test
    fun `highlights are signable`() {
        // The regression this file exists for.
        SIGNABLE_EVENT_KINDS shouldContain NostrEventKind.Highlight
    }

    @Test
    fun `every user initiated action the app publishes is signable`() {
        val required = listOf(
            NostrEventKind.Metadata,
            NostrEventKind.ShortTextNote,
            NostrEventKind.FollowList,
            NostrEventKind.EncryptedDirectMessages,
            NostrEventKind.EventDeletion,
            NostrEventKind.ShortTextNoteRepost,
            NostrEventKind.Reaction,
            NostrEventKind.ChatMessage,
            NostrEventKind.PollResponse,
            NostrEventKind.Reporting,
            NostrEventKind.ZapRequest,
            NostrEventKind.Highlight,
            NostrEventKind.MuteList,
            NostrEventKind.RelayListMetadata,
            NostrEventKind.BookmarksList,
            NostrEventKind.BlossomServerList,
            NostrEventKind.StreamMuteList,
            NostrEventKind.BlossomUploadBlob,
            NostrEventKind.CategorizedPeopleList,
            NostrEventKind.LongFormContent,
        )

        required.forEach { kind -> SIGNABLE_EVENT_KINDS shouldContain kind }
    }

    @Test
    fun `wallet and app-settings kinds are not offered to an external signer`() {
        // NWC events are signed with the wallet connection secret, and kind 30078 carried
        // Primal's app settings; NostrNotary rejects it outright.
        SIGNABLE_EVENT_KINDS shouldNotContain NostrEventKind.NwcRequest
        SIGNABLE_EVENT_KINDS shouldNotContain NostrEventKind.NwcResponse
        SIGNABLE_EVENT_KINDS shouldNotContain NostrEventKind.ApplicationSpecificData
        SIGNABLE_EVENT_KINDS shouldNotContain NostrEventKind.PrimalWalletOperation
    }

    @Test
    fun `the value set mirrors the kind list exactly`() {
        // The notary gates on the int set while the permission request maps the enum list; a
        // mismatch between them would silently reject a kind the user had already approved.
        SIGNABLE_EVENT_KIND_VALUES shouldBe SIGNABLE_EVENT_KINDS.map { it.value }.toSet()
        SIGNABLE_EVENT_KIND_VALUES.size shouldBe SIGNABLE_EVENT_KINDS.size
    }
}
