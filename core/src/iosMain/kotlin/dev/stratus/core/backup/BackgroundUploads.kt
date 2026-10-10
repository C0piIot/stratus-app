package dev.stratus.core.backup

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.Foundation.NSUUID
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * What the system is told to carry, and what it says afterwards.
 *
 * A background `URLSession` transfers with the app suspended or killed and
 * relaunches it to report (stratus-app#20). So this is **a dumb executor**:
 * hand it a request and a file, and much later it says what it saw. It does
 * not say what that means -- [outcomeOf] does, against the queue's own row,
 * because the row knows how long the file is and this object knows nothing at
 * all after a restart.
 *
 * Written in Kotlin and not in Swift on purpose. Kotlin/Native can implement
 * an Objective-C protocol -- Ktor's own Darwin engine does -- and that is
 * ninety seconds of an ordinary runner per iteration instead of seven minutes
 * of a Mac. What is left for Swift is the relaunch hook and nothing else.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class BackgroundUploads(
    /** Told what the platform saw, by the ticket the transfer carries. */
    private val settle: suspend (ticket: String, answer: TransferAnswer) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Whether the system may carry these transfers over mobile data.
     *
     * Set before a pass hands anything over, from the server's own setting
     * (stratus-app#133). **It is read when the session is built and not
     * afterwards**, because a background session is its configuration -- so
     * the honest statement is that it takes effect from the next launch of the
     * process rather than the next pass. The setting is still obeyed in the
     * meantime, by the pass refusing to hand anything over at all; this is what
     * stops a transfer *already running* from continuing on cellular. Needs a
     * device to confirm, which is stratus-app#117's.
     */
    var allowsCellular: Boolean = true

    /**
     * The one session, by identifier.
     *
     * The identifier is what reconnects a relaunched process to transfers the
     * system has been running without it, and two sessions sharing one is an
     * error -- so there is one of these per process, as there is one container.
     */
    private val session: NSURLSession by lazy {
        NSURLSession.sessionWithConfiguration(
            configuration = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(SESSION).apply {
                // The system's judgement about when, which is the whole point:
                // in practice on wifi and while charging. Asking for anything
                // else would promise what iOS does not offer.
                discretionary = true
                sessionSendsLaunchEvents = true
                allowsCellularAccess = allowsCellular
            },
            delegate = Delegate(),
            delegateQueue = null,
        )
    }

    /** Called once the system has finished reporting, so Swift can let go. */
    var onEventsDelivered: (() -> Unit)? = null

    /**
     * Starts a transfer and answers the ticket it will be known by.
     *
     * The ticket is ours and travels in `taskDescription`, the documented
     * place to carry something of your own and the only one that survives the
     * app being killed. `taskIdentifier` does not: it is unique within a
     * session and handed out again afterwards.
     *
     * Nothing else is remembered here, and that is deliberate. The body's path
     * is a pure function of the ticket, and everything else worth knowing is
     * in the row -- so a process that dies mid-transfer leaves nothing behind
     * that its successor needs.
     */
    suspend fun start(request: NSURLRequest, fill: suspend (Path) -> Unit): String {
        val ticket = NSUUID().UUIDString()
        val body = spoolPath(ticket)
        // Filled after the ticket is minted, because the path is derived from
        // it -- which is what lets the delegate find the body again with
        // nothing written down.
        fill(body)
        val task = session.uploadTaskWithRequest(request, fromFile = NSURL.fileURLWithPath(body.toString()))
        task.taskDescription = ticket
        task.resume()
        return ticket
    }

    /** What the system still has, for the queue to reconcile against. */
    suspend fun live(): Set<String> = suspendCancellableCoroutine { waiting ->
        session.getAllTasksWithCompletionHandler { tasks ->
            waiting.resume(
                tasks.orEmpty()
                    .filterIsInstance<NSURLSessionTask>()
                    .mapNotNull { it.taskDescription }
                    .toSet(),
            )
        }
    }

    private inner class Delegate : NSObject(), NSURLSessionTaskDelegateProtocol {

        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
            val ticket = task.taskDescription ?: return
            // Whatever the answer, the body is no longer anybody's: a camera
            // roll's worth of spooled copies is the camera roll a second time.
            SystemFileSystem.delete(spoolPath(ticket), mustExist = false)

            val response = task.response as? NSHTTPURLResponse
            val answer = TransferAnswer(
                status = response?.statusCode?.toInt(),
                offset = (response?.allHeaderFields?.get(TusProtocol.UPLOAD_OFFSET) as? String)?.toLongOrNull(),
                detail = didCompleteWithError?.localizedDescription,
            )
            scope.launch { settle(ticket, answer) }
        }

        override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
            onEventsDelivered?.invoke()
        }
    }

    companion object {
        const val SESSION = "dev.stratus.uploads"

        /** Where a body waits to be collected. A function of the ticket, so nothing has to remember it. */
        fun spoolPath(ticket: String): Path = Path(NSTemporaryDirectory() + "stratus-upload-" + ticket)
    }
}
