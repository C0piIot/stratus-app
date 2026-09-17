package dev.stratus.core.backup

/**
 * Where an asset goes on the server, as a pure function of the asset.
 *
 * This is the load-bearing idea of the whole backup. If the remote name were
 * arbitrary -- a random id, or something the server chose -- then "do you
 * already have this?" could only be answered from local memory, and losing that
 * memory would mean uploading the camera roll again. A reinstall, a new phone or
 * cleared app storage would each cost somebody forty thousand photographs over
 * their mobile data. So the path is derived, every time, from the picture itself.
 *
 * Identity is **the capture time, the original filename and the byte count**.
 * All three are intrinsic to the photograph, which is what makes them survive a
 * restore -- unlike any identifier either platform hands out.
 */
class RemoteLayout(root: String = DEFAULT_ROOT) {

    private val root: String = "/" + root.trim('/') + "/"

    /** The directory an asset belongs in. Grouped so a listing stays a sane size. */
    fun directoryFor(asset: Asset): String =
        "$root${asset.capturedAt.year}/${two(asset.capturedAt.month)}/"

    fun pathFor(asset: Asset): String = directoryFor(asset) + nameFor(asset, asset.originalName)

    /**
     * The movie half of a Live Photo, beside its still and sharing its name.
     *
     * Derived from the parent rather than from itself, so the pair cannot drift
     * into different folders when the movie carries its own, different timestamp.
     */
    fun motionPathFor(asset: Asset): String? {
        val motion = asset.motion ?: return null
        return directoryFor(asset) + nameFor(asset, motion.originalName)
    }

    /** Every directory that could hold any of [assets], for a rebuild to walk. */
    fun directoriesFor(assets: List<Asset>): List<String> =
        assets.map(::directoryFor).distinct().sorted()

    private fun nameFor(asset: Asset, fileName: String): String {
        val at = asset.capturedAt
        val stamp = "${at.year}-${two(at.month)}-${two(at.day)}_" +
            "${two(at.hour)}${two(at.minute)}${two(at.second)}"
        val base = fileName.substringBeforeLast('.', fileName).ifEmpty { "photo" }
        val extension = fileName.substringAfterLast('.', "")
        val suffix = if (extension.isEmpty()) "" else ".${extension.lowercase()}"
        return "${stamp}_$base.${digestOf(asset)}$suffix"
    }

    /**
     * Eight characters that make the name a function of the asset rather than of
     * its filename alone.
     *
     * Two pictures land on the same path only when their capture second, their
     * original name **and** their byte count all match -- at which point they are
     * almost certainly the same photograph, imported twice, and storing it once
     * is the right answer rather than a collision to be avoided.
     */
    private fun digestOf(asset: Asset): String {
        val at = asset.capturedAt
        val material = "${at.year}-${at.month}-${at.day}T${at.hour}:${at.minute}:${at.second}" +
            "|${asset.originalName}|${asset.sizeBytes}"
        var hash = FNV_OFFSET
        for (byte in material.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= FNV_PRIME
        }
        return (hash ushr 32).toString(16).padStart(8, '0').takeLast(8)
    }

    private fun two(value: Int): String = value.toString().padStart(2, '0')

    companion object {
        const val DEFAULT_ROOT = "Photos"
        private const val FNV_OFFSET = -3750763034362895579L // 14695981039346656037 unsigned
        private const val FNV_PRIME = 1099511628211L
    }
}
