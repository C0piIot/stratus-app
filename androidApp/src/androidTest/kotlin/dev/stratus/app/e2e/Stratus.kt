package dev.stratus.app.e2e

import androidx.test.platform.app.InstrumentationRegistry
import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavError
import dev.stratus.core.net.Credentials
import dev.stratus.core.net.stratusHttpClient
import io.ktor.client.engine.okhttp.OkHttp
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

    /** The server behind the app's back, for arranging a test and checking its outcome. */
    val dav = DavClient(stratusHttpClient(OkHttp.create(), Credentials(user, password)), "http://10.0.2.2:$port/dav/")

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

    fun names(path: String): List<String> = runBlocking { dav.list(path).map { it.name } }

    /**
     * Where the backup put a file with this original name, walking the year and
     * month folders under the backup root rather than recomputing the layout --
     * which is what RemoteLayoutTest pins, and a second copy of it here would
     * only have to agree.
     */
    fun backedUp(originalName: String, root: String = "/Photos/"): Pair<String, Long>? = runBlocking {
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
