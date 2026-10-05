package bosca.cli.bml.i18n

import bosca.graphql.client.execute
import bosca.graphql.gen.GetLocalizationProgress
import bosca.graphql.gen.GetLocalizationProjectConfig
import com.github.ajalt.clikt.core.CliktError
import kotlinx.coroutines.runBlocking

/**
 * Translation coverage per target language — the localization domain's own progress numbers,
 * printed for the project a BML site binds to.
 */
class I18nStatusCommand : I18nRemoteCommand(
    name = "status",
    helpText = "Show translation coverage for the localization project's target languages",
) {
    override fun run() = runBlocking {
        val gql = client()
        val config = gql.execute(
            GetLocalizationProjectConfig,
            GetLocalizationProjectConfig.Variables(project),
        ).localization.project
            ?: throw CliktError("Localization project $project not found (or not readable)")

        val sourceLanguage = config.sourceLanguage
        val targets = config.languages
            .map { it.languageTag }
            .filterNot { it.equals(sourceLanguage, ignoreCase = true) }

        echo("Source language: $sourceLanguage")
        if (targets.isEmpty()) {
            echo("No target languages declared — add them to the localization project in Studio.")
            return@runBlocking
        }
        for (tag in targets) {
            val progress = gql.execute(
                GetLocalizationProgress,
                GetLocalizationProgress.Variables(project, tag),
            ).localization.project?.progress ?: continue
            echo(
                "  $tag: ${progress.translatedStrings}/${progress.totalStrings} translated " +
                    "(${progress.percentage}%), ${progress.publishedStrings} published",
            )
        }
    }
}
