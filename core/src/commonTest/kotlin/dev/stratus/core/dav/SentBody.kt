package dev.stratus.core.dav

import io.ktor.client.request.HttpRequestData
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/**
 * What a request carried, for the tests that are about a wire format.
 *
 * Shared rather than written twice: the body a MockEngine hands back is one of
 * several shapes depending on how it was set, and a test that got the `when`
 * wrong would silently assert against an empty string.
 */
internal suspend fun HttpRequestData.sentBody(): String = when (val content = body) {
    is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
    is OutgoingContent.ReadChannelContent -> content.readFrom().readRemaining().readByteArray().decodeToString()
    else -> ""
}
