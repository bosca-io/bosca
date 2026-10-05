package bosca.content.metadata.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataAIService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.di.ObjectProvider
import bosca.di.annotation.ProviderName
import bosca.di.provide
import bosca.documents.DocumentNode
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
class MetadataAI(val metadata: Metadata)

@TypeController
class MetadataAIController(
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val metadataAIService: MetadataAIService,
) : GraphQLController<MetadataAI> {

    @Field
    suspend fun description(authentication: AuthenticationContext, ai: MetadataAI, document: DocumentInput?): String? {
        if (!metadataPermissionEvaluator.isAllowed(authentication, ai.metadata, PermissionAction.EXECUTE)) {
            return null
        }
        return metadataAIService.description(ai.metadata, document)
    }

    @Field
    suspend fun topics(authentication: AuthenticationContext, ai: MetadataAI, document: DocumentInput?): List<Collection> {
        if (!metadataPermissionEvaluator.isAllowed(authentication, ai.metadata, PermissionAction.EXECUTE)) {
            return emptyList()
        }
        return metadataAIService.topics(ai.metadata, document)
    }

    @Field
    suspend fun readingTimeInMinutes(authentication: AuthenticationContext, ai: MetadataAI, document: DocumentInput?): Int {
        if (!metadataPermissionEvaluator.isAllowed(authentication, ai.metadata, PermissionAction.EXECUTE)) {
            return 0
        }
        return metadataAIService.readingTimeInMinutes(ai.metadata, document)
    }

    @Field
    suspend fun content(authentication: AuthenticationContext, ai: MetadataAI, document: DocumentInput?, type: String): JsonElement {
        if (!metadataPermissionEvaluator.isAllowed(authentication, ai.metadata, PermissionAction.EXECUTE)) {
            return JsonNull
        }
        TODO()
    }
}
