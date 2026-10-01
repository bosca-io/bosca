package bosca.comments.graphql

import bosca.comments.model.Comment
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Field wiring for the [Comments] page wrapper (`comments` + `count`). */
@TypeController
class CommentsController : GraphQLController<Comments> {

    @Field
    fun comments(data: Comments): List<Comment> = data.comments

    @Field
    fun count(data: Comments): Int = data.count
}
