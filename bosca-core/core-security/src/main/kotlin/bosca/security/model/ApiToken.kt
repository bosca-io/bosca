package bosca.security.model

/**
 * Represents an API token credential projected for external consumption (GraphQL, admin UI).
 *
 * This wraps a [PrincipalCredential] of type [CredentialType.API_TOKEN] and provides
 * direct access to the token's [ApiTokenCredentialAttributes]. It exists as a separate
 * type so that the GraphQL type controller can bind to `ApiToken` without conflicting
 * with the existing `PrincipalCredential` GraphQL type.
 *
 * @param credential the backing credential row — must have type [CredentialType.API_TOKEN]
 */
data class ApiToken(
    val credential: PrincipalCredential,
) {
    init {
        require(credential.type == CredentialType.API_TOKEN) {
            "ApiToken requires an API_TOKEN credential, got ${credential.type}"
        }
    }

    /** The deserialized API token attributes from the credential's JSONB payload. */
    val attrs: ApiTokenCredentialAttributes get() = credential.attributes as ApiTokenCredentialAttributes
}

/**
 * Converts this [PrincipalCredential] into an [ApiToken].
 *
 * @throws IllegalArgumentException if this credential is not of type [CredentialType.API_TOKEN]
 */
fun PrincipalCredential.toApiToken() = ApiToken(this)

/**
 * The result of creating a new API token, pairing the persisted [ApiToken] with the
 * raw token string that is shown to the user exactly once and never stored.
 *
 * @param apiToken the persisted token metadata
 * @param rawToken the plaintext `bsk_...` token — must be displayed to the user immediately
 */
data class ApiTokenCreationResponse(
    val apiToken: ApiToken,
    val rawToken: String,
)
