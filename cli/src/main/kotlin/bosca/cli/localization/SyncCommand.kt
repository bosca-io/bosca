package bosca.cli.localization

import bosca.graphql.client.execute
import bosca.graphql.gen.SyncLocalizationFromProvider
import bosca.graphql.gen.SyncLocalizationToProvider
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking

/**
 * Triggers an external sync. Accepts `--direction push | pull | both` and prints
 * the provider-reported counts (and errors, if any) so CI logs surface what
 * changed during the sync.
 *
 * Since [bosca.localization.sync.CrowdinSyncProvider] is currently a stub, this
 * command returns an error message until the Crowdin integration lands; the
 * contract itself is complete.
 */
class SyncCommand : LocalizationSubcommand(
    name = "sync",
    help = "Trigger an external sync for a project"
) {

    private val direction by option(
        "--direction",
        help = "Sync direction: push | pull | both"
    ).default("both")

    override fun run() = runBlocking {
        val gql = client()
        if (direction == "push" || direction == "both") {
            echo("Pushing to provider...")
            val result = gql.execute(
                SyncLocalizationToProvider,
                SyncLocalizationToProvider.Variables(project),
            ).localization.syncToProvider
            printSyncResult(
                stringsAdded = result.stringsAdded,
                stringsUpdated = result.stringsUpdated,
                translationsAdded = result.translationsAdded,
                translationsUpdated = result.translationsUpdated,
                errors = result.errors,
            )
        }
        if (direction == "pull" || direction == "both") {
            echo("Pulling from provider...")
            val result = gql.execute(
                SyncLocalizationFromProvider,
                SyncLocalizationFromProvider.Variables(project),
            ).localization.syncFromProvider
            printSyncResult(
                stringsAdded = result.stringsAdded,
                stringsUpdated = result.stringsUpdated,
                translationsAdded = result.translationsAdded,
                translationsUpdated = result.translationsUpdated,
                errors = result.errors,
            )
        }
    }

    private fun printSyncResult(
        stringsAdded: Int,
        stringsUpdated: Int,
        translationsAdded: Int,
        translationsUpdated: Int,
        errors: List<String>,
    ) {
        echo("  strings added:       $stringsAdded")
        echo("  strings updated:     $stringsUpdated")
        echo("  translations added:  $translationsAdded")
        echo("  translations updated:$translationsUpdated")
        if (errors.isNotEmpty()) {
            echo("  errors:")
            errors.forEach { echo("    - $it") }
        }
    }
}
