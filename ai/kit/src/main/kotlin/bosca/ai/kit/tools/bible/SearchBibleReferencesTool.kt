package bosca.ai.kit.tools.bible

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.tools.KitTool
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Resolves a human-readable reference into structured USFM data without fetching the full
 * verse text — useful for validating references or planning a fetch.
 */
class SearchBibleReferencesTool(
    private val bibleService: BibleService,
    private val metadataService: MetadataService,
) : KitTool<SearchBibleReferencesTool.Input, SearchBibleReferencesTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "bible_search_references",
    description = "Parse and resolve a human-readable Bible reference into structured USFM reference data, without fetching the full text",
) {

    @Serializable
    data class Input(
        @property:LLMDescription("The UUID of the Bible translation")
        val bibleId: String,
        @property:LLMDescription("Human-readable Bible reference, e.g. 'John 3:16', 'Genesis 1:1-5'")
        val reference: String,
    )

    @Serializable
    data class ResolvedReference(
        val usfm: String,
        val bookUsfm: String,
        val chapterUsfm: String,
        val chapter: String,
        val verseCount: Int,
    )

    @Serializable
    data class Output(
        val references: List<ResolvedReference>,
        val error: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val bibleId = try {
            Uuid.parse(input.bibleId)
        } catch (e: Exception) {
            return Output(references = emptyList(), error = "Invalid bibleId: ${input.bibleId}")
        }
        val metadata = metadataService.getById(bibleId)
            ?: return Output(references = emptyList(), error = "Bible metadata not found")
        val bible = bibleService.getBible(metadata.id, metadata.version, null)
            ?: return Output(references = emptyList(), error = "Bible content not found")

        val references = bibleService.getReferences(bible, input.reference)
        if (references.isEmpty()) {
            return Output(references = emptyList(), error = "Could not resolve reference: '${input.reference}'")
        }

        return Output(
            references = references.map { ref ->
                ResolvedReference(
                    usfm = ref.usfm,
                    bookUsfm = ref.bookUsfm,
                    chapterUsfm = ref.chapterUsfm,
                    chapter = ref.chapter,
                    verseCount = ref.references.size,
                )
            },
        )
    }
}
