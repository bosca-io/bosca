@file:OptIn(ExperimentalUuidApi::class)

package bosca.cli.bml.i18n

import bosca.cli.BoscaCliCommand
import bosca.graphql.client.GraphQLClient
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * `bosca bml i18n` — the authoring loop between `.bml` source and the Bosca localization
 * project: `extract` inspects the compile-time manifest, `push` creates
 * missing keys and seeds source-language text, `status` reports translation coverage.
 * Translators keep working entirely in the localization workflow (Studio, download, Crowdin);
 * this command family only feeds source keys IN — it never deletes and never touches
 * translations in other languages.
 */
class BmlI18nCommand : BoscaCliCommand(name = "i18n") {
    override fun help(context: Context) =
        "Sync .bml localization keys with a Bosca localization project (extract / push / status)."

    override fun run() = Unit
}

/** Shared options for the subcommands that read the compile-time manifest. */
abstract class I18nManifestCommand(name: String, private val helpText: String) : BoscaCliCommand(name = name) {

    override fun help(context: Context): String = helpText

    private val manifestOption by option(
        "--manifest",
        help = "Path to the compiler's i18n manifest (default: ${I18nManifest.DEFAULT_PATH})",
    ).file()

    private val projectDir by option(
        "--project-dir",
        help = "BML project directory the default manifest path is resolved against",
    ).file().default(File("."))

    protected val manifestFile: File
        get() = manifestOption ?: File(projectDir, I18nManifest.DEFAULT_PATH)

    /** Parses the manifest, converting validation failures into clean CLI errors (exit 1). */
    protected fun loadManifest(): List<I18nManifestString> = try {
        I18nManifest.parse(manifestFile)
    } catch (e: IllegalArgumentException) {
        throw com.github.ajalt.clikt.core.CliktError(e.message)
    }
}

/** Shared options for the subcommands that also talk to the localization GraphQL API. */
abstract class I18nRemoteCommand(name: String, helpText: String) : I18nManifestCommand(name, helpText) {

    protected val endpoint by option(
        "--endpoint",
        envvar = "BOSCA_ENDPOINT",
        help = "The bosca-server GraphQL endpoint (e.g. https://api.example.com/graphql)",
    )

    protected val token by option(
        "--token",
        envvar = "BOSCA_TOKEN",
        help = "Bearer token for authentication",
    )

    protected val project: Uuid by option(
        "--project",
        help = "The localization project identifier (UUID)",
    ).convert { Uuid.parse(it) }.required()

    protected suspend fun client(): GraphQLClient =
        networkClient(
            endpoint = endpoint,
            token = token,
            requireAuthentication = false,
        ).boscaGraphql
}
