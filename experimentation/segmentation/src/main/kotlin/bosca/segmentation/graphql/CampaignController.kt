package bosca.segmentation.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.segmentation.model.Campaign
import bosca.segmentation.model.NotificationChannel
import bosca.segmentation.model.NotificationStatus
import bosca.segmentation.model.Segment
import bosca.segmentation.service.CampaignService
import bosca.segmentation.service.SegmentService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/**
 * Resolves fields on the Campaign GraphQL type, including nested segment loading.
 */
@TypeController(type = "Campaign")
class CampaignController(
    private val campaignService: CampaignService,
    private val segmentService: SegmentService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<Campaign> {

    @Field
    fun id(campaign: Campaign): UUID = campaign.id

    @Field
    fun name(campaign: Campaign): String = campaign.name

    @Field
    fun channel(campaign: Campaign): NotificationChannel = campaign.channel

    @Field
    fun status(campaign: Campaign): NotificationStatus = campaign.status

    @Field
    fun content(campaign: Campaign): JsonElement? = campaign.content

    @Field
    fun scheduledAt(campaign: Campaign): OffsetDateTime? = campaign.scheduledAt

    @Field
    fun endedAt(campaign: Campaign): OffsetDateTime? = campaign.endedAt

    @Field
    fun sentAt(campaign: Campaign): OffsetDateTime? = campaign.sentAt

    @Field
    fun sentCount(campaign: Campaign): Long = campaign.sentCount

    @Field
    fun created(campaign: Campaign): OffsetDateTime = campaign.created

    @Field
    fun modified(campaign: Campaign): OffsetDateTime = campaign.modified

    @Field
    fun placement(campaign: Campaign): String? = campaign.placement

    @Field
    fun weight(campaign: Campaign): Int = campaign.weight

    @Field
    suspend fun segments(authenticationContext: AuthenticationContext, campaign: Campaign): List<Segment> {
        groupEvaluator.verifyHasAdminGroup(authenticationContext)
        val segmentIds = campaignService.getSegmentIds(campaign.id)
        return segmentService.getByIds(segmentIds)
    }
}
