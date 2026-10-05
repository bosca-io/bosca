package bosca.source.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.source.model.Source
import bosca.source.model.SourceInput
import bosca.source.service.SourceService

object SourceMutation

@TypeController
class SourceMutationController(
    private val service: SourceService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<SourceMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, source: SourceInput): Source {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.add(source)
    }

    @Field
    suspend fun edit(authentication: AuthenticationContext, id: UUID, source: SourceInput): Source {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.edit(id, source)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        service.delete(id)
        return true
    }
}
