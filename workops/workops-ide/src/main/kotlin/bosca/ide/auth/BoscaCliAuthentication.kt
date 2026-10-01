package bosca.ide.auth

import bosca.core.security.AuthGraphqlImpl
import bosca.core.security.AuthHttpClient
import bosca.core.security.BoscaAuth
import bosca.core.security.BoscaAuthImpl
import bosca.core.security.IdentityStorage
import bosca.core.security.model.BoscaAuthConfig
import bosca.core.security.model.Identity
import bosca.ide.server.BoscaServerProfile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BoscaCliAuthenticationException(message: String) : RuntimeException(message)

/**
 * Supplies bearer tokens from named Bosca CLI profiles. The shared BoscaAuth
 * implementation classifies API tokens, refreshes expired JWT sessions, and
 * writes rotated credentials back to the CLI configuration.
 */
@Service(Service.Level.APP)
class BoscaCliAuthentication {
    private val store = BoscaCliConfigStore()
    private val profileLocks = ConcurrentHashMap<String, Mutex>()

    internal fun cliProfiles(): List<BoscaCliProfile> = store.profiles()

    internal fun resolvedCliProfileName(profile: BoscaServerProfile): String? =
        profile.cliProfileName.takeIf { it.isNotBlank() }
            ?: store.resolveProfileName(profile.graphqlEndpoint)

    suspend fun token(profile: BoscaServerProfile): String = authenticate(profile, forceRefresh = false)

    suspend fun refresh(profile: BoscaServerProfile): String = authenticate(profile, forceRefresh = true)

    private suspend fun authenticate(
        serverProfile: BoscaServerProfile,
        forceRefresh: Boolean,
    ): String {
        val cliProfileName = resolvedCliProfileName(serverProfile)
            ?: throw BoscaCliAuthenticationException(
                "No unambiguous Bosca CLI profile targets '${serverProfile.graphqlEndpoint}'. " +
                    "Run 'bosca login --profile <name>' and select that profile in Settings | Bosca Servers.",
            )
        val lockKey = "$cliProfileName\u0000${serverProfile.graphqlEndpoint.trimEnd('/')}"
        return profileLocks.computeIfAbsent(lockKey) { Mutex() }.withLock {
            authenticateNow(serverProfile, cliProfileName, forceRefresh)
        }
    }

    private suspend fun authenticateNow(
        serverProfile: BoscaServerProfile,
        cliProfileName: String,
        forceRefresh: Boolean,
    ): String {
        val cliProfile = store.profile(cliProfileName)
            ?: throw BoscaCliAuthenticationException(
                "Bosca CLI profile '$cliProfileName' does not exist. Run 'bosca login --profile $cliProfileName'.",
            )
        if (!sameEndpoint(cliProfile.endpoint, serverProfile.graphqlEndpoint)) {
            throw BoscaCliAuthenticationException(
                "Bosca CLI profile '$cliProfileName' targets '${cliProfile.endpoint}', not " +
                    "'${serverProfile.graphqlEndpoint}'. Update the server binding in Settings | Bosca Servers.",
            )
        }

        val auth = createAuth(serverProfile.graphqlEndpoint, cliProfileName)
        return try {
            auth.initialize(fetchProfile = false)
            val token = if (forceRefresh) auth.refresh()?.token?.token else auth.getToken()
            token ?: throw BoscaCliAuthenticationException(
                "Bosca CLI profile '$cliProfileName' is not authenticated. " +
                    "Run 'bosca login --profile $cliProfileName'.",
            )
        } finally {
            auth.destroy()
        }
    }

    private fun createAuth(endpoint: String, profileName: String): BoscaAuth {
        val config = BoscaAuthConfig(
            apiUrl = endpoint.removeSuffix("/graphql").removeSuffix("/"),
            graphqlUrl = endpoint,
            autoRefresh = false,
        )
        val tokenStorage = BoscaCliTokenStorage(store, profileName, endpoint)
        return BoscaAuthImpl(
            storage = tokenStorage,
            identityStorage = IdeIdentityStorage(),
            graphql = AuthGraphqlImpl(AuthHttpClient(config)),
            config = config,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }

    companion object {
        fun getInstance(): BoscaCliAuthentication =
            ApplicationManager.getApplication().getService(BoscaCliAuthentication::class.java)
    }
}

/** IDE authentication is headless, so identity lives only for the lifetime of one auth client. */
private class IdeIdentityStorage : IdentityStorage {
    private var identity: Identity? = null

    override suspend fun getIdentity(): Identity? = identity

    override suspend fun setIdentity(identity: Identity?) {
        this.identity = identity
    }
}
