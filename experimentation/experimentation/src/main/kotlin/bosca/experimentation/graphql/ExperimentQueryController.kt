package bosca.experimentation.graphql

import bosca.experimentation.model.Experiment
import bosca.experimentation.service.ExperimentService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object Experiments

@TypeController
class ExperimentQueryController(
    private val experimentService: ExperimentService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<Experiments> {

    @Field
    suspend fun all(authentication: AuthenticationContext, offset: Long, limit: Int): List<Experiment> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return experimentService.getAll(maxOf(offset, 0), limit.coerceIn(1, 100))
    }

    @Field
    suspend fun experiment(authentication: AuthenticationContext, id: UUID): Experiment? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return experimentService.getById(id)
    }

    @Field
    suspend fun byFlag(authentication: AuthenticationContext, flagId: UUID): List<Experiment> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return experimentService.getByFlagId(flagId)
    }
}
