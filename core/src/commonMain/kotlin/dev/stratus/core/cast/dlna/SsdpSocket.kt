package dev.stratus.core.cast.dlna

/**
 * The platform's half of discovery: a datagram out to the SSDP group and the
 * datagrams that come back. Nothing here reads them -- [Ssdp] does.
 */
fun interface SsdpSocket {
    /**
     * Sends [request] to [Ssdp.HOST]:[Ssdp.PORT] and hands [heard] every
     * datagram that answers within [listenMillis]. An answer to a search comes
     * back to the port it was sent from, so this needs no group membership.
     */
    suspend fun search(request: String, listenMillis: Long, heard: (String) -> Unit)
}
