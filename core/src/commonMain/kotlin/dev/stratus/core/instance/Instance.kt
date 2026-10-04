package dev.stratus.core.instance

import kotlin.random.Random

/**
 * One Stratus somebody has signed in to.
 *
 * **[id] is generated and opaque, and deliberately not the address.** Somebody
 * who moves their server to a new domain still has the same instance, and its
 * backup history has to follow them there rather than start again. Identity by
 * URL would be free today and a migration with real photographs behind it later.
 *
 * [backupEnabled] is separate from being signed in: an instance can be worth
 * browsing without being worth sending a camera roll to. Nothing reads it yet --
 * it is here for the same reason [backupRoot] was, because adding a field to a
 * stored record afterwards costs a migration.
 */
data class Instance(
    val id: String,
    val baseUrl: String,
    val username: String,
    val backupRoot: String = DEFAULT_BACKUP_ROOT,
    val backupEnabled: Boolean = false,
    /** Which sources feed this instance. Empty means nothing has been chosen yet. */
    val sources: Set<String> = emptySet(),
) {
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
         * It is a default and not a rule: [backupRoot] is a field, and an
         * instance that has one keeps it.
         */
        const val DEFAULT_BACKUP_ROOT = "files/phone_backup"
    }
}

/** Sixty-four random bits, which is an identifier and not a name. */
fun newInstanceId(): String = half() + half()

private fun half(): String = Random.nextInt().toUInt().toString(16).padStart(8, '0')
