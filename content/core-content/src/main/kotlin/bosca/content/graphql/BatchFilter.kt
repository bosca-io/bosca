package bosca.content.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.BatchFilter
import bosca.graphql.BatchItem
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

class MetadataBatchFilter(
    private val authentication: AuthenticationContext,
    private val permissionEvaluator: MetadataPermissionEvaluator,
) : BatchFilter<MetadataCacheKeyId, Metadata> {
    override suspend fun filter(items: List<BatchItem<MetadataCacheKeyId, Metadata>>): List<Metadata?> {
        val metadata = items.mapNotNull { it.data }
        val allowed = permissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)
        val allowedMap = metadata.mapIndexed { index, m -> m.id to allowed[index] }.toMap()
        return items.map { item ->
            item.data?.takeIf { allowedMap[it.id] ?: false }
        }
    }
}