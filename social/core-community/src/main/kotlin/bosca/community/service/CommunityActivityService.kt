package bosca.community.service

import bosca.community.model.CommunityActivity
import bosca.serialization.UUID
import bosca.service.Service
import kotlinx.serialization.json.JsonElement

/**
 * Service for managing community activities such as events, meetings, or recurring gatherings
 * that are organized within a community group.
 *
 * Activities belong to a community group and carry structured content and schedule
 * information as JSON, allowing flexible representation of different activity types.
 */
interface CommunityActivityService : Service {

    /**
     * Retrieves all activities belonging to the specified community group.
     *
     * @param groupId the unique identifier of the community group
     * @return a list of [CommunityActivity] instances associated with the group
     */
    suspend fun getActivities(groupId: UUID): List<CommunityActivity>

    /**
     * Retrieves a single activity by its unique identifier.
     *
     * @param id the unique identifier of the activity
     * @return the [CommunityActivity] if found, or `null` if no activity exists with the given ID
     */
    suspend fun getActivity(id: UUID): CommunityActivity?

    /**
     * Creates a new activity within the specified community group.
     *
     * @param groupId the unique identifier of the community group this activity belongs to
     * @param name the display name of the activity
     * @param description a textual description of the activity
     * @param type a string classifier for the kind of activity (e.g., "event", "meeting")
     * @param content optional JSON payload containing the structured content of the activity
     * @param schedule optional JSON payload describing the activity's scheduling information
     * @return the newly created [CommunityActivity]
     */
    suspend fun createActivity(groupId: UUID, name: String, description: String, type: String, content: JsonElement?, schedule: JsonElement?): CommunityActivity

    /**
     * Updates an existing activity. Only non-null parameters are applied; null values
     * leave the corresponding field unchanged.
     *
     * @param id the unique identifier of the activity to update
     * @param name the new display name, or `null` to keep the current value
     * @param description the new description, or `null` to keep the current value
     * @param type the new type classifier, or `null` to keep the current value
     * @param content the new structured content JSON, or `null` to keep the current value
     * @param schedule the new scheduling JSON, or `null` to keep the current value
     * @return the updated [CommunityActivity]
     */
    suspend fun updateActivity(id: UUID, name: String?, description: String?, type: String?, content: JsonElement?, schedule: JsonElement?): CommunityActivity
}
