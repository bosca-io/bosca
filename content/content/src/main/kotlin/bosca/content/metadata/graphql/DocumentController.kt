package bosca.content.metadata.graphql

import bosca.content.metadata.model.Document
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

@TypeController
class DocumentController(
    val service: MetadataService,
    val permissions: MetadataPermissionEvaluator,
    val json: Json
) : GraphQLController<Document> {

    @Field
    fun title(document: Document) = document.title

    @Field
    fun content(document: Document) = json.encodeToJsonElement(document.content)

    @Field
    suspend fun template(authentication: AuthenticationContext?, document: Document) =
        document.templateMetadataId?.let { templateMetadataId ->
            val metadata = service.getById(templateMetadataId, document.templateMetadataVersion ?: 1)
            metadata?.takeIf { permissions.isAllowed(authentication, it, PermissionAction.VIEW) }
        }
}