package bosca.content.metadata.graphql

import bosca.bible.providers.model.IBibleCatalog
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController("BibleCatalog")
class BibleCatalogController : GraphQLController<IBibleCatalog> {

    @Field
    fun id(value: IBibleCatalog) = value.id

    @Field
    fun systemId(value: IBibleCatalog) = value.systemId

    @Field
    fun kind(value: IBibleCatalog) = value.kind

    @Field
    fun name(value: IBibleCatalog) = value.name

    @Field
    fun nameLocal(value: IBibleCatalog) = value.nameLocal

    @Field
    fun abbreviation(value: IBibleCatalog) = value.abbreviation

    @Field
    fun abbreviationLocal(value: IBibleCatalog) = value.abbreviationLocal

    @Field
    fun description(value: IBibleCatalog) = value.description

    @Field
    fun languages(value: IBibleCatalog) = value.languages

    @Field
    fun attribution(value: IBibleCatalog) = value.attribution

    @Field
    fun relatedSystemId(value: IBibleCatalog) = value.relatedSystemId
}
