package bosca.content.timeevent.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.timeevent.model.TimeEventMetadataRelationship
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

/**
 * GraphQL type controller that resolves fields on the [TimeEventMetadataRelationship]
 * type, including the linked metadata item.
 */
@TypeController
class TimeEventMetadataRelationshipController(
    private val metadataService: MetadataService,
    private val permissionEvaluator: MetadataPermissionEvaluator
) : GraphQLController<TimeEventMetadataRelationship> {

    @Field
    fun timeEventId(relationship: TimeEventMetadataRelationship) = relationship.timeEventId

    @Field
    fun metadataId(relationship: TimeEventMetadataRelationship) = relationship.metadataId

    @Field
    fun metadataVersion(relationship: TimeEventMetadataRelationship) = relationship.metadataVersion

    @Field
    fun relationship(relationship: TimeEventMetadataRelationship) = relationship.relationship

    @Field
    fun attributes(relationship: TimeEventMetadataRelationship) = relationship.attributes

    @Field
    suspend fun metadata(authentication: AuthenticationContext, relationship: TimeEventMetadataRelationship): Metadata {
        val metadata = metadataService.getById(relationship.metadataId, relationship.metadataVersion)
            ?: error("Metadata not found: ${relationship.metadataId}")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
        return metadata
    }
}
