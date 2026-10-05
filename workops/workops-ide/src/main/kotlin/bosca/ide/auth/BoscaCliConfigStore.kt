package bosca.ide.auth

import bosca.core.security.TokenStorage
import bosca.core.security.model.TokenMetadata
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal const val DEFAULT_CLI_PROFILE_NAME = "default"

internal data class BoscaCliProfile(
    val name: String,
    val endpoint: String,
    val principalId: String?,
    val authenticated: Boolean,
    val active: Boolean,
) {
    override fun toString(): String = name
}

internal data class BoscaCliAuthState(
    val token: String?,
    val refreshToken: String?,
    val principalId: String?,
)

internal class BoscaCliConfigException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Reads and updates the profile/session file owned by the Bosca CLI. Updates use
 * the CLI's lock file and atomic replacement contract so the IDE and CLI can run
 * concurrently without overwriting unrelated profiles.
 */
internal class BoscaCliConfigStore(
    private val configDirectory: Path = defaultConfigDirectory(),
) {
    private val configFile = configDirectory.resolve("config.json")
    private val lockFile = configDirectory.resolve("config.lock")
    private val processLock = ReentrantLock()

    fun profiles(): List<BoscaCliProfile> {
        val root = load()
        val active = root.string("activeProfile")
            ?: if (root.getAsJsonObject("profiles") == null) DEFAULT_CLI_PROFILE_NAME else null
        return profileObjects(root)
            .map { (name, profile) ->
                val auth = profile.getAsJsonObject("auth")
                BoscaCliProfile(
                    name = name,
                    endpoint = profile.string("endpoint").orEmpty(),
                    principalId = auth?.string("principalId"),
                    authenticated = auth?.string("token") != null || auth?.string("refreshToken") != null,
                    active = name == active,
                )
            }
            .filter { it.endpoint.isNotBlank() }
            .sortedWith(compareByDescending<BoscaCliProfile> { it.active }.thenBy { it.name })
    }

    fun profile(name: String): BoscaCliProfile? = profiles().firstOrNull { it.name == name }

    fun resolveProfileName(endpoint: String): String? {
        val matches = profiles().filter { sameEndpoint(it.endpoint, endpoint) }
        return matches.firstOrNull { it.active }?.name ?: matches.singleOrNull()?.name
    }

    fun auth(profileName: String, endpoint: String): BoscaCliAuthState? {
        val profile = profileObjects(load())[profileName] ?: return null
        if (!sameEndpoint(profile.string("endpoint").orEmpty(), endpoint)) return null
        val auth = profile.getAsJsonObject("auth") ?: return null
        return BoscaCliAuthState(
            token = auth.string("token"),
            refreshToken = auth.string("refreshToken"),
            principalId = auth.string("principalId"),
        )
    }

    fun updateAuth(
        profileName: String,
        endpoint: String,
        transform: (BoscaCliAuthState) -> BoscaCliAuthState,
    ) = withLock {
        val root = loadUnlocked()
        val profiles = profilesObjectForUpdate(root)
        val profile = profiles.getAsJsonObject(profileName)
            ?: throw BoscaCliConfigException(
                "Bosca CLI profile '$profileName' no longer exists. Run 'bosca login --profile $profileName'.",
            )
        val storedEndpoint = profile.string("endpoint").orEmpty()
        if (!sameEndpoint(storedEndpoint, endpoint)) {
            throw BoscaCliConfigException(
                "Bosca CLI profile '$profileName' now targets '$storedEndpoint'; " +
                    "refusing to store credentials received from '$endpoint'.",
            )
        }

        val existing = profile.getAsJsonObject("auth")
        val updated = transform(
            BoscaCliAuthState(
                token = existing?.string("token"),
                refreshToken = existing?.string("refreshToken"),
                principalId = existing?.string("principalId"),
            ),
        )
        if (updated.token == null && updated.refreshToken == null && updated.principalId == null) {
            profile.remove("auth")
        } else {
            profile.add("auth", JsonObject().apply {
                updated.token?.let { addProperty("token", it) }
                updated.refreshToken?.let { addProperty("refreshToken", it) }
                updated.principalId?.let { addProperty("principalId", it) }
            })
        }
        saveUnlocked(root)
    }

    fun path(): Path = configFile

    private fun load(): JsonObject = withLock { loadUnlocked() }

    private fun loadUnlocked(): JsonObject {
        if (!Files.exists(configFile)) return JsonObject()
        return try {
            JsonParser.parseString(Files.readString(configFile)).asJsonObject
        } catch (error: Exception) {
            throw BoscaCliConfigException(
                "Could not read Bosca CLI configuration at '$configFile'. Fix it with the Bosca CLI and retry.",
                error,
            )
        }
    }

    private fun profileObjects(root: JsonObject): Map<String, JsonObject> {
        val profiles = root.getAsJsonObject("profiles")
        if (profiles != null) {
            return profiles.entrySet().mapNotNull { (name, value) ->
                value.takeIf { it.isJsonObject }?.asJsonObject?.let { name to it }
            }.toMap()
        }

        val endpoint = root.string("endpoint") ?: return emptyMap()
        return mapOf(
            DEFAULT_CLI_PROFILE_NAME to JsonObject().apply {
                addProperty("endpoint", endpoint)
                root.get("auth")?.takeIf { it.isJsonObject }?.let { add("auth", it.deepCopy()) }
            },
        )
    }

    private fun profilesObjectForUpdate(root: JsonObject): JsonObject {
        root.getAsJsonObject("profiles")?.let { return it }

        val profiles = JsonObject()
        val legacyEndpoint = root.string("endpoint")
        if (legacyEndpoint != null) {
            profiles.add(DEFAULT_CLI_PROFILE_NAME, JsonObject().apply {
                addProperty("endpoint", legacyEndpoint)
                root.get("auth")?.takeIf { it.isJsonObject }?.let { add("auth", it.deepCopy()) }
            })
            root.addProperty("activeProfile", DEFAULT_CLI_PROFILE_NAME)
        }
        root.remove("endpoint")
        root.remove("auth")
        root.add("profiles", profiles)
        return profiles
    }

    private fun saveUnlocked(root: JsonObject) {
        Files.createDirectories(configDirectory)
        val temporary = Files.createTempFile(configDirectory, "config", ".tmp")
        try {
            setOwnerOnlyPermissions(temporary)
            Files.writeString(temporary, GSON.toJson(root))
            try {
                Files.move(temporary, configFile, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, configFile, REPLACE_EXISTING)
            }
            setOwnerOnlyPermissions(configFile)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun <T> withLock(action: () -> T): T = processLock.withLock {
        Files.createDirectories(configDirectory)
        FileChannel.open(lockFile, CREATE, WRITE).use { channel ->
            setOwnerOnlyPermissions(lockFile)
            channel.lock().use { action() }
        }
    }

    private fun setOwnerOnlyPermissions(file: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                file,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    companion object {
        private val GSON = GsonBuilder().setPrettyPrinting().create()

        private fun defaultConfigDirectory(): Path {
            val xdgConfig = System.getenv("XDG_CONFIG_HOME")
            val base = if (xdgConfig.isNullOrBlank()) {
                Path.of(System.getProperty("user.home"), ".config")
            } else {
                Path.of(xdgConfig)
            }
            return base.resolve("bosca")
        }
    }
}

/** CLI-config-backed storage used by the same shared BoscaAuth implementation as the CLI. */
internal class BoscaCliTokenStorage(
    private val store: BoscaCliConfigStore,
    private val profileName: String,
    private val endpoint: String,
) : TokenStorage {
    override suspend fun getToken(): String? = store.auth(profileName, endpoint)?.token

    override suspend fun saveToken(token: String) =
        store.updateAuth(profileName, endpoint) { it.copy(token = token) }

    override suspend fun getRefreshToken(): String? = store.auth(profileName, endpoint)?.refreshToken

    override suspend fun saveRefreshToken(refreshToken: String) =
        store.updateAuth(profileName, endpoint) { it.copy(refreshToken = refreshToken) }

    override suspend fun getTokenMetadata(): TokenMetadata? = null

    override suspend fun saveTokenMetadata(metadata: TokenMetadata) = Unit

    override suspend fun clear() = store.updateAuth(profileName, endpoint) { BoscaCliAuthState(null, null, null) }
}

internal fun sameEndpoint(first: String, second: String): Boolean =
    first.trimEnd('/') == second.trimEnd('/')

private fun JsonObject.string(name: String): String? =
    get(name)
        ?.takeUnless { it.isJsonNull }
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
