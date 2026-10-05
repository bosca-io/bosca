package bosca.cli.swarm

import bosca.cli.api.ApiTokens
import bosca.cli.api.CliAuth
import bosca.cli.api.NetworkClient
import bosca.core.security.AuthGraphqlImpl
import bosca.core.security.AuthHttpClient
import bosca.core.security.BoscaAuthImpl
import bosca.core.security.IdentityStorage
import bosca.core.security.TokenStorage
import bosca.core.security.model.Identity
import bosca.core.security.model.TokenMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Keeps the bootstrap service-account login in memory, separate from the operator's CLI profiles. */
private class MemoryCredentials : TokenStorage, IdentityStorage {
    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var metadata: TokenMetadata? = null
    private var identity: Identity? = null

    override suspend fun getToken() = accessToken
    override suspend fun saveToken(token: String) { accessToken = token }
    override suspend fun getRefreshToken() = refreshToken
    override suspend fun saveRefreshToken(refreshToken: String) { this.refreshToken = refreshToken }
    override suspend fun getTokenMetadata() = metadata
    override suspend fun saveTokenMetadata(metadata: TokenMetadata) { this.metadata = metadata }
    override suspend fun getIdentity() = identity
    override suspend fun setIdentity(identity: Identity?) { this.identity = identity }
    override suspend fun clear() { accessToken = null; refreshToken = null; metadata = null; identity = null }
}

private class SiteTokenIssuer private constructor(
    private val scope: CoroutineScope,
    private val tokens: ApiTokens,
) {
    suspend fun create(name: String, scopes: List<String>?): String = tokens.create(
        name = name,
        description = "Bosca Swarm service token",
        scopes = scopes,
    ).rawToken

    fun close() = scope.cancel()

    companion object {
        suspend fun connect(site: SwarmSite): SiteTokenIssuer {
            val endpoint = "https://${site.hosts.api}/graphql"
            val config = CliAuth.configuration(endpoint)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val storage = MemoryCredentials()
                val auth = BoscaAuthImpl(
                    storage = storage,
                    identityStorage = storage,
                    graphql = AuthGraphqlImpl(AuthHttpClient(config)),
                    config = config,
                    scope = scope,
                )
                auth.signInWithPassword("sa", site.secrets.initialSa)
                checkNotNull(auth.getToken()) { "Service-account login returned no token for ${site.id}" }
                val network = NetworkClient(endpoint)
                network.tokenProvider = { auth.getToken() }
                network.onUnauthorized = { auth.refresh()?.token?.token }
                return SiteTokenIssuer(scope, ApiTokens(network))
            } catch (e: Exception) {
                scope.cancel()
                throw e
            }
        }
    }
}

/** Saves each raw token immediately, so a failed later site can resume without minting duplicates. */
internal suspend fun provisionSwarmTokens(
    config: SwarmConfig,
    create: suspend (SwarmSite, String, List<String>?) -> String,
    persist: (SwarmConfig) -> Unit,
): SwarmConfig {
    var current = config
    for (site in config.sites) {
        suspend fun addIfMissing(value: String, name: String, scopes: List<String>?, update: (SwarmSiteMl, String) -> SwarmSiteMl) {
            if (value.isNotBlank()) return
            val raw = create(site, name, scopes)
            require(raw.isNotBlank()) { "Token creation returned an empty value for ${site.id}/$name" }
            current = current.copy(sites = current.sites.map { existing ->
                if (existing.id == site.id) existing.copy(ml = update(existing.ml, raw)) else existing
            })
            persist(current)
        }
        addIfMissing(site.ml.boscaToken, "swarm-recommendation-bosca", null) { ml, token -> ml.copy(boscaToken = token) }
        addIfMissing(site.ml.artifactsPushToken, "swarm-recommendation-artifacts-push",
            listOf("artifacts:ml:model/*:*:push")) { ml, token -> ml.copy(artifactsPushToken = token) }
        addIfMissing(site.ml.artifactsPullToken, "swarm-recommendation-artifacts-pull",
            listOf("artifacts:ml:model/*:*:pull")) { ml, token -> ml.copy(artifactsPullToken = token) }
        val rootAuth = site.rootRegistryAuth
        if (rootAuth != null && rootAuth.username == "api_token" && rootAuth.password.isBlank()) {
            val raw = create(site, "swarm-root-image-pull", listOf(rootImagePullScope(site)))
            require(raw.isNotBlank()) { "Token creation returned an empty value for ${site.id}/swarm-root-image-pull" }
            current = current.copy(sites = current.sites.map { existing ->
                if (existing.id == site.id) existing.copy(rootRegistryAuth = rootAuth.copy(password = raw)) else existing
            })
            persist(current)
        }
    }
    return current
}

/** Scope for the repository across tags and digests because Docker pulls both manifests and blobs. */
internal fun rootImagePullScope(site: SwarmSite): String {
    val auth = requireNotNull(site.rootRegistryAuth)
    require(site.rootImage.startsWith("${auth.server}/")) { "Root image registry does not match rootRegistryAuth for ${site.id}" }
    val repository = site.rootImage.removePrefix("${auth.server}/").substringBefore('@').substringBeforeLast(':')
    require(repository.split('/').size == 2 && repository.split('/').all(String::isNotBlank)) {
        "Root image for ${site.id} must use namespace/repository coordinates"
    }
    return "artifacts:docker:$repository:*:pull"
}

/** One-time stage after the initial deployment; deploy itself never mints application tokens. */
internal suspend fun setupSwarmTokens(config: SwarmConfig, persist: (SwarmConfig) -> Unit): SwarmConfig {
    val issuers = mutableMapOf<String, SiteTokenIssuer>()
    try {
        return provisionSwarmTokens(config, create = { site, name, scopes ->
            val issuer = issuers[site.id] ?: SiteTokenIssuer.connect(site).also { issuers[site.id] = it }
            issuer.create(name, scopes)
        }, persist = persist)
    } finally {
        issuers.values.forEach(SiteTokenIssuer::close)
    }
}
