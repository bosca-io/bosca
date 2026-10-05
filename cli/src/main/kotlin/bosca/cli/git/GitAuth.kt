package bosca.cli.git

import bosca.cli.api.CliAuth
import bosca.cli.api.NetworkClient
import bosca.cli.config.ResolvedCliProfile

/**
 * Shared resolution of the credentials saved by `bosca login`, used by
 * both [GitSubcommand] and the git credential helper so they authenticate
 * to the GraphQL API identically.
 */
object GitAuth {

    /**
     * Applies the stored `bosca login` session to [network] via the shared
     * [CliAuth]/`BoscaAuth` stack, lazily refreshing an expired access token.
     *
     * Returns true if a token was applied, false if the user has not run
     * `bosca login`.
     */
    suspend fun applyStoredAuth(network: NetworkClient, profile: ResolvedCliProfile): Boolean =
        CliAuth.authenticate(
            network,
            profile.profile.endpoint,
            token = null,
            username = null,
            password = null,
            profileName = profile.name,
        )
}
