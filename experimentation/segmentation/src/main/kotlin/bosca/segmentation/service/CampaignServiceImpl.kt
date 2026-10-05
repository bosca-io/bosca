package bosca.segmentation.service

import bosca.cache.ServiceCache
import bosca.cache.serializers.UUIDKeySerializer
import bosca.db.transaction
import bosca.di.annotation.ProviderName
import bosca.communications.service.MessageService
import bosca.segmentation.cache.BannerWeightCacheKeySerializer
import bosca.segmentation.cache.BannerWeightKeyId
import bosca.segmentation.configuration.JobQueueNames
import bosca.segmentation.jobs.SendCampaignJob
import bosca.segmentation.model.BannerWeight
import bosca.segmentation.model.Campaign
import bosca.segmentation.model.CampaignInput
import bosca.segmentation.model.NotificationChannel
import bosca.segmentation.model.NotificationStatus
import bosca.segmentation.repository.CampaignRepository
import bosca.segmentation.repository.CampaignSegmentRepository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.enqueue
import bosca.sharedqueue.jobs.enqueueLater
import kotlin.time.toKotlinDuration

@ServiceImplementation
class CampaignServiceImpl(
    private val campaignRepository: CampaignRepository,
    private val campaignSegmentRepository: CampaignSegmentRepository,
    private val segmentService: SegmentService,
    private val messageService: MessageService,
    private val campaignMessageBuilder: CampaignMessageBuilder,
    @ProviderName(name = JobQueueNames.segmentationJobQueue)
    private val jobQueue: JobQueue
) : CampaignService {

    private val campaignByIdCache = ServiceCache("campaigns:id", UUIDKeySerializer) {
        campaignRepository.getById(it)
    }

    private val bannerWeightsCache = ServiceCache("campaigns:banner:weights", BannerWeightCacheKeySerializer) {
        if (it.profileId != null) {
            campaignRepository.getActiveBannerWeightsForPlacement(it.profileId, it.placement)
        } else {
            campaignRepository.getActiveBannerWeightsForPlacementEveryone(it.placement)
        }
    }

    private suspend fun invalidateCampaignCache(id: UUID) {
        bannerWeightsCache.clear()
        campaignByIdCache.remove(id)
    }

    override suspend fun getAll(offset: Long, limit: Int): List<Campaign> {
        return campaignRepository.getAll(offset, limit)
    }

    override suspend fun getById(id: UUID): Campaign? {
        return campaignByIdCache.get(id)
    }

    private fun validateInput(input: CampaignInput) {
        require(input.name.isNotBlank()) { "Campaign name must not be blank" }
        require(input.weight >= 0) { "Campaign weight must be non-negative" }
        val scheduledAt = input.scheduledAt
        val endedAt = input.endedAt
        if (scheduledAt != null && endedAt != null) {
            require(endedAt.isAfter(scheduledAt)) { "endedAt must be after scheduledAt" }
        }
        if (input.channel == NotificationChannel.EMAIL) {
            campaignMessageBuilder.validateEmailCampaignContent(input.content)
        }
    }

    override suspend fun add(input: CampaignInput): Campaign = transaction {
        validateInput(input)
        val campaign = Campaign(
            name = input.name,
            channel = input.channel,
            content = input.content,
            scheduledAt = input.scheduledAt,
            endedAt = input.endedAt,
            placement = input.placement,
            weight = input.weight
        )
        val created = campaignRepository.add(campaign)
        input.segmentIds.forEach { segmentId ->
            campaignSegmentRepository.add(created.id, segmentId)
        }
        invalidateCampaignCache(created.id)
        created
    }

    override suspend fun edit(id: UUID, input: CampaignInput): Campaign = transaction {
        validateInput(input)
        val existing = campaignByIdCache.get(id) ?: error("Campaign not found: $id")
        if (existing.status != NotificationStatus.DRAFT && existing.status != NotificationStatus.CANCELLED) {
            error("Only draft or cancelled campaigns can be edited")
        }
        require(input.channel == existing.channel) {
            "Campaign channel cannot be changed after creation (expected ${existing.channel}, got ${input.channel})"
        }
        val updated = existing.copy(
            name = input.name,
            channel = existing.channel,
            content = input.content,
            scheduledAt = input.scheduledAt,
            endedAt = input.endedAt,
            placement = input.placement,
            weight = input.weight
        )
        val result = campaignRepository.update(updated)
        campaignSegmentRepository.deleteByCampaignId(id)
        input.segmentIds.forEach { segmentId ->
            campaignSegmentRepository.add(id, segmentId)
        }
        invalidateCampaignCache(id)
        result
    }

    override suspend fun delete(id: UUID) = transaction {
        val campaign = campaignByIdCache.get(id) ?: error("Campaign not found: $id")
        if (campaign.status == NotificationStatus.SENDING) {
            error("Cannot delete a campaign that is currently sending")
        }
        if (campaign.status == NotificationStatus.SCHEDULED) {
            campaign.scheduledJobId?.let { jobId ->
                jobQueue.markCancelled(jobId)
            }
        }
        campaignSegmentRepository.deleteByCampaignId(id)
        campaignRepository.deleteById(id)
        invalidateCampaignCache(id)
    }

    override suspend fun getSegmentIds(campaignId: UUID): List<UUID> {
        return campaignSegmentRepository.getByCampaignId(campaignId)
            .map { it.segmentId }
    }

    override suspend fun send(id: UUID): Campaign {
        val result = campaignRepository.updateStatusIfSendable(id, NotificationStatus.SCHEDULED)
            ?: error("Campaign not found or not in a sendable status (must be DRAFT, SCHEDULED, or CANCELLED)")
        invalidateCampaignCache(id)
        val sendJob = SendCampaignJob(id)
        val scheduledAt = result.scheduledAt
        if (scheduledAt != null && scheduledAt.isAfter(OffsetDateTime.now())) {
            val delay = java.time.Duration.between(
                java.time.OffsetDateTime.now(),
                scheduledAt
            ).toKotlinDuration()
            val queuedJob = sendJob.enqueueLater(jobQueue, bosca.segmentation.jobs.SendCampaignJobExecutor::class, timeout = delay)
            campaignRepository.updateScheduledJobId(id, queuedJob.getId())
        } else {
            sendJob.enqueue(jobQueue, bosca.segmentation.jobs.SendCampaignJobExecutor::class)
        }
        return result
    }

    override suspend fun updateStatus(id: UUID, status: NotificationStatus): Campaign {
        val result = campaignRepository.updateStatus(id, status)
        invalidateCampaignCache(id)
        return result
    }

    override suspend fun updateSentStatus(id: UUID, status: NotificationStatus, sentAt: OffsetDateTime, sentCount: Long): Campaign {
        val result = campaignRepository.updateSentStatus(id, status, sentAt, sentCount)
        invalidateCampaignCache(id)
        return result
    }

    override suspend fun sendTest(id: UUID, segmentId: UUID) {
        val campaign = campaignByIdCache.get(id) ?: error("Campaign not found: $id")
        val profileIds = segmentService.getAudienceProfileIds(listOf(segmentId))
        if (profileIds.isEmpty()) {
            error("Test segment has no members")
        }
        val message = campaignMessageBuilder.buildCampaignMessage(campaign, profileIds)
        if (message != null) {
            messageService.sendNow(message)
        }
    }

    override suspend fun cancel(id: UUID): Campaign {
        val campaign = campaignByIdCache.get(id) ?: error("Campaign not found: $id")
        if (campaign.status != NotificationStatus.SCHEDULED
            && campaign.status != NotificationStatus.SENT
            && campaign.status != NotificationStatus.ACTIVE
        ) {
            error("Only scheduled, sent, or active campaigns can be cancelled")
        }
        campaign.scheduledJobId?.let { jobId ->
            jobQueue.markCancelled(jobId)
            campaignRepository.updateScheduledJobId(id, null)
        }
        val result = campaignRepository.updateStatus(id, NotificationStatus.CANCELLED)
        invalidateCampaignCache(id)
        return result
    }

    override suspend fun reactivate(id: UUID): Campaign {
        val campaign = campaignByIdCache.get(id) ?: error("Campaign not found: $id")
        if (campaign.status != NotificationStatus.CANCELLED) {
            error("Only cancelled campaigns can be reactivated")
        }
        val result = campaignRepository.updateStatus(id, NotificationStatus.ACTIVE)
        invalidateCampaignCache(id)
        return result
    }

    override suspend fun getActiveBannersForProfile(profileId: UUID): List<Campaign> {
        return campaignRepository.getActiveBannersForProfile(profileId)
    }

    override suspend fun getActiveBanners(): List<Campaign> {
        return campaignRepository.getActiveBannersEveryone()
    }

    override suspend fun getActiveBannerWeightsForPlacement(profileId: UUID?, placement: String): List<BannerWeight> {
        return bannerWeightsCache.get(BannerWeightKeyId(profileId, placement)) ?: emptyList()
    }

    override suspend fun getActiveBannerForPlacement(profileId: UUID?, placement: String): Campaign? {
        val weights = getActiveBannerWeightsForPlacement(profileId, placement)
        if (weights.isEmpty()) return null
        val selected = weightedRandomSelect(weights)
        return campaignByIdCache.get(selected.id)
    }

    companion object {
        internal fun weightedRandomSelect(weights: List<BannerWeight>): BannerWeight {
            val totalWeight = weights.sumOf { it.weight }
            if (totalWeight <= 0) return weights.random()
            var roll = java.util.concurrent.ThreadLocalRandom.current().nextInt(totalWeight)
            for (entry in weights) {
                roll -= entry.weight
                if (roll < 0) return entry
            }
            return weights.last()
        }

    }
}
