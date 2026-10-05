package bosca.ai.kit.tools.bible

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.tools.KitTool
import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** Lists the books available in a specific Bible translation. */
class ListBibleBooksTool(
    private val bibleService: BibleService,
    private val metadataService: MetadataService,
) : KitTool<ListBibleBooksTool.Input, ListBibleBooksTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "bible_list_books",
    description = "List all books in a specific Bible translation",
) {

    @Serializable
    data class Input(
        @property:LLMDescription("The UUID of the Bible translation (from bible_get_available)")
        val bibleId: String,
    )

    @Serializable
    data class Output(
        val books: List<BibleBook>,
        val error: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val bibleId = try {
            Uuid.parse(input.bibleId)
        } catch (e: Exception) {
            return Output(books = emptyList(), error = "Invalid bibleId: ${input.bibleId}")
        }
        val metadata = metadataService.getById(bibleId)
            ?: return Output(books = emptyList(), error = "Bible metadata not found for ID: ${input.bibleId}")
        val bible = bibleService.getBible(metadata.id, metadata.version, null)
            ?: return Output(books = emptyList(), error = "Bible content not found for ID: ${input.bibleId}")
        val books = bibleService.getBooks(bible)
        return Output(books = books)
    }
}
