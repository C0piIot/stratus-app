package dev.stratus.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {

    private fun hex(s: String) = sha256(s.encodeToByteArray()).joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    // The FIPS 180-4 examples, which is the only reason writing this is defensible.
    @Test
    fun matchesTheStandardsVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex("abc"))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test
    fun spansTheBlockBoundariesThePaddingGetsWrong() {
        // 55, 56 and 64 bytes: the last length that fits with its padding, the
        // first that does not, and an exact block. A naive implementation breaks
        // on one of these three.
        assertEquals("9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318", hex("a".repeat(55)))
        assertEquals("b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a", hex("a".repeat(56)))
        assertEquals("ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb", hex("a".repeat(64)))
    }

    @Test
    fun printsAFingerprintTheWayEveryToolPrintsOne() {
        assertEquals("E3:B0:C4", hexPairs(sha256(ByteArray(0))).take(8))
    }
}
