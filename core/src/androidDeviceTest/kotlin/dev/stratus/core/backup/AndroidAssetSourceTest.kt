package dev.stratus.core.backup

import android.Manifest
import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The half that needed a device.
 *
 * Everything this class decides lives in `assetOf`, which the fast loop already
 * covers. What is proved here is the part that cannot be reasoned about: that
 * the cursor reads back what `MediaStore` was given, that a bucket filter is
 * honoured, and that a granted permission is reported as one.
 */
class AndroidAssetSourceTest {

    @get:org.junit.Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
    )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val source = AndroidAssetSource(context)

    private val folder = "StratusTest${Random.nextInt(100_000)}"
    private val inserted = mutableListOf<android.net.Uri>()

    /**
     * Puts one file in the library with a capture time that survives.
     *
     * The dance with IS_PENDING is not ceremony: writing DATE_TAKEN on the
     * insert loses it, because the media scanner runs afterwards, finds no EXIF
     * in these bytes and writes its own answer over the top. Setting it once the
     * file is no longer pending is what makes it stick.
     */
    private fun seed(name: String, takenMillis: Long, bytes: Int = 16) {
        val pending = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$folder")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pending))
        resolver.openOutputStream(uri)!!.use { it.write(ByteArray(bytes) { 7 }) }

        val settled = ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
            put(MediaStore.Images.Media.DATE_TAKEN, takenMillis)
        }
        resolver.update(uri, settled, null, null)
        inserted += uri
    }

    @BeforeTest
    fun seedTheLibrary() {
        seed("one.jpg", utcMillisFor(2026, 9, 18, 10))
        seed("two.jpg", utcMillisFor(2026, 9, 18, 11))
    }

    @AfterTest
    fun tidyUp() {
        inserted.forEach { resolver.delete(it, null, null) }
    }

    @Test
    fun reportsAGrantedPermissionAsFullAccess() = runTest {
        assertEquals(MediaAccess.Full, source.access())
    }

    @Test
    fun findsTheFolderItWasGiven() = runTest {
        val mine = source.sources().singleOrNull { it.label == folder }
        assertTrue(mine != null, "the folder $folder was not among ${source.sources().map { it.label }}")
        assertEquals(2, mine.count)
    }

    @Test
    fun readsBackWhatTheLibraryWasGiven() = runTest {
        val bucket = source.sources().single { it.label == folder }.id
        val assets = source.assets(setOf(bucket))

        // Contents, not order: both rows were added in the same second, so
        // DATE_ADDED ties and what comes back first is arbitrary. The order that
        // matters is the queue's, and that is a unit test.
        assertEquals(setOf("one.jpg", "two.jpg"), assets.map { it.originalName }.toSet())
        assertEquals(setOf(16L), assets.map { it.sizeBytes }.toSet())

        // Every asset has a usable capture time, and no more is claimed than
        // that. MediaProvider derives DATE_TAKEN from EXIF and quietly discards
        // what an app writes, so asserting a particular millisecond here would
        // be testing MediaProvider rather than this code -- and *which* of the
        // two timestamps wins is the one judgement in all of this, which
        // `MediaRowTest` pins on the JVM in milliseconds.
        assertTrue(assets.all { it.capturedAtEpochMs > 0 }, assets.map { it.capturedAtEpochMs }.toString())
    }

    @Test
    fun honoursTheFolderFilter() = runTest {
        // A bucket nobody has: the answer is nothing, not everything.
        assertEquals(emptyList(), source.assets(setOf("no-such-bucket")))
    }

    @Test
    fun handsBackTheBytesItWasGiven() = runTest {
        val bucket = source.sources().single { it.label == folder }.id
        val asset = source.assets(setOf(bucket)).first()
        val read = source.open(asset.localId, AssetPart.Still).buffered().readByteArray()
        assertEquals(16, read.size)
    }
}

/** The same arithmetic as the shared tests, which cannot be imported from here. */
private fun utcMillisFor(year: Int, month: Int, day: Int, hour: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val monthPrime = if (month > 2) month - 3 else month + 9
    val dayOfYear = (153 * monthPrime + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return ((era * 146_097 + dayOfEra - 719_468) * 86_400 + hour * 3_600) * 1_000
}
