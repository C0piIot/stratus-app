package dev.stratus.core.crypto

/**
 * HMAC-SHA256, RFC 2104, on top of the digest next door.
 *
 * Here for the same reason [sha256] is: Kotlin/Native has no primitive to
 * borrow, and this is the other half of what a signed link needs.
 */
fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
    // A key longer than the block is hashed first, and a shorter one is padded
    // with the zeroes ByteArray already gives.
    val block = ByteArray(BLOCK)
    (if (key.size > BLOCK) sha256(key) else key).copyInto(block)

    val inner = ByteArray(BLOCK) { (block[it].toInt() xor 0x36).toByte() }
    val outer = ByteArray(BLOCK) { (block[it].toInt() xor 0x5c).toByte() }
    return sha256(outer + sha256(inner + message))
}

private const val BLOCK = 64
