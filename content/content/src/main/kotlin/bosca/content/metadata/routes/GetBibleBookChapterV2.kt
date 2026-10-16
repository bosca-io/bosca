package bosca.content.metadata.routes

import bosca.bible.BibleChapterContent
import bosca.bible.BibleContentUse
import bosca.bible.bibleJson
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.routes.annotations.RouteController
import bosca.security.service.AuthenticationContext
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement

@RouteController("/api/v2/content/metadata/bible/{id}/books/{bookUsfm}/chapter/{chapterUsfm}")
class GetBibleBookChapterV2(
    metadataService: MetadataService,
    metadataPermissionEvaluator: MetadataPermissionEvaluator,
    bibleService: BibleService,
) : BaseBibleRoute<JsonElement>(metadataService, metadataPermissionEvaluator, bibleService) {

    override fun serializer(): KSerializer<JsonElement> = JsonElement.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): JsonElement? {
        val bible = call.getBible(authenticationContext)
        val books = bibleService.getBooks(bible)
        val bookUsfm = call.pathParameters["bookUsfm"].toString()
        val book = books.find { it.usfm == bookUsfm } ?: return null
        val chapterUsfm = call.pathParameters["chapterUsfm"].toString()
        val content = bibleService.getChapterContent(book, chapterUsfm, BibleContentUse.DISPLAY).asSerializable()
        return bibleJson.encodeToJsonElement(BibleChapterContent.serializer(), content)
    }
}
