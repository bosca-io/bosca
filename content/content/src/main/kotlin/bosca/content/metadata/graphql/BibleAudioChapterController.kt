package bosca.content.metadata.graphql

import bosca.bible.IBibleAudioChapter
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController("BibleAudioChapter")
class BibleAudioChapterController : GraphQLController<IBibleAudioChapter> {
    @Field fun audio(chapter: IBibleAudioChapter) = chapter.audio
    @Field fun usfm(chapter: IBibleAudioChapter) = chapter.usfm
    @Field fun url(chapter: IBibleAudioChapter) = chapter.url
    @Field fun content(chapter: IBibleAudioChapter) = chapter.content
    @Field fun attribution(chapter: IBibleAudioChapter) = chapter.attribution
}
