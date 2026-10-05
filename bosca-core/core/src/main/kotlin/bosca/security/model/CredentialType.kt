package bosca.security.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

@DbMapper(CredentialTypeMapper::class)
@Serializable
enum class CredentialType {
    PASSWORD,
    PASSWORD_SCRYPT,
    OAUTH2,
    API_TOKEN,
    PASSKEY
}

object CredentialTypeMapper : EnumMapper<CredentialType>({ CredentialType.valueOf(it.uppercase()) })