package bosca.cli.git

import bosca.cli.config.CliConfigStore
import bosca.cli.config.DEFAULT_PROFILE_NAME
import bosca.cli.config.withPrivateFileLock
import bosca.cli.config.writePrivateFileAtomically
import com.github.ajalt.clikt.core.CliktError
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Per-profile, per-host cache of git API tokens, stored alongside the main CLI
 * config at `~/.config/bosca/git-credentials.json` with `0600`
 * permissions.
 *
 * This is the persistence layer behind the git credential helper: once
 * a token has been minted for a host it is reused for every subsequent
 * clone/fetch/push, so the user only ever provisions a credential once.
 * When the server rejects a token git invokes the helper's `erase`
 * action, which clears the entry here and forces a fresh mint on the
 * next operation — the same self-healing behaviour a build agent relies
 * on.
 */
object GitCredentialStore {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class Store(
        val profiles: Map<String, Map<String, String>> = emptyMap(),
        val routes: Map<String, Map<String, String>> = emptyMap(),
    )

    @Serializable
    private data class StoredStore(
        val profiles: Map<String, Map<String, String>>? = null,
        val tokens: Map<String, String>? = null,
        val routes: Map<String, Map<String, String>>? = null,
    )

    sealed interface LookupResult {
        data class Found(val profileName: String, val token: String) : LookupResult

        data object Missing : LookupResult

        data class Ambiguous(val profileNames: List<String>) : LookupResult
    }

    private val file: File
        get() = File(CliConfigStore.configDirectory(), "git-credentials.json")

    private val lockFile: File
        get() = File(CliConfigStore.configDirectory(), "git-credentials.lock")

    private fun loadLocked(): Store {
        if (!file.exists()) return Store()
        return try {
            val stored = json.decodeFromString<StoredStore>(file.readText())
            if (stored.profiles != null) {
                Store(stored.profiles, stored.routes.orEmpty())
            } else {
                Store(
                    profiles = stored.tokens
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { mapOf(DEFAULT_PROFILE_NAME to it) }
                        .orEmpty(),
                ).also(::saveLocked)
            }
        } catch (e: CliktError) {
            throw e
        } catch (_: Exception) {
            throw CliktError(
                "Could not read Bosca Git credentials at '${file.absolutePath}'. " +
                    "Fix or move the file and retry.",
            )
        }
    }

    private fun saveLocked(store: Store) {
        writePrivateFileAtomically(file, json.encodeToString(store))
    }

    /** Returns the selected profile's cached token for [host], or null. */
    @Synchronized
    fun get(
        host: String,
        profileName: String = CliConfigStore.selectedProfileName(),
    ): String? = withPrivateFileLock(lockFile) {
        loadLocked().profiles[profileName]?.get(host)
    }

    /**
     * Resolves a cached token without binding the installed Git helper to one
     * profile. A repository path learned from a successful Git operation wins,
     * followed by the preferred profile. Otherwise a token is returned only
     * when exactly one other profile has a credential for [host].
     */
    @Synchronized
    fun resolve(
        host: String,
        preferredProfileName: String = CliConfigStore.selectedProfileName(),
        path: String? = null,
    ): LookupResult = withPrivateFileLock(lockFile) {
        val store = loadLocked()
        val routeProfileName = normalizedPath(path)?.let { store.routes[host]?.get(it) }
        routeProfileName?.let { profileName ->
            store.profiles[profileName]?.get(host)?.let {
                return@withPrivateFileLock LookupResult.Found(profileName, it)
            }
        }
        store.profiles[preferredProfileName]?.get(host)?.let {
            return@withPrivateFileLock LookupResult.Found(preferredProfileName, it)
        }

        val candidates = store.profiles.entries
            .mapNotNull { (profileName, tokens) ->
                tokens[host]?.let { LookupResult.Found(profileName, it) }
            }
            .sortedBy { it.profileName }
        when (candidates.size) {
            0 -> LookupResult.Missing
            1 -> candidates.single()
            else -> LookupResult.Ambiguous(candidates.map { it.profileName })
        }
    }

    /** Caches [token] for [host] and [profileName], replacing that entry. */
    @Synchronized
    fun put(
        host: String,
        token: String,
        profileName: String = CliConfigStore.selectedProfileName(),
    ) = withPrivateFileLock(lockFile) {
        val store = loadLocked()
        val profileTokens = store.profiles[profileName].orEmpty() + (host to token)
        saveLocked(store.copy(profiles = store.profiles + (profileName to profileTokens)))
    }

    /**
     * Records a credential approved by Git without copying a token that is
     * already owned by another profile into the currently selected profile.
     */
    @Synchronized
    fun putFromHelper(
        host: String,
        token: String,
        profileName: String = CliConfigStore.selectedProfileName(),
        path: String? = null,
    ) = withPrivateFileLock(lockFile) {
        val store = loadLocked()
        val matchingProfiles = store.profiles
            .filterValues { it[host] == token }
            .keys
        val ownerProfileName = when {
            profileName in matchingProfiles -> profileName
            matchingProfiles.size == 1 -> matchingProfiles.single()
            matchingProfiles.isEmpty() -> profileName
            else -> null
        }

        var updated = store
        if (matchingProfiles.isEmpty()) {
            val profileTokens = store.profiles[profileName].orEmpty() + (host to token)
            updated = updated.copy(profiles = updated.profiles + (profileName to profileTokens))
        }
        val normalizedPath = normalizedPath(path)
        if (ownerProfileName != null && normalizedPath != null) {
            val hostRoutes = updated.routes[host].orEmpty() + (normalizedPath to ownerProfileName)
            updated = updated.copy(routes = updated.routes + (host to hostRoutes))
        }
        if (updated != store) saveLocked(updated)
    }

    /** Removes the selected profile's cached token for [host]. */
    @Synchronized
    fun remove(
        host: String,
        profileName: String = CliConfigStore.selectedProfileName(),
    ) = withPrivateFileLock(lockFile) {
        val store = loadLocked()
        val profileTokens = store.profiles[profileName].orEmpty()
        if (host in profileTokens) {
            val remaining = profileTokens - host
            val profiles = if (remaining.isEmpty()) {
                store.profiles - profileName
            } else {
                store.profiles + (profileName to remaining)
            }
            val hostRoutes = store.routes[host]
                .orEmpty()
                .filterValues { it != profileName }
            val routes = if (hostRoutes.isEmpty()) {
                store.routes - host
            } else {
                store.routes + (host to hostRoutes)
            }
            saveLocked(store.copy(profiles = profiles, routes = routes))
        }
    }

    /**
     * Removes a credential rejected by Git from the profile that owns the
     * supplied token. When Git omits the token, the selected profile wins, or
     * the sole profile containing [host] is used as an unambiguous fallback.
     */
    @Synchronized
    fun removeFromHelper(
        host: String,
        token: String?,
        preferredProfileName: String = CliConfigStore.selectedProfileName(),
        path: String? = null,
    ) = withPrivateFileLock(lockFile) {
        val store = loadLocked()
        val routeProfileName = normalizedPath(path)?.let { store.routes[host]?.get(it) }
        val targetProfiles = when {
            token != null -> store.profiles
                .filterValues { it[host] == token }
                .keys
            routeProfileName != null -> setOf(routeProfileName)
            store.profiles[preferredProfileName]?.containsKey(host) == true -> setOf(preferredProfileName)
            else -> store.profiles
                .filterValues { host in it }
                .keys
                .takeIf { it.size == 1 }
                .orEmpty()
        }
        if (targetProfiles.isEmpty()) return@withPrivateFileLock

        var profiles = store.profiles
        for (profileName in targetProfiles) {
            val remaining = profiles[profileName].orEmpty() - host
            profiles = if (remaining.isEmpty()) {
                profiles - profileName
            } else {
                profiles + (profileName to remaining)
            }
        }
        val hostRoutes = store.routes[host]
            .orEmpty()
            .filterValues { it !in targetProfiles }
        val routes = if (hostRoutes.isEmpty()) {
            store.routes - host
        } else {
            store.routes + (host to hostRoutes)
        }
        saveLocked(store.copy(profiles = profiles, routes = routes))
    }

    /** Removes every cached Git credential belonging to [profileName]. */
    @Synchronized
    fun removeProfile(profileName: String) = withPrivateFileLock(lockFile) {
        val store = loadLocked()
        val routes = store.routes.mapValues { (_, hostRoutes) ->
            hostRoutes.filterValues { it != profileName }
        }.filterValues { it.isNotEmpty() }
        if (profileName in store.profiles || routes != store.routes) {
            saveLocked(store.copy(profiles = store.profiles - profileName, routes = routes))
        }
    }

    /** Absolute path to the credential cache file, for diagnostics. */
    fun path(): String = file.absolutePath

    private fun normalizedPath(path: String?): String? =
        path?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
}
