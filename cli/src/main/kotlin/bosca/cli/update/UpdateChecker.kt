package bosca.cli.update

import bosca.cli.Version
import bosca.cli.config.CliConfigStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Checks public GitHub releases, or an explicit artifact/GitHub repository
 * override. Cached notices are scoped to that source.
 * Passive checks are optional, bounded, and never disrupt ordinary commands.
 */
object UpdateChecker {
    /** Explicit GitHub repository override for release checks. */
    const val REPOSITORY_ENV = "BOSCA_CLI_REPOSITORY"
    const val DEFAULT_REPOSITORY = "bosca-io/bosca"
    const val DEFAULT_INSTALL_SCRIPT_URL = "https://bosca.io/cli/install.sh"
    /** Raw repository download URL override, shared with cli/install.sh. */
    const val ARTIFACTS_URL_ENV = "BOSCA_CLI_ARTIFACTS_URL"
    /** Optional token for direct private artifact repository lookups. */
    const val ARTIFACTS_TOKEN_ENV = "BOSCA_CLI_ARTIFACTS_TOKEN"

    private const val CHECK_INTERVAL_SECONDS = 24L * 60 * 60
    private val CONNECT_TIMEOUT = Duration.ofMillis(1500)
    private val REQUEST_TIMEOUT = Duration.ofMillis(3000)
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT)
            .callTimeout(REQUEST_TIMEOUT)
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stableVersion = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
    private val versionPattern = Regex(
        "v?([0-9]+(?:\\.[0-9]+){0,2})(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
            "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?",
    )
    private val notified = AtomicBoolean(false)

    /** Explicit version checks suppress the duplicate post-command notice. */
    @Volatile
    var suppressPassive: Boolean = false

    /** Release listing endpoint and the repository used to decode its response. */
    data class ReleaseSource(
        val listingUrl: String,
        val artifactsUrl: String? = null,
        val githubRepository: String? = null,
    )

    /** Published release and the repository used by its upgrade command. */
    @Serializable
    data class LatestRelease(
        val version: String,
        val artifactsUrl: String? = null,
        val repository: String? = null,
    )

    @Serializable
    data class GitHubRelease(
        @SerialName("tag_name") val tagName: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
    )

    @Serializable
    private data class ArtifactVersion(val version: String)

    @Serializable
    private data class ArtifactVersions(val versions: List<ArtifactVersion>)

    private val releasesSerializer = ListSerializer(GitHubRelease.serializer())

    @Serializable
    private data class UpdateCheckCache(
        val source: String? = null,
        val lastCheckEpochSeconds: Long = 0,
        val latestRelease: LatestRelease? = null,
    )

    private val cacheFile: File
        get() = File(CliConfigStore.configDirectory(), "update-check.json")

    private fun loadCache(): UpdateCheckCache = try {
        json.decodeFromString(UpdateCheckCache.serializer(), cacheFile.readText())
    } catch (_: IOException) {
        UpdateCheckCache()
    } catch (_: IllegalArgumentException) {
        UpdateCheckCache()
    }

    private fun saveCache(cache: UpdateCheckCache) {
        try {
            CliConfigStore.configDirectory().mkdirs()
            cacheFile.writeText(json.encodeToString(UpdateCheckCache.serializer(), cache))
        } catch (_: IOException) {
            // An unwritable cache must not prevent an update check or ordinary command.
        }
    }

    /** Normalizes an explicit GitHub repository name. */
    fun releasesRepository(configured: String? = System.getenv(REPOSITORY_ENV)): String =
        configured?.trim()?.trim('/').takeUnless { it.isNullOrEmpty() } ?: DEFAULT_REPOSITORY

    /** Selects an explicit artifact repository or GitHub releases, defaulting to the public Bosca repository. */
    fun releaseSource(
        artifactsUrl: String? = System.getenv(ARTIFACTS_URL_ENV),
        repository: String? = System.getenv(REPOSITORY_ENV),
    ): ReleaseSource {
        val artifacts = artifactsUrl?.trim()?.trimEnd('/').takeUnless { it.isNullOrEmpty() }
        if (artifacts != null) {
            val uri = URI.create(artifacts)
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
                uri.rawQuery == null && uri.rawFragment == null &&
                Regex(".*/raw/[^/]+/[^/]+").matches(uri.rawPath)) {
                "$ARTIFACTS_URL_ENV must be a raw repository download URL: https://HOST/raw/NAMESPACE/NAME"
            }
            val slash = artifacts.lastIndexOf('/')
            return ReleaseSource(
                artifacts.substring(0, slash) + "/api" + artifacts.substring(slash),
                artifactsUrl = artifacts,
            )
        }
        val name = releasesRepository(repository)
        return ReleaseSource("https://api.github.com/repos/$name/releases?per_page=100", githubRepository = name)
    }

    /** Builds the install command for a GitHub-distributed CLI. */
    fun installCommand(repository: String): String =
        "curl -fsSL https://raw.githubusercontent.com/$repository/main/cli/install.sh | $REPOSITORY_ENV=$repository sh"

    /** Builds an upgrade command using the checked release's actual repository. */
    fun installCommand(release: LatestRelease): String {
        release.artifactsUrl?.let { repository ->
            fun quoted(value: String) = "'" + value.replace("'", "'\\''") + "'"
            val installer = repository.trimEnd('/') + "/" + release.version + "/install.sh"
            return "curl -fsSL " + quoted(installer) + " | $ARTIFACTS_URL_ENV=" + quoted(repository) + " sh"
        }
        return release.repository?.let(::installCommand) ?: "curl -fsSL $DEFAULT_INSTALL_SCRIPT_URL | sh"
    }

    /** Fetches a stable release from the selected source; returns null on lookup failure without falling back to another source. */
    fun fetchLatestRelease(source: ReleaseSource): LatestRelease? = try {
        val request = Request.Builder()
            .url(source.listingUrl)
            .header("Accept", "application/json")
            .get()
        val token = when {
            source.artifactsUrl != null -> System.getenv(ARTIFACTS_TOKEN_ENV)
            source.githubRepository != null -> System.getenv("GITHUB_TOKEN")
            else -> null
        }
        if (!token.isNullOrBlank()) request.header("Authorization", "Bearer $token")
        client.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body.string()
            when {
                source.artifactsUrl != null -> {
                    val listing = json.decodeFromString(ArtifactVersions.serializer(), body)
                    listing.versions.firstOrNull { stableVersion.matches(it.version) }
                        ?.let { LatestRelease(it.version, artifactsUrl = source.artifactsUrl) }
                }
                else -> {
                    val repository = requireNotNull(source.githubRepository)
                    latestReleaseVersion(json.decodeFromString(releasesSerializer, body))
                        ?.let { LatestRelease(it, repository = repository) }
                }
            }
        }
    } catch (e: IOException) {
        if (Thread.currentThread().isInterrupted) throw e
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Reuses only cache entries from this source; old unscoped entries are discarded. */
    internal fun cachedLatestRelease(source: ReleaseSource, now: Long = System.currentTimeMillis() / 1000): LatestRelease? {
        val cached = loadCache().takeIf { it.source == source.listingUrl } ?: UpdateCheckCache(source = source.listingUrl)
        if (now - cached.lastCheckEpochSeconds in 0 until CHECK_INTERVAL_SECONDS) return cached.latestRelease
        val release = fetchLatestRelease(source) ?: cached.latestRelease
        saveCache(UpdateCheckCache(source.listingUrl, now, release))
        return release
    }

    /**
     * Selects the first stable, non-draft CLI release in GitHub's newest-first listing.
     * CLI releases use bare `<major>.<minor>.<patch>` tags; prefixed tags belong to other components.
     */
    fun latestReleaseVersion(releases: List<GitHubRelease>): String? =
        releases.asSequence()
            .filter { !it.draft && !it.prerelease }
            .map { it.tagName }
            .firstOrNull(stableVersion::matches)

    /**
     * Compares two version strings (tolerating an optional leading `v` and
     * prerelease/build suffixes) and returns true when [remote] is strictly
     * newer than [current]. Missing release components count as zero. Build
     * metadata does not affect precedence; prerelease identifiers follow SemVer
     * ordering, with numeric identifiers compared numerically. Invalid versions
     * never produce an update notice.
     */
    fun isNewer(remote: String, current: String): Boolean {
        fun parse(value: String): Pair<List<BigInteger>, List<String>>? {
            val match = versionPattern.matchEntire(value.trim()) ?: return null
            val core = match.groupValues[1].split('.').map { it.toBigInteger() }
            val prerelease = match.groupValues[2].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            return core to prerelease
        }
        val (r, rPre) = parse(remote) ?: return false
        val (c, cPre) = parse(current) ?: return false
        for (i in 0 until maxOf(r.size, c.size)) {
            val ri = r.getOrNull(i) ?: BigInteger.ZERO
            val ci = c.getOrNull(i) ?: BigInteger.ZERO
            if (ri != ci) return ri > ci
        }
        if (rPre.isEmpty() != cPre.isEmpty()) return rPre.isEmpty()
        for (i in 0 until minOf(rPre.size, cPre.size)) {
            val ri = rPre[i]
            val ci = cPre[i]
            if (ri == ci) continue
            val rNumber = ri.takeIf { it.all { char -> char in '0'..'9' } }?.toBigInteger()
            val cNumber = ci.takeIf { it.all { char -> char in '0'..'9' } }?.toBigInteger()
            val comparison = when {
                rNumber != null && cNumber != null -> rNumber.compareTo(cNumber)
                rNumber != null -> -1
                cNumber != null -> 1
                else -> ri.compareTo(ci)
            }
            if (comparison != 0) return comparison > 0
        }
        return rPre.size > cPre.size
    }

    /** Displays a cached, daily update notice for interactive terminals; errors never disrupt commands. */
    fun maybeNotify() {
        try {
            if (suppressPassive || notified.get() || Version.isDev || !isEnabled() || System.console() == null) return
            val release = cachedLatestRelease(releaseSource()) ?: return
            if (isNewer(release.version, Version.current) && notified.compareAndSet(false, true)) {
                System.err.println()
                System.err.println("A new release of bosca is available: " + Version.current + " → " + release.version)
                System.err.println("  Update: " + installCommand(release))
            }
        } catch (_: IllegalArgumentException) {
            // Invalid optional release-source configuration must not fail ordinary commands.
        }
    }

    private fun isEnabled(): Boolean {
        if (!System.getenv("BOSCA_NO_UPDATE_CHECK").isNullOrEmpty()) return false
        return when (System.getenv("BOSCA_UPDATE_CHECK")?.trim()?.lowercase()) {
            "0", "false", "no", "off" -> false
            else -> true
        }
    }
}
