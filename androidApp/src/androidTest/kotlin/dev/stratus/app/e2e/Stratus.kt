package dev.stratus.app.e2e

import androidx.test.platform.app.InstrumentationRegistry
import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavError
import dev.stratus.core.net.Credentials
import dev.stratus.core.net.stratusHttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress

/**
 * The backend scripts/e2e.sh started, as the test sees it: reached directly at
 * the emulator's alias for its host, while the app goes through a [FaultyLink].
 */
object Stratus {
    private val arguments = InstrumentationRegistry.getArguments()

    val port: Int = arguments.getString("stratusPort")?.toInt() ?: 18098
    val user: String = arguments.getString("stratusUser") ?: "e2e"
    val password: String = arguments.getString("stratusPassword") ?: "e2e-secret"

    /** 10.0.2.2 is the emulator's name for the machine it runs on. */
    val upstream = InetSocketAddress("10.0.2.2", port)

    /** The same user on a second server under another password: a password changed. */
    val otherPassword: String = arguments.getString("stratusPassword2") ?: "e2e-secret-2"
    val otherUpstream = InetSocketAddress("10.0.2.2", arguments.getString("stratusPort2")?.toInt() ?: 18097)

    /**
     * The server behind the app's back, for arranging a test and checking its
     * outcome -- on `/files/`, which is the writable tree. The app signs in at
     * the origin and walks into it; these paths start inside it, so arranging
     * a folder reads the same as it always did.
     */
    val dav = DavClient(stratusHttpClient(OkHttp.create(), Credentials(user, password)), "http://10.0.2.2:$port/files/")

    fun folder(path: String) = runBlocking { dav.makeCollection(path) }

    fun file(path: String, bytes: ByteArray = "hello".encodeToByteArray()) = runBlocking { dav.put(path, bytes) }

    fun exists(path: String): Boolean = runBlocking {
        try {
            dav.stat(path)
            true
        } catch (_: DavError.NotFound) {
            false
        }
    }

    /** Gone, whether or not it was there. */
    fun remove(path: String) = runBlocking {
        try {
            dav.delete(path)
        } catch (_: Exception) {
        }
    }

    fun names(path: String): List<String> = runBlocking { dav.list(path).map { it.name } }

    /** What a file holds, for checking what somebody else wrote into it. */
    fun read(path: String): String = runBlocking {
        dav.read(path) { it.readRemaining().readByteArray().decodeToString() }
    }

    /**
     * Where the backup put a file with this original name, walking the year and
     * month folders under the backup root rather than recomputing the layout --
     * which is what RemoteLayoutTest pins, and a second copy of it here would
     * only have to agree.
     */
    /** Every path under the backup root, for a failure to say what did arrive. */
    fun underBackupRoot(root: String = "/phone_backup/"): List<String> = runBlocking {
        runCatching {
            dav.list(root).flatMap { year ->
                if (!year.isDirectory) listOf(year.path) else dav.list(year.path).flatMap { month ->
                    if (!month.isDirectory) listOf(month.path) else dav.list(month.path).map { it.path }
                }
            }
        }.getOrElse { listOf("(could not be read: $it)") }
    }

    /** Deletes one file, the way somebody clearing space in the web UI would. */
    fun delete(path: String) = runBlocking { dav.delete(path) }

    fun backedUp(originalName: String, root: String = "/phone_backup/"): Pair<String, Long>? = runBlocking {
        if (!exists(root)) return@runBlocking null
        for (year in dav.list(root).filter { it.isDirectory }) {
            for (month in dav.list(year.path).filter { it.isDirectory }) {
                dav.list(month.path).firstOrNull { originalName.substringBeforeLast('.') in it.name }
                    ?.let { return@runBlocking it.path to (it.size ?: -1) }
            }
        }
        null
    }
}
