package bosca.cli.config

import bosca.cli.BoscaCliCommand
import bosca.cli.api.CliAuth
import bosca.cli.git.GitCredentialStore
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Authenticates with a Bosca server and stores the session locally via the
 * shared `io.bosca:auth-shared` library ([CliAuth]). The session persists to
 * `~/.config/bosca/config.json` (the same file the rest of the CLI reads).
 * Supports:
 *  - Password login (--username/--password)
 *  - OAuth2 authorization code flow via browser (--oauth2)
 *  - Direct API token storage (--api-token)
 *  - Refreshing the stored session (--refresh-token)
 */
class LoginCommand : BoscaCliCommand(name = "login") {
    override fun help(context: Context) = "Authenticate with a Bosca server and store credentials"

    private val profile by option(
        "--profile", "-p",
        help = "Profile name to create or update (default: selected or active profile)",
    )

    private val endpoint by option(
        "--url", "-u",
        envvar = "BOSCA_ENDPOINT",
        help = "Bosca GraphQL endpoint URL"
    )

    private val username by option("--username", help = "Login with username/password")

    private val password by option("--password", help = "Password for username login")

    private val oauth2Provider by option(
        "--oauth2",
        help = "OAuth2 provider name (e.g. google, facebook) — opens browser for authentication"
    )

    private val apiToken by option(
        "--api-token",
        help = "Store a pre-existing API token directly"
    )

    private val refreshToken by option(
        "--refresh-token",
        help = "Refresh the stored session token using the saved refresh token"
    ).flag()

    override fun run() {
        val config = cliConfig
        val explicitProfile = profile ?: CliInvocation.profileOverride
        val directoryEndpoint = CliConfigStore.readDirectoryEndpoint()
        val directoryProfile = if (explicitProfile == null) {
            when {
                directoryEndpoint == null || config.profiles.isEmpty() -> null
                else -> CliConfigStore.directoryProfileSelection(config)
            }
        } else {
            null
        }
        val targetProfile = explicitProfile
            ?: directoryProfile?.profileName
            ?: (if (directoryEndpoint != null && config.profiles.isEmpty()) DEFAULT_PROFILE_NAME else null)
            ?: config.activeProfile
            ?: DEFAULT_PROFILE_NAME
        val directoryControlsSelection =
            directoryProfile != null ||
                (directoryEndpoint != null && targetProfile !in config.profiles)
        val makeActive =
            (explicitProfile != null && !directoryControlsSelection) ||
                directoryEndpoint == null
        val directoryToSync = directoryEndpoint
            ?.takeIf { directoryControlsSelection }
            ?.let { File(it.directory) }
        requireValidProfileName(targetProfile)
        val url = endpoint
            ?: directoryProfile?.endpoint
            ?: directoryEndpoint
                ?.takeIf { explicitProfile == null || targetProfile !in config.profiles }
                ?.endpoint
            ?: config.profiles[targetProfile]?.endpoint
            ?: DEFAULT_BOSCA_ENDPOINT
        val suppliedApiToken = apiToken
        val suppliedOauth2Provider = oauth2Provider
        val suppliedUsername = username

        when {
            refreshToken -> refreshStoredToken(targetProfile, endpoint, makeActive)
            suppliedApiToken != null ->
                storeApiToken(targetProfile, url, suppliedApiToken, makeActive, directoryToSync)
            suppliedOauth2Provider != null ->
                loginOAuth2(targetProfile, url, suppliedOauth2Provider, makeActive, directoryToSync)
            suppliedUsername != null -> loginPassword(targetProfile, url, suppliedUsername, password ?: run {
                echo("Error: --password required with --username", err = true)
                return
            }, makeActive, directoryToSync)
            else -> {
                echo("Error: specify --username, --oauth2 <provider>, --api-token <token>, or --refresh-token", err = true)
                return
            }
        }
    }

    private fun refreshStoredToken(
        profileName: String,
        endpointOverride: String?,
        makeActive: Boolean,
    ) = runBlocking {
        val profile = cliConfig.profiles[profileName]
        val savedRefreshToken = profile?.auth?.refreshToken
        if (savedRefreshToken == null) {
            echo("Error: no refresh token stored for profile '$profileName'. Run 'bosca login' first.", err = true)
            return@runBlocking
        }
        if (endpointOverride != null && !isSameEndpoint(endpointOverride, profile.endpoint)) {
            throw CliktError(
                "Cannot refresh profile '$profileName' against a different server. " +
                    "Use '${profile.endpoint}' or log in again to change the profile endpoint.",
            )
        }

        val auth = CliAuth.create(profile.endpoint, profileName)
        auth.initialize(fetchProfile = false) // load the current + refresh token from disk
        val response = try {
            // Force a real refresh (not lazy getToken()): the stored access token
            // may still be valid locally yet rejected by the server (401).
            auth.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            echo("Error: token refresh failed — ${e.message}", err = true)
            echo("Run 'bosca login' to re-authenticate.", err = true)
            return@runBlocking
        }
        if (response == null) {
            echo("Error: no refresh token stored. Run 'bosca login' first.", err = true)
            return@runBlocking
        }
        // refresh() persisted the rotated token via ConfigFileTokenStorage.
        if (makeActive) activateProfile(profileName)
        echo("Token refreshed successfully for profile '$profileName'.")
        echo("Config: ${CliConfigStore.configPath()}")
    }

    private fun storeApiToken(
        profileName: String,
        url: String,
        token: String,
        makeActive: Boolean,
        directory: File?,
    ) {
        CliConfigStore.update { config ->
            config.withProfile(
                profileName,
                ProfileConfig(endpoint = url, auth = AuthConfig(token = token)),
                makeActive = makeActive,
            )
        }
        syncDirectoryProfile(directory, profileName, url)
        GitCredentialStore.removeProfile(profileName)
        echo("API token stored in profile '$profileName'. Endpoint: $url")
        echo("Config: ${CliConfigStore.configPath()}")
    }

    private fun loginPassword(
        profileName: String,
        url: String,
        user: String,
        pass: String,
        makeActive: Boolean,
        directory: File?,
    ) = runBlocking {
        val auth = CliAuth.createForLogin(url, profileName)
        val response = try {
            auth.signInWithPassword(user, pass)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            echo("Error: login failed — ${e.message}", err = true)
            return@runBlocking
        }
        GitCredentialStore.removeProfile(profileName)
        // signInWithPassword persisted token + refresh token; record the principal id too.
        persistPrincipalId(profileName, response.principal.id.toString(), makeActive)
        syncDirectoryProfile(directory, profileName, url)
        echo("Logged in as ${response.principal.id} using profile '$profileName'.")
        echo("Config: ${CliConfigStore.configPath()}")
    }

    private fun loginOAuth2(
        profileName: String,
        url: String,
        provider: String,
        makeActive: Boolean,
        directory: File?,
    ) = runBlocking {
        val serverUrl = url.removeSuffix("/graphql").removeSuffix("/")
        val port = 6979
        val callbackUrl = "http://127.0.0.1:$port/callback"
        val latch = CountDownLatch(1)
        var exchangeToken: String? = null
        var error: String? = null

        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        httpServer.createContext("/callback") { exchange ->
            val query = exchange.requestURI.rawQuery
            if (query != null && query.isNotEmpty()) {
                val params = query.split("&").mapNotNull { param ->
                    val parts = param.split("=", limit = 2)
                    if (parts.size == 2) URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8") else null
                }.toMap()
                exchangeToken = params["exchangeToken"]
                if (params.containsKey("error")) {
                    error = params["error_description"] ?: params["error"]
                }
            }

            val body = if (error == null) {
                "<html><body><h2>Authentication successful</h2><p>You can close this window.</p></body></html>"
            } else {
                "<html><body><h2>Authentication failed</h2><p>$error</p></body></html>"
            }
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
            latch.countDown()
        }
        httpServer.start()

        val authUrl = "$serverUrl/oauth2/$provider/login?redirect=$callbackUrl"

        echo("Opening browser for OAuth2 authentication...")
        echo("If the browser doesn't open, visit: $authUrl")

        try {
            val os = System.getProperty("os.name").lowercase()
            val command = when {
                os.contains("mac") -> "open"
                os.contains("win") -> "rundll32"
                else -> "xdg-open"
            }
            val args = when {
                os.contains("win") -> listOf(command, "url.dll,FileProtocolHandler", authUrl)
                else -> listOf(command, authUrl)
            }
            ProcessBuilder(args).start()
        } catch (_: Exception) {
            // Browser open failed — user has the URL printed above
        }

        val received = latch.await(120, TimeUnit.SECONDS)
        httpServer.stop(0)

        if (!received) {
            echo("Error: timed out waiting for OAuth2 callback (120s)", err = true)
            return@runBlocking
        }

        if (error != null) {
            echo("Error: $error", err = true)
            return@runBlocking
        }

        val captured = exchangeToken
        if (captured == null) {
            echo("Error: no exchange token received from OAuth2 callback", err = true)
            return@runBlocking
        }

        val auth = CliAuth.createForLogin(url, profileName)
        val response = try {
            auth.exchange(captured)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if ("exchange token" in msg && ("expired" in msg || "not found" in msg)) {
                echo("Error: OAuth2 exchange token expired. Please retry 'bosca login'.", err = true)
            } else {
                echo("Error: token exchange failed — $msg", err = true)
            }
            return@runBlocking
        }
        GitCredentialStore.removeProfile(profileName)
        // exchange() persisted token + refresh token; record the principal id too.
        persistPrincipalId(profileName, response.principal.id.toString(), makeActive)
        syncDirectoryProfile(directory, profileName, url)
        echo("Logged in successfully using profile '$profileName'.")
        echo("Config: ${CliConfigStore.configPath()}")
    }

    /** Records the principal id alongside the session BoscaAuth just persisted. */
    private fun persistPrincipalId(
        profileName: String,
        principalId: String,
        makeActive: Boolean,
    ) {
        CliConfigStore.update { config ->
            val profile = config.profiles[profileName] ?: ProfileConfig()
            config.withProfile(
                profileName,
                profile.copy(auth = (profile.auth ?: AuthConfig()).copy(principalId = principalId)),
                makeActive = makeActive,
            )
        }
    }

    private fun activateProfile(profileName: String) {
        CliConfigStore.update { config ->
            if (profileName in config.profiles) config.copy(activeProfile = profileName) else config
        }
    }

    private fun syncDirectoryProfile(
        directory: File?,
        profileName: String,
        endpoint: String,
    ) {
        if (directory != null) {
            CliConfigStore.saveDirectoryProfile(
                profileName,
                ProfileConfig(endpoint = endpoint),
                directory,
            )
        }
    }
}
