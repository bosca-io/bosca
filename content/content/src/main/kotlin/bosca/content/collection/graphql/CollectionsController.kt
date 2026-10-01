package bosca.content.collection.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.find.FindQueryInput
import bosca.content.metadata.graphql.CollectionTemplates
import bosca.content.security.CollectionPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

object Collections

@TypeController
class CollectionsController(
    private val service: CollectionService,
    private val permissions: CollectionPermissionEvaluator
) : GraphQLController<Collections> {

    @Field
    fun templates() = CollectionTemplates

    @Field
    suspend fun find(authentication: AuthenticationContext?, query: FindQueryInput): List<Collection> {
        val collections = service.find(query)
        return collections.filter {
            permissions.isAllowed(authentication, it, PermissionAction.VIEW)
        }
    }

    @Field
    suspend fun findBySystem(authentication: AuthenticationContext?, query: FindQueryInput): List<Collection> {
        val collections = service.findBySystem(query)
        return collections.filter {
            permissions.isAllowed(authentication, it, PermissionAction.VIEW)
        }
    }

    @Field
    suspend fun findCount(query: FindQueryInput): Long {
        return service.findCount(query)
    }

    @Field
    suspend fun root(authentication: AuthenticationContext?) = collection(authentication, UUID.NIL)

    @Field
    suspend fun collection(authentication: AuthenticationContext?, id: UUID): Collection? {
        val collection = service.getById(id) ?: return null
        if (!permissions.isAllowed(authentication, collection, PermissionAction.VIEW)) return null
        return collection
    }

}
