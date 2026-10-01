package bosca.ai.kit.tools.bible

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.tools.KitTool
import bosca.bible.components.filter
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Fetches real scripture text by human-readable reference. LLMs are never the source of
 * Bible content — this reads it from the installed Bible translation.
 */
class GetBibleVersesTool(
    private val bibleService: BibleService,
    private val metadataService: MetadataService,
) : KitTool<GetBibleVersesTool.Input, GetBibleVersesTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "bible_get_verses",
    description = "Get Bible verse text by human-readable reference (e.g., 'John 3:16', 'Genesis 1:1-5', 'Psalm 23'). Returns plain text with verse numbers marked.",
) {

    @Serializable
    data class Input(
        @property:LLMDescription("The UUID of the Bible translation (from bible_get_available)")
        val bibleId: String,
        @property:LLMDescription("Human-readable Bible reference, e.g. 'John 3:16', 'Genesis 1:1-5', 'Psalm 23', 'Romans 8:28-30'")
        val reference: String,
    )

    @Serializable
    data class Output(
        val reference: String,
        val text: String,
        val resolvedUsfm: String? = null,
        val error: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val bibleId = try {
            Uuid.parse(input.bibleId)
        } catch (e: Exception) {
            return Output(reference = input.reference, text = "", error = "Invalid bibleId: ${input.bibleId}")
        }
        val metadata = metadataService.getById(bibleId)
            ?: return Output(reference = input.reference, text = "", error = "Bible metadata not found")
        val bible = bibleService.getBible(metadata.id, metadata.version, null)
            ?: return Output(reference = input.reference, text = "", error = "Bible content not found")

        val references = bibleService.getReferences(bible, input.reference)
        if (references.isEmpty()) {
            return Output(
                reference = input.reference,
                text = "",
                error = "Could not resolve reference: '${input.reference}'. Check the book name, chapter, and verse numbers.",
            )
        }

        val resultText = StringBuilder()
        val usfmParts = mutableListOf<String>()

        for (ref in references) {
            val chapter = try {
                bibleService.getChapter(bible, ref)
            } catch (e: Exception) {
                return Output(reference = input.reference, text = "", error = "Chapter not found for reference: ${ref.usfm}")
            }
            val chapterComponents = chapter.getChapterComponents() ?: continue

            val hasVerseNumbers = ref.references.any { it.number.isNotEmpty() }
            val components = if (hasVerseNumbers) {
                chapterComponents.filter(ref) ?: continue
            } else {
                chapterComponents
            }

            resultText.append(ComponentTextRenderer.render(components))
            resultText.append('\n')
            usfmParts.add(ref.usfm)
        }

        return Output(
            reference = input.reference,
            text = resultText.toString().trim(),
            resolvedUsfm = usfmParts.joinToString(", "),
        )
    }
}
