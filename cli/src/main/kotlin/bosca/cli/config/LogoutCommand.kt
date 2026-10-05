package bosca.cli.config

import bosca.cli.BoscaCliCommand
import bosca.cli.api.CliAuth
import bosca.cli.git.GitCredentialStore
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException

/**
 * Removes stored credentials and best-effort invalidates the session
 * server-side via the shared `io.bosca:auth-shared` library.
 */
class LogoutCommand : BoscaCliCommand(name = "logout") {
    override fun help(context: Context) = "Clear one profile's stored credentials and sign out"

    private val profile by option(
        "--profile", "-p",
        help = "Profile to log out (default: selected or active profile)",
    )

    override fun run() = runBlocking {
        val resolved = requireProfile(profile)

        if (resolved.profile.auth?.token != null) {
            try {
                val auth = CliAuth.create(resolved.profile.endpoint, resolved.name)
                auth.initialize(fetchProfile = false) // load the stored token so signOut can send it
                auth.signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort server-side invalidation; local credentials are cleared below regardless.
            }
        }

        // Ensure local credentials are gone even if the server call failed.
        CliConfigStore.update { current ->
            val currentProfile = current.profiles[resolved.name] ?: return@update current
            current.withProfile(resolved.name, currentProfile.copy(auth = null))
        }
        GitCredentialStore.removeProfile(resolved.name)
        echo("Logged out profile '${resolved.name}'. Credentials removed from ${CliConfigStore.configPath()}")
    }
}
