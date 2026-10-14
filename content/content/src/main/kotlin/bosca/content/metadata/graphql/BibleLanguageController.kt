package bosca.content.metadata.graphql

import bosca.bible.IBibleLanguage
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController


@TypeController("BibleLanguage")
class BibleLanguageController : GraphQLController<IBibleLanguage> {

    @Field
    fun iso(language: IBibleLanguage) = language.iso

    @Field
    fun name(language: IBibleLanguage) = language.name

    @Field
    fun nameLocal(language: IBibleLanguage) = language.nameLocal ?: language.name

    @Field
    fun script(language: IBibleLanguage) = language.script

    @Field
    fun scriptCode(language: IBibleLanguage) = language.scriptCode

    @Field
    fun scriptDirection(language: IBibleLanguage) = language.scriptDirection
}
