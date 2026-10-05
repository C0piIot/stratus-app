package dev.stratus.core.documents

import platform.FileProvider.NSFileProviderDomain
import platform.FileProvider.NSFileProviderManager

/**
 * One domain per server, which is what puts each of them under Locations in
 * Files (stratus-app#105).
 *
 * The exact twin of Android's "one root per server": there, the same hook
 * tells the resolver the roots changed; here it adds and removes domains.
 * Both hang off `InstanceStore`'s `changed`, because the moment a server is
 * added or forgotten is the only moment this can be wrong.
 *
 * A domain is named by the instance id, which is also that server's own root
 * document -- so the extension can read the root container straight off the
 * domain it was asked in, with nothing to look up.
 *
 * Idempotent on purpose: it is called after every change and compares rather
 * than remembers, because what the system holds outlives this process.
 */
internal fun refreshDomains(roots: List<DocumentRoot>) {
    NSFileProviderManager.getDomainsWithCompletionHandler { existing, _ ->
        val have = existing.orEmpty().filterIsInstance<NSFileProviderDomain>()
        val wanted = roots.associateBy { it.instanceId }

        for (domain in have) {
            if (domain.identifier !in wanted) {
                NSFileProviderManager.removeDomain(domain) { }
            }
        }
        for ((id, root) in wanted) {
            if (have.none { it.identifier == id }) {
                NSFileProviderManager.addDomain(
                    // The path is the domain's own corner of the document
                    // storage group; the id serves, being unique and never
                    // handed out twice.
                    NSFileProviderDomain(
                        identifier = id,
                        displayName = root.summary,
                        pathRelativeToDocumentStorage = id,
                    ),
                ) { }
            }
        }
    }
}
