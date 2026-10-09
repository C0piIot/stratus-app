package dev.stratus.core.documents

import platform.FileProvider.NSFileProviderDomain
import platform.FileProvider.NSFileProviderManager

/**
 * The domain that puts the server under Locations in Files (stratus-app#105).
 *
 * The exact twin of Android's root: there, the same hook tells the resolver
 * the roots changed; here it adds or removes the domain. Both hang off
 * `ServerStore`'s `changed`, because signing in or out is the only moment
 * this can be wrong.
 *
 * **One domain with a fixed identifier** (stratus-app#131). It used to be
 * named by the server's generated id, and there is no id any more -- which
 * also means an older install's domain is a stale one, removed by the same
 * comparison as any other.
 *
 * Idempotent on purpose: it is called after every change and compares rather
 * than remembers, because what the system holds outlives this process.
 */
internal fun refreshDomains(root: DocumentRoot?) {
    NSFileProviderManager.getDomainsWithCompletionHandler { existing, _ ->
        val have = existing.orEmpty().filterIsInstance<NSFileProviderDomain>()

        for (domain in have) {
            if (root == null || domain.identifier != DOMAIN) {
                NSFileProviderManager.removeDomain(domain) { }
            }
        }
        if (root != null && have.none { it.identifier == DOMAIN }) {
            NSFileProviderManager.addDomain(
                // The path is the domain's own corner of the document storage
                // group, and the identifier serves for it as well.
                NSFileProviderDomain(
                    identifier = DOMAIN,
                    displayName = root.summary,
                    pathRelativeToDocumentStorage = DOMAIN,
                ),
            ) { }
        }
    }
}

/** The one domain's identifier, which is a constant now rather than an id. */
private const val DOMAIN = "stratus"

