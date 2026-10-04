import FileProvider
import StratusCore

// The library as a location in Files, and in every document picker
// (stratus-app#105). The Android twin is StratusDocumentsProvider.
//
// Non-replicated on purpose. The replicated extension is iOS 16 and is a sync
// engine: the system keeps its own copy of the tree and advances it by
// anchors, and WebDAV has no change feed to advance one from. This one reads
// through -- the system asks for an item, and for its bytes when it needs
// them -- which is what the shared DocumentTree already does for Android.
//
// Like that one, this file is the shell: every decision is in
// `dev.stratus.core.documents`, where a test reaches it without a Mac. It
// speaks to `IosDocuments` in **identifier strings**, never building a
// DocumentRef of its own, so there is one place that knows what an id is.

/// One row, as the system wants it.
///
/// A document id **is** the identifier: #104 settled that an id has to
/// survive being written down and handed back, which is the same promise this
/// API makes of `NSFileProviderItemIdentifier`.
final class Item: NSObject, NSFileProviderItem {
    private let row: DocumentRow

    init(_ row: DocumentRow) {
        self.row = row
    }

    var itemIdentifier: NSFileProviderItemIdentifier {
        NSFileProviderItemIdentifier(row.ref.id)
    }

    var parentItemIdentifier: NSFileProviderItemIdentifier {
        guard let parent = row.ref.parent() else { return .rootContainer }
        return NSFileProviderItemIdentifier(parent.id)
    }

    var filename: String { row.name }

    var typeIdentifier: String { row.isDirectory ? "public.folder" : "public.data" }

    var documentSize: NSNumber? { row.size.map { NSNumber(value: $0.int64Value) } }

    var contentModificationDate: Date? {
        row.lastModifiedEpochMs.map { Date(timeIntervalSince1970: $0.doubleValue / 1000) }
    }

    /// The only version WebDAV honestly offers, which is why `DocumentRow`
    /// carries it: without one the system cannot tell a file that changed
    /// from one that did not.
    var versionIdentifier: Data? { row.etag?.data(using: .utf8) }

    var capabilities: NSFileProviderItemCapabilities {
        row.isDirectory
            ? [.allowsReading, .allowsAddingSubItems, .allowsContentEnumerating, .allowsRenaming, .allowsDeleting]
            : [.allowsReading, .allowsWriting, .allowsRenaming, .allowsDeleting]
    }
}

/// A whole folder at a time: a PROPFIND answers all of it, so paging here
/// would be cutting up an answer already in hand.
final class Enumerator: NSObject, NSFileProviderEnumerator {
    private let documents: IosDocuments
    private let identifier: String

    init(documents: IosDocuments, identifier: String) {
        self.documents = documents
        self.identifier = identifier
    }

    func invalidate() {}

    func enumerateItems(for observer: NSFileProviderEnumerationObserver, startingAt page: NSFileProviderPage) {
        documents.children(id: identifier) { rows, failure in
            if let failure {
                observer.finishEnumeratingWithError(Failure.of(failure))
                return
            }
            observer.didEnumerate((rows ?? []).map(Item.init))
            observer.finishEnumerating(upTo: nil)
        }
    }
}

enum Failure {
    /// The shared half reports a message; this side needs an `Error`.
    static func of(_ message: String) -> NSError {
        NSError(
            domain: "dev.stratus.fileprovider",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: message]
        )
    }

    static let noSuchItem = NSError(
        domain: NSFileProviderErrorDomain,
        code: NSFileProviderError.noSuchItem.rawValue
    )
}

final class FileProviderExtension: NSFileProviderExtension {
    private let documents = IosDocuments()

    // MARK: Identity

    override func item(for identifier: NSFileProviderItemIdentifier) throws -> NSFileProviderItem {
        // This API is synchronous and the answer is a round trip, so there is
        // nowhere to put the wait but here. It is served from the folder's
        // own listing most of the time -- see DocumentTree.one.
        var row: DocumentRow?
        let waiting = DispatchSemaphore(value: 0)
        documents.one(id: identifierOf(identifier)) { found, _ in
            row = found
            waiting.signal()
        }
        guard waiting.wait(timeout: .now() + 30) == .success, let row else { throw Failure.noSuchItem }
        return Item(row)
    }

    /// Where a materialised item lives on this disk.
    ///
    /// A directory per identifier, because a name repeats across folders and
    /// across servers -- every one of them has a `files`.
    override func urlForItem(withPersistentIdentifier identifier: NSFileProviderItemIdentifier) -> URL? {
        guard let item = try? item(for: identifier) else { return nil }
        return NSFileProviderManager.default.documentStorageURL
            .appendingPathComponent(encode(identifier.rawValue), isDirectory: true)
            .appendingPathComponent(item.filename)
    }

    override func persistentIdentifierForItem(at url: URL) -> NSFileProviderItemIdentifier? {
        guard let identifier = decode(url.deletingLastPathComponent().lastPathComponent) else { return nil }
        return NSFileProviderItemIdentifier(identifier)
    }

    // MARK: Contents

    override func providePlaceholder(at url: URL, completionHandler: @escaping (Error?) -> Void) {
        guard let identifier = persistentIdentifierForItem(at: url), let item = try? item(for: identifier) else {
            completionHandler(Failure.noSuchItem)
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
            completionHandler(Failure.noSuchItem)
            return
        }
        try? FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(), withIntermediateDirectories: true
        )
        documents.download(id: identifier.rawValue, toPath: url.path) { failure in
            completionHandler(failure.map(Failure.of))
        }
    }

    /// A file edited in another app, on its way back to the server. Half of
    /// what this feature is for.
    override func itemChanged(at url: URL) {
        guard let identifier = persistentIdentifierForItem(at: url) else { return }
        documents.upload(id: identifier.rawValue, fromPath: url.path, contentType: nil) { _ in }
    }

    override func stopProvidingItem(at url: URL) {
        // The copy is the system's cache and not state of ours: dropping it
        // costs one download and keeps nothing stale.
        try? FileManager.default.removeItem(at: url)
        providePlaceholder(at: url) { _ in }
    }

    // MARK: Enumeration

    override func enumerator(for identifier: NSFileProviderItemIdentifier) throws -> NSFileProviderEnumerator {
        Enumerator(documents: documents, identifier: identifierOf(identifier))
    }

    /// The root of a domain is one server, and a domain is named by the
    /// instance's id -- which is also that server's own root document.
    private func identifierOf(_ identifier: NSFileProviderItemIdentifier) -> String {
        guard identifier == .rootContainer else { return identifier.rawValue }
        return domain?.identifier.rawValue ?? identifier.rawValue
    }

    private func encode(_ identifier: String) -> String {
        Data(identifier.utf8).base64EncodedString(options: [])
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "+", with: "-")
    }

    private func decode(_ component: String) -> String? {
        let base64 = component
            .replacingOccurrences(of: "_", with: "/")
            .replacingOccurrences(of: "-", with: "+")
        guard let data = Data(base64Encoded: base64) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
