package bosca.content.metadata.graphql

import bosca.bible.IBibleAudioContent
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController("BibleAudioContent")
class BibleAudioContentController : GraphQLController<IBibleAudioContent> {
    @Field fun contentType(content: IBibleAudioContent) = content.contentType
    @Field fun contentLength(content: IBibleAudioContent) = content.contentLength
    @Field fun timecodes(content: IBibleAudioContent) = content.timecodes
}
