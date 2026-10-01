package bosca.content.metadata.service

import bosca.content.metadata.model.MetadataJobHistory
import bosca.content.transition.model.JobHistoryId
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for tracking the execution history of jobs associated with metadata entries.
 * Provides operations to record, query, and await completion of metadata-level workflow jobs.
 * Unlike [CollectionJobHistoryService], this service tracks jobs scoped to a specific
 * metadata version.
 */
interface MetadataJobHistoryService : Service {

    /**
     * Retrieves the identifier of the most recently created job for a given metadata version.
     *
     * @param id the metadata identifier
     * @param version the metadata version number
     * @return the latest job history identifier, or null if no jobs exist for this metadata version
     */
    suspend fun getLatestJobId(id: UUID, version: Int): JobHistoryId?

    /**
     * Records a new job history entry for a metadata version.
     *
     * @param history the job history entry to persist
     * @return the persisted job history entry
     */
    suspend fun addHistory(history: MetadataJobHistory): MetadataJobHistory

    /**
     * Retrieves the complete job execution history for a given metadata version.
     *
     * @param id the metadata identifier
     * @param version the metadata version number
     * @return the list of all job history entries for this metadata version
     */
    suspend fun getHistory(id: UUID, version: Int): List<MetadataJobHistory>

    /**
     * Updates the status message of an active job without marking it complete.
     * Typically called when a job transitions from queued to running.
     *
     * @param id the metadata identifier
     * @param version the metadata version number
     * @param jobId the job execution identifier
     * @param status the new status message
     */
    suspend fun setStatus(id: UUID, version: Int, jobId: UUID, status: String)

    /**
     * Marks a specific job execution as complete, recording its final status and outcome.
     *
     * @param id the metadata identifier
     * @param version the metadata version number
     * @param jobId the job execution identifier
     * @param status the final status message describing the outcome
     * @param success whether the job completed successfully
     */
    suspend fun setComplete(id: UUID, version: Int, jobId: UUID, status: String, success: Boolean)

    /**
     * Suspends until a specific job execution completes, then returns its final history entry.
     * This is a blocking-style suspend call that polls or awaits the job's completion.
     *
     * @param id the metadata identifier
     * @param version the metadata version number
     * @param jobId the job execution identifier to wait on
     * @return the completed job history entry
     */
    suspend fun waitForComplete(id: UUID, version: Int, jobId: UUID): MetadataJobHistory

    /**
     * Retrieves all currently active (incomplete) jobs for a given metadata version.
     * Active jobs are those that have not yet completed, including jobs that are
     * pending, running, or scheduled for future execution.
     *
     * @param id the metadata identifier
     * @param version the metadata version number
     * @return the list of all incomplete job history entries for this metadata version
     */
    suspend fun getActiveJobs(id: UUID, version: Int): List<MetadataJobHistory>
}