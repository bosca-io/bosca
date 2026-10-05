package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.attachment.Attachment

@Repository
interface AttachmentRepository {

    @Query("select * from workops.attachment where id = :id and deleted_at is null")
    suspend fun getById(id: UUID): Attachment?

    @Query(
        """
        select * from workops.attachment
        where task_id = :taskId and deleted_at is null
        order by uploaded_at desc
        """
    )
    suspend fun listForTask(taskId: UUID): List<Attachment>

    @Query(
        """
        insert into workops.attachment
            (task_id, storage_object_id, filename, content_type, size_bytes,
             uploaded_by_profile_id, description)
        values
            (:taskId, :storageObjectId, :filename, :contentType, :sizeBytes,
             :uploadedByProfileId, :description)
        returning *
        """
    )
    suspend fun add(input: AttachmentInsertParams): Attachment

    @Query(
        """
        update workops.attachment set deleted_at = now()
        where id = :id and deleted_at is null
        returning *
        """
    )
    suspend fun softDelete(id: UUID): Attachment?

    @Query(
        """
        select coalesce(sum(size_bytes), 0)::bigint
        from workops.attachment
        where task_id = :taskId and deleted_at is null
        """
    )
    suspend fun totalForTask(taskId: UUID): Long
}

data class AttachmentInsertParams(
    val taskId: UUID,
    val storageObjectId: UUID?,
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val uploadedByProfileId: UUID,
    val description: String?,
)

@Repository
interface ProjectAttachmentLimitsRepository {

    @Query(
        """
        select max_attachment_bytes::bigint        as max_attachment_bytes,
               max_total_attachment_bytes::bigint  as max_total_attachment_bytes,
               attachment_content_type_denylist    as denylist
        from workops.project
        where id = :projectId
        """
    )
    suspend fun get(projectId: UUID): ProjectAttachmentLimits?
}

data class ProjectAttachmentLimits(
    @bosca.db.annotation.ColumnName("max_attachment_bytes")
    val maxAttachmentBytes: Long,
    @bosca.db.annotation.ColumnName("max_total_attachment_bytes")
    val maxTotalAttachmentBytes: Long,
    val denylist: List<String>,
)
