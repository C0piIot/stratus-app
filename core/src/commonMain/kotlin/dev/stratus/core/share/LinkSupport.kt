package dev.stratus.core.share

import io.ktor.client.HttpClient
import io.ktor.client.request.head

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

    /** False once a server has been found not to do this. */
    val offered: Boolean get() = !refused

    suspend fun honours(link: String): Boolean {
        val worked = runCatching { http.head(link).status.value == 200 }.getOrDefault(false)
        refused = !worked
        return worked
    }
}
