package bosca.cli

import bosca.cli.api.CliAuth
import bosca.cli.api.NetworkClient
import bosca.cli.config.CliConfig
import bosca.cli.config.CliConfigStore
import bosca.cli.config.ResolvedCliProfile
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError

/**
 * Base for every Bosca CLI command.
 *
 * Configuration and profile selection are invocation context, so commands
 * consume them here instead of independently loading and resolving the same
 * state. Values are lazy: commands that only operate on local files never read
 * the CLI config.
 */
abstract class BoscaCliCommand(name: String) : CliktCommand(name = name) {

    protected val cliConfig: CliConfig by lazy(LazyThreadSafetyMode.NONE) {
        CliConfigStore.load()
    }

    protected val selectedProfileName: String by lazy(LazyThreadSafetyMode.NONE) {
        CliConfigStore.selectedProfileName(cliConfig)
    }

    protected val selectedProfile: ResolvedCliProfile by lazy(LazyThreadSafetyMode.NONE) {
        requireProfile()
    }

    protected fun findProfile(name: String? = null): ResolvedCliProfile? =
        CliConfigStore.resolveProfile(name, cliConfig)

    protected fun requireProfile(name: String? = null): ResolvedCliProfile =
        findProfile(name)
            ?: throw CliktError("Profile '${name ?: selectedProfileName}' does not exist.")

    /**
     * Creates the canonical CLI network client with explicit credentials taking
     * precedence over the selected profile's stored session.
     */
    protected suspend fun networkClient(
        endpoint: String? = null,
        token: String? = null,
        username: String? = null,
        password: String? = null,
        requireAuthentication: Boolean = true,
    ): NetworkClient {
        if (username != null && password == null) {
            throw CliktError("--password is required when using --username.")
        }

        val profile = selectedProfile
        val effectiveEndpoint = endpoint ?: profile.profile.endpoint
        val network = NetworkClient(effectiveEndpoint)
        val authenticated = CliAuth.authenticate(
            network = network,
            endpoint = effectiveEndpoint,
            token = token,
            username = username,
            password = password,
            profileName = profile.name,
        )
        if (requireAuthentication && !authenticated) {
            throw CliktError("Not authenticated. Run 'bosca login' or pass --token/--username.")
        }
        return network
    }
}
