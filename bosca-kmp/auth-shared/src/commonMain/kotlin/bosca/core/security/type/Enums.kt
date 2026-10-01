package bosca.core.security.type

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Plain Kotlin enums for the auth library's GraphQL types. `auth-shared` hand-rolls
 * its GraphQL over Ktor (no Apollo), so these are the public, serializable enum
 * types its models expose. Each constant's name matches the GraphQL schema enum
 * value verbatim, so the kotlinx-serialization wire form is identical to what
 * the server sends/expects. [rawValue] preserves the `.rawValue` accessor the
 * Apollo enums offered (used when building OAuth redirect URLs).
 */

@Serializable
enum class ThirdPartyType {
    @SerialName("APPLE") APPLE,
    @SerialName("FACEBOOK") FACEBOOK,
    @SerialName("GOOGLE") GOOGLE;

    val rawValue: String get() = name
}

@Serializable
enum class ProfileType {
    @SerialName("CHILD") CHILD,
    @SerialName("GENERIC") GENERIC,
    @SerialName("ORGANIZATION") ORGANIZATION;

    val rawValue: String get() = name
}

@Serializable
enum class ProfileVisibility {
    @SerialName("FRIENDS") FRIENDS,
    @SerialName("FRIENDS_OF_FRIENDS") FRIENDS_OF_FRIENDS,
    @SerialName("PUBLIC") PUBLIC,
    @SerialName("SYSTEM") SYSTEM,
    @SerialName("USER") USER;

    val rawValue: String get() = name
}

@Serializable
enum class CredentialType {
    @SerialName("API_TOKEN") API_TOKEN,
    @SerialName("OAUTH2") OAUTH2,
    @SerialName("PASSKEY") PASSKEY,
    @SerialName("PASSWORD") PASSWORD,
    @SerialName("PASSWORD_SCRYPT") PASSWORD_SCRYPT;

    val rawValue: String get() = name
}

@Serializable
enum class GroupType {
    @SerialName("PRINCIPAL") PRINCIPAL,
    @SerialName("SYSTEM") SYSTEM;

    val rawValue: String get() = name
}
