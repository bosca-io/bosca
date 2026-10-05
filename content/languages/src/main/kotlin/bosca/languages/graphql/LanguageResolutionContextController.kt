package bosca.languages.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.languages.model.LanguageResolutionContext
import bosca.languages.service.LanguagesService

@TypeController
class LanguageResolutionContextController(
    private val service: LanguagesService,
) : GraphQLController<LanguageResolutionContext> {

    @Field
    fun id(context: LanguageResolutionContext) = context.id

    @Field
    fun key(context: LanguageResolutionContext) = context.key

    @Field
    fun name(context: LanguageResolutionContext) = context.name

    @Field
    fun description(context: LanguageResolutionContext) = context.description

    @Field
    fun fallbackLanguageTag(context: LanguageResolutionContext) = context.fallbackLanguageTag

    @Field
    suspend fun mappings(context: LanguageResolutionContext) = service.getLanguageTagMappings(context.id)
}
