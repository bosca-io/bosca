package bosca.source.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.source.model.Source
import bosca.source.service.SourceService

object Sources

@TypeController
class SourcesController(
    private val service: SourceService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<Sources> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<Source> {
        groupEvaluator.verifyHasEditorGroup(authentication)
        return service.getAll()
    }

    @Field
    suspend fun source(authentication: AuthenticationContext, id: UUID): Source {
        groupEvaluator.verifyHasEditorGroup(authentication)
        return service.getById(id)
    }
}
