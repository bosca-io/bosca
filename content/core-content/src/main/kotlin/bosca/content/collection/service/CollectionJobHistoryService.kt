package bosca.content.collection.service

import bosca.content.collection.model.CollectionJobHistory
import bosca.content.transition.model.JobHistoryId
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for tracking the execution history of jobs associated with collections.
 * Provides operations to record, query, and await completion of collection-level workflow jobs.
 */
interface CollectionJobHistoryService : Service {

    /**
     * Retrieves the identifier of the most recently created job for a given collection.
     *
     * @param id the collection identifier
     * @return the latest job history identifier, or null if no jobs exist for this collection
     */
    suspend fun getLatestJobId(id: UUID): JobHistoryId?

    /**
     * Records a new job history entry for a collection.
     *
     * @param history the job history entry to persist
     * @return the persisted job history entry
     */
    suspend fun addHistory(history: CollectionJobHistory): CollectionJobHistory

    /**
     * Retrieves the complete job execution history for a given collection.
     *
     * @param id the collection identifier
     * @return the list of all job history entries for this collection
     */
    suspend fun getHistory(id: UUID): List<CollectionJobHistory>

    /**
     * Updates the status message of an active job without marking it complete.
     * Typically called when a job transitions from queued to running.
     *
     * @param id the collection identifier
     * @param jobId the job execution identifier
     * @param status the new status message
     */
    suspend fun setStatus(id: UUID, jobId: UUID, status: String)

    /**
     * Marks a specific job execution as complete, recording its final status and outcome.
     *
     * @param id the collection identifier
     * @param jobId the job execution identifier
     * @param status the final status message describing the outcome
     * @param success whether the job completed successfully
     */
    suspend fun setComplete(id: UUID, jobId: UUID, status: String, success: Boolean)

    /**
     * Suspends until a specific job execution completes, then returns its final history entry.
     * This is a blocking-style suspend call that polls or awaits the job's completion.
     *
     * @param id the collection identifier
     * @param jobId the job execution identifier to wait on
     * @return the completed job history entry
     */
    suspend fun waitForComplete(id: UUID, jobId: UUID): CollectionJobHistory

    /**
     * Retrieves all currently active (incomplete) jobs for a given collection.
     * Active jobs are those that have not yet completed, including jobs that are
     * pending, running, or scheduled for future execution.
     *
     * @param id the collection identifier
     * @return the list of all incomplete job history entries for this collection
     */
    suspend fun getActiveJobs(id: UUID): List<CollectionJobHistory>
}
