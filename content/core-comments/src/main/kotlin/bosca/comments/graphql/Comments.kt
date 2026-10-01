package bosca.comments.graphql

import bosca.comments.model.Comment

/**
 * A page of comments plus the total count — the long-standing `Comments` GraphQL
 * shape that both `Metadata.comments` and `Comment.replies` return. [comments]
 * is the requested page (newest first); [count] is the total visible to the
 * caller. Built by the resolvers in the content modules from [CommentService].
 */
data class Comments(
    val comments: List<Comment>,
    val count: Int,
)
