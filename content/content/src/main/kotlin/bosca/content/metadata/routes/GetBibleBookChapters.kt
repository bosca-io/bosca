package bosca.content.metadata.routes

import bosca.content.metadata.model.BibleChapter
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.routes.annotations.RouteController
import kotlinx.serialization.builtins.ListSerializer
import bosca.security.service.AuthenticationContext
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer

@RouteController("/api/v1/content/metadata/bible/{id}/books/{usfm}")
class GetBibleBookChapters(
    metadataService: MetadataService,
    metadataPermissionEvaluator: MetadataPermissionEvaluator,
    bibleService: BibleService,
) : BaseBibleRoute<List<BibleChapter>>(metadataService, metadataPermissionEvaluator, bibleService) {

    override fun serializer(): KSerializer<List<BibleChapter>> = ListSerializer(BibleChapter.serializer())

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): List<BibleChapter> {
        val bible = call.getBible(authenticationContext)
        val books = bibleService.getBooks(bible)
        val usfm = call.pathParameters["usfm"]
        val book = books.find { it.usfm == usfm } ?: return emptyList()
        return bibleService.getChapters(book).map {
            it.copy(components = null)
        }
    }
}