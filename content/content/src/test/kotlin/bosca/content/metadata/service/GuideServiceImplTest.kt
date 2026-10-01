package bosca.content.metadata.service

import bosca.cache.Cache
import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.asCoroutineContext
import bosca.content.metadata.model.GuideStep
import bosca.content.metadata.model.GuideStepModule
import bosca.content.metadata.repository.GuideRepository
import bosca.content.metadata.repository.GuideStepModuleRepository
import bosca.content.metadata.repository.GuideStepRepository
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(InternalDI::class)
class GuideServiceImplTest {

    private val guideRepository = mockk<GuideRepository>()
    private val guideStepRepository = mockk<GuideStepRepository>()
    private val guideStepModuleRepository = mockk<GuideStepModuleRepository>()
    private val connectionManager = mockk<bosca.db.ConnectionManager>()
    private val cacheManager = mockk<CacheManager>(relaxed = true)
    private val requestCacheSerializer = mockk<RequestCacheSerializer>(relaxed = true)
    private val remoteCache = mockk<Cache<Any>>(relaxed = true)

    private lateinit var service: GuideServiceImpl

    private val metadataId = UUID.random()
    private val version = 1

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()

        coEvery { cacheManager.maybeAddCache<Any>(any(), any()) } returns remoteCache
        coEvery { cacheManager.getCache<Any>(any()) } returns remoteCache
        every { remoteCache.keySerializer } returns mockk(relaxed = true)

        provides { cacheManager }
        provides { requestCacheSerializer }

        service = GuideServiceImpl(
            guideRepository = guideRepository,
            guideStepRepository = guideStepRepository,
            guideStepModuleRepository = guideStepModuleRepository,
        )

        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.connection() } returns connectionManager
        coEvery { connectionManager.commitTransaction(any()) } returns Unit
        coEvery {
            bosca.db.transaction<Any?>(any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[0] as suspend () -> Any?
            block()
        }
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
        ProviderRegistry.clear()
    }

    private suspend fun <T> withRequestCache(block: suspend () -> T): T {
        val cache = RequestCache(cacheManager, requestCacheSerializer)
        return withContext(cache.asCoroutineContext()) {
            block()
        }
    }

    @Test
    fun `reorderSteps updates sort positions based on provided ID sequence`() = runTest {
        withRequestCache {
            val steps = listOf(
                GuideStep(id = 10, metadataId = metadataId, version = version, stepMetadataId = UUID.random(), stepMetadataVersion = 1, sort = 0),
                GuideStep(id = 20, metadataId = metadataId, version = version, stepMetadataId = UUID.random(), stepMetadataVersion = 1, sort = 1),
                GuideStep(id = 30, metadataId = metadataId, version = version, stepMetadataId = UUID.random(), stepMetadataVersion = 1, sort = 2),
            )

            coEvery { guideStepRepository.getByMetadataIdAndVersion(metadataId, version) } returns steps
            coEvery { guideStepRepository.setGuideStepSort(any(), any(), any(), any()) } returns Unit

            // Reverse the order: 30, 20, 10
            service.reorderSteps(metadataId, version, listOf(30, 20, 10))

            coVerify { guideStepRepository.setGuideStepSort(metadataId, version, 30, 0) }
            coVerify { guideStepRepository.setGuideStepSort(metadataId, version, 20, 1) }
            coVerify { guideStepRepository.setGuideStepSort(metadataId, version, 10, 2) }
        }
    }

    @Test
    fun `reorderSteps skips unknown step IDs gracefully`() = runTest {
        withRequestCache {
            val steps = listOf(
                GuideStep(id = 10, metadataId = metadataId, version = version, stepMetadataId = UUID.random(), stepMetadataVersion = 1, sort = 0),
                GuideStep(id = 20, metadataId = metadataId, version = version, stepMetadataId = UUID.random(), stepMetadataVersion = 1, sort = 1),
            )

            coEvery { guideStepRepository.getByMetadataIdAndVersion(metadataId, version) } returns steps
            coEvery { guideStepRepository.setGuideStepSort(any(), any(), any(), any()) } returns Unit

            // Include a non-existent ID (999)
            service.reorderSteps(metadataId, version, listOf(20, 999, 10))

            coVerify { guideStepRepository.setGuideStepSort(metadataId, version, 20, 0) }
            coVerify { guideStepRepository.setGuideStepSort(metadataId, version, 10, 2) }
            coVerify(exactly = 0) { guideStepRepository.setGuideStepSort(metadataId, version, 999, any()) }
        }
    }

    @Test
    fun `reorderSteps with empty list does not update any sorts`() = runTest {
        withRequestCache {
            coEvery { guideStepRepository.getByMetadataIdAndVersion(metadataId, version) } returns emptyList()

            service.reorderSteps(metadataId, version, emptyList())

            coVerify(exactly = 0) { guideStepRepository.setGuideStepSort(any(), any(), any(), any()) }
        }
    }

    @Test
    fun `reorderModules updates sort positions for modules within a step`() = runTest {
        withRequestCache {
            val stepId = 5L
            val modules = listOf(
                GuideStepModule(id = 100, metadataId = metadataId, version = version, step = stepId, moduleMetadataId = UUID.random(), moduleMetadataVersion = 1, sort = 0),
                GuideStepModule(id = 200, metadataId = metadataId, version = version, step = stepId, moduleMetadataId = UUID.random(), moduleMetadataVersion = 1, sort = 1),
                GuideStepModule(id = 300, metadataId = metadataId, version = version, step = stepId, moduleMetadataId = UUID.random(), moduleMetadataVersion = 1, sort = 2),
            )

            coEvery { guideStepModuleRepository.getByMetadataIdAndVersionAndStep(metadataId, version, stepId) } returns modules
            coEvery { guideStepModuleRepository.setSort(any(), any()) } returns Unit

            // Reverse module order: 300, 200, 100
            service.reorderModules(metadataId, version, stepId, listOf(300, 200, 100))

            coVerify { guideStepModuleRepository.setSort(300, 0) }
            coVerify { guideStepModuleRepository.setSort(200, 1) }
            coVerify { guideStepModuleRepository.setSort(100, 2) }
        }
    }

    @Test
    fun `reorderModules skips unknown module IDs gracefully`() = runTest {
        withRequestCache {
            val stepId = 5L
            val modules = listOf(
                GuideStepModule(id = 100, metadataId = metadataId, version = version, step = stepId, moduleMetadataId = UUID.random(), moduleMetadataVersion = 1, sort = 0),
            )

            coEvery { guideStepModuleRepository.getByMetadataIdAndVersionAndStep(metadataId, version, stepId) } returns modules
            coEvery { guideStepModuleRepository.setSort(any(), any()) } returns Unit

            service.reorderModules(metadataId, version, stepId, listOf(999, 100))

            coVerify { guideStepModuleRepository.setSort(100, 1) }
            coVerify(exactly = 0) { guideStepModuleRepository.setSort(999, any()) }
        }
    }

    @Test
    fun `reorderModules with empty list does not update any sorts`() = runTest {
        withRequestCache {
            val stepId = 5L
            coEvery { guideStepModuleRepository.getByMetadataIdAndVersionAndStep(metadataId, version, stepId) } returns emptyList()

            service.reorderModules(metadataId, version, stepId, emptyList())

            coVerify(exactly = 0) { guideStepModuleRepository.setSort(any(), any()) }
        }
    }

    @Test
    fun `reorderSteps preserves order when IDs match current order`() = runTest {
        withRequestCache {
            val steps = listOf(
                GuideStep(id = 1, metadataId = metadataId, version = version, stepMetadataId = UUID.random(), stepMetadataVersion = 1, sort = 0),
                GuideStep(id = 2, metadataId = metadataId, version = version, stepMetadataId = UUID.random(), stepMetadataVersion = 1, sort = 1),
            )

            coEvery { guideStepRepository.getByMetadataIdAndVersion(metadataId, version) } returns steps
            coEvery { guideStepRepository.setGuideStepSort(any(), any(), any(), any()) } returns Unit

            // Same order as current
            service.reorderSteps(metadataId, version, listOf(1, 2))

            coVerify { guideStepRepository.setGuideStepSort(metadataId, version, 1, 0) }
            coVerify { guideStepRepository.setGuideStepSort(metadataId, version, 2, 1) }
        }
    }
}
