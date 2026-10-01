package bosca.community.service

import bosca.community.model.PrayedByEntry
import bosca.community.model.Prayer
import bosca.community.model.PrayerAnniversary
import bosca.community.model.PrayerComment
import bosca.community.model.PrayerLike
import bosca.community.model.Prayers
import bosca.community.model.PrayerShare
import bosca.community.model.PrayerStatus
import bosca.security.model.PermissionService
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/**
 * Service for managing prayer requests within community groups.
 *
 * Prayer requests are user-submitted entries associated with a community group
 * and a user profile. Each request carries structured JSON content and optional
 * attributes, and has a lifecycle tracked by [PrayerStatus].
 *
 * Extends [PermissionService] so that [Prayer] entities participate in the
 * standard permission-evaluator pipeline — `prayer_permissions` rows link
 * security groups (not community groups) to individual prayers.
 */
interface PrayerService : PermissionService<Prayer, UUID> {

    /**
     * Retrieves a paginated list of prayer requests for the specified community group.
     *
     * @param groupId the unique identifier of the community group
     * @param limit the maximum number of prayer requests to return
     * @param offset the number of prayer requests to skip before returning results
     * @return a list of [Prayer] instances for the requested page
     */
    suspend fun getRequests(groupId: UUID, limit: Int, offset: Int): List<Prayer>

    /**
     * Retrieves a paginated list of prayer requests for the specified community group,
     * optionally filtered by status, ordered by most recent activity.
     *
     * @param groupId the unique identifier of the community group
     * @param status optional list of statuses to filter by; null returns all statuses
     * @param limit the maximum number of prayer requests to return
     * @param offset the number of prayer requests to skip before returning results
     * @return a [Prayers] containing the items and total count
     */
    suspend fun getRequests(groupId: UUID, status: List<PrayerStatus>?, limit: Int, offset: Int): Prayers

    /**
     * Retrieves an aggregated prayer feed across the user's community groups,
     * optionally filtered by specific groups and/or status.
     *
     * @param profileId the user profile whose groups define the feed scope
     * @param groupIds optional subset of group IDs to filter by; null returns prayers from all user groups
     * @param status optional list of statuses to filter by; null returns all statuses
     * @param limit the maximum number of prayer requests to return
     * @param offset the number of prayer requests to skip before returning results
     * @return a [Prayers] containing the items and total count
     */
    suspend fun getFeed(profileId: UUID, groupIds: List<UUID>?, status: List<PrayerStatus>?, limit: Int, offset: Int): Prayers

    /**
     * Retrieves a single prayer request by its unique identifier.
     *
     * @param id the unique identifier of the prayer request
     * @return the [Prayer] if found, or `null` if no request exists with the given ID
     */
    suspend fun getRequest(id: UUID): Prayer?

    /**
     * Creates a new prayer request within the specified community group.
     *
     * @param groupId the unique identifier of the community group this request belongs to
     * @param profileId the unique identifier of the user profile submitting the request
     * @param title the display title of the prayer request
     * @param content JSON payload containing the structured content of the prayer request
     * @param attributes optional JSON metadata to attach to the request
     * @return the newly created [Prayer]
     */
    suspend fun addRequest(groupId: UUID, profileId: UUID, title: String, content: JsonElement, attributes: JsonElement?): Prayer

    /**
     * Updates the status of a prayer request and refreshes activity timestamps.
     * If the new status is [PrayerStatus.ANSWERED], stamps `answered_at`.
     *
     * @param id the unique identifier of the prayer request
     * @param status the new status to set
     * @return the updated [Prayer]
     * @throws IllegalStateException if the prayer is not found
     */
    suspend fun updateStatus(id: UUID, status: PrayerStatus): Prayer

    /**
     * Looks up the community groups that a prayer belongs to.
     *
     * @param prayerId the unique identifier of the prayer request
     * @return the community group UUIDs
     */
    suspend fun getCommunityGroupsForPrayer(prayerId: UUID): List<UUID>

    /**
     * Records that a user has prayed for a prayer request.
     *
     * @param prayerId the prayer request to mark
     * @param profileId the user who prayed
     * @return the new prayer action count
     */
    suspend fun markPrayed(prayerId: UUID, profileId: UUID): Int

    /**
     * Removes a user's prayed mark from a prayer request.
     *
     * @param prayerId the prayer request to unmark
     * @param profileId the user to unmark
     * @return the new prayer action count
     */
    suspend fun unmarkPrayed(prayerId: UUID, profileId: UUID): Int

    /**
     * Retrieves the list of users who have prayed for a prayer request.
     *
     * @param prayerId the prayer request
     * @param limit the maximum number of entries to return
     * @param offset the number of entries to skip
     * @return a list of [PrayedByEntry] instances
     */
    suspend fun getPrayedBy(prayerId: UUID, limit: Int, offset: Int): List<PrayedByEntry>

    /**
     * Checks whether a specific user has prayed for a prayer request.
     *
     * @param prayerId the prayer request
     * @param profileId the user to check
     * @return true if the user has prayed for this request
     */
    suspend fun hasPrayed(prayerId: UUID, profileId: UUID): Boolean

    /**
     * Records that a user has liked a prayer request.
     */
    suspend fun likePrayer(prayerId: UUID, profileId: UUID): Int

    /**
     * Removes a user's like from a prayer request.
     */
    suspend fun unlikePrayer(prayerId: UUID, profileId: UUID): Int

    /**
     * Retrieves the list of users who have liked a prayer request.
     */
    suspend fun getLikes(prayerId: UUID, limit: Int, offset: Int): List<PrayerLike>

    /**
     * Checks whether a specific user has liked a prayer request.
     */
    suspend fun hasLiked(prayerId: UUID, profileId: UUID): Boolean

    /**
     * Adds a comment to a prayer request.
     */
    suspend fun addComment(prayerId: UUID, profileId: UUID, content: String, parentId: Long? = null, attributes: JsonElement? = null): PrayerComment

    /**
     * Retrieves a single prayer comment by its identifier.
     *
     * @param id the comment identifier
     * @return the comment, including its deletion state, or `null` when it does not exist
     */
    suspend fun getComment(id: Long): PrayerComment?

    /**
     * Retrieves top-level comments for a prayer request.
     */
    suspend fun getComments(prayerId: UUID, limit: Int, offset: Int): List<PrayerComment>

    /**
     * Retrieves replies to a specific comment.
     */
    suspend fun getReplies(parentId: Long, limit: Int, offset: Int): List<PrayerComment>

    /**
     * Soft-deletes a comment from a prayer request.
     */
    suspend fun deleteComment(prayerId: UUID, commentId: Long)

    /**
     * Shares a prayer with a specific profile.
     */
    suspend fun sharePrayer(prayerId: UUID, profileId: UUID)

    /**
     * Removes a prayer share from a specific profile.
     */
    suspend fun unsharePrayer(prayerId: UUID, profileId: UUID)

    /**
     * Retrieves the list of profiles a prayer has been shared with.
     */
    suspend fun getShares(prayerId: UUID): List<PrayerShare>

    /**
     * Retrieves prayers that have been shared with a specific profile.
     */
    suspend fun getSharedWithMe(profileId: UUID, limit: Int, offset: Int): Prayers

    /**
     * Retrieves anniversary milestones for a prayer.
     */
    suspend fun getAnniversaries(prayerId: UUID): List<PrayerAnniversary>

    /**
     * Sets or clears the suppress-anniversaries flag on a prayer.
     */
    suspend fun suppressAnniversaries(id: UUID, suppress: Boolean): Prayer

    /**
     * Scans all answered prayers and posts any due anniversary milestones.
     * Idempotent — already-posted milestones are skipped.
     */
    suspend fun scanAndPostAnniversaries()

    /**
     * Deletes a prayer request by its unique identifier.
     *
     * @param id the unique identifier of the prayer request to delete
     */
    suspend fun deleteRequest(id: UUID)
}
