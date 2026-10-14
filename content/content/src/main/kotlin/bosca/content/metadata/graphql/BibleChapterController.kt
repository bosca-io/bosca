package bosca.content.metadata.graphql

import bosca.bible.BibleContentUse
import bosca.bible.Reference
import bosca.bible.components.findVerses
import bosca.content.metadata.model.BibleBookChapter
import bosca.content.metadata.model.BibleReference
import bosca.content.metadata.model.toJson
import bosca.content.metadata.service.BibleService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import kotlinx.serialization.json.JsonElement


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
        val content = bibleService.getChapterContent(chapter.book, chapter.chapter.usfm, BibleContentUse.DISPLAY)
        return content.chapter[Reference(chapter.chapter.usfm)]?.toJson()
    }

    @Field
    suspend fun attribution(chapter: BibleBookChapter) =
        bibleService.getChapterContent(chapter.book, chapter.chapter.usfm, BibleContentUse.DISPLAY).attribution

    @Field
    suspend fun verses(chapter: BibleBookChapter): List<BibleReference> {
        val content = bibleService.getChapterContent(chapter.book, chapter.chapter.usfm, BibleContentUse.DISPLAY)
        val components = content.chapter[Reference(chapter.chapter.usfm)] ?: return emptyList()
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
