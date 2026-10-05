package bosca.content.metadata.routes

import bosca.content.metadata.model.Bible
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.routes.annotations.RouteController
import bosca.security.service.AuthenticationContext
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer

@RouteController("/api/v1/content/metadata/bible/{id}")
class GetBible(
    metadataService: MetadataService,
    metadataPermissionEvaluator: MetadataPermissionEvaluator,
    bibleService: BibleService,
) : BaseBibleRoute<Bible>(metadataService, metadataPermissionEvaluator, bibleService) {

    override fun serializer(): KSerializer<Bible> = Bible.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): Bible {
        return call.getBible(authenticationContext)
    }
}