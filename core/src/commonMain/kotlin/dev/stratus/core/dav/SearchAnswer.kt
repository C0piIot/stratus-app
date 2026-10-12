package dev.stratus.core.dav

/**
 * What one `SEARCH` came back with.
 *
 * [cut] is the server saying it stopped early -- RFC 5323 2.3.1, which it
 * reports as a 507 on the collection's own href inside the multistatus. There
 * is no cursor in this protocol, so a cut answer is not a first page: it is
 * everything that will be given for that word, and the only way to see the
 * rest is a narrower one. Saying so is the whole reason this is not a list.
 */
data class SearchAnswer(
    val found: List<DavResource>,
    val cut: Boolean = false,
)
