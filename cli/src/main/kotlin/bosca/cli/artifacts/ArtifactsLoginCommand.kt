package bosca.cli.artifacts

import bosca.cli.api.ApiTokens
import bosca.cli.config.DeploymentHosts
import bosca.cli.tokens.TokenSubcommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Creates an API token scoped to artifact operations and uses it
 * to authenticate Docker and npm. Docker receives the token through stdin;
 * npm's user configuration stores it only for the registry's `/npm/` endpoint.
 */
class ArtifactsLoginCommand internal constructor(
    private val dockerExecutable: String,
    private val npmConfigFile: File,
) : TokenSubcommand(name = "login") {
    constructor() : this("docker", npmUserConfigFile())

    override fun help(context: Context) = "Generate an API token and configure Docker and npm artifact access"

    private val registryOption by option(
        "--registry", "-r",
        envvar = "BOSCA_ARTIFACTS_REGISTRY",
        help = "Artifacts registry host or HTTP(S) URL (default: artifacts.<domain> of the Bosca endpoint)"
    )

    private val tokenName by option(
        "--token-name",
        help = "Name for the generated API token"
    ).default("artifacts-login")

    override suspend fun execute(apiTokens: ApiTokens) {
        val registryUrl = artifactRegistryUrl(registryOption ?: DeploymentHosts.artifactsRegistry(endpoint))
        val registry = registryUrl.rawAuthority
        echo("Creating API token for artifact registry access...")

        val result = apiTokens.create(
            name = tokenName,
            description = "Docker and npm artifact access for $registry",
            scopes = listOf("artifacts:pull"),
        )

        echo("Token created: ${result.apiToken.tokenPrefix}...")

        val tokenId = result.apiToken.id

        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(dockerExecutable, "login", registry, "--username", "api_token", "--password-stdin")
                .redirectErrorStream(true)
                .start()
        }

        process.outputStream.use { stdin ->
            stdin.write(result.rawToken.toByteArray())
            stdin.flush()
        }

        val (exitCode, output) = withContext(Dispatchers.IO) {
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor() to output
        }

        if (output.isNotEmpty()) {
            echo(output)
        }

        if (exitCode != 0) {
            echo("Docker login failed (exit code $exitCode)", err = true)
            echo("Revoking token...")
            apiTokens.revoke(tokenId)
            return
        }

        withContext(Dispatchers.IO) {
            configureNpmRegistry(npmConfigFile, registryUrl, result.rawToken)
        }

        echo("")
        echo("Logged in to $registry")
        echo("npm @bosca registry configured at ${registryUrl.resolve("/npm/")}")
        echo("Token ID: $tokenId (revoke with: bosca tokens revoke $tokenId)")
    }
}
