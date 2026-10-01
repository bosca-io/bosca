package bosca.segmentation.jobs

import bosca.di.provide
import bosca.communications.service.MessageService
import bosca.segmentation.configuration.JobQueueNames
import bosca.segmentation.model.NotificationStatus
import bosca.segmentation.model.NotificationChannel
import bosca.segmentation.service.CampaignMessageBuilder
import bosca.segmentation.service.CampaignService
import bosca.segmentation.service.SegmentService
import bosca.serialization.OffsetDateTime
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

@JobDefinition(SendCampaignJob::class, JobQueueNames.segmentationJobQueue, "send-campaign")
class SendCampaignJobExecutor : AbstractJobExecutor<SendCampaignJob>(
    SendCampaignJob.serializer()
) {
    override suspend fun execute() {
        val job = getJobDefinition()
        val campaignService: CampaignService = provide()
        val segmentService: SegmentService = provide()
        val messageService: MessageService = provide()
        val messageBuilder: CampaignMessageBuilder = provide()

        val campaign = campaignService.getById(job.campaignId)
            ?: error("Campaign not found: ${job.campaignId}")

        campaignService.updateStatus(job.campaignId, NotificationStatus.SENDING)

        try {
            val segmentIds = campaignService.getSegmentIds(job.campaignId)

            var offset = 0L
            var totalSent = 0L
            while (true) {
                val profileIds = segmentService.getAudienceProfileIdsPaged(segmentIds, offset, AUDIENCE_PAGE_SIZE)
                if (profileIds.isEmpty()) break

                val message = messageBuilder.buildCampaignMessage(campaign, profileIds)
                if (message != null) {
                    messageService.send(message)
                } else {
                    log.info("Banner campaign ${job.campaignId} activated for ${profileIds.size} profiles")
                }
                totalSent += profileIds.size
                offset += AUDIENCE_PAGE_SIZE
            }

            val finalStatus = if (campaign.channel == NotificationChannel.BANNER) {
                NotificationStatus.ACTIVE
            } else {
                NotificationStatus.SENT
            }
            campaignService.updateSentStatus(
                id = job.campaignId,
                status = finalStatus,
                sentAt = OffsetDateTime.now(),
                sentCount = totalSent
            )
        } catch (e: Exception) {
            log.error("Failed to send campaign: ${job.campaignId}", e)
            campaignService.updateStatus(job.campaignId, NotificationStatus.FAILED)
            throw e
        }
    }

    companion object {
        // SendGrid accepts at most 1,000 recipients/personalizations in one Mail Send request.
        private const val AUDIENCE_PAGE_SIZE = 1000
        private val log = LoggerFactory.getLogger(SendCampaignJobExecutor::class.java)
    }
}
