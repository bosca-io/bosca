package bosca.content.metadata.routes

import bosca.bible.BibleAttribution
import bosca.bible.BibleContentUse
import bosca.bible.Reference
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.BibleChapter
import bosca.content.metadata.model.toJson
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.routes.annotations.RouteController
import bosca.security.service.AuthenticationContext
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

@Serializable
class ChapterContent(
    val human: String,
    val humanLong: String,
    val usfm: String,
    val chapter: BibleChapter,
    val content: List<JsonElement>,
    val attribution: BibleAttribution? = null,
)

@RouteController("/api/v1/content/metadata/bible/{id}/contents")
class GetBibleBookChapterVerses(
    metadataService: MetadataService,
    metadataPermissionEvaluator: MetadataPermissionEvaluator,
    bibleService: BibleService,
) : BaseBibleRoute<List<ChapterContent>>(metadataService, metadataPermissionEvaluator, bibleService) {

    override fun serializer(): KSerializer<List<ChapterContent>> = ListSerializer(ChapterContent.serializer())

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): List<ChapterContent> {
        val bible = call.getBible(authenticationContext)
        val reference = Reference(call.request.queryParameters["usfm"]?.replace(' ', '+') ?: error("missing usfm"))
        return getChapterVerses(reference, bible, bibleService)
    }

    companion object {

        /** Resolves chapter references, optionally reading display content and its attribution. */
        suspend fun getChapterVerses(
            reference: Reference,
            bible: Bible,
            bibleService: BibleService,
            includeContent: Boolean = true,
        ): List<ChapterContent> {
            val mapping = reference.references.filter { it.chapterUsfm.isNotEmpty() }.groupBy { it.chapterUsfm }
            val chapters = mutableListOf<ChapterContent>()
            val books = bibleService.getBooks(bible).associateBy { it.usfm }
            for ((chapterUsfm, references) in mapping) {
                val chapter = bibleService.getChapter(bible, Reference(chapterUsfm))
                val content = if (includeContent) {
                    bibleService.getChapterContent(bible, Reference(chapterUsfm), BibleContentUse.DISPLAY)
                } else null
                if (content != null && content.chapter[Reference(chapterUsfm)] == null) continue
                val filtered = content?.let { chapterContent ->
                    references.map { chapterContent.chapter[it]?.toJson() ?: JsonNull }
                } ?: emptyList()
                val fullReference = Reference(references.joinToString("+") { it.usfm })
                val book = books.getValue(fullReference.bookUsfm)
                chapters.add(
                    ChapterContent(
                        fullReference.toHuman(book.nameShort ?: book.nameLong ?: book.abbreviation),
                        fullReference.toHuman(book.nameLong ?: book.abbreviation),
                        fullReference.usfm,
                        chapter.copy(components = null),
                        filtered,
                        content?.attribution?.asSerializable(),
                    )
                )
            }
            return chapters
        }
    }
}
