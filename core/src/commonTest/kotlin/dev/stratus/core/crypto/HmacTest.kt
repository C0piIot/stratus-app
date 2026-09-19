package dev.stratus.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

class HmacTest {

    private fun hex(key: ByteArray, message: String) =
        hmacSha256(key, message.encodeToByteArray()).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    // RFC 4231, cases 1, 2 and 7. The last one is the only reason the key is
    // hashed first, and the only case that catches forgetting to.
    @Test
    fun matchesTheStandardsVectors() {
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            hex(ByteArray(20) { 0x0b }, "Hi There"),
        )
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            hex("Jefe".encodeToByteArray(), "what do ya want for nothing?"),
        )
        assertEquals(
            "9b09ffa71b942fcb27635fbcd5b0e944bfdc63644f0713938a7f51535c3a35e2",
            hex(
                ByteArray(131) { 0xaa.toByte() },
                "This is a test using a larger than block-size key and a larger " +
                    "than block-size data. The key needs to be hashed before being " +
                    "used by the HMAC algorithm.",
            ),
        )
    }

    @Test
    fun anEmptyKeyAndAnEmptyMessageAreStillAnAnswer() {
        assertEquals(
            "b613679a0814d9ec772f95d778c35fc5ff1697c493715653c6c712144292c5ad",
            hex(ByteArray(0), ""),
        )
    }
}
