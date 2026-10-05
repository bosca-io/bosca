package bosca.core.security

import bosca.core.security.model.Identity

internal object IdentityCodec {
    fun encode(identity: Identity): String =
        AuthHttpClient.JSON.encodeToString(Identity.serializer(), identity)

    fun decode(value: String?): Identity? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            AuthHttpClient.JSON.decodeFromString(Identity.serializer(), value)
        }.getOrNull()
    }
}
