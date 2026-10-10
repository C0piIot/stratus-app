package dev.stratus.core.backup

import io.ktor.http.Url

/** Where the account of a pass goes on this platform. */
interface LogSink {
    /** One line, as it happens. */
    fun line(text: String)

    /**
     * The end of a pass: [summary] is the one line worth keeping on its own,
     * [trail] everything said on the way to it.
     *
     * Two methods rather than one because the two destinations want different
     * things. A console wants the lines as they happen and nothing at the end;
     * Sentry wants one event per pass with the lines hanging off it, because a
     * line is not an event and three hundred of them are not three hundred.
     */
    fun pass(summary: String, trail: List<String>)
}

/** Everything said, as it is said. What a platform with nowhere better uses. */
internal object ConsoleLogSink : LogSink {
    override fun line(text: String) = println(text)

    /** The summary already went out as a line; there is nothing to collect it into. */
    override fun pass(summary: String, trail: List<String>) = Unit
}

internal expect fun platformLogSink(): LogSink

/**
 * A running account of what a pass is actually sending.
 *
 * It answers one question nothing else here can: **is it uploading the same
 * photographs over and over?** The Backup screen says how much is left and the
 * journal says what the last pass did, and neither of them says *which* files
 * went -- so forty re-sent every six hours and forty new ones look identical
 * from the outside, and the second is what a working backup looks like.
 *
 * **It is off for every address but one**, and that is the whole of its
 * privacy story. The lines carry filenames out of somebody's camera roll, and
 * pointing that at your own server is a reasonable thing to do with your own
 * photographs and not a reasonable thing to do with a stranger's. There is no
 * switch to offer instead: the app has no developer settings, and a surface
 * built to ask a question only one install has is a surface to maintain for
 * ever.
 */
class BackupLog(
    baseUrl: String,
    private val sink: LogSink = platformLogSink(),
) {
    /**
     * Whether anything is written, and public because the caller has to ask
     * before it speaks: how much is left is a query, and one per file for a
     * log nobody will read is a query spent for nothing.
     */
    val on: Boolean =
        runCatching { Url(baseUrl).host }.getOrNull()?.lowercase() == NARRATED_HOST

    private val trail = mutableListOf<String>()
    private var dropped = 0

    fun say(line: String) {
        if (!on) return
        sink.line(line)
        remember(line)
    }

    /** The last line of a pass, which is the one the rest of them hang from. */
    fun summarise(line: String) {
        if (!on) return
        sink.line(line)
        remember(line)
        sink.pass(line, whole())
        trail.clear()
        dropped = 0
    }

    /**
     * Bounded, because a first pass over forty thousand photographs would
     * otherwise be a megabyte of event for a question answered by a few
     * hundred lines.
     *
     * The first line is kept whatever happens -- it is the pass's own counts,
     * which is what the rest is read against -- and the newest are what push
     * the middle out. How many went is said rather than left to be inferred
     * from a story that starts in the middle.
     */
    private fun remember(line: String) {
        trail += line
        if (trail.size > KEPT) {
            trail.removeAt(1)
            dropped++
        }
    }

    private fun whole(): List<String> =
        if (dropped == 0) trail.toList() else trail.toMutableList().apply { add(1, "-- $dropped lines dropped --") }

    companion object {
        /**
         * The one instance whose backups are narrated.
         *
         * In the source rather than in the build's environment, which is where
         * `SENTRY_DSN` lives and for a reason that does not apply here: a DSN
         * is a credential a fork must not inherit, and this is an address.
         * Keeping it readable is what makes "why is my camera roll in somebody
         * else's Sentry?" a question that can be answered by looking.
         */
        const val NARRATED_HOST = "stratus.dropdatabase.es"

        private const val KEPT = 200
    }
}
