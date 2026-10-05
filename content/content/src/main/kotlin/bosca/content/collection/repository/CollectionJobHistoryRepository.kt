package bosca.content.collection.repository

import bosca.content.collection.model.CollectionJobHistory
import bosca.content.transition.model.JobHistoryId
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface CollectionJobHistoryRepository {

    @Query("select job_name as name, job_id as id from collection_job_history where id = :id order by created desc limit 1")
    suspend fun getLatestJobId(id: UUID): JobHistoryId?

    @Query("select * from collection_job_history where id = :id and job_id = :jobId")
    suspend fun getJob(id: UUID, jobId: UUID): CollectionJobHistory?

    @Query("insert into collection_job_history (id, job_name, job_id, status, principal, language_tag, delayed_until) values (:id, :jobName, :jobId, :status, :principal, :languageTag, :delayedUntil) returning *")
    suspend fun addHistory(history: CollectionJobHistory): CollectionJobHistory

    @Query("select * from collection_job_history where id = :id order by created desc")
    suspend fun getHistory(id: UUID): List<CollectionJobHistory>

    @Query("update collection_job_history set status = :status where id = :id and job_id = :jobId returning *")
    suspend fun setStatus(id: UUID, jobId: UUID, status: String): CollectionJobHistory?

    @Query("update collection_job_history set status = :status, success = :success, complete = now() where id = :id and job_id = :jobId returning *")
    suspend fun setComplete(id: UUID, jobId: UUID, status: String, success: Boolean): CollectionJobHistory?

    @Query("select * from collection_job_history where id = :id and complete is null order by created desc")
    suspend fun getActiveJobs(id: UUID): List<CollectionJobHistory>

    @Query("select * from collection_job_history where job_id = :jobId and complete is null limit 1")
    suspend fun getActiveJobByJobId(jobId: UUID): CollectionJobHistory?
}
