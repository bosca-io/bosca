package bosca.cli.api

import bosca.cli.config.ConfigFileTokenStorage
import bosca.cli.config.CliConfigStore
import bosca.cli.config.isSameEndpoint
import bosca.cli.git.GitCredentialStore
import bosca.core.security.AuthGraphqlImpl
import bosca.core.security.AuthHttpClient
import bosca.core.security.BoscaAuth
import bosca.core.security.BoscaAuthImpl
import bosca.core.security.IdentityStorage
import bosca.core.security.model.BoscaAuthConfig
import bosca.core.security.model.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Builds and wires the shared [BoscaAuth] (io.bosca:auth-shared) for the CLI,
 * replacing the old bespoke `Security` login/refresh stack. Sessions persist to
 * `~/.config/bosca/config.json` via [ConfigFileTokenStorage].
 *
 * Long-running commands such as CI agents and MCP servers keep the returned
 * [BoscaAuth] alive through [NetworkClient.tokenProvider]. Their sessions rotate
 * periodically instead of waiting for access-token expiry, which may occur after
 * the server-side refresh token has expired.
 */
object CliAuth {

    /**
     * Creates a [BoscaAuth] for [endpoint] (a GraphQL URL), persisting to the
     * selected or explicitly supplied [profileName].
     */
    fun create(
        endpoint: String,
        profileName: String = CliConfigStore.selectedProfileName(),
    ): BoscaAuth = create(endpoint, profileName, allowEndpointChange = false)

    /**
     * Creates authentication storage for a deliberate new login. Unlike a
     * restored session, a successful login may retarget an existing profile.
     */
    fun createForLogin(
        endpoint: String,
        profileName: String = CliConfigStore.selectedProfileName(),
    ): BoscaAuth = create(endpoint, profileName, allowEndpointChange = true)

    private fun create(
        endpoint: String,
        profileName: String,
        allowEndpointChange: Boolean,
    ): BoscaAuth {
        val config = configuration(endpoint)
        val graphql = AuthGraphqlImpl(AuthHttpClient(config))
        val tokenStorage = ConfigFileTokenStorage(endpoint, profileName, allowEndpointChange)
        return BoscaAuthImpl(
            storage = tokenStorage,
            identityStorage = CliIdentityStorage(),
            graphql = graphql,
            config = config,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    internal fun configuration(endpoint: String): BoscaAuthConfig {
        val apiUrl = endpoint.removeSuffix("/graphql").removeSuffix("/")
        return BoscaAuthConfig(
            apiUrl = apiUrl,
            graphqlUrl = endpoint,
            autoRefresh = true,
        )
    }

    /**
     * Resolves credentials and wires [network] with a per-request bearer token.
     * Priority mirrors prior CLI behavior: explicit [token], then
     * [username]/[password] sign-in, then the stored `bosca login` credentials.
     *
     * Apart from the transient `--token` case, everything flows through
     * [BoscaAuth], whose `TokenManager` understands both credential kinds: a
     * **JWT session** (lazily refreshed per request) and a long-lived **API
     * token** (returned as-is, never refreshed). So `getToken()` is correct for
     * either a stored API key or a refreshable session.
     *
     * @return true if the network was authenticated, false if no credentials exist.
     */
    suspend fun authenticate(
        network: NetworkClient,
        endpoint: String,
        token: String?,
        username: String?,
        password: String?,
        profileName: String = CliConfigStore.selectedProfileName(),
    ): Boolean {
        // Transient explicit token (--token / BOSCA_TOKEN): used as-is and never
        // persisted to config — so it doesn't go through BoscaAuth/storage.
        if (token != null) {
            network.tokenProvider = { token }
            return true
        }

        val explicitLogin = username != null && password != null
        if (!explicitLogin) {
            val storedProfile = CliConfigStore.load().profiles[profileName] ?: return false
            if (!isSameEndpoint(storedProfile.endpoint, endpoint)) return false
        }

        val auth = if (explicitLogin) {
            createForLogin(endpoint, profileName)
        } else {
            create(endpoint, profileName)
        }
        if (explicitLogin) {
            // Refreshable JWT session; signInWithPassword persists it to config.json.
            auth.signInWithPassword(username, password)
            GitCredentialStore.removeProfile(profileName)
        } else {
            // Stored credentials: BoscaAuth restores either a JWT session or an
            // API token. A null token means no usable session exists.
            auth.initialize(fetchProfile = false)
            if (auth.getToken() == null) return false
        }
        network.tokenProvider = { auth.getToken() }
        // On a server 401 for a still-locally-valid token, force a refresh and
        // retry once. Returns null (→ surface the 401) for an opaque API token,
        // which has no refresh token.
        network.onUnauthorized = { auth.refresh()?.token?.token }
        return true
    }
}

/** The headless CLI deliberately keeps identity state only for the lifetime of one auth client. */
private class CliIdentityStorage : IdentityStorage {
    private var identity: Identity? = null

    override suspend fun getIdentity(): Identity? = identity

    override suspend fun setIdentity(identity: Identity?) {
        this.identity = identity
    }
}
