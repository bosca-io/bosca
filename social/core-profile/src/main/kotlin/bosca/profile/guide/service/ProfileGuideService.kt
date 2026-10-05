package bosca.profile.guide.service

import bosca.profile.guide.model.GuideProgressStatistics
import bosca.profile.guide.model.ProfileGuideHistory
import bosca.profile.guide.model.ProfileGuideProgress
import bosca.serialization.UUID
import bosca.service.Service
import kotlinx.serialization.json.JsonElement

/**
 * Service for tracking a profile's progress through guided content (e.g., tutorials,
 * walkthroughs, or multi-step workflows).
 *
 * Guide progress is tracked per profile per metadata item (at a specific version). Each
 * guide consists of numbered steps, and the service records which steps have been completed.
 * A history log captures each step-completion event for auditing and analytics.
 */
interface ProfileGuideService : Service {

    /**
     * Retrieves a paginated list of all guide progress records for a profile, across
     * all guides the profile has interacted with.
     *
     * @param profileId the UUID of the profile
     * @param limit the maximum number of progress records to return (defaults to 10)
     * @param offset the number of records to skip for pagination (defaults to 0)
     * @return the list of [ProfileGuideProgress] records for the profile
     */
    suspend fun getAllProgress(profileId: UUID, limit: Int = 10, offset: Long = 0): List<ProfileGuideProgress>

    /**
     * Retrieves a paginated list of active progress records for one guide across all of its versions.
     *
     * @param profileId the UUID of the profile
     * @param metadataId the UUID of the guide's metadata item
     * @param limit the maximum number of progress records to return
     * @param offset the number of records to skip for pagination
     * @return the matching [ProfileGuideProgress] records ordered by most recent activity
     */
    suspend fun getAllProgress(
        profileId: UUID,
        metadataId: UUID,
        limit: Int = 10,
        offset: Long = 0,
    ): List<ProfileGuideProgress>

    /**
     * Returns the total number of guide progress records for a profile.
     *
     * @param profileId the UUID of the profile
     * @return the count of distinct guides the profile has progress in
     */
    suspend fun getProgressCount(profileId: UUID): Long

    /**
     * Returns the total number of active progress records for one guide and profile.
     *
     * @param profileId the UUID of the profile
     * @param metadataId the UUID of the guide's metadata item
     * @return the number of matching guide versions with active progress
     */
    suspend fun getProgressCount(profileId: UUID, metadataId: UUID): Long

    /**
     * Retrieves the progress record for a specific guide (metadata item at a given version)
     * and profile.
     *
     * @param profileId the UUID of the profile
     * @param metadataId the UUID of the guide's metadata item
     * @param version the version of the guide's metadata item
     * @return the [ProfileGuideProgress] for the specified guide, or `null` if no progress exists
     */
    suspend fun getProgress(profileId: UUID, metadataId: UUID, version: Int): ProfileGuideProgress?

    /**
     * Retrieves aggregate progression statistics for a guide across all metadata versions.
     *
     * @param metadataId the UUID of the guide's metadata item
     * @return active, historical, completion, total, and unique-profile counts
     */
    suspend fun getStatistics(metadataId: UUID): GuideProgressStatistics

    /**
     * Retrieves the profiles with active progress for a guide across all metadata versions.
     *
     * @param metadataId the UUID of the guide's metadata item
     * @param limit the maximum number of profile IDs to return
     * @param offset the number of profiles to skip for pagination
     * @return distinct profile IDs ordered by most recent activity
     */
    suspend fun getActiveProfileIds(metadataId: UUID, limit: Int = 25, offset: Long = 0): List<UUID>

    /**
     * Retrieves active progress for one guide and a page of profiles in a single operation.
     *
     * @param metadataId the UUID of the guide's metadata item
     * @param profileIds the profile UUIDs whose progress should be returned
     * @return matching progress records across all guide versions, ordered by recent activity
     */
    suspend fun getActiveProgress(metadataId: UUID, profileIds: List<UUID>): List<ProfileGuideProgress>

    /**
     * Retrieves a paginated list of all guide history entries for a profile, across all guides.
     * History entries represent individual step-completion events.
     *
     * @param profileId the UUID of the profile
     * @param limit the maximum number of history entries to return (defaults to 10)
     * @param offset the number of entries to skip for pagination (defaults to 0)
     * @return the list of [ProfileGuideHistory] entries for the profile
     */
    suspend fun getAllHistory(profileId: UUID, limit: Int = 10, offset: Long = 0): List<ProfileGuideHistory>

    /**
     * Returns the total number of guide history entries for a profile, across all guides.
     *
     * @param profileId the UUID of the profile
     * @return the total count of history entries
     */
    suspend fun getHistoryCount(profileId: UUID): Long

    /**
     * Retrieves a paginated list of history entries for a specific guide and profile.
     *
     * @param profileId the UUID of the profile
     * @param metadataId the UUID of the guide's metadata item
     * @param version the version of the guide's metadata item
     * @param limit the maximum number of history entries to return (defaults to 10)
     * @param offset the number of entries to skip for pagination (defaults to 0)
     * @return the list of [ProfileGuideHistory] entries for the specified guide
     */
    suspend fun getHistory(
        profileId: UUID,
        metadataId: UUID,
        version: Int,
        limit: Int = 10,
        offset: Long = 0
    ): List<ProfileGuideHistory>

    /**
     * Returns the total number of history entries for a specific guide and profile.
     *
     * @param profileId the UUID of the profile
     * @param metadataId the UUID of the guide's metadata item
     * @param version the version of the guide's metadata item
     * @return the count of history entries for the specified guide
     */
    suspend fun getHistoryCount(profileId: UUID, metadataId: UUID, version: Int): Long

    /**
     * Records that a profile has completed a step in a guide. If the profile has no existing
     * progress for this guide, a new progress record is created. The completed step is appended
     * to the list of completed step IDs, and a history entry is logged.
     * Successfully saved steps emit completion analytics after the transaction commits; finishing
     * the guide also emits a guide completion. Request analytics identity is supplied by the caller's context.
     *
     * @param profileId the UUID of the profile completing the step
     * @param metadataId the UUID of the guide's metadata item
     * @param metadataVersion the version of the guide's metadata item
     * @param stepId the identifier of the step that was completed
     * @param attributes optional JSON attributes to associate with this progress update
     * @return the updated [ProfileGuideProgress], or `null` if the progress could not be recorded
     */
    suspend fun addProgress(
        profileId: UUID,
        metadataId: UUID,
        metadataVersion: Int,
        stepId: Long,
        attributes: JsonElement?
    ): ProfileGuideProgress?

    /**
     * Removes a profile's progress record for a specific guide, allowing the user to
     * withdraw from a guide they previously started. This deletes the progress tracking
     * data but does not affect the guide's completion history.
     *
     * @param profileId the UUID of the profile whose progress should be removed
     * @param metadataId the UUID of the guide's metadata item
     * @param metadataVersion the version of the guide's metadata item
     */
    suspend fun deleteProgress(profileId: UUID, metadataId: UUID, metadataVersion: Int)
}
