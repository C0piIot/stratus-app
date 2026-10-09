package dev.stratus.core.server

/**
 * The Stratus somebody has signed in to. There is one (stratus-app#131).
 *
 * It carried a generated opaque id until the app stopped holding several at
 * once. That id said which server a row belonged to, and with one server the
 * rows belong to the server; the one thing it really did -- tell *the same
 * server at a new address* from *a different server* -- was never the id's
 * doing but the user's, and that rule stands with no field behind it: editing
 * the address keeps the backup record, signing out drops it.
 *
 * [sources] is also the switch. **Empty means no backup**, and choosing a
 * source is how it is turned on -- there is no separate flag, because two ways
 * to say "not now" is one of them eventually disagreeing with the other.
 */
data class Server(
    val baseUrl: String,
    val username: String,
    val backupRoot: String = DEFAULT_BACKUP_ROOT,
    /** Which sources feed the backup. Empty means none, which means no backup. */
    val sources: Set<String> = emptySet(),
) {
    /** Whether a pass has anything to do, which is a question about [sources]. */
    val backupEnabled: Boolean get() = sources.isNotEmpty()

    companion object {
        /**
         * Where a camera roll lands by default.
         *
         * Under `files/` because the base URL is the origin now, and the
         * origin of a Stratus is a read-only listing of its collections --
         * the writable tree is one level down (stratus-backend#279). The two
         * segments cost nothing: the directory maker walks every one of them
         * and treats "already there" as the ordinary answer.
         *
         * Named for what wrote it rather than for what is in it. `Photos`
         * read like a folder somebody had made and chosen; this is a process
         * filing things, and a person looking at their tree should be able to
         * tell which it was.
         *
         * It is a default and not a rule: [backupRoot] is a field, and a
         * server that has one keeps it.
         */
        const val DEFAULT_BACKUP_ROOT = "files/phone_backup"
    }
}
