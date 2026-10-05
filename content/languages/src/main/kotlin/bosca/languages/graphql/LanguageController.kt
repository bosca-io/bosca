package bosca.languages.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.languages.model.Language

@TypeController
class LanguageController : GraphQLController<Language> {

    @Field
    fun tag(language: Language) = language.tag

    @Field
    fun name(language: Language) = language.name

    @Field
    fun localName(language: Language) = language.localName

    @Field
    fun attributes(language: Language) = language.attributes
}