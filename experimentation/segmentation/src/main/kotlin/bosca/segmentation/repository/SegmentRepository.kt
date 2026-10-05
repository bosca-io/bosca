package bosca.segmentation.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.segmentation.model.Segment
import bosca.segmentation.model.SegmentStatus
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

@Repository
interface SegmentRepository {

    @Query("select * from segmentation.segments order by created desc limit :limit offset :offset")
    suspend fun getAll(offset: Long, limit: Int): List<Segment>

    @Query("select * from segmentation.segments where id = :id")
    suspend fun getById(id: UUID): Segment?

    @Query("select * from segmentation.segments where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<Segment>

    @Query("""
        insert into segmentation.segments (name, description, type, status, analytics_query_id, configuration, scheduled_job_id)
        values (:name, :description, (:type)::segmentation.segment_type, (:status)::segmentation.segment_status, :analyticsQueryId, :configuration::jsonb, :scheduledJobId)
        returning *
    """)
    suspend fun add(segment: Segment): Segment

    @Query("""
        update segmentation.segments
        set name = :name, description = :description, type = (:type)::segmentation.segment_type,
            status = (:status)::segmentation.segment_status, analytics_query_id = :analyticsQueryId,
            configuration = :configuration::jsonb, scheduled_job_id = :scheduledJobId, modified = now()
        where id = :id
        returning *
    """)
    suspend fun update(segment: Segment): Segment

    @Query("update segmentation.segments set status = (:status)::segmentation.segment_status, modified = now() where id = :id returning *")
    suspend fun updateStatus(id: UUID, status: SegmentStatus): Segment

    @Query("update segmentation.segments set scheduled_job_id = :scheduledJobId, modified = now() where id = :id returning *")
    suspend fun updateScheduledJobId(id: UUID, scheduledJobId: UUID?): Segment

    @Query("update segmentation.segments set last_evaluated = :lastEvaluated, member_count = :memberCount, modified = now() where id = :id returning *")
    suspend fun updateEvaluation(id: UUID, lastEvaluated: OffsetDateTime, memberCount: Long): Segment

    @Query("delete from segmentation.segments where id = :id")
    suspend fun deleteById(id: UUID)
}
