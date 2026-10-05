package bosca.content.metadata.routes

import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.routes.annotations.RouteController
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import bosca.server.ServerCall

@RouteController("/api/v1/content/metadata/bible/{id}/books")
class GetBibleBooks(
    metadataService: MetadataService,
    metadataPermissionEvaluator: MetadataPermissionEvaluator,
    bibleService: BibleService,
) : BaseBibleRoute<List<BibleBook>>(metadataService, metadataPermissionEvaluator, bibleService) {

    override fun serializer(): KSerializer<List<BibleBook>> = ListSerializer(BibleBook.serializer())

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): List<BibleBook> {
        val bible = call.getBible(authenticationContext)
        return bibleService.getBooks(bible)
    }
}