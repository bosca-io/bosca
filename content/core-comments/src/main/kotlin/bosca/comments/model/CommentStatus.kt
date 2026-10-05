package bosca.comments.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

@DbMapper(CommentStatusMapper::class)
@Serializable
enum class CommentStatus {
    PENDING,
    BLOCKED,
    PENDING_APPROVAL,
    APPROVED,
}

object CommentStatusMapper : EnumMapper<CommentStatus>({ CommentStatus.valueOf(it.uppercase()) })