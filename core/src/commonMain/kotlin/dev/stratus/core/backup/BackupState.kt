package dev.stratus.core.backup

/**
 * What to say about a backup.
 *
 * The distinction this type exists to force is between [Waiting] and [NeedsYou]:
 * **one resolves itself and the other does not.** A backoff after a timeout and
 * a rejected password are both "nothing is uploading", and rendering them the
 * same is how somebody stops reading the screen. A sealed type makes the
 * compiler insist they are answered separately, which no amount of careful
 * wording would.
 */
sealed interface BackupState {
    /** Nothing has ever run, which is not the same as nothing being wrong. */
    data object NeverRun : BackupState

    /** Everything that could be sent has been. */
    data class Idle(val lastRunAt: Long, val uploaded: Int) : BackupState

    data class Working(val done: Int, val left: Int, val current: String?) : BackupState

    /** It will come back to this on its own. */
    data class Waiting(
        val reason: WaitingReason,
        val left: Int,
        val bytesLeft: Long,
        val until: Long?,
        val lastRunAt: Long,
    ) : BackupState

    /** It will not, until somebody does something. */
    data class NeedsYou(val reason: AttentionReason, val affected: Int) : BackupState
}

enum class WaitingReason {
    /** Work is runnable; the app is simply not running it this moment. */
    ForTheNextPass,

    /** Everything left is serving out a backoff after something that may pass. */
    ForARetry,
}

sealed interface AttentionReason {
    /** The camera roll cannot be read, so nothing can be backed up at all. */
    data object TheLibraryIsNotReadable : AttentionReason

    /** Work the queue has given up on. [detail] is what the server said. */
    data class SomethingWillNotSend(val detail: String?) : AttentionReason
}
