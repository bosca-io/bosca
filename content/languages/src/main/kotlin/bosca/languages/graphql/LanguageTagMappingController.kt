package bosca.languages.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.languages.model.LanguageTagMapping

@TypeController
class LanguageTagMappingController : GraphQLController<LanguageTagMapping> {

    @Field
    fun contextId(mapping: LanguageTagMapping) = mapping.contextId

    @Field
    fun sourceLanguageTag(mapping: LanguageTagMapping) = mapping.sourceLanguageTag

    @Field
    fun resolvedLanguageTag(mapping: LanguageTagMapping) = mapping.resolvedLanguageTag
}
