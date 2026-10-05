@file:OptIn(
    bosca.core.annotations.Internal::class,
    bosca.di.annotation.InternalDI::class,
)

package bosca.segmentation.jobs

import bosca.communications.model.Message
import bosca.communications.model.MessageChannel
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.communications.service.MessageService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.segmentation.model.Campaign
import bosca.segmentation.model.NotificationChannel
import bosca.segmentation.model.NotificationStatus
import bosca.segmentation.service.CampaignMessageBuilder
import bosca.segmentation.service.CampaignService
import bosca.segmentation.service.SegmentService
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SendCampaignJobExecutorTest {

    private val json = Json {
        serializersModule = SerializersModule {
            contextual(UUID::class, UUIDSerializer())
        }
    }
    private val campaignService = mockk<CampaignService>(relaxed = true)
    private val segmentService = mockk<SegmentService>()
    private val messageService = mockk<MessageService>(relaxed = true)
    private val messageBuilder = mockk<CampaignMessageBuilder>()

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<CampaignService> { campaignService }
        provides<SegmentService> { segmentService }
        provides<MessageService> { messageService }
        provides<CampaignMessageBuilder> { messageBuilder }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `email campaign queues provider-safe audience pages`() = runTest {
        val campaignId = UUID.random()
        val segmentId = UUID.random()
        val firstPage = List(1000) { UUID.random() }
        val secondPage = listOf(UUID.random())
        val campaign = Campaign(
            id = campaignId,
            name = "Newsletter",
            channel = NotificationChannel.EMAIL,
        )
        coEvery { campaignService.getById(campaignId) } returns campaign
        coEvery { campaignService.getSegmentIds(campaignId) } returns listOf(segmentId)
        coEvery {
            segmentService.getAudienceProfileIdsPaged(listOf(segmentId), 0, 1000)
        } returns firstPage
        coEvery {
            segmentService.getAudienceProfileIdsPaged(listOf(segmentId), 1000, 1000)
        } returns secondPage
        coEvery {
            segmentService.getAudienceProfileIdsPaged(listOf(segmentId), 2000, 1000)
        } returns emptyList()
        every { messageBuilder.buildCampaignMessage(campaign, any()) } answers {
            Message(
                channels = listOf(MessageChannel.EMAIL),
                subject = "Newsletter",
                recipients = secondArg(),
                content = listOf(MessageContent(MessageContentType.TEXT, "Hello")),
            )
        }
        val definition = SendCampaignJob(campaignId)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(SendCampaignJob.serializer(), definition),
            executor = SendCampaignJobExecutor::class,
        )
        val queue = mockk<JobQueue>(relaxed = true)
        val sentMessages = mutableListOf<Message>()

        withContext(queue.asCoroutineContext(job)) {
            SendCampaignJobExecutor().execute()
        }

        coVerify(exactly = 2) { messageService.send(capture(sentMessages)) }
        assertEquals(listOf(1000, 1), sentMessages.map { it.recipients.size })
        assertTrue(sentMessages.all { it.recipients.size <= 1000 })
        coVerify(exactly = 1) {
            campaignService.updateSentStatus(
                campaignId,
                NotificationStatus.SENT,
                any(),
                1001,
            )
        }
    }
}
