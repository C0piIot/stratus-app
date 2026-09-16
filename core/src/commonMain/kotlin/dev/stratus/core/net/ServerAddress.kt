package dev.stratus.core.net

/** The two schemes worth speaking to, and the ports they imply. */
enum class Scheme(val wire: String, val defaultPort: Int) {
    Http("http", 80),
    Https("https", 443),
}

/**
 * What somebody typed into the address field, taken apart.
 *
 * A null [scheme] or [path] means they typed none, which is different from
 * having typed the default: "no scheme" is what licenses trying https and then
 * asking about http, and "no path" is what licenses looking for `/dav/`.
 */
data class ServerAddress(
    val scheme: Scheme?,
    val host: String,
    val port: Int?,
    val path: String?,
)

/** Why an address could not be used, in terms a screen can phrase. */
sealed interface AddressProblem {
    data object Empty : AddressProblem
    data class UnknownScheme(val typed: String) : AddressProblem
    data class BadPort(val typed: String) : AddressProblem
    data object CredentialsInUrl : AddressProblem
    data object QueryOrFragment : AddressProblem
    data class NotAHost(val typed: String) : AddressProblem
}

sealed interface ParsedAddress {
    data class Valid(val address: ServerAddress) : ParsedAddress
    data class Invalid(val problem: AddressProblem) : ParsedAddress
}

/**
 * Reads an address the way somebody types one: bare host, host and port, with or
 * without a scheme, with or without a trailing slash.
 *
 * Refuses rather than repairs where repairing would hide a mistake. A query or a
 * fragment means they pasted a link to a page instead of the server, and quietly
 * dropping it would sign them in to somewhere they did not mean; credentials in
 * the URL would end up stored in a field meant for an address.
 */
fun parseServerAddress(typed: String): ParsedAddress {
    var rest = typed.trim()
    if (rest.isEmpty()) return ParsedAddress.Invalid(AddressProblem.Empty)

    var scheme: Scheme? = null
    val separator = rest.indexOf("://")
    if (separator >= 0) {
        val named = rest.substring(0, separator).lowercase()
        scheme = Scheme.entries.firstOrNull { it.wire == named }
            ?: return ParsedAddress.Invalid(AddressProblem.UnknownScheme(named))
        rest = rest.substring(separator + 3)
    }

    if (rest.any { it == '?' || it == '#' }) {
        return ParsedAddress.Invalid(AddressProblem.QueryOrFragment)
    }

    val authorityEnd = rest.indexOf('/').let { if (it < 0) rest.length else it }
    var authority = rest.substring(0, authorityEnd)
    val typedPath = rest.substring(authorityEnd)

    if ('@' in authority) return ParsedAddress.Invalid(AddressProblem.CredentialsInUrl)
    if (authority.isEmpty()) return ParsedAddress.Invalid(AddressProblem.NotAHost(typed.trim()))

    // An IPv6 literal has to arrive in brackets, or splitting on the last colon
    // would take the final group of the address for a port.
    val host: String
    var portText: String? = null
    if (authority.startsWith("[")) {
        val close = authority.indexOf(']')
        if (close < 0) return ParsedAddress.Invalid(AddressProblem.NotAHost(authority))
        host = authority.substring(0, close + 1)
        val after = authority.substring(close + 1)
        when {
            after.isEmpty() -> {}
            after.startsWith(":") -> portText = after.substring(1)
            else -> return ParsedAddress.Invalid(AddressProblem.NotAHost(authority))
        }
    } else {
        val colon = authority.lastIndexOf(':')
        if (colon >= 0) {
            portText = authority.substring(colon + 1)
            authority = authority.substring(0, colon)
        }
        host = authority
    }

    if (host.isEmpty() || host.any { it.isWhitespace() }) {
        return ParsedAddress.Invalid(AddressProblem.NotAHost(host))
    }

    val port = portText?.let {
        it.toIntOrNull()?.takeIf { value -> value in 1..65535 }
            ?: return ParsedAddress.Invalid(AddressProblem.BadPort(it))
    }

    return ParsedAddress.Valid(
        ServerAddress(
            scheme = scheme,
            // The host is case-insensitive; the path is not, and a server with a
            // case-sensitive filesystem will not forgive us for lowercasing it.
            host = host.lowercase(),
            port = port,
            path = normalisePath(typedPath),
        ),
    )
}

/** "" and "/" both mean "they typed no path". Anything else gains both slashes. */
private fun normalisePath(typed: String): String? {
    val trimmed = typed.trim('/')
    if (trimmed.isEmpty()) return null
    return "/$trimmed/"
}
