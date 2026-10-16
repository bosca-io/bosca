package bosca.content.metadata.graphql

import bosca.bible.IBibleAudio
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController("BibleAudio")
class BibleAudioController : GraphQLController<IBibleAudio> {

    @Field
    fun id(value: IBibleAudio) = value.id

    @Field
    fun name(value: IBibleAudio) = value.name

    @Field
    fun nameLocal(value: IBibleAudio) = value.nameLocal

    @Field
    fun abbreviation(value: IBibleAudio) = value.abbreviation

    @Field
    fun attribution(value: IBibleAudio) = value.attribution
}
