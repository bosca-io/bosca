package bosca.security.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Marker interface representing an encoded (but not necessarily hashed) password.
 *
 * This is the base type in the password encoding hierarchy, allowing different
 * encoding strategies (plaintext-encoded, hashed) to be handled polymorphically.
 */
interface EncodedPassword

/**
 * An [EncodedPassword] that has been cryptographically hashed for secure storage.
 *
 * Implementations hold the hashed representation of a password, suitable for
 * persisting in [CredentialPasswordAttributes] and comparing during authentication.
 */
interface HashedEncodedPassword : EncodedPassword {

    /** The hashed password string, ready for storage or comparison. */
    val hash: String
}

@Serializable
sealed interface CredentialAttributes {

    val identifier: String
    val type: CredentialType

    fun withIdentifier(identifier: String): CredentialAttributes

    fun withPassword(password: HashedEncodedPassword): CredentialPasswordAttributes

}

data class SimplePasswordAttributes(
    override val identifier: String,
    val password: String
) : CredentialAttributes {

    @Transient
    override val type = CredentialType.PASSWORD

    override fun withIdentifier(identifier: String) = copy(identifier = identifier.lowercase().trim())

    override fun withPassword(password: HashedEncodedPassword) = CredentialPasswordAttributes(
        identifier = identifier,
        password = password
    )

}

@Serializable
@SerialName("password")
class CredentialPasswordAttributes
private constructor(
    @Serializable(with = IdentifierSerializer::class)
    override val identifier: String,
    val password: String
) : CredentialAttributes {

    constructor(identifier: String, password: HashedEncodedPassword) : this(identifier.lowercase().trim(), password.hash)

    @Transient
    override val type = CredentialType.PASSWORD

    override fun withIdentifier(identifier: String) = CredentialPasswordAttributes(identifier = identifier.lowercase().trim(), password = password)

    override fun withPassword(password: HashedEncodedPassword) = CredentialPasswordAttributes(identifier = identifier, password = password.hash)

}

private object IdentifierSerializer : KSerializer<String> {

    override val descriptor = PrimitiveSerialDescriptor("identifier", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String = decoder.decodeString().lowercase().trim()

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value.lowercase().trim())
}

@Serializable
@SerialName("oauth2")
data class OAuth2CredentialAttributes(
    override val identifier: String,
    @SerialName("local_id")
    val localId: String? = null,
    val tokens: String? = null,
    @SerialName("type")
    val source: String? = null
) : CredentialAttributes {

    @Transient
    override val type = CredentialType.OAUTH2

    override fun withIdentifier(identifier: String) = throw UnsupportedOperationException("OAuth2 credentials cannot be updated")

    override fun withPassword(password: HashedEncodedPassword): CredentialPasswordAttributes = throw UnsupportedOperationException("OAuth2 credentials cannot be updated")

}

@Serializable
@SerialName("password_scrypt")
data class ScryptCredentialAttributes(
    val salt: String,
    @SerialName("local_id")
    val localId: String? = null,
    override val identifier: String,
    val passwordHash: String
) : CredentialAttributes {

    @Transient
    override val type = CredentialType.PASSWORD_SCRYPT

    override fun withIdentifier(identifier: String) = copy(identifier = identifier.lowercase().trim())

    override fun withPassword(password: HashedEncodedPassword) = CredentialPasswordAttributes(identifier.lowercase().trim(), password)

}

/**
 * Credential attributes for a long-lived API token used for programmatic access.
 *
 * The [identifier] stores the SHA-256 hash of the raw token (prefixed with `sha256:`)
 * so that the token can be verified without storing the plaintext secret. The raw
 * token is generated with 256 bits of cryptographic randomness, making SHA-256
 * sufficient — slow hashes like Argon2 are unnecessary for high-entropy secrets
 * and would add unacceptable latency to every API request.
 *
 * Scopes optionally restrict what the token can do; when `null`, the token inherits
 * the full permissions of its owning principal. Similarly, [allowedGroups] can
 * restrict which of the principal's groups the token acts under.
 */
@Serializable
@SerialName("api_token")
data class ApiTokenCredentialAttributes(
    override val identifier: String,
    /** Human-readable label for identifying the token in the admin UI. */
    val name: String,
    /** Optional longer description of the token's intended purpose. */
    val description: String? = null,
    /** First 12 characters of the raw token, displayed in the UI for identification. */
    @SerialName("token_prefix")
    val tokenPrefix: String,
    /** Permission scopes restricting what this token can do. Null means unrestricted. */
    val scopes: List<String>? = null,
    /** Subset of the principal's group IDs this token may act under. Null means all groups. */
    @SerialName("allowed_groups")
    val allowedGroups: List<String>? = null,
    /** ISO-8601 timestamp when this token expires. Null means it never expires. */
    @SerialName("expires_at")
    val expiresAt: String? = null,
    /** ISO-8601 timestamp of the most recent authentication using this token. */
    @SerialName("last_used_at")
    val lastUsedAt: String? = null,
    /** IP address of the most recent authentication using this token. */
    @SerialName("last_used_ip")
    val lastUsedIp: String? = null,
    /** ISO-8601 timestamp when this token was revoked. Null means it is active. */
    @SerialName("revoked_at")
    val revokedAt: String? = null,
    /** UUID of the principal who created this token (may differ from the owning principal for admin-created tokens). */
    @SerialName("created_by")
    val createdBy: String
) : CredentialAttributes {

    @Transient
    override val type = CredentialType.API_TOKEN

    override fun withIdentifier(identifier: String) = copy(identifier = identifier)

    override fun withPassword(password: HashedEncodedPassword): CredentialPasswordAttributes =
        throw UnsupportedOperationException("API tokens do not use passwords")
}

/**
 * Credential attributes for a WebAuthn passkey (FIDO2) bound to a principal.
 *
 * The [identifier] stores the base64url-encoded credential ID assigned by the authenticator
 * during registration. The [publicKeyCose] holds the COSE-encoded public key used to verify
 * assertion signatures. The [signCount] is incremented by the authenticator on each use and
 * checked server-side to detect cloned credentials.
 */
@Serializable
@SerialName("passkey")
data class PasskeyCredentialAttributes(
    override val identifier: String,
    /** Human-readable label for identifying this passkey in the UI (e.g., "MacBook Pro Touch ID"). */
    val name: String,
    /** Base64url-encoded COSE public key from the authenticator's attestation response. */
    @SerialName("public_key_cose")
    val publicKeyCose: String,
    /** Authenticator sign counter, verified on each authentication to detect credential cloning. */
    @SerialName("sign_count")
    val signCount: Long = 0,
    /** AAGUID identifying the authenticator model (useful for admin visibility). */
    val aaguid: String? = null,
    /** Transport hints reported by the authenticator (e.g., "internal", "usb", "ble", "nfc"). */
    val transports: List<String> = emptyList(),
    /** ISO-8601 timestamp of the most recent authentication using this passkey. */
    @SerialName("last_used_at")
    val lastUsedAt: String? = null,
    /** ISO-8601 timestamp when this passkey was registered. */
    @SerialName("created_at")
    val createdAt: String,
) : CredentialAttributes {

    @Transient
    override val type = CredentialType.PASSKEY

    override fun withIdentifier(identifier: String) = copy(identifier = identifier)

    override fun withPassword(password: HashedEncodedPassword): CredentialPasswordAttributes =
        throw UnsupportedOperationException("Passkeys do not use passwords")
}

@Serializable
data class PrincipalCredential(
    val id: Long = 0,
    @Contextual
    val principal: UUID,
    val type: CredentialType,
    @Contextual
    @ColumnName("attributes")
    val attributesJson: JsonElement,
    /**
     * The original originator: where this credential first came from — the caller-supplied originator of the
     * login/signup request that created it. Stamped once at creation and never updated; null when none.
     */
    val originator: String? = null,
    /**
     * The last originator: where the most recent interactive login using this credential came from. Set to
     * [originator] at creation and updated on each subsequent login that supplies an originator.
     */
    @ColumnName("last_originator")
    val lastOriginator: String? = null
) {

    constructor(
        principal: UUID,
        attributes: CredentialAttributes,
        originator: String? = null
    ) : this(
        principal = principal,
        type = attributes.type,
        attributesJson = attributes.toJson(),
        originator = originator,
        // At creation the original and last originator are the same.
        lastOriginator = originator
    )

    val attributes: CredentialAttributes
        get() = when (type) {
            CredentialType.PASSWORD -> Json.decodeFromJsonElement(CredentialPasswordAttributes.serializer(), attributesJson)
            CredentialType.PASSWORD_SCRYPT -> Json.decodeFromJsonElement(ScryptCredentialAttributes.serializer(), attributesJson)
            CredentialType.OAUTH2 -> Json.decodeFromJsonElement(OAuth2CredentialAttributes.serializer(), attributesJson)
            CredentialType.API_TOKEN -> Json.decodeFromJsonElement(ApiTokenCredentialAttributes.serializer(), attributesJson)
            CredentialType.PASSKEY -> Json.decodeFromJsonElement(PasskeyCredentialAttributes.serializer(), attributesJson)
        }

    fun withIdentifier(identifier: String) = withAttributes(attributes.withIdentifier(identifier))

    fun withPassword(password: HashedEncodedPassword) = withAttributes(attributes.withPassword(password))

    fun withPasswordAndIdentifier(password: HashedEncodedPassword, identifier: String) = withAttributes(attributes.withPassword(password).withIdentifier(identifier))

    private fun withAttributes(attributes: CredentialAttributes) = copy(
        type = attributes.type,
        attributesJson = attributes.toJson()
    )

    companion object {
        private fun CredentialAttributes.toJson() = when (this) {
            is SimplePasswordAttributes -> error("SimplePasswordAttributes should not be serialized")
            is CredentialPasswordAttributes -> Json.encodeToJsonElement(CredentialPasswordAttributes.serializer(), this)
            is ScryptCredentialAttributes -> Json.encodeToJsonElement(ScryptCredentialAttributes.serializer(), this)
            is OAuth2CredentialAttributes -> Json.encodeToJsonElement(OAuth2CredentialAttributes.serializer(), this)
            is ApiTokenCredentialAttributes -> Json.encodeToJsonElement(ApiTokenCredentialAttributes.serializer(), this)
            is PasskeyCredentialAttributes -> Json.encodeToJsonElement(PasskeyCredentialAttributes.serializer(), this)
        }
    }
}

