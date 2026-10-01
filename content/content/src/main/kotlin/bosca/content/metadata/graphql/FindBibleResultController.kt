package bosca.content.metadata.graphql

import bosca.content.metadata.model.BibleBookChapter
import bosca.content.metadata.model.BibleReference
import bosca.content.metadata.model.FindBibleResult
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController


@TypeController
class FindBibleResultController : GraphQLController<FindBibleResult> {

    @Field
    fun book(findResult: FindBibleResult) = findResult.book

    @Field
    fun chapter(findResult: FindBibleResult): BibleBookChapter? = findResult.chapter?.let { BibleBookChapter(findResult.book, it) }

    @Field
    fun component(findResult: FindBibleResult) = findResult.component

    @Field
    fun reference(findResult: FindBibleResult) = BibleReference(findResult.reference.usfm, findResult.human, findResult.humanShort)
}