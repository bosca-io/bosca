package bosca.content.metadata.graphql

import bosca.content.metadata.model.BibleLanguage
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController


@TypeController
class BibleLanguageController : GraphQLController<BibleLanguage> {

    @Field
    fun iso(language: BibleLanguage) = language.iso

    @Field
    fun name(language: BibleLanguage) = language.name

    @Field
    fun nameLocal(language: BibleLanguage) = language.nameLocal

    @Field
    fun script(language: BibleLanguage) = language.script

    @Field
    fun scriptCode(language: BibleLanguage) = language.scriptCode

    @Field
    fun scriptDirection(language: BibleLanguage) = language.scriptDirection
}