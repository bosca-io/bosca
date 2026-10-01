package bosca.content.metadata.routes

import bosca.content.metadata.model.BibleChapter
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.routes.annotations.RouteController
import bosca.security.service.AuthenticationContext
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer

@RouteController("/api/v1/content/metadata/bible/{id}/books/{bookUsfm}/chapter/{chapterUsfm}")
class GetBibleBookChapter(
    metadataService: MetadataService,
    metadataPermissionEvaluator: MetadataPermissionEvaluator,
    bibleService: BibleService,
) : BaseBibleRoute<BibleChapter>(metadataService, metadataPermissionEvaluator, bibleService) {

    override fun serializer(): KSerializer<BibleChapter> = BibleChapter.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): BibleChapter? {
        val bible = call.getBible(authenticationContext)
        val books = bibleService.getBooks(bible)
        val bookUsfm = call.pathParameters["bookUsfm"].toString()
        val book = books.find { it.usfm == bookUsfm } ?: return null
        val chapterUsfm = call.pathParameters["chapterUsfm"].toString()
        return bibleService.getChapter(book, chapterUsfm)
    }
}