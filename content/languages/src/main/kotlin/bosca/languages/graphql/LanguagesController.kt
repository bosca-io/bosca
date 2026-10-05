package bosca.languages.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.languages.service.LanguagesService

object Languages

@TypeController
class LanguagesController(
    private val service: LanguagesService
) : GraphQLController<Languages> {

    @Field
    suspend fun all() = service.getAll()

    @Field
    suspend fun resolutionContexts() = service.getResolutionContexts()

    @Field
    suspend fun resolve(contextKey: String, languageTag: String?) = service.resolveLanguageTag(contextKey, languageTag)
}
