package bosca.recommendations.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.recommendations.model.PersonalizationSignalDefinition
import bosca.recommendations.service.PersonalizationSignalService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object PersonalizationSignals

/**
 * Admin-only GraphQL query fields for listing and inspecting Personalization Signal definitions —
 * the config that tells the recommender which profile attributes / segments personalize results.
 */
@TypeController
class PersonalizationSignalsController(
    private val service: PersonalizationSignalService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<PersonalizationSignals> {

    @Field
    suspend fun all(authentication: AuthenticationContext, offset: Long, limit: Int): List<PersonalizationSignalDefinition> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.getAll(maxOf(offset, 0), limit.coerceIn(1, 100))
    }

    @Field
    suspend fun signal(authentication: AuthenticationContext, id: UUID): PersonalizationSignalDefinition? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.getById(id)
    }
}
