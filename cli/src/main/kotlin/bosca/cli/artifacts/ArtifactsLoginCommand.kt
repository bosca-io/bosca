package bosca.cli.artifacts

import bosca.cli.api.ApiTokens
import bosca.cli.config.DeploymentHosts
import bosca.cli.tokens.TokenSubcommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Creates an API token scoped to artifact operations and uses it
 * to authenticate with the Docker registry via `docker login`.
 * The generated token is passed securely through stdin so it
 * never appears in process listings or shell history.
 */
class ArtifactsLoginCommand : TokenSubcommand(name = "login") {
    override fun help(context: Context) = "Generate an API token and log in to the artifacts Docker registry"

    private val registryOption by option(
        "--registry", "-r",
        envvar = "BOSCA_ARTIFACTS_REGISTRY",
        help = "Artifacts registry hostname (default: artifacts.<domain> of the Bosca endpoint)"
    )

    private val tokenName by option(
        "--token-name",
        help = "Name for the generated API token"
    ).default("artifacts-docker-login")

    override suspend fun execute(apiTokens: ApiTokens) {
        val registry = registryOption ?: DeploymentHosts.artifactsRegistry(endpoint)
        echo("Creating API token for artifact registry access...")

        val result = apiTokens.create(
            name = tokenName,
            description = "Auto-generated token for Docker registry login to $registry",
            scopes = listOf("artifacts:pull"),
        )

        echo("Token created: ${result.apiToken.tokenPrefix}...")

        val tokenId = result.apiToken.id

        val process = withContext(Dispatchers.IO) {
            ProcessBuilder("docker", "login", registry, "--username", "api_token", "--password-stdin")
                .redirectErrorStream(true)
                .start()
        }

        process.outputStream.use { stdin ->
            stdin.write(result.rawToken.toByteArray())
            stdin.flush()
        }

        val exitCode = withContext(Dispatchers.IO) {
            process.waitFor()
        }
        val output = process.inputStream.bufferedReader().readText().trim()

        if (output.isNotEmpty()) {
            echo(output)
        }

        if (exitCode != 0) {
            echo("Docker login failed (exit code $exitCode)", err = true)
            echo("Revoking token...")
            apiTokens.revoke(tokenId)
            return
        }

        echo("")
        echo("Logged in to $registry")
        echo("Token ID: $tokenId (revoke with: bosca tokens revoke $tokenId)")
    }
}
