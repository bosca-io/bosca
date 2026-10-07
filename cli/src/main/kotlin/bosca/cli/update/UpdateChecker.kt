package bosca.cli.update

import bosca.cli.Version
import bosca.cli.config.CliConfigStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Checks the release source selected by the website's installer, or an explicit
 * artifact/GitHub repository override. Cached notices are scoped to that source.
 * Passive checks are optional, bounded, and never disrupt ordinary commands.
 */
object UpdateChecker {
    /** Explicit GitHub repository override for release checks. */
    const val REPOSITORY_ENV = "BOSCA_CLI_REPOSITORY"
    const val DEFAULT_REPOSITORY = "bosca-io/bosca"
    const val DEFAULT_INSTALL_SCRIPT_URL = "https://bosca.io/cli/install.sh"
    /** Metadata published by the website using its configured installer repository. */
    const val DEFAULT_RELEASES_URL = "https://bosca.io/cli/releases.json"
    /** Raw repository download URL override, shared with cli/install.sh. */
    const val ARTIFACTS_URL_ENV = "BOSCA_CLI_ARTIFACTS_URL"
    /** Optional token for direct private artifact repository lookups. */
    const val ARTIFACTS_TOKEN_ENV = "BOSCA_CLI_ARTIFACTS_TOKEN"
    const val TAG_PREFIX = "cli-v"

    private const val CHECK_INTERVAL_SECONDS = 24L * 60 * 60
    private val CONNECT_TIMEOUT = Duration.ofMillis(1500)
    private val REQUEST_TIMEOUT = Duration.ofMillis(3000)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stableVersion = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
    private val notified = AtomicBoolean(false)

    /** Explicit version checks suppress the duplicate post-command notice. */
    @Volatile
    var suppressPassive: Boolean = false

    /** Metadata endpoint and the optional direct repository used to decode its response. */
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

    private fun loadCache(): UpdateCheckCache =
        runCatching { json.decodeFromString(UpdateCheckCache.serializer(), cacheFile.readText()) }.getOrElse { UpdateCheckCache() }

    private fun saveCache(cache: UpdateCheckCache) {
        runCatching {
            CliConfigStore.configDirectory().mkdirs()
            cacheFile.writeText(json.encodeToString(UpdateCheckCache.serializer(), cache))
        }
    }

    /** Normalizes an explicit GitHub repository name. */
    fun releasesRepository(configured: String? = System.getenv(REPOSITORY_ENV)): String =
        configured?.trim()?.trim('/').takeUnless { it.isNullOrEmpty() } ?: DEFAULT_REPOSITORY

    /** Selects an explicit artifact repository, an explicit GitHub repository, or website metadata. */
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
        if (!repository.isNullOrBlank()) {
            val name = releasesRepository(repository)
            return ReleaseSource("https://api.github.com/repos/$name/releases?per_page=100", githubRepository = name)
        }
        return ReleaseSource(DEFAULT_RELEASES_URL)
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
    fun fetchLatestRelease(source: ReleaseSource): LatestRelease? = runCatching {
        val client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
        val request = HttpRequest.newBuilder(URI.create(source.listingUrl))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .GET()
        val token = when {
            source.artifactsUrl != null -> System.getenv(ARTIFACTS_TOKEN_ENV)
            source.githubRepository != null -> System.getenv("GITHUB_TOKEN")
            else -> null
        }
        if (!token.isNullOrBlank()) request.header("Authorization", "Bearer $token")
        val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return null
        when {
            source.artifactsUrl != null -> {
                val listing = json.decodeFromString(ArtifactVersions.serializer(), response.body())
                listing.versions.firstOrNull { stableVersion.matches(it.version) }
                    ?.let { LatestRelease(it.version, artifactsUrl = source.artifactsUrl) }
            }
            source.githubRepository != null -> {
                latestReleaseVersion(json.decodeFromString(releasesSerializer, response.body()))
                    ?.let { LatestRelease(it, repository = source.githubRepository) }
            }
            else -> json.decodeFromString(LatestRelease.serializer(), response.body())
                .takeIf { stableVersion.matches(it.version) }
        }
    }.getOrNull()

    /** Reuses only cache entries from this source; old unscoped entries are discarded. */
    internal fun cachedLatestRelease(source: ReleaseSource, now: Long = System.currentTimeMillis() / 1000): LatestRelease? {
        val cached = loadCache().takeIf { it.source == source.listingUrl } ?: UpdateCheckCache(source = source.listingUrl)
        if (now - cached.lastCheckEpochSeconds < CHECK_INTERVAL_SECONDS) return cached.latestRelease
        val release = fetchLatestRelease(source) ?: cached.latestRelease
        saveCache(UpdateCheckCache(source.listingUrl, now, release))
        return release
    }

    /** Selects the first stable, non-draft CLI release in GitHub's newest-first listing. */
    fun latestReleaseVersion(releases: List<GitHubRelease>): String? =
        releases.asSequence()
            .filter { !it.draft && !it.prerelease && it.tagName.startsWith(TAG_PREFIX) }
            .map { it.tagName.removePrefix(TAG_PREFIX) }
            .firstOrNull(stableVersion::matches)

    /**
     * Compares two version strings (tolerating an optional leading `v` and
     * pre-release/build suffixes) and returns true when [remote] is strictly
     * newer than [current]. Numeric release components compare numerically;
     * missing components count as 0, so "5.9" > "5.8.4". When the numeric cores
     * are equal, a final release outranks a pre-release of it (so "5.9.0" is newer
     * than "5.9.0-rc1", and never the reverse).
     */
    fun isNewer(remote: String, current: String): Boolean {
        // Split a version into its numeric release core and an optional
        // pre-release/build tail (everything after the first '-' or '+').
        fun coreAndPre(s: String): Pair<List<Int>, String> {
            val v = s.trim().removePrefix("v")
            val cut = v.indexOfFirst { it == '-' || it == '+' }
            val core = if (cut >= 0) v.substring(0, cut) else v
            val pre = if (cut >= 0) v.substring(cut + 1) else ""
            return core.split('.').map { it.toIntOrNull() ?: 0 } to pre
        }
        val (r, rPre) = coreAndPre(remote)
        val (c, cPre) = coreAndPre(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val ri = r.getOrNull(i) ?: 0
            val ci = c.getOrNull(i) ?: 0
            if (ri != ci) return ri > ci
        }
        // Equal numeric cores: a final release (no pre-release tag) is newer than
        // a pre-release; otherwise fall back to a lexical tiebreak (equal => not newer).
        if (rPre.isEmpty() != cPre.isEmpty()) return rPre.isEmpty()
        return rPre > cPre
    }

    /** Displays a cached, daily update notice for interactive terminals; errors never disrupt commands. */
    fun maybeNotify() {
        runCatching {
            if (suppressPassive || notified.get() || Version.isDev || !isEnabled() || System.console() == null) return
            val release = cachedLatestRelease(releaseSource()) ?: return
            if (isNewer(release.version, Version.current) && notified.compareAndSet(false, true)) {
                System.err.println()
                System.err.println("A new release of bosca is available: " + Version.current + " → " + release.version)
                System.err.println("  Update: " + installCommand(release))
            }
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
