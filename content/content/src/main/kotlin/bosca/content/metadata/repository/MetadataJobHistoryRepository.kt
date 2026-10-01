package bosca.content.metadata.repository

import bosca.content.metadata.model.MetadataJobHistory
import bosca.content.transition.model.JobHistoryId
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface MetadataJobHistoryRepository {

    @Query("select job_name as name, job_id as id from metadata_job_history where id = :id and version = :version order by created desc limit 1")
    suspend fun getLatestJobId(id: UUID, version: Int): JobHistoryId?

    @Query("select * from metadata_job_history where id = :id and version = :version and job_id = :jobId")
    suspend fun getJob(id: UUID, version: Int, jobId: UUID): MetadataJobHistory?

    @Query("insert into metadata_job_history (id, version, job_name, job_id, status, principal, delayed_until) values (:id, :version, :jobName, :jobId, :status, :principal, :delayedUntil) returning *")
    suspend fun addHistory(history: MetadataJobHistory): MetadataJobHistory

    @Query("select * from metadata_job_history where id = :id and version = :version order by created desc")
    suspend fun getHistory(id: UUID, version: Int): List<MetadataJobHistory>

    @Query("update metadata_job_history set status = :status where id = :id and version = :version and job_id = :jobId returning *")
    suspend fun setStatus(id: UUID, version: Int, jobId: UUID, status: String): MetadataJobHistory?

    @Query("update metadata_job_history set status = :status, success = :success, complete = now() where id = :id and version = :version and job_id = :jobId returning *")
    suspend fun setComplete(id: UUID, version: Int, jobId: UUID, status: String, success: Boolean): MetadataJobHistory?

    @Query("select * from metadata_job_history where id = :id and version = :version and complete is null order by created desc")
    suspend fun getActiveJobs(id: UUID, version: Int): List<MetadataJobHistory>

    @Query("select * from metadata_job_history where job_id = :jobId and complete is null limit 1")
    suspend fun getActiveJobByJobId(jobId: UUID): MetadataJobHistory?
}
