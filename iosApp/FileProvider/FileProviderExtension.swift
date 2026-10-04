import FileProvider
import StratusCore

// The library as a location in the Files app, and in every document picker
// (stratus-app#105). The Android twin is StratusDocumentsProvider.
//
// Non-replicated on purpose. The replicated extension is iOS 16 and is a sync
// engine: the system keeps its own copy of the tree and advances it by
// anchors, and WebDAV has no change feed to advance one from. This one reads
// through -- the system asks for an item, and for its bytes when it needs
// them -- which is what the shared DocumentTree already does for Android.
//
// Like that one, this file is the shell. Every decision in it belongs to
// `dev.stratus.core.documents`, where a test reaches it without a device.

/// One row, as the system wants it.
///
/// A `DocumentRef`'s id **is** the identifier: #104 settled that a document id
/// has to survive being written down and handed back, which is the same
/// promise this API makes.
final class Item: NSObject, NSFileProviderItem {
    private let row: DocumentRow

    init(_ row: DocumentRow) {
        self.row = row
    }

    var itemIdentifier: NSFileProviderItemIdentifier {
        NSFileProviderItemIdentifier(row.ref.id)
    }

    var parentItemIdentifier: NSFileProviderItemIdentifier {
        guard let parent = row.ref.parent() else {
            return .rootContainer
        }
        return NSFileProviderItemIdentifier(parent.id)
    }

    var filename: String {
        row.name.isEmpty ? row.ref.instanceId : row.name
    }

    var typeIdentifier: String {
        row.isDirectory ? "public.folder" : "public.data"
    }

    var documentSize: NSNumber? {
        row.size.map { NSNumber(value: $0.int64Value) } ?? nil
    }

    var contentModificationDate: Date? {
        row.lastModifiedEpochMs.map { Date(timeIntervalSince1970: Double(truncating: $0) / 1000) } ?? nil
    }

    /// The only version WebDAV honestly offers. Without it the system cannot
    /// tell a file that changed from one that did not.
    var versionIdentifier: Data? {
        row.etag?.data(using: .utf8)
    }

    var capabilities: NSFileProviderItemCapabilities {
        row.isDirectory
            ? [.allowsReading, .allowsAddingSubItems, .allowsContentEnumerating, .allowsRenaming, .allowsDeleting]
            : [.allowsReading, .allowsWriting, .allowsRenaming, .allowsDeleting]
    }
}

final class Enumerator: NSObject, NSFileProviderEnumerator {
    private let tree: DocumentTree
    private let ref: DocumentRef

    init(tree: DocumentTree, ref: DocumentRef) {
        self.tree = tree
        self.ref = ref
    }

    func invalidate() {}

    func enumerateItems(for observer: NSFileProviderEnumerationObserver, startingAt page: NSFileProviderPage) {
        // A whole folder at a time: a PROPFIND answers all of it, so paging
        // here would be cutting up an answer we already hold.
        tree.childrenWaiting(ref: ref) { listing, error in
            if let error {
                observer.finishEnumeratingWithError(error)
                return
            }
            observer.didEnumerate((listing ?? []).map(Item.init))
            observer.finishEnumerating(upTo: nil)
        }
    }
}

final class FileProviderExtension: NSFileProviderExtension {
    private let tree = IosDocuments().tree()

    // MARK: Identity

    override func item(for identifier: NSFileProviderItemIdentifier) throws -> NSFileProviderItem {
        guard let row = tree.oneWaiting(id: identifier.rawValue) else {
            throw NSError(domain: NSFileProviderErrorDomain, code: NSFileProviderError.noSuchItem.rawValue)
        }
        return Item(row)
    }

    /// Where a materialised item lives on this disk.
    ///
    /// One directory per identifier so that a name can repeat across folders,
    /// which it does: every server has a `files`.
    override func urlForItem(withPersistentIdentifier identifier: NSFileProviderItemIdentifier) -> URL? {
        guard let item = try? item(for: identifier) else { return nil }
        return NSFileProviderManager.default.documentStorageURL
            .appendingPathComponent(identifier.rawValue.data(using: .utf8)!.base64EncodedString(), isDirectory: true)
            .appendingPathComponent(item.filename)
    }

    override func persistentIdentifierForItem(at url: URL) -> NSFileProviderItemIdentifier? {
        guard let encoded = url.deletingLastPathComponent().lastPathComponent
            .data(using: .utf8).flatMap({ Data(base64Encoded: $0) }),
            let identifier = String(data: encoded, encoding: .utf8)
        else { return nil }
        return NSFileProviderItemIdentifier(identifier)
    }

    // MARK: Contents

    override func providePlaceholder(at url: URL, completionHandler: @escaping (Error?) -> Void) {
        guard let identifier = persistentIdentifierForItem(at: url), let item = try? item(for: identifier) else {
            completionHandler(NSError(domain: NSFileProviderErrorDomain, code: NSFileProviderError.noSuchItem.rawValue))
            return
        }
        do {
            let placeholder = NSFileProviderManager.placeholderURL(for: url)
            try FileManager.default.createDirectory(
                at: placeholder.deletingLastPathComponent(), withIntermediateDirectories: true
            )
            try NSFileProviderManager.writePlaceholder(at: placeholder, withMetadata: item)
            completionHandler(nil)
        } catch {
            completionHandler(error)
        }
    }

    override func startProvidingItem(at url: URL, completionHandler: @escaping (Error?) -> Void) {
        guard let identifier = persistentIdentifierForItem(at: url) else {
            completionHandler(NSError(domain: NSFileProviderErrorDomain, code: NSFileProviderError.noSuchItem.rawValue))
            return
        }
        try? FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(), withIntermediateDirectories: true
        )
        tree.downloadWaiting(id: identifier.rawValue, toPath: url.path) { error in
            completionHandler(error)
        }
    }

    /// A file edited in another app, on its way back to the server. Half of
    /// what this feature is for.
    override func itemChanged(at url: URL) {
        guard let identifier = persistentIdentifierForItem(at: url) else { return }
        tree.uploadWaiting(id: identifier.rawValue, fromPath: url.path, contentType: nil) { _ in }
    }

    override func stopProvidingItem(at url: URL) {
        // The copy is the system's cache, not state of ours: dropping it costs
        // one download and keeps nothing stale.
        try? FileManager.default.removeItem(at: url)
        providePlaceholder(at: url) { _ in }
    }

    // MARK: Enumeration

    override func enumerator(for identifier: NSFileProviderItemIdentifier) throws -> NSFileProviderEnumerator {
        // The root of a domain is one server, and a domain's identifier is the
        // instance's id -- so the root container is that server's own root.
        let ref: DocumentRef
        if identifier == .rootContainer {
            guard let instance = domain?.identifier.rawValue else {
                throw NSError(domain: NSFileProviderErrorDomain, code: NSFileProviderError.notAuthenticated.rawValue)
            }
            ref = DocumentRef.Companion().parse(id: instance)
        } else {
            ref = DocumentRef.Companion().parse(id: identifier.rawValue)
        }
        return Enumerator(tree: tree, ref: ref)
    }
}
