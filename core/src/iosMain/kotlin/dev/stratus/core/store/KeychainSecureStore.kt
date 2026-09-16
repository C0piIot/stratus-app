package dev.stratus.core.store

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
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

/**
 * The Keychain, one generic-password item per key.
 *
 * Built with CoreFoundation rather than bridged Foundation dictionaries because
 * that is what the `SecItem` functions actually take; bridging costs a cast that
 * has to be right for reasons nothing checks.
 *
 * **Accessibility is `AfterFirstUnlock` rather than `WhenUnlocked`, and that is
 * this app's whole purpose talking.** Uploads happen while the phone is locked on
 * a charger overnight; credentials readable only while unlocked would be
 * unreadable at exactly the moment the backup runs, and the symptom -- "it never
 * uploads at night" -- gives no hint of the cause.
 */
class KeychainFailure(val status: Int) : Exception("the keychain refused the item: OSStatus $status")

@OptIn(ExperimentalForeignApi::class)
class KeychainSecureStore(private val service: String = "dev.stratus.app") : SecureStore {

    override suspend fun read(key: String): String? = memScoped {
        val query = identifying(key)
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)

        val found = alloc<CFTypeRefVar>()
        if (SecItemCopyMatching(query, found.ptr) != errSecSuccess) return@memScoped null

        val data: CFDataRef = found.value?.reinterpret() ?: return@memScoped null
        try {
            val length = CFDataGetLength(data).convert<Int>()
            val bytes: CPointer<ByteVar> = CFDataGetBytePtr(data)?.reinterpret() ?: return@memScoped null
            bytes.readBytes(length).decodeToString()
        } finally {
            CFRelease(data)
        }
    }

    override suspend fun write(key: String, value: String) = memScoped {
        val bytes = value.encodeToByteArray()
        val data = bytes.usePinned { pinned ->
            CFDataCreate(
                null,
                if (bytes.isEmpty()) null else pinned.addressOf(0).reinterpret(),
                bytes.size.convert(),
            )
        } ?: return@memScoped
        defer { CFRelease(data) }

        val item = identifying(key)
        CFDictionarySetValue(item, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
        CFDictionarySetValue(item, kSecValueData, data)

        val added = SecItemAdd(item, null)
        val status = if (added == errSecDuplicateItem) {
            // Adding over an item that exists fails rather than replacing it,
            // which is the first thing this gets wrong if nobody says so.
            val changes = CFDictionaryCreateMutable(
                null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
            )!!
            defer { CFRelease(changes) }
            CFDictionarySetValue(changes, kSecValueData, data)
            SecItemUpdate(identifying(key), changes)
        } else {
            added
        }

        // Swallowing this would mean signing in, storing nothing, and being
        // signed out again on the next launch with no explanation anywhere.
        if (status != errSecSuccess) throw KeychainFailure(status)
        Unit
    }

    override suspend fun delete(key: String): Unit = memScoped {
        SecItemDelete(identifying(key))
        Unit
    }

    /** The three attributes that name one item, on a dictionary freed with the scope. */
    private fun MemScope.identifying(key: String): CFMutableDictionaryRef {
        val dictionary = CFDictionaryCreateMutable(
            null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
        )!!
        defer { CFRelease(dictionary) }
        CFDictionarySetValue(dictionary, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(dictionary, kSecAttrService, cfString(service))
        CFDictionarySetValue(dictionary, kSecAttrAccount, cfString(key))
        return dictionary
    }

    private fun MemScope.cfString(value: String): CFStringRef {
        val ref = CFStringCreateWithCString(null, value.cstr.ptr, kCFStringEncodingUTF8)!!
        defer { CFRelease(ref) }
        return ref
    }
}
