package bosca.content.metadata.graphql

import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.model.BibleBookChapter
import bosca.content.metadata.model.BibleReference
import bosca.content.metadata.service.BibleService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController


@TypeController
class BibleBookController(
    private val bibleService: BibleService
) : GraphQLController<BibleBook> {

    @Field
    fun abbreviation(book: BibleBook) = book.abbreviation

    @Field
    fun nameShort(book: BibleBook) = book.nameShort

    @Field
    fun nameLong(book: BibleBook) = book.nameLong

    @Field
    fun reference(book: BibleBook) = BibleReference(
        usfm = book.usfm,
        human = book.nameLong ?: "",
        humanShort = book.nameShort ?: ""
    )

    @Field
    suspend fun chapters(book: BibleBook) = bibleService.getChapters(book).map {
        BibleBookChapter(book, it)
    }
}