package bosca.segmentation.service

import bosca.segmentation.model.BannerWeight
import bosca.segmentation.model.Campaign
import bosca.segmentation.model.CampaignInput
import bosca.segmentation.model.NotificationStatus
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for managing campaigns that deliver content to
 * segment audiences through banners, email, and push channels.
 *
 * Handles the full campaign lifecycle from creation through scheduling
 * and delivery, coordinating with the segment service for audience resolution
 * and the message service for actual delivery.
 */
interface CampaignService : Service {

    /**
     * Retrieves a paginated list of all campaigns.
     *
     * @param offset the number of campaigns to skip for pagination
     * @param limit the maximum number of campaigns to return
     * @return the list of [Campaign] instances
     */
    suspend fun getAll(offset: Long, limit: Int): List<Campaign>

    /**
     * Retrieves a single campaign by its identifier.
     *
     * @param id the UUID of the campaign
     * @return the [Campaign] matching the given [id], or `null` if not found
     */
    suspend fun getById(id: UUID): Campaign?

    /**
     * Creates a new campaign and links it to the specified segments.
     *
     * @param input the campaign specification including channel, content, and target segments
     * @return the newly created [Campaign]
     */
    suspend fun add(input: CampaignInput): Campaign

    /**
     * Updates an existing campaign. Only campaigns in DRAFT or CANCELLED status can be edited.
     *
     * @param id the UUID of the campaign to update
     * @param input the updated campaign specification
     * @return the modified [Campaign]
     */
    suspend fun edit(id: UUID, input: CampaignInput): Campaign

    /**
     * Deletes a campaign and its segment associations.
     *
     * @param id the UUID of the campaign to delete
     */
    suspend fun delete(id: UUID)

    /**
     * Retrieves the segment IDs associated with a campaign.
     *
     * @param campaignId the UUID of the campaign
     * @return the list of segment UUIDs linked to the campaign
     */
    suspend fun getSegmentIds(campaignId: UUID): List<UUID>

    /**
     * Dispatches a campaign to its audience, transitioning its
     * status to SENDING and then SENT upon completion. For email and push
     * channels, messages are enqueued via the job system. For banners, the
     * campaign is activated for display.
     *
     * @param id the UUID of the campaign to send
     * @return the updated [Campaign] with delivery status
     */
    suspend fun send(id: UUID): Campaign

    /**
     * Updates the status of a campaign.
     *
     * @param id the UUID of the campaign
     * @param status the new status to set
     * @return the updated [Campaign]
     */
    suspend fun updateStatus(id: UUID, status: NotificationStatus): Campaign

    /**
     * Sends a test delivery of a campaign to the members of a specific segment
     * without changing the campaign's status. Useful for previewing how the
     * campaign will appear before dispatching to the full audience.
     *
     * @param id the UUID of the campaign to test
     * @param segmentId the UUID of the segment whose members will receive the test
     */
    suspend fun sendTest(id: UUID, segmentId: UUID)

    /**
     * Cancels a scheduled or active campaign, transitioning its status to CANCELLED
     * and preventing any further delivery. For scheduled campaigns this prevents
     * the queued job from executing; for active banners this removes them from display.
     *
     * @param id the UUID of the campaign to cancel
     * @return the updated [Campaign] with CANCELLED status
     */
    suspend fun cancel(id: UUID): Campaign

    /**
     * Reactivates a cancelled campaign by returning it to ACTIVE status,
     * making it visible again for banner display or eligible for re-sending.
     *
     * @param id the UUID of the cancelled campaign to reactivate
     * @return the updated [Campaign] with ACTIVE status
     */
    suspend fun reactivate(id: UUID): Campaign

    /**
     * Retrieves all active banner campaigns whose segments include the
     * specified profile or that target an EVERYONE segment, ordered by
     * weight descending.
     *
     * @param profileId the UUID of the profile to check for active banners
     * @return the list of active banner [Campaign] instances targeting this profile
     */
    suspend fun getActiveBannersForProfile(profileId: UUID): List<Campaign>

    /**
     * Retrieves all active banner campaigns that target an EVERYONE segment,
     * ordered by weight descending. Used when no profile is available
     * (anonymous users).
     *
     * @return the list of active banner [Campaign] instances for everyone
     */
    suspend fun getActiveBanners(): List<Campaign>

    /**
     * Retrieves the IDs and weights of all active banner campaigns for a
     * given placement. When [profileId] is provided, returns banners matching
     * segment membership or targeting an EVERYONE segment. When [profileId]
     * is null, returns only banners targeting an EVERYONE segment.
     *
     * The returned weights are relative probabilities for random selection,
     * not priorities. Clients should cache this list and perform weighted
     * random selection to pick a banner to display.
     *
     * @param profileId the UUID of the profile, or null for anonymous users
     * @param placement the placement identifier to filter by (e.g., "top", "hero")
     * @return the list of [BannerWeight] entries for matching campaigns
     */
    suspend fun getActiveBannerWeightsForPlacement(profileId: UUID?, placement: String): List<BannerWeight>

    /**
     * Selects a single active banner campaign for a given placement using
     * weighted random selection. Retrieves all matching banner weights,
     * performs a random draw proportional to each banner's weight, and
     * returns the full campaign data for the selected banner.
     *
     * @param profileId the UUID of the profile, or null for anonymous users
     * @param placement the placement identifier to filter by (e.g., "top", "hero")
     * @return the randomly selected [Campaign], or null if no banners match
     */
    suspend fun getActiveBannerForPlacement(profileId: UUID?, placement: String): Campaign?

    /**
     * Updates a campaign's status along with delivery metadata after a send
     * operation completes. Records when the campaign was sent and how many
     * recipients were reached.
     *
     * @param id the UUID of the campaign
     * @param status the post-delivery status (e.g., SENT or ACTIVE)
     * @param sentAt the timestamp when delivery completed
     * @param sentCount the number of recipients the campaign was delivered to
     * @return the updated [Campaign]
     */
    suspend fun updateSentStatus(id: UUID, status: NotificationStatus, sentAt: OffsetDateTime, sentCount: Long): Campaign
}
