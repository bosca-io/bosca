package bosca.source.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.source.model.Source
import kotlinx.serialization.json.JsonElement


@TypeController
class SourceController(
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<Source> {

    @Field
    fun id(source: Source) = source.id

    @Field
    fun name(source: Source) = source.name

    @Field
    fun description(source: Source) = source.description

    @Field
    fun configuration(authentication: AuthenticationContext, source: Source): JsonElement {
        groupEvaluator.verifyHasSaGroup(authentication)
        return source.configuration
    }
}
