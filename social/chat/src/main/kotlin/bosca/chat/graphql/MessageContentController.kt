package bosca.chat.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

@TypeController
class MessageContentController(
    private val metadataService: MetadataService,
    private val permissionEvaluator: MetadataPermissionEvaluator,
) : GraphQLController<MessageContent> {

    @Field
    fun type(content: MessageContent) = content.type

    @Field
    fun content(content: MessageContent) = content.content

    @Field
    fun attributes(content: MessageContent) = content.attributes

    @Field
    suspend fun metadata(authentication: AuthenticationContext?, content: MessageContent): Metadata? {
        val resolvable = content.type == MessageContentType.METADATA ||
            content.type == MessageContentType.IMAGE ||
            content.type == MessageContentType.VIDEO ||
            content.type == MessageContentType.AUDIO ||
            content.type == MessageContentType.FILE
        if (!resolvable) return null
        return try {
            val metadata = metadataService.getById(UUID.parse(content.content)) ?: return null
            if (!permissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)) return null
            metadata
        } catch (_: Exception) {
            null
        }
    }
}
