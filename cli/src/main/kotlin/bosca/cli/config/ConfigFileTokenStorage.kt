package bosca.cli.config

import bosca.core.security.TokenStorage
import bosca.core.security.model.TokenMetadata

/**
 * [TokenStorage] backed by the CLI's `~/.config/bosca/config.json`, so
 * `BoscaAuth` persists its session in the same file `bosca login` has always
 * used. Credentials are scoped to [profileName], allowing independent accounts
 * on the same endpoint.
 *
 * Token expiry [TokenMetadata] is not persisted (the schema has no field for it);
 * `TokenManager` transparently falls back to parsing the JWT's `exp`/`iat`, so a
 * cold start still schedules refresh correctly. A non-JWT API token simply has no
 * metadata and is returned as-is.
 *
 * @param endpoint the GraphQL endpoint written with the profile on every save.
 * @param profileName the profile whose credentials are read and updated.
 * @param allowEndpointChange whether a successful new login may replace an
 * existing profile's endpoint. Restored sessions keep this false so a stale,
 * long-lived client cannot read or overwrite credentials after another process
 * retargets the profile.
 */
class ConfigFileTokenStorage(
    private val endpoint: String,
    private val profileName: String = CliConfigStore.selectedProfileName(),
    private val allowEndpointChange: Boolean = false,
) : TokenStorage {

    private fun current() = CliConfigStore.load()

    private fun currentProfile(): ProfileConfig? =
        current().profiles[profileName]
            ?.takeIf { isSameEndpoint(it.endpoint, endpoint) }

    private fun persist(transform: (AuthConfig) -> AuthConfig) {
        CliConfigStore.update { config ->
            val existing = config.profiles[profileName]
            val endpointChanged = existing != null && !isSameEndpoint(existing.endpoint, endpoint)
            if (endpointChanged && !allowEndpointChange) {
                throw IllegalStateException(
                    "Profile '$profileName' now targets '${existing.endpoint}'; " +
                        "refusing to store credentials received from '$endpoint'.",
                )
            }
            val profile = existing ?: ProfileConfig(endpoint = endpoint)
            val auth = if (endpointChanged) AuthConfig() else profile.auth ?: AuthConfig()
            config.withProfile(
                profileName,
                profile.copy(endpoint = endpoint, auth = transform(auth)),
            )
        }
    }

    override suspend fun getToken(): String? = currentProfile()?.auth?.token

    override suspend fun saveToken(token: String) = persist { it.copy(token = token) }

    override suspend fun getRefreshToken(): String? = currentProfile()?.auth?.refreshToken

    override suspend fun saveRefreshToken(refreshToken: String) = persist { it.copy(refreshToken = refreshToken) }

    // config.json carries no expiry metadata; TokenManager falls back to JWT parsing.
    override suspend fun getTokenMetadata(): TokenMetadata? = null

    override suspend fun saveTokenMetadata(metadata: TokenMetadata) = Unit

    override suspend fun clear() {
        CliConfigStore.update { config ->
            val profile = config.profiles[profileName] ?: return@update config
            if (!isSameEndpoint(profile.endpoint, endpoint)) return@update config
            config.withProfile(profileName, profile.copy(auth = null))
        }
    }
}
