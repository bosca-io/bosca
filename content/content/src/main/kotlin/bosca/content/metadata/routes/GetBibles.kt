package bosca.content.metadata.routes

import bosca.content.find.FindQueryInput
import bosca.content.metadata.model.Bible
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull

@RouteController("/api/v1/content/metadata/bibles")
class GetBibles(
    private val metadataService: MetadataService,
    private val bibleService: BibleService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
) : Route<List<Bible>>() {

    override fun serializer(): KSerializer<List<Bible>> = ListSerializer(Bible.serializer())

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): List<Bible> {
        val metadatas = metadataService.find(FindQueryInput(
            contentTypes = listOf("bosca/v-bible")
        ))
        val filtered = metadataPermissionEvaluator.filterAllowed(authenticationContext, metadatas, PermissionAction.VIEW)
        val bibles = filtered.mapNotNull {
            bibleService.getBible(it.id, it.version, null)?.copy(styles = JsonNull)
        }
        return bibles
    }
}