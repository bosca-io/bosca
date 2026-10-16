package bosca.content.metadata.graphql

import bosca.bible.providers.model.BibleSource
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController("BibleSource")
class BibleSourceController : GraphQLController<BibleSource> {

    @Field
    fun id(value: BibleSource) = value.id

    @Field
    fun configurationId(value: BibleSource) = value.configurationId

    @Field
    fun provider(value: BibleSource) = value.provider

    @Field
    fun providerBibleId(value: BibleSource) = value.providerBibleId

    @Field
    fun metadataId(value: BibleSource) = value.metadataId

    @Field
    fun metadataVersion(value: BibleSource) = value.metadataVersion

    @Field
    fun variant(value: BibleSource) = value.variant

    @Field
    fun kind(value: BibleSource) = value.kind

    @Field
    fun name(value: BibleSource) = value.name

    @Field
    fun nameLocal(value: BibleSource) = value.nameLocal

    @Field
    fun abbreviation(value: BibleSource) = value.abbreviation

    @Field
    fun attribution(value: BibleSource) = value.attribution

    @Field
    fun allowedUses(value: BibleSource) = value.allowedUses.toList()

    @Field
    fun version(value: BibleSource) = value.version

    @Field
    fun created(value: BibleSource) = value.created

    @Field
    fun modified(value: BibleSource) = value.modified
}
