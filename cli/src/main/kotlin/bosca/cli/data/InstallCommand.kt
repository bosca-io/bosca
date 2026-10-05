package bosca.cli.data

import bosca.cli.BoscaCliCommand
import bosca.cli.api.ContentCollections
import bosca.cli.api.ContentMetadata
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Provisions templates, collections, metadata, and relationships
 * into a running Bosca instance from a JSON install manifest.
 *
 * Usage:
 * ```
 * bosca data install --manifest path/to/manifest.json \
 *                    --url http://localhost:8080/graphql \
 *                    --username admin@bosca.io \
 *                    --password password
 * ```
 */
class InstallCommand : BoscaCliCommand(
    name = "install",
) {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Bootstrap a Bosca instance with content from a JSON manifest"
    private val manifest by option("--manifest", "-m", help = "Path to the JSON install manifest file")
        .required()

    private val url by option(
        "--url", "-u",
        envvar = "BOSCA_ENDPOINT",
        help = "Bosca GraphQL endpoint URL",
    )

    private val token by option(
        "--token", "-t",
        envvar = "BOSCA_TOKEN",
        help = "Bearer token for authentication",
    )

    private val username by option("--username", help = "Authentication username")

    private val password by option("--password", help = "Authentication password")

    override fun run() {
        val manifestFile = File(manifest)
        if (!manifestFile.exists()) {
            echo("Error: Manifest file not found: ${manifestFile.absolutePath}", err = true)
            return
        }

        val useBootstrapDefaults = token == null && username == null && selectedProfile.profile.auth == null
        val effectiveUsername = username ?: if (useBootstrapDefaults) "admin@bosca.io" else null
        val effectivePassword = password ?: if (useBootstrapDefaults) "password" else null

        runBlocking {
            val network = networkClient(
                endpoint = url,
                token = token,
                username = effectiveUsername,
                password = effectivePassword,
            )
            val metadata = ContentMetadata(network)
            val collections = ContentCollections(network)
            val installer = DataInstaller(metadata, collections)
            installer.install(manifestFile)
        }
    }
}
