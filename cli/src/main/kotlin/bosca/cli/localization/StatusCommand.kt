package bosca.cli.localization

import bosca.graphql.client.execute
import bosca.graphql.gen.GetLocalizationProgress
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.coroutines.runBlocking

/**
 * Prints translation coverage for a project+language pair, including the split
 * between human-authored and AI-generated translations so reviewers know how
 * much remains to be audited before publishing.
 */
class StatusCommand : LocalizationSubcommand(
    name = "status",
    help = "Display translation progress for a project+language"
) {

    private val languageTag by option("--language", help = "Language tag").required()

    override fun run() = runBlocking {
        val gql = client()
        val result = gql.execute(
            GetLocalizationProgress,
            GetLocalizationProgress.Variables(project, languageTag),
        ).localization.project
            ?: throw CliktError("Localization project $project not found (or not readable)")
        val progress = result.progress
        echo("Project: ${result.name}")
        echo("Language: $languageTag")
        echo("Total strings:        ${progress.totalStrings}")
        echo("Translated:           ${progress.translatedStrings}")
        echo("Approved:             ${progress.approvedStrings}")
        echo("Published:            ${progress.publishedStrings}")
        echo("AI-generated:         ${progress.aiGeneratedStrings}")
        echo("Human-translated:     ${progress.humanTranslatedStrings}")
        echo("Percent complete:     %.1f%%".format(progress.percentage))
    }
}
