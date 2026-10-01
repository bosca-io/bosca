package bosca.comments

/**
 * The targeted comment was not found.
 */
class CommentNotFoundException(val commentId: Long) :
    RuntimeException("Comment not found: $commentId")

/**
 * The reply parent does not belong to the same thread as the new comment.
 */
class CommentThreadMismatchException(val parentId: Long, val reason: String) :
    RuntimeException("Parent comment $parentId: $reason")

/**
 * The like row for the given profile did not exist when attempting to remove a like.
 */
class CommentLikeNotFoundException(val commentId: Long, val profileId: bosca.serialization.UUID) :
    RuntimeException("Like row for profile $profileId on comment $commentId not found")
