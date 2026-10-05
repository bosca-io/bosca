package bosca.cli.localization

import bosca.graphql.client.execute
import bosca.graphql.gen.ExportFormat
import bosca.graphql.gen.ExportLocalization
import bosca.graphql.gen.ExportRequestInput
import bosca.graphql.gen.TranslationState
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Exports translations for a project+language in the requested format and writes
 * the payload to disk. Output file naming defaults to the server-supplied
 * [bosca.localization.model.ExportResult.fileName] unless `--output` overrides it.
 */
class DownloadCommand : LocalizationSubcommand(
    name = "download",
    help = "Download translated strings for a project+language in a target format"
) {

    private val languageTag by option("--language", help = "Language tag to export").required()

    private val format by option(
        "--format",
        help = "Export format (ANDROID_XML | IOS_STRINGS | IOS_STRINGSDICT | JSON_I18N | JSON_FLAT | JSON_NESTED | ARB | XLIFF)"
    ).default("JSON_I18N")

    private val state by option(
        "--state",
        help = "Which translation states to include: PUBLISHED | APPROVED | ALL"
    ).default("PUBLISHED")

    private val outputDir by option("--output", "-o", help = "Output directory (default: current dir)")
        .file().default(File("."))

    override fun run() = runBlocking {
        val gql = client()
        val states = when (state.uppercase()) {
            "ALL" -> null
            "APPROVED" -> listOf(TranslationState.APPROVED, TranslationState.PUBLISHED)
            "PUBLISHED" -> listOf(TranslationState.PUBLISHED)
            else -> throw CliktError("Unsupported translation state '$state'. Use PUBLISHED, APPROVED, or ALL.")
        }
        val exportFormat = try {
            ExportFormat.valueOf(format.uppercase())
        } catch (_: IllegalArgumentException) {
            throw CliktError("Unsupported export format '$format'.")
        }
        val result = gql.execute(
            ExportLocalization,
            ExportLocalization.Variables(
                ExportRequestInput(
                    format = exportFormat,
                    languageTag = languageTag,
                    projectId = project,
                    statesFilter = states,
                ),
            ),
        ).localization.export
        val content = result.content
        val fileName = result.fileName
        val target = File(outputDir, fileName)
        val canonicalOutput = outputDir.canonicalFile.toPath()
        val canonicalTarget = target.canonicalFile.toPath()
        require(canonicalTarget.startsWith(canonicalOutput)) {
            "Export filename '$fileName' resolves outside the output directory"
        }
        target.parentFile?.mkdirs()
        target.writeText(content)
        echo("Wrote ${content.length} bytes to ${target.absolutePath}")
    }
}
