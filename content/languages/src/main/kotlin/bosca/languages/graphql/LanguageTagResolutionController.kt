package bosca.languages.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.languages.model.LanguageTagResolution

@TypeController
class LanguageTagResolutionController : GraphQLController<LanguageTagResolution> {

    @Field
    fun requestedLanguageTag(resolution: LanguageTagResolution) = resolution.requestedLanguageTag

    @Field
    fun normalizedLanguageTag(resolution: LanguageTagResolution) = resolution.normalizedLanguageTag

    @Field
    fun resolvedLanguageTag(resolution: LanguageTagResolution) = resolution.resolvedLanguageTag

    @Field
    fun usedFallback(resolution: LanguageTagResolution) = resolution.usedFallback
}
