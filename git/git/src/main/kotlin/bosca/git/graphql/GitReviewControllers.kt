package bosca.git.graphql

import bosca.git.model.Review
import bosca.git.model.ReviewComment
import bosca.git.model.ReviewStatus
import bosca.git.repository.ReviewCommentRepository
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/**
 * Resolves all fields on [GitReview], including the computed [comments] field
 * that requires a service call to load line-level review comments.
 */
@TypeController(type = "GitReview")
class GitReviewController(
    private val reviewCommentRepository: ReviewCommentRepository
) : GraphQLController<Review> {

    @Field
    fun id(source: Review): UUID = source.id

    @Field
    fun pullRequestId(source: Review): UUID = source.pullRequestId

    @Field
    fun reviewerId(source: Review): UUID = source.reviewerId

    @Field
    fun status(source: Review): ReviewStatus = source.status

    @Field
    fun body(source: Review): String? = source.body

    @Field
    fun dismissedAt(source: Review): OffsetDateTime? = source.dismissedAt

    @Field
    fun dismissReason(source: Review): String? = source.dismissReason

    @Field
    fun created(source: Review): OffsetDateTime = source.created

    @Field
    suspend fun comments(source: Review): List<ReviewComment> {
        return reviewCommentRepository.findByReview(source.id)
    }
}

/**
 * Resolves all fields on [GitReviewComment] representing a line-level comment
 * anchored to a specific position in a pull request diff.
 */
@TypeController(type = "GitReviewComment")
class GitReviewCommentController : GraphQLController<ReviewComment> {

    @Field
    fun id(source: ReviewComment): UUID = source.id

    @Field
    fun reviewId(source: ReviewComment): UUID = source.reviewId

    @Field
    fun pullRequestId(source: ReviewComment): UUID = source.pullRequestId

    @Field
    fun authorId(source: ReviewComment): UUID = source.authorId

    @Field
    fun filePath(source: ReviewComment): String = source.filePath

    @Field
    fun oldLineNumber(source: ReviewComment): Int? = source.oldLineNumber

    @Field
    fun newLineNumber(source: ReviewComment): Int? = source.newLineNumber

    @Field
    fun commitSha(source: ReviewComment): String = source.commitSha

    @Field
    fun content(source: ReviewComment): String = source.content

    @Field
    fun outdated(source: ReviewComment): Boolean = source.outdated

    @Field
    fun resolved(source: ReviewComment): Boolean = source.resolved

    @Field
    fun created(source: ReviewComment): OffsetDateTime = source.created

    @Field
    fun updated(source: ReviewComment): OffsetDateTime = source.updated
}
