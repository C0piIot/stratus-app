package dev.stratus.app.e2e

import android.Manifest
import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/** What the phone does outside the app: permissions, the camera roll, the shell. */
object Phone {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    val context = instrumentation.targetContext
    private val app = context.packageName

    val photoPermissions = arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
    )

    fun shell(command: String): String {
        val out = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes().decodeToString() }
    }

    /** As the system settings do it: from outside, while the app is running. */
    fun grantPhotos() = photoPermissions.forEach { instrumentation.uiAutomation.grantRuntimePermission(app, it) }

    private const val CONTROLLER = "com.android.permissioncontroller"

    /** The permission dialog, if it appears within [timeoutMs]. */
    fun permissionDialogShown(timeoutMs: Long = 5_000): Boolean =
        device.wait(Until.hasObject(By.pkg(CONTROLLER).depth(0)), timeoutMs)

    fun allowAll() {
        val button = device.wait(
            Until.findObject(By.res(CONTROLLER, "permission_allow_all_button")),
            5_000,
        ) ?: device.wait(Until.findObject(By.res(CONTROLLER, "permission_allow_button")), 2_000)
        requireNotNull(button) { "no allow button on the permission dialog" }.click()
    }

    /** The second time it asks, Android offers "don't ask again" under another id. */
    fun deny() {
        val button = device.wait(Until.findObject(By.res(CONTROLLER, "permission_deny_button")), 5_000)
            ?: device.wait(Until.findObject(By.res(CONTROLLER, "permission_deny_and_dont_ask_again_button")), 2_000)
        requireNotNull(button) { "no deny button on the permission dialog" }.click()
    }

    fun inFront(): String = device.currentPackageName

    fun backToApp() {
        device.pressBack()
        device.wait(Until.hasObject(By.pkg(app).depth(0)), 5_000)
    }

    fun toHomeAndBack() {
        device.pressHome()
        device.waitForIdle()
        shell("monkey -p $app -c android.intent.category.LAUNCHER 1")
        device.wait(Until.hasObject(By.pkg(app).depth(0)), 5_000)
    }

    fun airplane(on: Boolean) {
        shell("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}")
        // Radios take a moment either way; what matters is that it settles.
        Thread.sleep(3_000)
    }

    /**
     * A photograph in the camera roll, taken [takenMillis], in a folder of its
     * own so tests do not see each other's.
     *
     * Inserted by this process, which is the app's: the app owns the row and
     * MediaStore would show it without any permission. What gates reading is
     * the permission check in AndroidAssetSource, which is what is under test.
     */
    fun photograph(name: String, folder: String, bytes: ByteArray, takenMillis: Long = System.currentTimeMillis()): Uri {
        val resolver = context.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/$folder")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pending))
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(
            uri,
            ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
                put(MediaStore.Images.Media.DATE_TAKEN, takenMillis)
            },
            null,
            null,
        )
        return uri
    }

    /**
     * What Downloads holds under this name, and how many bytes are really in
     * each copy -- read, because the SIZE column says 0 until the scanner has
     * been, which is not the question.
     */
    fun downloads(name: String): List<Long> {
        val resolver = context.contentResolver
        val ids = mutableListOf<Long>()
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf("${name.substringBeforeLast('.')}%"),
            null,
        )?.use { while (it.moveToNext()) ids += it.getLong(0) }
        return ids.map { id ->
            val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            resolver.openInputStream(uri)?.use { stream ->
                var total = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    total += n
                }
                total
            } ?: -1
        }
    }
}
