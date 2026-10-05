package bosca.content.metadata.graphql

import bosca.bible.bibleJson
import bosca.bible.components.IComponent
import bosca.bible.components.findVerses
import bosca.content.metadata.model.BibleBookChapter
import bosca.content.metadata.model.BibleReference
import bosca.content.metadata.service.BibleService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement


@TypeController("BibleChapter")
class BibleChapterController(
    private val bibleService: BibleService
) : GraphQLController<BibleBookChapter> {

    @Field
    fun reference(chapter: BibleBookChapter): BibleReference {
        return BibleReference( // TODO
            usfm = chapter.chapter.usfm,
            human = "${chapter.book.nameLong} ${chapter.chapter.usfm.split('.').last()}",
            humanShort = "${chapter.book.nameShort} ${chapter.chapter.usfm.split('.').last()}"
        )
    }

    @Field
    suspend fun component(chapter: BibleBookChapter): JsonElement? {
        return bibleService.getChapter(chapter.book, chapter.chapter.usfm).components
    }

    @Field
    fun verses(chapter: BibleBookChapter): List<BibleReference> {
        val components = chapter.chapter.components?.let {
            bibleJson.decodeFromJsonElement<IComponent>(it)
        } ?: return emptyList()
        val chapterHumanLong = "${chapter.book.nameLong} ${chapter.chapter.usfm.split('.').last()}"
        val chapterHumanShort = "${chapter.book.nameShort} ${chapter.chapter.usfm.split('.').last()}"
        return components.findVerses().map {
            BibleReference(
                usfm = it.usfm,
                human = "$chapterHumanLong:${it.number}",
                humanShort = "$chapterHumanShort:${it.number}",
            )
        }
    }
}