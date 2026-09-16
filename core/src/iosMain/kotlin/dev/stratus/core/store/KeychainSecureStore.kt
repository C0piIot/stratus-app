package dev.stratus.core.store

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.darwin.OSStatus

/**
 * The Keychain, one generic-password item per key.
 *
 * **Accessibility is `AfterFirstUnlock` rather than `WhenUnlocked`, and that is
 * this app's whole purpose talking.** Uploads happen while the phone is locked
 * on a charger overnight; credentials readable only while unlocked would be
 * unreadable at exactly the moment the backup runs, and the symptom -- "it never
 * uploads at night" -- gives no hint of the cause.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KeychainSecureStore(private val service: String = "dev.stratus.app") : SecureStore {

    override suspend fun read(key: String): String? {
        val query = NSMutableDictionary().apply {
            identify(key)
            setObject(true, forKeyedSubscript = kSecReturnData as NSString)
            setObject(kSecMatchLimitOne, forKeyedSubscript = kSecMatchLimit as NSString)
        }
        val found = SecItemCopyMatching(query, null)
        if (found != errSecSuccess) return null

        // The value comes back only when a place is given for it, which the
        // Kotlin binding expresses as a second call carrying the same query.
        val holder = arrayOfNulls<Any?>(1)
        val status = copyMatchingInto(query, holder)
        if (status != errSecSuccess) return null
        val data = holder[0] as? NSData ?: return null
        return NSString.create(data, NSUTF8StringEncoding) as String?
    }

    override suspend fun write(key: String, value: String) {
        val bytes = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val item = NSMutableDictionary().apply {
            identify(key)
            setObject(kSecAttrAccessibleAfterFirstUnlock, forKeyedSubscript = kSecAttrAccessible as NSString)
            setObject(bytes, forKeyedSubscript = kSecValueData as NSString)
        }
        val added = SecItemAdd(item, null)
        if (added == errSecDuplicateItem) {
            // Adding over an existing item fails rather than replacing it, which
            // is the first thing this gets wrong if nobody says so.
            val query = NSMutableDictionary().apply { identify(key) }
            val update = NSMutableDictionary().apply {
                setObject(bytes, forKeyedSubscript = kSecValueData as NSString)
            }
            SecItemUpdate(query, update)
        }
    }

    override suspend fun delete(key: String) {
        SecItemDelete(NSMutableDictionary().apply { identify(key) })
    }

    private fun NSMutableDictionary.identify(key: String) {
        setObject(kSecClassGenericPassword, forKeyedSubscript = kSecClass as NSString)
        setObject(service, forKeyedSubscript = kSecAttrService as NSString)
        setObject(key, forKeyedSubscript = kSecAttrAccount as NSString)
    }

    private fun copyMatchingInto(query: NSMutableDictionary, holder: Array<Any?>): OSStatus =
        SecItemCopyMatching(query, holder.refTo(0))
}
