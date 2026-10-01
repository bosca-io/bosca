package bosca.community.repository

import bosca.community.model.PrayerComment
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface PrayerCommentRepository {

    @Query("select * from community.prayer_comments where id = :id")
    suspend fun getComment(id: Long): PrayerComment?

    @Query(
        """
        insert into community.prayer_comments (prayer_id, parent_id, profile_id, content, attributes, status)
        values (:prayerId, :parentId, :profileId, :content, :attributes, 'approved')
        returning *
        """
    )
    suspend fun addComment(prayerId: UUID, parentId: Long?, profileId: UUID, content: String, attributes: JsonElement?): PrayerComment

    @Query(
        """
        select * from community.prayer_comments
        where prayer_id = :prayerId and parent_id is null and not deleted
        order by created desc
        limit :limit offset :offset
        """
    )
    suspend fun getComments(prayerId: UUID, limit: Int, offset: Int): List<PrayerComment>

    @Query(
        """
        select * from community.prayer_comments
        where parent_id = :parentId and not deleted
        order by created asc
        limit :limit offset :offset
        """
    )
    suspend fun getReplies(parentId: Long, limit: Int, offset: Int): List<PrayerComment>

    @Query("select count(*) from community.prayer_comments where prayer_id = :prayerId and not deleted")
    suspend fun countComments(prayerId: UUID): Long

    @Query("update community.prayer_comments set deleted = true, modified = now() where id = :id")
    suspend fun deleteComment(id: Long)
}
