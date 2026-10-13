package bosca.content.metadata.routes

import bosca.bible.BibleContentUse
import bosca.bible.Reference
import bosca.bible.components.filter
import bosca.content.metadata.model.toJson
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.routes.annotations.RouteController
import bosca.security.service.AuthenticationContext
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull

@RouteController("/api/v1/content/metadata/bible/contents")
class GetBibleLanguageVerses(
    metadataService: MetadataService,
    metadataPermissionEvaluator: MetadataPermissionEvaluator,
    bibleService: BibleService,
) : BaseBibleRoute<List<ChapterContent>>(metadataService, metadataPermissionEvaluator, bibleService) {

    override fun serializer(): KSerializer<List<ChapterContent>> = ListSerializer(ChapterContent.serializer())

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): List<ChapterContent> {
        val bible = call.getBibleByLanguage(authenticationContext)
        val reference = Reference(call.request.queryParameters["usfm"]?.replace(' ', '+') ?: error("missing usfm"))
        val mapping = reference.references.groupBy { it.chapterUsfm }
        val chapters = mutableListOf<ChapterContent>()
        val books = bibleService.getBooks(bible).associateBy { it.usfm }
        for ((chapterUsfm, references) in mapping) {
            val chapter = bibleService.getChapter(bible, Reference(chapterUsfm))
            val content = bibleService.getChapterContent(bible, Reference(chapterUsfm), BibleContentUse.DISPLAY)
            val components = content.chapter[content.chapter.reference] ?: continue
            val filtered = references.map { components.filter(it)?.toJson() ?: JsonNull }
            val fullReference = Reference(references.joinToString("+") { it.usfm })
            val book = books.getValue(fullReference.bookUsfm)
            chapters.add(
                ChapterContent(
                    fullReference.toHuman(book.nameShort ?: book.nameLong ?: book.abbreviation),
                    fullReference.toHuman(book.nameLong ?: book.abbreviation),
                    fullReference.usfm,
                    chapter.copy(components = null),
                    filtered,
                    content.attribution?.asSerializable(),
                )
            )
        }
        return chapters
    }
}
