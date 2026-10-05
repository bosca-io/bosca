package bosca.cli.git

import bosca.cli.api.ApiTokens
import bosca.cli.api.NetworkClient
import bosca.cli.config.CliInvocation
import bosca.cli.config.CliConfigStore
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * The backbone that lets every git operation authenticate against a
 * Bosca server without the user thinking about credentials.
 *
 * It plays two roles:
 *
 *  - **git credential helper** ([parseInput]/[formatCredential]/
 *    [resolveToken]): git invokes `bosca git credential-helper get` on
 *    demand; we return a token for the host, minting and caching one the
 *    first time. This is the same on-demand model a build agent uses.
 *  - **installer** ([install]): wires the helper into git's per-host
 *    config so it is consulted for Bosca hosts only, leaving GitHub and
 *    other remotes untouched.
 */
object GitCredentialHelper {

    /** Username git presents alongside the API token over HTTPS basic auth. */
    const val USERNAME = "api_token"

    /** Scopes minted for auto-provisioned git credentials (read + write). */
    val SCOPES = listOf("git:read", "git:write")

    // ---------------------------------------------------------------
    // git credential protocol (pure — unit tested)
    // ---------------------------------------------------------------

    /**
     * Parses the `key=value` lines git writes to a credential helper's
     * stdin. The request is terminated by a blank line or EOF; anything
     * after the blank line is ignored.
     */
    fun parseInput(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) break
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            map[line.substring(0, idx)] = line.substring(idx + 1)
        }
        return map
    }

    /** Formats the credential reply git expects on a helper's stdout. */
    fun formatCredential(username: String, password: String): String =
        buildString {
            append("username=").append(username).append('\n')
            append("password=").append(password).append('\n')
            append('\n')
        }

    /**
     * Resolves the host from a parsed credential request. git supplies
     * `host` (optionally with a port) and `protocol` separately.
     */
    fun hostOf(request: Map<String, String>): String? =
        request["host"]?.takeIf { it.isNotBlank() }

    // ---------------------------------------------------------------
    // token resolution
    // ---------------------------------------------------------------

    /**
     * Returns a usable git token for [host]: a context-selected or path-routed
     * cached token when possible, otherwise a freshly minted-and-cached token.
     * Returns null when several profiles are still ambiguous or no authenticated
     * profile can mint a token.
     */
    suspend fun resolveToken(host: String, path: String? = null): String? {
        val config = CliConfigStore.load()
        val contextualProfileName = CliInvocation.profileOverride
            ?: CliConfigStore.directoryProfileSelection(config)?.profileName
        if (contextualProfileName != null) {
            return GitCredentialStore.get(host, contextualProfileName) ?: mintAndCache(host)
        }

        return when (
            val result = GitCredentialStore.resolve(
                host,
                preferredProfileName = CliConfigStore.selectedProfileName(config),
                path = path,
            )
        ) {
            is GitCredentialStore.LookupResult.Found -> result.token
            GitCredentialStore.LookupResult.Missing -> mintAndCache(host)
            is GitCredentialStore.LookupResult.Ambiguous -> null
        }
    }

    /**
     * Mints a new git-scoped API token for [host] via the GraphQL API,
     * authenticating from the stored `bosca login` session, and caches
     * it. Returns null if the CLI is not authenticated or the mint fails.
     * Used by the credential-helper subprocess, which has no command
     * context of its own.
     */
    suspend fun mintAndCache(host: String, tokenName: String = "git-$host"): String? {
        val profile = CliConfigStore.resolveProfile() ?: return null
        val network = NetworkClient(profile.profile.endpoint)
        if (!GitAuth.applyStoredAuth(network, profile)) return null
        return try {
            mint(network, host, tokenName)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Ensures a cached git credential exists for [host], minting one over
     * the already-authenticated [network] if absent. Lets `clone`
     * provision a credential using whatever auth the user supplied to the
     * command (token, username/password, or stored session). Returns true
     * if a credential is now available. On mint failure invokes [warn]
     * and returns false without aborting the caller.
     */
    suspend fun ensureCredential(
        host: String,
        network: NetworkClient,
        warn: ((String) -> Unit)? = null,
    ): Boolean {
        if (GitCredentialStore.get(host) != null) return true
        return try {
            mint(network, host, "git-$host")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn?.invoke("could not provision a git credential for $host: ${e.message}")
            false
        }
    }

    private suspend fun mint(network: NetworkClient, host: String, tokenName: String): String {
        val result = ApiTokens(network).create(
            name = tokenName,
            description = "Auto-provisioned git credential for $host",
            scopes = SCOPES,
        )
        return result.rawToken.also { GitCredentialStore.put(host, it) }
    }

    // ---------------------------------------------------------------
    // git-config installation
    // ---------------------------------------------------------------

    /** The git-config key that scopes this helper to [host] alone. */
    fun configKey(host: String): String = "credential.https://$host.helper"

    /** Enables repository-path-aware credential requests for [host]. */
    fun useHttpPathConfigKey(host: String): String = "credential.https://$host.useHttpPath"

    /** The git-config value that re-invokes this CLI as a credential helper. */
    fun configValue(profileName: String? = null): String =
        buildString {
            append("!\"").append(executablePath()).append('"')
            if (profileName != null) append(" --profile \"").append(profileName).append('"')
            append(" git credential-helper")
        }

    /**
     * A `key=value` pair suitable for `git -c …`, used to wire the helper
     * into a single git invocation (e.g. the initial clone, before any
     * repo config exists).
     */
    fun configArg(host: String): String =
        "${configKey(host)}=${configValue(CliInvocation.profileOverride)}"

    /** A `key=value` pair that preserves the repository path in helper requests. */
    fun useHttpPathConfigArg(host: String): String = "${useHttpPathConfigKey(host)}=true"

    /**
     * Command-line Git configuration for one operation. The empty helper value
     * clears inherited helpers before installing the invocation-aware Bosca
     * helper, ensuring an explicit profile is consulted first.
     */
    fun invocationConfigArgs(host: String): List<String> = listOf(
        "-c",
        "${configKey(host)}=",
        "-c",
        configArg(host),
        "-c",
        useHttpPathConfigArg(host),
    )

    /**
     * Persists the helper into the user's global git config for [host]
     * (and that host only), so all later git operations against it
     * authenticate automatically. Idempotent. Returns true on success;
     * on failure invokes [warn] (if provided) and returns false rather
     * than aborting the surrounding command.
     */
    fun install(host: String, warn: ((String) -> Unit)? = null): Boolean {
        val helperResult = GitCli.capture("config", "--global", "--replace-all", configKey(host), configValue())
        if (!helperResult.isSuccess) {
            warn?.invoke("could not configure git credential helper for $host: ${helperResult.stderr}")
            return false
        }
        val pathResult = GitCli.capture(
            "config",
            "--global",
            "--replace-all",
            useHttpPathConfigKey(host),
            "true",
        )
        if (!pathResult.isSuccess) {
            warn?.invoke("could not enable path-aware git credentials for $host: ${pathResult.stderr}")
            return false
        }
        return true
    }

    /**
     * Best path to re-invoke this CLI as a credential helper: the
     * current native binary when available, otherwise the `bosca` name
     * resolved from PATH.
     */
    private fun executablePath(): String {
        val command = ProcessHandle.current().info().command().orElse(null)
        if (command != null && File(command).name.startsWith("bosca")) return command
        return "bosca"
    }
}
