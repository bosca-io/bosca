package bosca.recommendations.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.recommendations.model.PersonalizationSignalDefinition
import bosca.recommendations.model.PersonalizationSignalDefinitionInput
import bosca.recommendations.service.PersonalizationSignalService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object PersonalizationSignalsMutation

/**
 * Admin-only GraphQL mutations for the lifecycle of Personalization Signal definitions: creation,
 * editing (both validated — key uniqueness, JSONata parse, cohort/valueType rule), and deletion. The
 * service backfills the cached signals on already-persisted attributes when a definition changes.
 */
@TypeController
class PersonalizationSignalsMutationController(
    private val service: PersonalizationSignalService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<PersonalizationSignalsMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, signal: PersonalizationSignalDefinitionInput): PersonalizationSignalDefinition {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.add(signal)
    }

    @Field
    suspend fun edit(authentication: AuthenticationContext, id: UUID, signal: PersonalizationSignalDefinitionInput): PersonalizationSignalDefinition {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.edit(id, signal)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        service.delete(id)
        return true
    }
}
