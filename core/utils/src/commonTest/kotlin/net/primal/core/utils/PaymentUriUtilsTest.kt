package net.primal.core.utils

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PaymentUriUtilsTest {

    private val validLnInvoice = "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqy" +
        "pqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq8rkx3yf5tcsyz3d73gafn" +
        "h3cax9rn449d9p5uxz9ezhhypd0elx87sjle52x86fux2ypatgddc6k63n7erqz25le42c4u4ecky03ylcqca784w"

    @Test
    fun isLnInvoice_returnsTrueForCorrectLightningInvoice() {
        validLnInvoice.isLnInvoice() shouldBe true
    }

    @Test
    fun isLnInvoice_returnsFalseForWrongPrefix() {
        "bitcoin:something".isLnInvoice() shouldBe false
    }

    @Test
    fun isLnInvoice_returnsTrueForPartialLnbcString() {
        "lnbcblablabla".isLnInvoice() shouldBe true
    }

    @Test
    fun isLnUrl_returnsTrueForCorrectLnUrl() {
        "lnurl1dp68gurn8ghj7urjd9kkzmpwdejhgtewwajkcmpdddhx7amw9akxuatjd3cz7ctvv4uqjeypkv".isLnUrl() shouldBe true
    }

    @Test
    fun isLnUrl_returnsFalseForInvalidLnUrl() {
        "somethingRandom".isLnUrl() shouldBe false
    }

    @Test
    fun isLnUrl_returnsTrueForPartialLnUrlString() {
        "lnurl1abcdefgh".isLnUrl() shouldBe true
    }

    @Test
    fun isLightningAddressUri_returnTrueForCorrectLightningLud16Uri() {
        "lightning:alex@primal.net".isLightningUri() shouldBe true
    }

    @Test
    fun isLightningAddressUri_returnTrueForCorrectLightningLnUrlUri() {
        "lightning:lnurl1dp68gurn8ghj7urjd9kkzmpwdejhgtewwajkcmpdddhx7amw9akxuatjd3cz7ctvv4uqjeypkv"
            .isLightningUri() shouldBe true
    }

    @Test
    fun isLightningAddressUri_returnFalseForMissingProtocol() {
        "alex@primal.net".isLightningUri() shouldBe false
    }

    @Test
    fun isLightningAddressUri_returnFalseForMissingLud16OrLnUrl() {
        "lightning:somethingInvalid".isLightningUri() shouldBe false
    }

    @Test
    fun isLightningAddressUri_returnTrueForCorrectLightningInvoice() {
        "lightning:$validLnInvoice".isLightningUri() shouldBe true
    }

    @Test
    fun isBitcoinAddressUri_returnsTrueForCorrectBitcoinUri() {
        "bitcoin:bc1q99ygnq68xrvqd9up7vgapnytwmss4am6ytessw".isBitcoinUri() shouldBe true
    }

    @Test
    fun isBitcoinAddressUri_returnsFalseForMissingProtocol() {
        "bc1q99ygnq68xrvqd9up7vgapnytwmss4am6ytessw".isBitcoinUri() shouldBe false
    }

    @Test
    fun isBitcoinAddressUri_returnsFalseForInvalidBtcAddress() {
        "bitcoin:butInvalidBtcAddress".isBitcoinUri() shouldBe false
    }

    @Test
    fun isBitcoinAddress_returnsTrueForCorrectBtcAddress() {
        "bc1q99ygnq68xrvqd9up7vgapnytwmss4am6ytessw".isBitcoinAddress() shouldBe true
    }

    @Test
    fun isBitcoinAddress_returnsTrueForCorrectLongBtcAddress() {
        "BC1QEPMVA76NVJVZFW2PLYKA37WEUW6VY2UE8E406T3YN900KAVEH86QWSUDXQ".isBitcoinAddress() shouldBe true
    }

    @Test
    fun isBitcoinAddress_returnsFalseForInvalidBtcAddress() {
        "bc1invalidbitcoinaddress".isBitcoinAddress() shouldBe false
    }

    @Test
    fun isBitcoinAddress_returnsFalseForNullString() {
        null.isBitcoinAddress() shouldBe false
    }
}
