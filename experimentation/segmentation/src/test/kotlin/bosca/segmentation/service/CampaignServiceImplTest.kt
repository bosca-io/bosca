package bosca.segmentation.service

import bosca.cache.Cache
import bosca.cache.CacheManager
import bosca.cache.CacheValue
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.communications.service.MessageService
import bosca.segmentation.model.BannerWeight
import bosca.segmentation.model.Campaign
import bosca.segmentation.model.CampaignInput
import bosca.segmentation.model.NotificationChannel
import bosca.segmentation.model.NotificationStatus
import bosca.segmentation.repository.CampaignRepository
import bosca.segmentation.repository.CampaignSegmentRepository
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(InternalDI::class)
class CampaignServiceImplTest {

    private val campaignRepository = mockk<CampaignRepository>(relaxed = true)
    private val campaignSegmentRepository = mockk<CampaignSegmentRepository>(relaxed = true)
    private val segmentService = mockk<SegmentService>(relaxed = true)
    private val messageService = mockk<MessageService>(relaxed = true)
    private val campaignMessageBuilder = mockk<CampaignMessageBuilder>(relaxed = true)
    private val jobQueue = mockk<JobQueue>(relaxed = true)
    private val cacheManager = mockk<CacheManager>(relaxed = true)
    private val requestCacheSerializer = mockk<RequestCacheSerializer>(relaxed = true)
    private val remoteCache = mockk<Cache<Any>>(relaxed = true)

    private lateinit var service: CampaignServiceImpl

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()

        coEvery { cacheManager.maybeAddCache<Any>(any(), any()) } returns remoteCache
        coEvery { cacheManager.getCache<Any>(any()) } returns remoteCache
        every { remoteCache.keySerializer } returns mockk(relaxed = true)
        coEvery { remoteCache.get(any()) } returns mockk<CacheValue> {
            every { exists } returns false
            every { value } returns null
        }

        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery {
            bosca.db.transaction<Any?>(any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[0] as suspend () -> Any?
            block()
        }

        provides { cacheManager }
        provides { requestCacheSerializer }

        service = CampaignServiceImpl(
            campaignRepository,
            campaignSegmentRepository,
            segmentService,
            messageService,
            campaignMessageBuilder,
            jobQueue
        )
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    private suspend fun <T> withRequestCache(block: suspend () -> T): T {
        val cache = RequestCache(cacheManager, requestCacheSerializer)
        return withContext(cache.asCoroutineContext()) {
            block()
        }
    }

    // --- add ---

    @Test
    fun `add passes placement and weight to repository`() = runTest {
        withRequestCache {
            val segmentId = UUID.random()
            val input = CampaignInput(
                name = "Hero Banner",
                channel = NotificationChannel.BANNER,
                segmentIds = listOf(segmentId),
                placement = "hero",
                weight = 80
            )
            val created = Campaign(
                id = UUID.random(),
                name = "Hero Banner",
                channel = NotificationChannel.BANNER,
                placement = "hero",
                weight = 80
            )
            coEvery { campaignRepository.add(any()) } returns created

            val result = service.add(input)

            assertEquals("hero", result.placement)
            assertEquals(80, result.weight)
            coVerify { campaignSegmentRepository.add(created.id, segmentId) }
        }
    }

    @Test
    fun `add with default placement and weight`() = runTest {
        withRequestCache {
            val input = CampaignInput(
                name = "Push Blast",
                channel = NotificationChannel.PUSH,
                segmentIds = emptyList()
            )
            val created = Campaign(
                id = UUID.random(),
                name = "Push Blast",
                channel = NotificationChannel.PUSH
            )
            coEvery { campaignRepository.add(any()) } returns created

            val result = service.add(input)

            assertNull(result.placement)
            assertEquals(0, result.weight)
        }
    }

    @Test
    fun `add validates BML email campaign content`() = runTest {
        withRequestCache {
            val content = JsonObject(
                mapOf(
                    "project" to JsonPrimitive("product-emails"),
                    "templateKey" to JsonPrimitive("newsletter")
                )
            )
            val input = CampaignInput(
                name = "Email Blast",
                channel = NotificationChannel.EMAIL,
                content = content,
                segmentIds = emptyList()
            )
            val created = Campaign(
                id = UUID.random(),
                name = "Email Blast",
                channel = NotificationChannel.EMAIL,
                content = content
            )
            coEvery { campaignRepository.add(any()) } returns created

            service.add(input)

            verify { campaignMessageBuilder.validateEmailCampaignContent(content) }
        }
    }

    // --- edit ---

    @Test
    fun `edit updates placement and weight`() = runTest {
        withRequestCache {
            val id = UUID.random()
            val existing = Campaign(
                id = id,
                name = "Old Banner",
                channel = NotificationChannel.BANNER,
                status = NotificationStatus.DRAFT,
                placement = "top",
                weight = 10
            )
            val updated = existing.copy(
                name = "Updated Banner",
                placement = "hero",
                weight = 90
            )
            coEvery { campaignRepository.getById(id) } returns existing
            coEvery { campaignRepository.update(any()) } returns updated

            val input = CampaignInput(
                name = "Updated Banner",
                channel = NotificationChannel.BANNER,
                segmentIds = emptyList(),
                placement = "hero",
                weight = 90
            )
            val result = service.edit(id, input)

            assertEquals("hero", result.placement)
            assertEquals(90, result.weight)
        }
    }

    @Test
    fun `edit rejects non-draft non-cancelled campaigns`() = runTest {
        withRequestCache {
            val id = UUID.random()
            val existing = Campaign(
                id = id,
                name = "Active Banner",
                channel = NotificationChannel.BANNER,
                status = NotificationStatus.ACTIVE
            )
            coEvery { campaignRepository.getById(id) } returns existing

            val input = CampaignInput(
                name = "Updated",
                channel = NotificationChannel.BANNER,
                segmentIds = emptyList()
            )
            assertFailsWith<IllegalStateException> {
                service.edit(id, input)
            }
        }
    }

    // --- getActiveBannerWeightsForPlacement ---

    @Test
    fun `getActiveBannerWeightsForPlacement with profileId delegates to profile-aware query`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val weights = listOf(
                BannerWeight(UUID.random(), 80),
                BannerWeight(UUID.random(), 20)
            )
            coEvery {
                campaignRepository.getActiveBannerWeightsForPlacement(profileId, "top")
            } returns weights

            val result = service.getActiveBannerWeightsForPlacement(profileId, "top")

            assertEquals(2, result.size)
            assertEquals(80, result[0].weight)
            assertEquals(20, result[1].weight)
        }
    }

    @Test
    fun `getActiveBannerWeightsForPlacement with null profileId delegates to everyone query`() = runTest {
        withRequestCache {
            val weights = listOf(BannerWeight(UUID.random(), 50))
            coEvery {
                campaignRepository.getActiveBannerWeightsForPlacementEveryone("sidebar")
            } returns weights

            val result = service.getActiveBannerWeightsForPlacement(null, "sidebar")

            assertEquals(1, result.size)
            assertEquals(50, result[0].weight)
        }
    }

    @Test
    fun `getActiveBannerWeightsForPlacement returns empty for no matches`() = runTest {
        withRequestCache {
            coEvery {
                campaignRepository.getActiveBannerWeightsForPlacementEveryone("nonexistent")
            } returns emptyList()

            val result = service.getActiveBannerWeightsForPlacement(null, "nonexistent")

            assertEquals(0, result.size)
        }
    }

    // --- getActiveBannerForPlacement ---

    @Test
    fun `getActiveBannerForPlacement returns null when no weights match`() = runTest {
        withRequestCache {
            coEvery {
                campaignRepository.getActiveBannerWeightsForPlacementEveryone("top")
            } returns emptyList()

            val result = service.getActiveBannerForPlacement(null, "top")

            assertNull(result)
        }
    }

    @Test
    fun `getActiveBannerForPlacement returns campaign for single match`() = runTest {
        withRequestCache {
            val campaignId = UUID.random()
            val campaign = Campaign(
                id = campaignId,
                name = "Only Banner",
                channel = NotificationChannel.BANNER,
                placement = "top",
                weight = 100
            )
            coEvery {
                campaignRepository.getActiveBannerWeightsForPlacementEveryone("top")
            } returns listOf(BannerWeight(campaignId, 100))
            coEvery { campaignRepository.getById(campaignId) } returns campaign

            val result = service.getActiveBannerForPlacement(null, "top")

            assertNotNull(result)
            assertEquals(campaignId, result.id)
        }
    }

    // --- getActiveBannersForProfile ---

    @Test
    fun `getActiveBannersForProfile delegates to repository`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val campaigns = listOf(
                Campaign(id = UUID.random(), name = "B1", channel = NotificationChannel.BANNER, weight = 50),
                Campaign(id = UUID.random(), name = "B2", channel = NotificationChannel.BANNER, weight = 30)
            )
            coEvery { campaignRepository.getActiveBannersForProfile(profileId) } returns campaigns

            val result = service.getActiveBannersForProfile(profileId)

            assertEquals(2, result.size)
        }
    }

    // --- getActiveBanners ---

    @Test
    fun `getActiveBanners returns everyone banners`() = runTest {
        withRequestCache {
            val campaigns = listOf(
                Campaign(id = UUID.random(), name = "Everyone", channel = NotificationChannel.BANNER, weight = 100)
            )
            coEvery { campaignRepository.getActiveBannersEveryone() } returns campaigns

            val result = service.getActiveBanners()

            assertEquals(1, result.size)
            assertEquals("Everyone", result[0].name)
        }
    }

    // --- delete ---

    @Test
    fun `delete removes campaign and segment associations`() = runTest {
        withRequestCache {
            val id = UUID.random()

            service.delete(id)

            coVerify {
                campaignSegmentRepository.deleteByCampaignId(id)
                campaignRepository.deleteById(id)
            }
        }
    }

    // --- cancel ---

    @Test
    fun `cancel rejects campaigns not in scheduled, sent, or active status`() = runTest {
        withRequestCache {
            val id = UUID.random()
            val campaign = Campaign(
                id = id,
                name = "Draft",
                channel = NotificationChannel.BANNER,
                status = NotificationStatus.DRAFT
            )
            coEvery { campaignRepository.getById(id) } returns campaign

            assertFailsWith<IllegalStateException> {
                service.cancel(id)
            }
        }
    }

    // --- reactivate ---

    @Test
    fun `reactivate rejects non-cancelled campaigns`() = runTest {
        withRequestCache {
            val id = UUID.random()
            val campaign = Campaign(
                id = id,
                name = "Active",
                channel = NotificationChannel.BANNER,
                status = NotificationStatus.ACTIVE
            )
            coEvery { campaignRepository.getById(id) } returns campaign

            assertFailsWith<IllegalStateException> {
                service.reactivate(id)
            }
        }
    }

    @Test
    fun `reactivate transitions cancelled campaign to active`() = runTest {
        withRequestCache {
            val id = UUID.random()
            val campaign = Campaign(
                id = id,
                name = "Cancelled",
                channel = NotificationChannel.BANNER,
                status = NotificationStatus.CANCELLED
            )
            val reactivated = campaign.copy(status = NotificationStatus.ACTIVE)
            coEvery { campaignRepository.getById(id) } returns campaign
            coEvery {
                campaignRepository.updateStatus(id, NotificationStatus.ACTIVE)
            } returns reactivated

            val result = service.reactivate(id)

            assertEquals(NotificationStatus.ACTIVE, result.status)
        }
    }
}
