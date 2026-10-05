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
 * Resolves the latest published `bosca` release from the project's GitHub
 * Releases and surfaces a non-intrusive "update available" notice.
 *
 * CLI releases are tagged `cli-v<version>` in a repository shared with other
 * independently versioned components, so "latest" is the newest non-draft,
 * non-prerelease release carrying that tag prefix — the same rule the installer
 * script applies, so the CLI and installer always agree on what "latest" means.
 *
 * The passive check is deliberately conservative: it only runs for interactive
 * terminals, at most once per day (cached), never blocks meaningfully, and
 * swallows every error — a missing network must never disrupt a command.
 */
object UpdateChecker {

    /** Environment variable that points release lookups at another repository (`owner/name`), such as a fork. */
    const val REPOSITORY_ENV = "BOSCA_CLI_REPOSITORY"

    /** The repository whose GitHub Releases publish `bosca`. */
    const val DEFAULT_REPOSITORY = "bosca-io/bosca"

    /** Short URL of the installer script for [DEFAULT_REPOSITORY] releases. */
    const val DEFAULT_INSTALL_SCRIPT_URL = "https://cli.bosca.io/install.sh"

    /** Tag prefix of CLI releases; the remainder of the tag is the version. */
    const val TAG_PREFIX = "cli-v"

    /** How often the passive check refreshes from GitHub. */
    private const val CHECK_INTERVAL_SECONDS = 24L * 60 * 60

    /** Tight bounds so a slow/blocked network never stalls the CLI. */
    private val CONNECT_TIMEOUT = Duration.ofMillis(1500)
    private val REQUEST_TIMEOUT = Duration.ofMillis(3000)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Ensures the notice prints at most once per process. */
    private val notified = AtomicBoolean(false)

    /**
     * Set by commands that perform their own explicit check (e.g. `bosca
     * version --check`) so the post-run passive notice doesn't duplicate it.
     */
    @Volatile
    var suppressPassive: Boolean = false

    // ── GitHub model (subset of the list-releases response) ─────────────────

    /** One GitHub release, as returned by `GET /repos/{owner}/{repo}/releases`. */
    @Serializable
    data class GitHubRelease(
        @SerialName("tag_name") val tagName: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
    )

    private val releasesSerializer = ListSerializer(GitHubRelease.serializer())

    // ── Persistent throttle/cache ────────────────────────────────────────────

    @Serializable
    private data class UpdateCheckCache(
        val lastCheckEpochSeconds: Long = 0,
        val latestVersion: String? = null,
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

    // ── Public API ───────────────────────────────────────────────────────────

    /** The `owner/name` repository releases are read from: [REPOSITORY_ENV] when set, else [DEFAULT_REPOSITORY]. */
    fun releasesRepository(configured: String? = System.getenv(REPOSITORY_ENV)): String =
        configured?.trim()?.trim('/').takeUnless { it.isNullOrEmpty() } ?: DEFAULT_REPOSITORY

    /**
     * The one-line install/upgrade command shown to users: [DEFAULT_INSTALL_SCRIPT_URL] for the
     * project's releases, or the other repository's own installer with [REPOSITORY_ENV] set.
     */
    fun installCommand(repository: String): String =
        if (repository == DEFAULT_REPOSITORY) {
            "curl -fsSL $DEFAULT_INSTALL_SCRIPT_URL | sh"
        } else {
            "curl -fsSL https://raw.githubusercontent.com/$repository/main/cli/install.sh | $REPOSITORY_ENV=$repository sh"
        }

    /**
     * Fetches the newest published version from the repository's releases, or
     * null on any failure (offline, timeout, rate limit, no CLI release,
     * malformed body). Never throws.
     */
    fun fetchLatestVersion(repository: String): String? = runCatching {
        val client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
        val request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/$repository/releases?per_page=100"))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/vnd.github+json")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return null
        latestReleaseVersion(json.decodeFromString(releasesSerializer, response.body()))
    }.getOrNull()

    /**
     * Picks the newest CLI version from a release listing that GitHub orders
     * newest-first: the first published (non-draft, non-prerelease) release
     * tagged [TAG_PREFIX] followed by a version starting with a digit. Returns
     * null when the listing has no CLI release.
     */
    fun latestReleaseVersion(releases: List<GitHubRelease>): String? =
        releases.asSequence()
            .filter { !it.draft && !it.prerelease && it.tagName.startsWith(TAG_PREFIX) }
            .map { it.tagName.removePrefix(TAG_PREFIX) }
            .firstOrNull { it.firstOrNull()?.isDigit() == true }

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

    /**
     * Passive, throttled "update available" notice, intended to run after a
     * command completes. No-ops for dev builds, opted-out users, non-interactive
     * terminals, and when already up to date. Refreshes from GitHub at most
     * once per [CHECK_INTERVAL_SECONDS]; otherwise it reports from cache instantly.
     * Every failure is swallowed.
     */
    fun maybeNotify() {
        runCatching {
            if (suppressPassive || notified.get()) return
            if (Version.isDev) return
            if (!isEnabled()) return
            if (!isInteractive()) return

            val repository = releasesRepository()
            val cache = loadCache()
            val now = System.currentTimeMillis() / 1000
            var latest = cache.latestVersion

            if (now - cache.lastCheckEpochSeconds >= CHECK_INTERVAL_SECONDS) {
                val fetched = fetchLatestVersion(repository)
                // Record the attempt regardless of outcome so we don't re-check
                // every invocation while offline; keep the prior known-latest.
                saveCache(UpdateCheckCache(lastCheckEpochSeconds = now, latestVersion = fetched ?: cache.latestVersion))
                if (fetched != null) latest = fetched
            }

            val newest = latest ?: return
            if (isNewer(newest, Version.current) && notified.compareAndSet(false, true)) {
                printNotice(Version.current, newest, repository)
            }
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** Update checks are on by default; opt out via env. */
    private fun isEnabled(): Boolean {
        if (!System.getenv("BOSCA_NO_UPDATE_CHECK").isNullOrEmpty()) return false
        return when (System.getenv("BOSCA_UPDATE_CHECK")?.trim()?.lowercase()) {
            "0", "false", "no", "off" -> false
            else -> true
        }
    }

    /**
     * True only when attached to a real terminal. `System.console()` is null
     * when stdio is piped/redirected (CI, scripts, the embedded MCP server), so
     * this keeps the nudge out of non-interactive contexts.
     */
    private fun isInteractive(): Boolean = System.console() != null

    private fun printNotice(current: String, latest: String, repository: String) {
        System.err.println()
        System.err.println("A new release of bosca is available: $current → $latest")
        System.err.println("  Update: ${installCommand(repository)}")
    }
}
