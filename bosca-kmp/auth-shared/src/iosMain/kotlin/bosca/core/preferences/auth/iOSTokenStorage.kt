
package bosca.core.preferences.auth

import bosca.core.security.IdentityCodec
import bosca.core.security.IdentityStorage
import bosca.core.security.TokenMetadataCodec
import bosca.core.security.TokenStorage
import bosca.core.security.model.Identity
import bosca.core.security.model.TokenMetadata
import platform.Foundation.*
import platform.Security.*
import platform.CoreFoundation.*
import kotlinx.cinterop.*
import platform.darwin.OSStatus

@Suppress("ClassName")
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class iOSTokenStorage : TokenStorage, IdentityStorage {

    private val defaultProperties: Map<CFStringRef?, CFTypeRef?>
    private val legacyProperties: Map<CFStringRef?, CFTypeRef?>
    private val usesSharedAccessGroup: Boolean

    init {
        val cfService = CFBridgingRetain("Bosca")
        legacyProperties = mapOf(kSecClass to kSecClassGenericPassword, kSecAttrService to cfService)
        val accessGroup = NSBundle.mainBundle.objectForInfoDictionaryKey(KEYCHAIN_ACCESS_GROUP) as? String
        usesSharedAccessGroup = !accessGroup.isNullOrBlank()
        defaultProperties = if (accessGroup.isNullOrBlank()) {
            legacyProperties
        } else {
            legacyProperties + (kSecAttrAccessGroup to CFBridgingRetain(accessGroup))
        }
    }

    override suspend fun getToken(): String? = get("token")

    override suspend fun saveToken(token: String) {
        save("token", token)
    }

    override suspend fun getRefreshToken(): String? = get("refresh_token")

    override suspend fun saveRefreshToken(refreshToken: String) {
        save("refresh_token", refreshToken)
    }

    override suspend fun getTokenMetadata(): TokenMetadata? =
        TokenMetadataCodec.decode(get("token_meta"))

    override suspend fun saveTokenMetadata(metadata: TokenMetadata) {
        save("token_meta", TokenMetadataCodec.encode(metadata))
    }

    override suspend fun getIdentity(): Identity? =
        IdentityCodec.decode(get("identity"))

    override suspend fun setIdentity(identity: Identity?) {
        if (identity == null) {
            delete("identity")
        } else {
            save("identity", IdentityCodec.encode(identity))
        }
    }

    override suspend fun clear() {
        delete("token")
        delete("refresh_token")
        delete("token_meta")
        if (usesSharedAccessGroup) {
            delete("token", legacyProperties)
            delete("refresh_token", legacyProperties)
            delete("token_meta", legacyProperties)
        }
    }

    private fun save(key: String, value: String) {
        @Suppress("CAST_NEVER_SUCCEEDS")
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding)
        if(!add(key, data)) {
            update(key, data)
        }
    }

    private fun get(key: String): String? {
        val value = get(key, defaultProperties)
        if (value != null || !usesSharedAccessGroup) return value
        val legacy = get(key, legacyProperties) ?: return null
        save(key, legacy)
        delete(key, legacyProperties)
        return legacy
    }

    private fun get(key: String, properties: Map<CFStringRef?, CFTypeRef?>): String? = retain(key) {
        val value = alloc<CFTypeRefVar>()
        val status = run(properties,
            kSecAttrAccount to it,
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne
        ) {
            SecItemCopyMatching(it, value.ptr)
        }
        status.validate(errSecItemNotFound)
        if (status == errSecItemNotFound) {
            return@retain null
        }
        val data = CFBridgingRelease(value.value) as NSData
        @Suppress("CAST_NEVER_SUCCEEDS")
        return NSString.create(data, NSUTF8StringEncoding) as String
    }

    private fun add(key: String, value: NSData?): Boolean = retain(key, value) { k, v ->
        val attributes = mutableListOf(
            kSecAttrAccount to k,
            kSecValueData to v,
        )
        sharedTokenAccessibility(key)?.let(attributes::add)
        val status = run(*attributes.toTypedArray()) {
            SecItemAdd(it, null)
        }
        status.validate(errSecDuplicateItem)
        status != errSecDuplicateItem
    }

    private fun update(key: String, value: NSData?): Unit = retain(key, value) { k, v ->
        val status = run(
            kSecAttrAccount to k,
            kSecReturnData to kCFBooleanFalse
        ) {
            val attributes = mutableListOf(kSecValueData to v)
            sharedTokenAccessibility(key)?.let(attributes::add)
            val attributesDictionary = cfDictionaryOf(*attributes.toTypedArray())
            val output = SecItemUpdate(it, attributesDictionary)
            CFBridgingRelease(attributesDictionary)
            output
        }
        status.validate(0)
    }

    /** The notification extension must read the access token while the device is locked. */
    private fun sharedTokenAccessibility(key: String): Pair<CFStringRef?, CFTypeRef?>? =
        if (usesSharedAccessGroup && key == "token") {
            kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        } else {
            null
        }

    private fun delete(
        key: String,
        properties: Map<CFStringRef?, CFTypeRef?> = defaultProperties,
    ): Unit = retain(key) {
        val status = run(properties, kSecAttrAccount to it) {
            SecItemDelete(it)
        }
        status.validate(errSecItemNotFound)
    }

    private inline fun MemScope.run(
        vararg input: Pair<CFStringRef?, CFTypeRef?>,
        operation: (query: CFDictionaryRef?) -> OSStatus,
    ): OSStatus = run(defaultProperties, *input, operation = operation)

    private inline fun MemScope.run(
        properties: Map<CFStringRef?, CFTypeRef?>,
        vararg input: Pair<CFStringRef?, CFTypeRef?>,
        operation: (query: CFDictionaryRef?) -> OSStatus,
    ): OSStatus {
        val query = cfDictionaryOf(properties + mapOf(*input))
        val output = operation(query)
        CFBridgingRelease(query)
        return output
    }

    private fun MemScope.cfDictionaryOf(vararg items: Pair<CFStringRef?, CFTypeRef?>) = cfDictionaryOf(mapOf(*items))

    private fun MemScope.cfDictionaryOf(map: Map<CFStringRef?, CFTypeRef?>): CFDictionaryRef? {
        val size = map.size
        val keys = allocArrayOf(*map.keys.toTypedArray())
        val values = allocArrayOf(*map.values.toTypedArray())
        return CFDictionaryCreate(
            kCFAllocatorDefault,
            keys.reinterpret(),
            values.reinterpret(),
            size.convert(),
            null,
            null
        )
    }

    private fun OSStatus.validate(expectedError: OSStatus) {
        if (this != 0 && this != expectedError) {
            val cfMessage = SecCopyErrorMessageString(this, null)
            val nsMessage = CFBridgingRelease(cfMessage) as? NSString
            @Suppress("CAST_NEVER_SUCCEEDS")
            val message = nsMessage as String
            error("Keychain error $this: $message")
        }
    }

    private inline fun <T> retain(value: Any?, block: MemScope.(CFTypeRef?) -> T): T = memScoped {
        val v = CFBridgingRetain(value)
        return try {
            block(v)
        } finally {
            CFBridgingRelease(v)
        }
    }

    private inline fun <T> retain(value1: Any?, value2: Any?, block: MemScope.(CFTypeRef?, CFTypeRef?) -> T): T =
        memScoped {
            val v1 = CFBridgingRetain(value1)
            val v2 = CFBridgingRetain(value2)
            return try {
                block(v1, v2)
            } finally {
                CFBridgingRelease(v2)
                CFBridgingRelease(v1)
            }
        }

    private companion object {
        const val KEYCHAIN_ACCESS_GROUP = "BoscaKeychainAccessGroup"
    }
}
