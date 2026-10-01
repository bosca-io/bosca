package bosca.cli.config

import com.github.ajalt.clikt.core.CliktError
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

const val DEFAULT_PROFILE_NAME = "default"
const val DEFAULT_BOSCA_ENDPOINT = "http://localhost:8080/graphql"
const val DIRECTORY_CONFIG_FILE_NAME = ".boscarc"

/**
 * Persistent CLI configuration stored at ~/.config/bosca/config.json.
 * Holds named server/account profiles and the profile selected by default.
 */
@Serializable
data class CliConfig(
    val activeProfile: String? = null,
    val profiles: Map<String, ProfileConfig> = emptyMap(),
) {
    fun withProfile(name: String, profile: ProfileConfig, makeActive: Boolean = false): CliConfig =
        copy(
            activeProfile = if (makeActive || activeProfile == null) name else activeProfile,
            profiles = profiles + (name to profile),
        )

    fun withoutProfile(name: String): CliConfig {
        val remaining = profiles - name
        val nextActive = when {
            remaining.isEmpty() -> null
            activeProfile != name && activeProfile in remaining -> activeProfile
            else -> remaining.keys.sorted().first()
        }
        return copy(activeProfile = nextActive, profiles = remaining)
    }

    internal fun normalized(): CliConfig {
        val normalizedActive = when {
            profiles.isEmpty() -> null
            activeProfile in profiles -> activeProfile
            else -> profiles.keys.sorted().first()
        }
        return if (normalizedActive == activeProfile) this else copy(activeProfile = normalizedActive)
    }
}

/**
 * A saved Bosca server/account pair. Authentication is kept with the endpoint
 * so accounts on the same server remain independent.
 */
@Serializable
data class ProfileConfig(
    val endpoint: String = DEFAULT_BOSCA_ENDPOINT,
    val auth: AuthConfig? = null,
)

/**
 * Stored authentication state. Either a long-lived API token or
 * a JWT session with refresh capability.
 */
@Serializable
data class AuthConfig(
    val token: String? = null,
    val refreshToken: String? = null,
    val principalId: String? = null,
)

/**
 * The pre-profile config shape. It is decoded separately so an upgrade can
 * preserve the existing endpoint and credentials in the `default` profile.
 */
@Serializable
private data class StoredCliConfig(
    val activeProfile: String? = null,
    val profiles: Map<String, ProfileConfig>? = null,
    val endpoint: String? = null,
    val auth: AuthConfig? = null,
)

data class ResolvedCliProfile(
    val name: String,
    val profile: ProfileConfig,
    val saved: Boolean,
)

/** Public, extensible, non-secret configuration stored in a directory's `.boscarc`. */
@Serializable
data class DirectoryConfig(
    val url: String,
)

/** A public, non-secret Bosca endpoint read from a directory's `.boscarc`. */
data class DirectoryEndpointSelection(
    val directory: String,
    val file: File,
    val endpoint: String,
)

/** A structurally valid directory-specific profile selection and its source file. */
data class DirectoryProfileSelection(
    val directory: String,
    val file: File,
    val profileName: String,
    val endpoint: String,
)

/**
 * Per-process selection set by the root `--profile` option. It never writes the
 * config file, so the persistent active profile is unchanged.
 */
object CliInvocation {
    internal var profileOverride: String? = null
        private set

    fun selectProfile(name: String?) {
        profileOverride = name
    }
}

private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val directoryConfigJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

private val profileNamePattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

fun isValidProfileName(name: String): Boolean = profileNamePattern.matches(name)

/** Treats a single trailing slash as insignificant when matching stored credentials to a server. */
fun isSameEndpoint(first: String, second: String): Boolean =
    first.trimEnd('/') == second.trimEnd('/')

/**
 * Reads and writes the CLI configuration file, creating the
 * directory structure on first use.
 */
object CliConfigStore {

    /**
     * Test seam: when non-null, overrides the config directory that is normally
     * derived from `XDG_CONFIG_HOME` / the home directory. Lets tests redirect
     * reads and writes to a temp directory instead of touching the developer's
     * real `~/.config/bosca`. Not used in production.
     */
    internal var directoryOverride: File? = null

    /** Test seam for commands whose profile selection depends on the invocation directory. */
    internal var workingDirectoryOverride: File? = null

    private val configDir: File
        get() {
            directoryOverride?.let { return it }
            val xdgConfig = System.getenv("XDG_CONFIG_HOME")
            val base = if (xdgConfig != null) File(xdgConfig) else File(System.getProperty("user.home"), ".config")
            return File(base, "bosca")
        }

    private val configFile: File
        get() = File(configDir, "config.json")

    private val lockFile: File
        get() = File(configDir, "config.lock")

    @Synchronized
    fun load(): CliConfig = withPrivateFileLock(lockFile) {
        loadLocked()
    }

    private fun loadLocked(): CliConfig {
        if (!configFile.exists()) return CliConfig()
        return try {
            val stored = json.decodeFromString<StoredCliConfig>(configFile.readText())
            if (stored.profiles == null) {
                CliConfig(
                    activeProfile = DEFAULT_PROFILE_NAME,
                    profiles = mapOf(
                        DEFAULT_PROFILE_NAME to ProfileConfig(
                            endpoint = stored.endpoint ?: DEFAULT_BOSCA_ENDPOINT,
                            auth = stored.auth,
                        ),
                    ),
                ).also(::saveLocked)
            } else {
                CliConfig(
                    activeProfile = stored.activeProfile,
                    profiles = stored.profiles,
                ).normalized()
            }
        } catch (e: CliktError) {
            throw e
        } catch (_: Exception) {
            throw CliktError(
                "Could not read Bosca CLI configuration at '${configFile.absolutePath}'. " +
                    "Fix or move the file and retry.",
            )
        }
    }

    @Synchronized
    fun save(config: CliConfig) = withPrivateFileLock(lockFile) {
        saveLocked(config)
    }

    private fun saveLocked(config: CliConfig) {
        writePrivateFileAtomically(
            configFile,
            json.encodeToString(config.normalized()),
        )
    }

    @Synchronized
    fun update(transform: (CliConfig) -> CliConfig): CliConfig =
        withPrivateFileLock(lockFile) {
            val updated = transform(loadLocked()).normalized()
            saveLocked(updated)
            updated
        }

    /**
     * Resolves profile selection in precedence order: an invocation override,
     * the nearest directory binding, the global active profile, then the
     * implicit default profile.
     */
    fun selectedProfileName(config: CliConfig = load()): String =
        CliInvocation.profileOverride
            ?: directoryProfileSelection(config)?.profileName
            ?: config.activeProfile
            ?: DEFAULT_PROFILE_NAME

    /**
     * Finds the closest endpoint binding for [directory], including bindings
     * on its parents. A binding at a repository root therefore applies
     * throughout that repository while a nested binding can override it.
     * The endpoint is matched to a private saved profile configuration.
     */
    fun directoryProfileSelection(
        config: CliConfig = load(),
        directory: File = workingDirectory(),
    ): DirectoryProfileSelection? {
        val local = readDirectoryEndpoint(directory) ?: return null
        val matchingNames = config.profiles
            .filterValues { isSameEndpoint(local.endpoint, it.endpoint) }
            .keys
            .sorted()
        val profileName = when {
            matchingNames.isEmpty() -> throw CliktError(
                "No saved profile targets the URL '${local.endpoint}' from '${local.file.absolutePath}'. " +
                    "Create one with 'bosca login --profile <name>' or remove the file " +
                    "with 'bosca profile unset'.",
            )
            matchingNames.size == 1 -> matchingNames.single()
            config.activeProfile in matchingNames -> config.activeProfile!!
            else -> throw CliktError(
                "Multiple saved profiles (${matchingNames.joinToString()}) target the URL " +
                    "'${local.endpoint}' from '${local.file.absolutePath}'. " +
                    "Select one with '--profile <name>' or make one active with 'bosca profile use <name>'.",
            )
        }
        return DirectoryProfileSelection(
            directory = local.directory,
            file = local.file,
            profileName = profileName,
            endpoint = local.endpoint,
        )
    }

    /**
     * Reads the nearest `.boscarc` without requiring a matching profile to
     * exist locally. Login uses its endpoint to bootstrap a saved profile on
     * a new machine.
     */
    fun readDirectoryEndpoint(
        directory: File = workingDirectory(),
    ): DirectoryEndpointSelection? {
        val file = findDirectoryConfigFile(directory) ?: return null
        val endpoint = parseDirectoryEndpoint(file)
        return DirectoryEndpointSelection(
            directory = file.parentFile.absolutePath,
            file = file,
            endpoint = endpoint,
        )
    }

    /** Writes a profile's endpoint URL to the extensible `.boscarc` JSON in [directory]. */
    fun saveDirectoryProfile(
        profileName: String,
        profile: ProfileConfig,
        directory: File = workingDirectory(),
    ): DirectoryProfileSelection {
        val normalizedDirectory = File(directoryPath(directory))
        if (!normalizedDirectory.isDirectory) {
            throw CliktError("Directory '${normalizedDirectory.absolutePath}' does not exist.")
        }
        val file = File(normalizedDirectory, DIRECTORY_CONFIG_FILE_NAME)
        writeTextFileAtomically(
            file,
            directoryConfigJson.encodeToString(
                DirectoryConfig.serializer(),
                DirectoryConfig(url = profile.endpoint),
            ) + "\n",
        )
        return DirectoryProfileSelection(
            directory = normalizedDirectory.absolutePath,
            file = file,
            profileName = profileName,
            endpoint = profile.endpoint,
        )
    }

    /** Finds the closest `.boscarc` without parsing it, so `profile unset` can repair invalid files. */
    fun findDirectoryConfigFile(directory: File = workingDirectory()): File? {
        var path = directory.toPath().toAbsolutePath().normalize()
        while (true) {
            val file = path.resolve(DIRECTORY_CONFIG_FILE_NAME).toFile()
            if (file.exists()) return file
            path = path.parent ?: return null
        }
    }

    /** Returns the normalized absolute path used for directory configuration. */
    fun directoryPath(directory: File = workingDirectory()): String =
        directory.toPath().toAbsolutePath().normalize().toString()

    fun resolveProfile(
        name: String? = null,
        config: CliConfig = load(),
    ): ResolvedCliProfile? {
        val selectedName = name ?: selectedProfileName(config)
        val saved = config.profiles[selectedName]
        if (saved != null) return ResolvedCliProfile(selectedName, saved, saved = true)

        // Preserve the pre-login localhost behavior without pretending that a
        // user-selected missing profile exists.
        if (
            name == null &&
            CliInvocation.profileOverride == null &&
            config.activeProfile == null &&
            config.profiles.isEmpty()
        ) {
            return ResolvedCliProfile(DEFAULT_PROFILE_NAME, ProfileConfig(), saved = false)
        }
        return null
    }

    fun configPath(): String = configFile.absolutePath

    fun workingDirectory(): File =
        workingDirectoryOverride
            ?: directoryOverride
            ?: File(System.getProperty("user.dir"))

    /**
     * The directory holding all Bosca CLI state (`~/.config/bosca` by
     * default). Exposed so sibling stores — e.g. the git credential
     * cache — share the exact same XDG-aware location.
     */
    fun configDirectory(): File = configDir

    private fun parseDirectoryEndpoint(file: File): String {
        if (!file.isFile) {
            throw CliktError("Directory configuration '${file.absolutePath}' is not a file.")
        }
        val config = try {
            directoryConfigJson.decodeFromString(DirectoryConfig.serializer(), file.readText())
        } catch (_: Exception) {
            throw invalidDirectoryConfig(file)
        }
        val endpoint = config.url
        val uri = try {
            URI(endpoint)
        } catch (_: Exception) {
            null
        }
        if (
            endpoint.isBlank() ||
            uri?.scheme?.lowercase() !in setOf("http", "https") ||
            uri?.host.isNullOrBlank()
        ) {
            throw invalidDirectoryConfig(file)
        }
        return endpoint
    }

    private fun invalidDirectoryConfig(file: File) =
        CliktError(
            "Could not read directory configuration at '${file.absolutePath}'. " +
                "Expected JSON containing an HTTP or HTTPS URL in the string field 'url'.",
        )
}

/**
 * Serializes state-file updates across concurrently running CLI processes.
 * This matters for long-lived MCP servers refreshing one profile while another
 * CLI invocation updates a different profile.
 */
internal fun <T> withPrivateFileLock(lockFile: File, action: () -> T): T {
    lockFile.parentFile?.mkdirs()
    RandomAccessFile(lockFile, "rw").use { randomAccess ->
        setOwnerOnlyPermissions(lockFile)
        val lock = randomAccess.channel.lock()
        try {
            return action()
        } finally {
            lock.release()
        }
    }
}

/** Writes a complete owner-only state file without exposing partial JSON to readers. */
internal fun writePrivateFileAtomically(file: File, contents: String) {
    val directory = file.parentFile
    directory?.mkdirs()
    val temporary = File.createTempFile(".${file.name}.", ".tmp", directory)
    try {
        setOwnerOnlyPermissions(temporary)
        temporary.writeText(contents)
        try {
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING)
        }
        setOwnerOnlyPermissions(file)
    } finally {
        if (temporary.exists()) temporary.delete()
    }
}

/** Writes a non-secret text file atomically using ordinary user-readable permissions. */
internal fun writeTextFileAtomically(file: File, contents: String) {
    val directory = file.parentFile
    directory?.mkdirs()
    val temporary = File.createTempFile(".${file.name}.", ".tmp", directory)
    try {
        temporary.writeText(contents)
        try {
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING)
        }
        file.setReadable(true, false)
        file.setWritable(true, true)
    } finally {
        if (temporary.exists()) temporary.delete()
    }
}

private fun setOwnerOnlyPermissions(file: File) {
    file.setReadable(false, false)
    file.setReadable(true, true)
    file.setWritable(false, false)
    file.setWritable(true, true)
}
