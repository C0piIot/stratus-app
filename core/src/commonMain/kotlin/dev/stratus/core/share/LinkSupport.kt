package dev.stratus.core.share

import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.http.HttpHeaders
import kotlin.coroutines.cancellation.CancellationException

/**
 * Whether this server honours the links this app signs.
 *
 * `ShareLinks` mints tokens in Stratus's format. Pointed at any other WebDAV
 * server the app would still offer to share and to cast, and produce a URL that
 * server has never heard of -- a dead link handed to somebody, or a television
 * that sits there. This is how that is known rather than assumed.
 *
 * **Asked with the link somebody is actually sending**, at the moment they send
 * it, rather than by probing at startup. A speculative check would have to sign
 * something to ask about, and the only path always available to sign is the
 * root -- putting an all-access signature on the wire to answer a question
 * nobody had yet. The answer is remembered, so the second file does not ask.
 *
 * The request carries no credentials, deliberately: with them it would succeed
 * against any server and prove nothing.
 */
class LinkSupport(private val http: HttpClient) {

    private var refused = false

    /** What the last check was answered with: a status and a type, or the exception. */
    var lastAnswer: String? = null
        private set

    /** False once a server has been found not to do this. */
    val offered: Boolean get() = !refused

    /**
     * Whether a link answers as [contentType], without deciding anything about
     * links in general: a server that signs links and does not do HLS is still
     * one that signs links. The type is the test and not the status, because a
     * server that ignores the query hands back the file itself.
     */
    suspend fun serves(link: String, contentType: String): Boolean = runCatching {
        val response = http.head(link)
        lastAnswer = "${response.status.value} ${response.headers[HttpHeaders.ContentType]}"
        response.status.value == 200 && response.headers[HttpHeaders.ContentType]?.startsWith(contentType) == true
    }.onFailure { lastAnswer = it.toString() }.getOrDefault(false)

    /**
     * A refusal is remembered and a failure is not: a server that restarted,
     * or a phone that lost the network, says nothing about links, and taking
     * it for a no hid casting and sharing until the app was restarted.
     */
    suspend fun honours(link: String): LinkAnswer {
        val status = try {
            http.head(link).also {
                lastAnswer = "${it.status.value} ${it.headers[HttpHeaders.ContentType]}"
            }.status.value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastAnswer = e.toString()
            return LinkAnswer.NoAnswer
        }
        return when {
            status == 200 -> LinkAnswer.Honoured.also { refused = false }
            status >= 500 -> LinkAnswer.NoAnswer
            else -> LinkAnswer.Refused.also { refused = true }
        }
    }
}

enum class LinkAnswer { Honoured, Refused, NoAnswer }
