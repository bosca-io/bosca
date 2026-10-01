package bosca.content.healthcheck

import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.transition.model.BeginTransitionInput
import bosca.content.transition.service.Transitioner
import bosca.db.transaction
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/**
 * Marker object representing the ContentHealthCheckMutation GraphQL type.
 */
object ContentHealthCheckMutation

/**
 * GraphQL controller that provides mutation operations for resolving content
 * health check issues identified by the diagnostic queries.
 */
@TypeController
class ContentHealthCheckMutationController(
    private val metadataService: MetadataService,
    private val permissionEvaluator: MetadataPermissionEvaluator,
    private val transitioner: Transitioner,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<ContentHealthCheckMutation> {

    /**
     * Ensures all metadata related to the given parent are transitioned to the
     * published state and made fully public. Items that are not yet marked as
     * ready will be marked ready first. Uses the standard workflow transition
     * mechanism rather than directly setting state. Requires EDIT permission
     * on each related item.
     */
    @Field
    suspend fun resolveUnpublishedRelationships(
        authentication: AuthenticationContext,
        id: UUID
    ): Boolean {
        groupEvaluator.verifyHasEditorGroup(authentication)
        val parent = metadataService.getById(id) ?: error("Metadata not found: $id")
        permissionEvaluator.verifyAllowed(authentication, parent, PermissionAction.EDIT)
        val principal = authentication.principal()?.asPrincipal() ?: error("missing principal")
        transaction {
            val relationships = metadataService.getRelationships(id)
            for (relationship in relationships) {
                var related = metadataService.getById(relationship.metadataId2) ?: continue
                permissionEvaluator.verifyAllowed(authentication, related, PermissionAction.EDIT)
                if (related.workflowStateId != "published") {
                    if (related.ready == null) {
                        related = metadataService.setReady(related, principal)
                    }
                    transitioner.beginTransition(
                        authentication,
                        BeginTransitionInput(
                            metadataId = related.id,
                            version = related.version,
                            stateId = "published",
                            status = "resolved by health check",
                        ),
                        related
                    )
                }
                if (!related.public) {
                    metadataService.setPublic(related, true)
                }
                if (!related.publicContent) {
                    metadataService.setPublicContent(related, true)
                }
                if (!related.publicSupplementary && metadataService.getSupplementary(related.id).isNotEmpty()) {
                    metadataService.setPublicSupplementary(related, true)
                }
            }
        }
        return true
    }
}
