package bosca.content.metadata.graphql

import bosca.bible.IBibleAudioTimecode
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController("BibleAudioTimecode")
class BibleAudioTimecodeController : GraphQLController<IBibleAudioTimecode> {
    @Field fun usfm(timecode: IBibleAudioTimecode) = timecode.usfm
    @Field fun start(timecode: IBibleAudioTimecode) = timecode.start
    @Field fun end(timecode: IBibleAudioTimecode) = timecode.end
}
