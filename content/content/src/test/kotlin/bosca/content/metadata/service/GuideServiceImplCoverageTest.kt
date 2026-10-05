package bosca.content.metadata.service

import bosca.cache.Cache
import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.CacheManager
import bosca.cache.CacheValue
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.asCoroutineContext
import bosca.content.metadata.model.Guide
import bosca.content.metadata.model.GuideInput
import bosca.content.metadata.model.GuideStep
import bosca.content.metadata.model.GuideStepCount
import bosca.content.metadata.model.GuideStepInput
import bosca.content.metadata.model.GuideStepModule
import bosca.content.metadata.model.GuideStepModuleInput
import bosca.content.metadata.model.GuideType
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.repository.GuideRepository
import bosca.content.metadata.repository.GuideStepModuleRepository
import bosca.content.metadata.repository.GuideStepRepository
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.graphql.Batch
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class GuideServiceImplCoverageTest {

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

    /**
     * A real (non-mock) key serializer that round-trips the typed key value, so the
     * [RequestCache] batch path resolves keys correctly against the batch resolver.
     */
    private val fakeKeySerializer = object : CacheKeySerializer<Any> {
        override fun toLocalKey(cacheName: String, value: Any): CacheKey<Any> = object : CacheKey<Any> {
            override val cacheName: String = cacheName
            override val key: Any = value
            override fun toRemoteKey(prefix: Boolean): String =
                if (prefix) "$cacheName:" else "$cacheName:$value"
        }

        override fun fromRemoteKey(key: String): CacheKey<Any> = error("not used in tests")
    }

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()

        coEvery { cacheManager.maybeAddCache<Any>(any(), any()) } returns remoteCache
        coEvery { cacheManager.getCache<Any>(any()) } returns remoteCache
        every { remoteCache.keySerializer } returns fakeKeySerializer
        // Remote batch lookup: report every requested key as a cache miss so the
        // batch resolver runs and populates the batch from the repository.
        coEvery { remoteCache.getBatch(any()) } answers {
            @Suppress("UNCHECKED_CAST")
            val keys = firstArg<List<CacheKey<Any>>>()
            keys.map {
                object : CacheValue {
                    override val value: String? = null
                    override val exists: Boolean = false
                }
            }
        }

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

    /**
     * Drives the single-key cache path to a `null` result: the remote reports an entry that
     * *exists* but has a `null` payload (a negative cache entry). Because the entry exists,
     * [RequestCache.get] never invokes the single-key resolver, so `ServiceCache.get(...)`
     * returns `null` — exercising the service's `?: emptyList()` / `?: 0` / `?: error(...)`
     * fallbacks without violating the repositories' non-null return contracts.
     */
    private fun stubRemoteNegativeHit() {
        coEvery { remoteCache.get(any()) } returns object : CacheValue {
            override val value: String? = null
            override val exists: Boolean = true
        }
    }

    private fun metadata(id: UUID = UUID.random()): Metadata = Metadata(
        id = id,
        name = "m",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = 0L,
        languageTag = "en",
        workflowStateId = "draft",
    )

    // ── getGuide ───────────────────────────────────────────────────────────────

    @Test
    fun `getGuide resolves via repository on cache miss`() = runTest {
        withRequestCache {
            val guide = Guide(
                metadataId = metadataId,
                version = version,
                rrule = null,
                type = GuideType.LINEAR,
                templateMetadataId = null,
                templateMetadataVersion = null,
            )
            coEvery { guideRepository.getByMetadataIdAndVersion(metadataId, version) } returns guide

            val result = service.getGuide(metadataId, version)

            assertNotNull(result)
            assertEquals(metadataId, result.metadataId)
            assertEquals(GuideType.LINEAR, result.type)
        }
    }

    // ── getGuideStep ─────────────────────────────────────────────────────────────

    @Test
    fun `getGuideStep returns matching step`() = runTest {
        withRequestCache {
            val steps = listOf(
                GuideStep(id = 1, metadataId = metadataId, version = version, stepMetadataId = UUID.random(), stepMetadataVersion = 1, sort = 0),
                GuideStep(id = 2, metadataId = metadataId, version = version, stepMetadataId = UUID.random(), stepMetadataVersion = 1, sort = 1),
            )
            coEvery { guideStepRepository.getByMetadataIdAndVersion(metadataId, version) } returns steps

            val step = service.getGuideStep(metadataId, version, 2)

            assertEquals(2L, step.id)
        }
    }

    @Test
    fun `getGuideStep throws when step id not present`() = runTest {
        withRequestCache {
            val steps = listOf(
                GuideStep(id = 1, metadataId = metadataId, version = version, stepMetadataId = UUID.random(), stepMetadataVersion = 1, sort = 0),
            )
            coEvery { guideStepRepository.getByMetadataIdAndVersion(metadataId, version) } returns steps

            assertFailsWith<IllegalStateException> {
                service.getGuideStep(metadataId, version, 999)
            }
        }
    }

    @Test
    fun `getGuideStep throws when steps resolve to null`() = runTest {
        withRequestCache {
            // Remote reports an existing-but-empty entry (negative cache hit) so the resolver
            // is skipped and cache.get() returns null -> service raises "missing guide steps".
            stubRemoteNegativeHit()

            assertFailsWith<IllegalStateException> {
                service.getGuideStep(metadataId, version, 1)
            }
        }
    }

    // ── getGuideStepModules ──────────────────────────────────────────────────────

    @Test
    fun `getGuideStepModules returns resolved modules`() = runTest {
        withRequestCache {
            val stepId = 5L
            val modules = listOf(
                GuideStepModule(id = 100, metadataId = metadataId, version = version, step = stepId, moduleMetadataId = UUID.random(), moduleMetadataVersion = 1, sort = 0),
            )
            coEvery { guideStepModuleRepository.getByMetadataIdAndVersionAndStep(metadataId, version, stepId) } returns modules

            val result = service.getGuideStepModules(metadataId, version, stepId)

            assertEquals(1, result.size)
            assertEquals(100L, result.first().id)
        }
    }

    @Test
    fun `getGuideStepModules returns empty list when resolver yields null`() = runTest {
        withRequestCache {
            val stepId = 7L
            // Remote negative hit -> cache.get() returns null -> service returns emptyList().
            stubRemoteNegativeHit()

            val result = service.getGuideStepModules(metadataId, version, stepId)

            assertTrue(result.isEmpty())
        }
    }

    // ── getGuideSteps (three branches) ──────────────────────────────────────────

    @Test
    fun `getGuideSteps with offset and limit uses paged repository query`() = runTest {
        withRequestCache {
            val paged = listOf(
                GuideStep(id = 3, metadataId = metadataId, version = version, stepMetadataId = UUID.random(), stepMetadataVersion = 1, sort = 0),
            )
            coEvery { guideStepRepository.getByMetadataIdAndVersion(metadataId, version, 10, 5) } returns paged

            val result = service.getGuideSteps(metadataId, version, offset = 10, limit = 5)

            assertEquals(1, result.size)
            assertEquals(3L, result.first().id)
        }
    }

    @Test
    fun `getGuideSteps with offset only uses offset repository query`() = runTest {
        withRequestCache {
            val paged = listOf(
                GuideStep(id = 4, metadataId = metadataId, version = version, stepMetadataId = UUID.random(), stepMetadataVersion = 1, sort = 0),
            )
            coEvery { guideStepRepository.getByMetadataIdAndVersion(metadataId, version, 2) } returns paged

            val result = service.getGuideSteps(metadataId, version, offset = 2, limit = null)

            assertEquals(1, result.size)
            assertEquals(4L, result.first().id)
        }
    }

    @Test
    fun `getGuideSteps with no offset uses cache and returns resolved list`() = runTest {
        withRequestCache {
            val cached = listOf(
                GuideStep(id = 5, metadataId = metadataId, version = version, stepMetadataId = UUID.random(), stepMetadataVersion = 1, sort = 0),
            )
            coEvery { guideStepRepository.getByMetadataIdAndVersion(metadataId, version) } returns cached

            val result = service.getGuideSteps(metadataId, version, offset = null, limit = null)

            assertEquals(1, result.size)
            assertEquals(5L, result.first().id)
        }
    }

    @Test
    fun `getGuideSteps with limit only falls through to cache branch`() = runTest {
        withRequestCache {
            // offset == null so the (offset != null && limit != null) and (offset != null) branches are skipped
            coEvery { guideStepRepository.getByMetadataIdAndVersion(metadataId, version) } returns emptyList()

            val result = service.getGuideSteps(metadataId, version, offset = null, limit = 9)

            assertTrue(result.isEmpty())
        }
    }

    @Test
    fun `getGuideSteps cache branch returns empty when resolver yields null`() = runTest {
        withRequestCache {
            // Remote negative hit -> cache.get() returns null -> service returns emptyList().
            stubRemoteNegativeHit()

            val result = service.getGuideSteps(metadataId, version, offset = null, limit = null)

            assertTrue(result.isEmpty())
        }
    }

    // ── getStepCount ─────────────────────────────────────────────────────────────

    @Test
    fun `getStepCount returns resolved count`() = runTest {
        withRequestCache {
            coEvery { guideStepRepository.getCountByMetadataIdAndVersion(metadataId, version) } returns 42L

            val count = service.getStepCount(metadataId, version)

            assertEquals(42L, count)
        }
    }

    @Test
    fun `getStepCount returns zero when resolver yields null`() = runTest {
        withRequestCache {
            // Remote negative hit -> cache.get() returns null -> service returns 0.
            stubRemoteNegativeHit()

            val count = service.getStepCount(metadataId, version)

            assertEquals(0L, count)
        }
    }

    // ── batch helpers ────────────────────────────────────────────────────────────

    @Test
    fun `addGuidesToBatch resolves guides via batch resolver`() = runTest {
        withRequestCache {
            val guide = Guide(
                metadataId = metadataId,
                version = version,
                rrule = null,
                type = GuideType.LINEAR,
                templateMetadataId = null,
                templateMetadataVersion = null,
            )
            coEvery { guideRepository.getByMetadataIds(any()) } returns listOf(guide)

            val batch = Batch<MetadataCacheKeyId, Guide>(
                listOf(MetadataCacheKeyId(metadataId, version))
            )
            service.addGuidesToBatch(batch)

            assertNotNull(batch.getData(MetadataCacheKeyId(metadataId, version)))
        }
    }

    @Test
    fun `addStepCountsToBatch resolves counts via batch resolver`() = runTest {
        withRequestCache {
            coEvery { guideStepRepository.getCountByMetadataIds(any()) } returns listOf(
                GuideStepCount(metadataId = metadataId, version = version, count = 3L)
            )

            val batch = Batch<MetadataCacheKeyId, Long>(
                listOf(MetadataCacheKeyId(metadataId, version))
            )
            service.addStepCountsToBatch(batch)

            assertEquals(3L, batch.getData(MetadataCacheKeyId(metadataId, version)))
        }
    }

    @Test
    fun `addGuideStepsToBatch resolves steps and fills empty defaults`() = runTest {
        withRequestCache {
            val step = GuideStep(id = 1, metadataId = metadataId, version = version, stepMetadataId = UUID.random(), stepMetadataVersion = 1, sort = 0)
            coEvery { guideStepRepository.getByMetadataIds(any()) } returns listOf(step)

            val batch = Batch<MetadataCacheKeyId, List<GuideStep>>(
                listOf(MetadataCacheKeyId(metadataId, version))
            )
            service.addGuideStepsToBatch(batch)

            assertNotNull(batch.getData(MetadataCacheKeyId(metadataId, version)))
        }
    }

    @Test
    fun `addGuideStepModulesToBatch resolves modules and fills empty defaults`() = runTest {
        withRequestCache {
            val module = GuideStepModule(id = 1, metadataId = metadataId, version = version, step = 5, moduleMetadataId = UUID.random(), moduleMetadataVersion = 1, sort = 0)
            coEvery { guideStepModuleRepository.getByMetadataIds(any()) } returns listOf(module)

            val batch = Batch<MetadataCacheKeyId, List<GuideStepModule>>(
                listOf(MetadataCacheKeyId(metadataId, version))
            )
            service.addGuideStepModulesToBatch(batch)

            assertNotNull(batch.getData(MetadataCacheKeyId(metadataId, version)))
        }
    }

    // ── setGuideRrule / setGuideStepSort ─────────────────────────────────────────

    @Test
    fun `setGuideRrule delegates to repository`() = runTest {
        withRequestCache {
            coEvery { guideRepository.setRrule(metadataId, version, "FREQ=DAILY") } returns Unit

            service.setGuideRrule(metadataId, version, "FREQ=DAILY")

            coVerify { guideRepository.setRrule(metadataId, version, "FREQ=DAILY") }
        }
    }

    @Test
    fun `setGuideType delegates to repository`() = runTest {
        withRequestCache {
            coEvery { guideRepository.setType(metadataId, version, GuideType.CALENDAR) } returns Unit

            service.setGuideType(metadataId, version, GuideType.CALENDAR)

            coVerify { guideRepository.setType(metadataId, version, GuideType.CALENDAR) }
        }
    }

    @Test
    fun `setGuideStepSort delegates to repository`() = runTest {
        withRequestCache {
            coEvery { guideStepRepository.setGuideStepSort(metadataId, version, 7L, 3) } returns Unit

            service.setGuideStepSort(metadataId, version, 7L, 3)

            coVerify { guideStepRepository.setGuideStepSort(metadataId, version, 7L, 3) }
        }
    }

    // ── removeFromCache ──────────────────────────────────────────────────────────

    @Test
    fun `removeFromCache clears all guide caches`() = runTest {
        withRequestCache {
            service.removeFromCache(metadataId, version)
            // No repository interaction; exercises the four cache removes.
            service.removeFromCache(metadataId, null)
        }
    }

    // ── addGuide ─────────────────────────────────────────────────────────────────

    @Test
    fun `addGuide persists guide with steps and modules`() = runTest {
        withRequestCache {
            val stepMeta = UUID.random()
            val moduleMeta = UUID.random()
            val guideInput = GuideInput(
                guideType = GuideType.LINEAR,
                rrule = "FREQ=WEEKLY",
                steps = listOf(
                    GuideStepInput(
                        stepMetadataId = stepMeta,
                        stepMetadataVersion = 1,
                        modules = listOf(
                            GuideStepModuleInput(moduleMetadataId = moduleMeta, moduleMetadataVersion = 1),
                        ),
                    ),
                ),
                templateMetadataId = null,
                templateMetadataVersion = null,
            )

            val persistedGuide = Guide(
                metadataId = metadataId, version = version, rrule = "FREQ=WEEKLY",
                type = GuideType.LINEAR, templateMetadataId = null, templateMetadataVersion = null,
            )
            val persistedStep = GuideStep(id = 11, metadataId = metadataId, version = version, stepMetadataId = stepMeta, stepMetadataVersion = 1, sort = 0)

            val persistedModule = GuideStepModule(id = 99, metadataId = metadataId, version = version, step = 11, moduleMetadataId = moduleMeta, moduleMetadataVersion = 1, sort = 0)
            coEvery { guideRepository.add(any()) } returns persistedGuide
            coEvery { guideStepRepository.add(any()) } returns persistedStep
            coEvery { guideStepModuleRepository.add(any()) } returns persistedModule

            val result = service.addGuide(metadataId, version, guideInput)

            assertEquals(GuideType.LINEAR, result.type)
            coVerify { guideRepository.add(any()) }
            coVerify { guideStepRepository.add(any()) }
            coVerify { guideStepModuleRepository.add(any()) }
        }
    }

    @Test
    fun `addGuide with no steps persists just the guide`() = runTest {
        withRequestCache {
            val guideInput = GuideInput(
                guideType = GuideType.CALENDAR,
                rrule = null,
                steps = emptyList(),
                templateMetadataId = null,
                templateMetadataVersion = null,
            )
            val persistedGuide = Guide(
                metadataId = metadataId, version = version, rrule = null,
                type = GuideType.CALENDAR, templateMetadataId = null, templateMetadataVersion = null,
            )
            coEvery { guideRepository.add(any()) } returns persistedGuide

            val result = service.addGuide(metadataId, version, guideInput)

            assertEquals(GuideType.CALENDAR, result.type)
            coVerify(exactly = 0) { guideStepRepository.add(any()) }
        }
    }

    @Test
    fun `addGuide throws when step metadata id missing`() = runTest {
        withRequestCache {
            val guideInput = GuideInput(
                guideType = GuideType.LINEAR, rrule = null,
                steps = listOf(GuideStepInput(stepMetadataId = null, stepMetadataVersion = 1, modules = emptyList())),
                templateMetadataId = null, templateMetadataVersion = null,
            )
            coEvery { guideRepository.add(any()) } returns Guide(
                metadataId = metadataId, version = version, rrule = null,
                type = GuideType.LINEAR, templateMetadataId = null, templateMetadataVersion = null,
            )

            assertFailsWith<IllegalStateException> { service.addGuide(metadataId, version, guideInput) }
        }
    }

    @Test
    fun `addGuide throws when step metadata version missing`() = runTest {
        withRequestCache {
            val guideInput = GuideInput(
                guideType = GuideType.LINEAR, rrule = null,
                steps = listOf(GuideStepInput(stepMetadataId = UUID.random(), stepMetadataVersion = null, modules = emptyList())),
                templateMetadataId = null, templateMetadataVersion = null,
            )
            coEvery { guideRepository.add(any()) } returns Guide(
                metadataId = metadataId, version = version, rrule = null,
                type = GuideType.LINEAR, templateMetadataId = null, templateMetadataVersion = null,
            )

            assertFailsWith<IllegalStateException> { service.addGuide(metadataId, version, guideInput) }
        }
    }

    @Test
    fun `addGuide throws when module metadata id missing`() = runTest {
        withRequestCache {
            val guideInput = GuideInput(
                guideType = GuideType.LINEAR, rrule = null,
                steps = listOf(
                    GuideStepInput(
                        stepMetadataId = UUID.random(), stepMetadataVersion = 1,
                        modules = listOf(GuideStepModuleInput(moduleMetadataId = null, moduleMetadataVersion = 1)),
                    ),
                ),
                templateMetadataId = null, templateMetadataVersion = null,
            )
            coEvery { guideRepository.add(any()) } returns Guide(
                metadataId = metadataId, version = version, rrule = null,
                type = GuideType.LINEAR, templateMetadataId = null, templateMetadataVersion = null,
            )
            coEvery { guideStepRepository.add(any()) } returns GuideStep(
                id = 1, metadataId = metadataId, version = version, stepMetadataId = UUID.random(), stepMetadataVersion = 1, sort = 0,
            )

            assertFailsWith<IllegalStateException> { service.addGuide(metadataId, version, guideInput) }
        }
    }

    @Test
    fun `addGuide throws when module metadata version missing`() = runTest {
        withRequestCache {
            val guideInput = GuideInput(
                guideType = GuideType.LINEAR, rrule = null,
                steps = listOf(
                    GuideStepInput(
                        stepMetadataId = UUID.random(), stepMetadataVersion = 1,
                        modules = listOf(GuideStepModuleInput(moduleMetadataId = UUID.random(), moduleMetadataVersion = null)),
                    ),
                ),
                templateMetadataId = null, templateMetadataVersion = null,
            )
            coEvery { guideRepository.add(any()) } returns Guide(
                metadataId = metadataId, version = version, rrule = null,
                type = GuideType.LINEAR, templateMetadataId = null, templateMetadataVersion = null,
            )
            coEvery { guideStepRepository.add(any()) } returns GuideStep(
                id = 1, metadataId = metadataId, version = version, stepMetadataId = UUID.random(), stepMetadataVersion = 1, sort = 0,
            )

            assertFailsWith<IllegalStateException> { service.addGuide(metadataId, version, guideInput) }
        }
    }

    // ── addGuideStep ─────────────────────────────────────────────────────────────

    @Test
    fun `addGuideStep persists step and modules`() = runTest {
        withRequestCache {
            val step = GuideStepInput(
                stepMetadataId = UUID.random(), stepMetadataVersion = 1,
                modules = listOf(GuideStepModuleInput(moduleMetadataId = UUID.random(), moduleMetadataVersion = 1)),
            )
            val persistedStep = GuideStep(id = 22, metadataId = metadataId, version = version, stepMetadataId = UUID.random(), stepMetadataVersion = 1, sort = 4)
            val persistedModule = GuideStepModule(id = 77, metadataId = metadataId, version = version, step = 22, moduleMetadataId = UUID.random(), moduleMetadataVersion = 1, sort = 0)
            coEvery { guideStepRepository.add(any()) } returns persistedStep
            coEvery { guideStepModuleRepository.add(any()) } returns persistedModule

            val result = service.addGuideStep(metadataId, version, step, 4)

            assertEquals(22L, result.id)
            coVerify { guideStepModuleRepository.add(any()) }
        }
    }

    @Test
    fun `addGuideStep throws when step metadata id missing`() = runTest {
        withRequestCache {
            val step = GuideStepInput(stepMetadataId = null, stepMetadataVersion = 1, modules = emptyList())
            assertFailsWith<IllegalStateException> { service.addGuideStep(metadataId, version, step, 0) }
        }
    }

    @Test
    fun `addGuideStep throws when step metadata version missing`() = runTest {
        withRequestCache {
            val step = GuideStepInput(stepMetadataId = UUID.random(), stepMetadataVersion = null, modules = emptyList())
            assertFailsWith<IllegalStateException> { service.addGuideStep(metadataId, version, step, 0) }
        }
    }

    @Test
    fun `addGuideStep throws when module metadata id missing`() = runTest {
        withRequestCache {
            val step = GuideStepInput(
                stepMetadataId = UUID.random(), stepMetadataVersion = 1,
                modules = listOf(GuideStepModuleInput(moduleMetadataId = null, moduleMetadataVersion = 1)),
            )
            coEvery { guideStepRepository.add(any()) } returns GuideStep(
                id = 1, metadataId = metadataId, version = version, stepMetadataId = UUID.random(), stepMetadataVersion = 1, sort = 0,
            )
            assertFailsWith<IllegalStateException> { service.addGuideStep(metadataId, version, step, 0) }
        }
    }

    @Test
    fun `addGuideStep throws when module metadata version missing`() = runTest {
        withRequestCache {
            val step = GuideStepInput(
                stepMetadataId = UUID.random(), stepMetadataVersion = 1,
                modules = listOf(GuideStepModuleInput(moduleMetadataId = UUID.random(), moduleMetadataVersion = null)),
            )
            coEvery { guideStepRepository.add(any()) } returns GuideStep(
                id = 1, metadataId = metadataId, version = version, stepMetadataId = UUID.random(), stepMetadataVersion = 1, sort = 0,
            )
            assertFailsWith<IllegalStateException> { service.addGuideStep(metadataId, version, step, 0) }
        }
    }

    // ── addGuideStepModule ───────────────────────────────────────────────────────

    @Test
    fun `addGuideStepModule persists module`() = runTest {
        withRequestCache {
            val module = GuideStepModuleInput(moduleMetadataId = UUID.random(), moduleMetadataVersion = 1)
            val persisted = GuideStepModule(id = 33, metadataId = metadataId, version = version, step = 5, moduleMetadataId = UUID.random(), moduleMetadataVersion = 1, sort = 2)
            coEvery { guideStepModuleRepository.add(any()) } returns persisted

            val result = service.addGuideStepModule(metadataId, version, 5, module, 2)

            assertEquals(33L, result.id)
        }
    }

    @Test
    fun `addGuideStepModule throws when module metadata id missing`() = runTest {
        withRequestCache {
            val module = GuideStepModuleInput(moduleMetadataId = null, moduleMetadataVersion = 1)
            assertFailsWith<IllegalStateException> { service.addGuideStepModule(metadataId, version, 5, module, 0) }
        }
    }

    @Test
    fun `addGuideStepModule throws when module metadata version missing`() = runTest {
        withRequestCache {
            val module = GuideStepModuleInput(moduleMetadataId = UUID.random(), moduleMetadataVersion = null)
            assertFailsWith<IllegalStateException> { service.addGuideStepModule(metadataId, version, 5, module, 0) }
        }
    }

    // ── deleteGuide ──────────────────────────────────────────────────────────────

    @Test
    fun `deleteGuide deletes steps, marks metadata deleted, and removes guide`() = runTest {
        withRequestCache {
            val svc = mockk<MetadataService>(relaxed = true)
            val stepMetaId = UUID.random()
            val step = GuideStep(id = 1, metadataId = metadataId, version = version, stepMetadataId = stepMetaId, stepMetadataVersion = 1, sort = 0)

            coEvery { guideStepRepository.getByMetadataIdAndVersion(metadataId, version) } returns listOf(step)
            // deleteGuideStep internals
            coEvery { guideStepModuleRepository.getByMetadataIdAndVersionAndStep(metadataId, version, 1L) } returns emptyList()
            coEvery { guideStepRepository.getByMetadataIdAndVersionAndStep(metadataId, version, 1L) } returns step
            coEvery { svc.getById(stepMetaId, 1) } returns metadata(stepMetaId)
            coEvery { svc.markDeleted(any()) } returns Unit
            coEvery { guideStepRepository.deleteGuideStepByMetadataIdAndVersionAndStepId(metadataId, version, 1L) } returns Unit
            // deleteGuide internals
            val guideMeta = metadata()
            coEvery { svc.getById(metadataId, version) } returns guideMeta
            coEvery { guideRepository.deleteByMetadataIdAndVersion(metadataId, version) } returns Unit

            service.deleteGuide(svc, metadataId, version)

            coVerify { guideRepository.deleteByMetadataIdAndVersion(metadataId, version) }
            val guideMetaId = guideMeta.id
            coVerify { svc.markDeleted(guideMetaId) }
        }
    }

    @Test
    fun `deleteGuide when guide metadata missing skips markDeleted for guide`() = runTest {
        withRequestCache {
            val svc = mockk<MetadataService>(relaxed = true)

            coEvery { guideStepRepository.getByMetadataIdAndVersion(metadataId, version) } returns emptyList()
            coEvery { svc.getById(metadataId, version) } returns null
            coEvery { guideRepository.deleteByMetadataIdAndVersion(metadataId, version) } returns Unit

            service.deleteGuide(svc, metadataId, version)

            coVerify { guideRepository.deleteByMetadataIdAndVersion(metadataId, version) }
        }
    }

    // ── deleteGuideStep ──────────────────────────────────────────────────────────

    @Test
    fun `deleteGuideStep deletes modules and step metadata`() = runTest {
        withRequestCache {
            val svc = mockk<MetadataService>(relaxed = true)
            val moduleMetaId = UUID.random()
            val stepMetaId = UUID.random()
            val stepId = 9L
            val module = GuideStepModule(id = 50, metadataId = metadataId, version = version, step = stepId, moduleMetadataId = moduleMetaId, moduleMetadataVersion = 1, sort = 0)
            val step = GuideStep(id = stepId, metadataId = metadataId, version = version, stepMetadataId = stepMetaId, stepMetadataVersion = 2, sort = 0)

            coEvery { guideStepModuleRepository.getByMetadataIdAndVersionAndStep(metadataId, version, stepId) } returns listOf(module)
            coEvery { svc.getById(moduleMetaId, 1) } returns metadata(moduleMetaId)
            coEvery { svc.markDeleted(any()) } returns Unit
            coEvery { guideStepModuleRepository.delete(50L) } returns Unit
            coEvery { guideStepRepository.getByMetadataIdAndVersionAndStep(metadataId, version, stepId) } returns step
            coEvery { svc.getById(stepMetaId, 2) } returns metadata(stepMetaId)
            coEvery { guideStepRepository.deleteGuideStepByMetadataIdAndVersionAndStepId(metadataId, version, stepId) } returns Unit

            service.deleteGuideStep(svc, metadataId, version, stepId)

            coVerify { guideStepModuleRepository.delete(50L) }
            coVerify { guideStepRepository.deleteGuideStepByMetadataIdAndVersionAndStepId(metadataId, version, stepId) }
        }
    }

    @Test
    fun `deleteGuideStep skips module with null module metadata id`() = runTest {
        withRequestCache {
            val svc = mockk<MetadataService>(relaxed = true)
            val stepId = 9L
            val module = GuideStepModule(id = 50, metadataId = metadataId, version = version, step = stepId, moduleMetadataId = null, moduleMetadataVersion = 1, sort = 0)
            val step = GuideStep(id = stepId, metadataId = metadataId, version = version, stepMetadataId = null, stepMetadataVersion = null, sort = 0)

            coEvery { guideStepModuleRepository.getByMetadataIdAndVersionAndStep(metadataId, version, stepId) } returns listOf(module)
            coEvery { guideStepRepository.getByMetadataIdAndVersionAndStep(metadataId, version, stepId) } returns step
            coEvery { guideStepRepository.deleteGuideStepByMetadataIdAndVersionAndStepId(metadataId, version, stepId) } returns Unit

            service.deleteGuideStep(svc, metadataId, version, stepId)

            // module was skipped (continue) so delete never called for it
            coVerify(exactly = 0) { guideStepModuleRepository.delete(50L) }
            coVerify { guideStepRepository.deleteGuideStepByMetadataIdAndVersionAndStepId(metadataId, version, stepId) }
        }
    }

    @Test
    fun `deleteGuideStep skips module with null module metadata version`() = runTest {
        withRequestCache {
            val svc = mockk<MetadataService>(relaxed = true)
            val stepId = 9L
            val module = GuideStepModule(id = 50, metadataId = metadataId, version = version, step = stepId, moduleMetadataId = UUID.random(), moduleMetadataVersion = null, sort = 0)
            val step = GuideStep(id = stepId, metadataId = metadataId, version = version, stepMetadataId = null, stepMetadataVersion = null, sort = 0)

            coEvery { guideStepModuleRepository.getByMetadataIdAndVersionAndStep(metadataId, version, stepId) } returns listOf(module)
            coEvery { guideStepRepository.getByMetadataIdAndVersionAndStep(metadataId, version, stepId) } returns step
            coEvery { guideStepRepository.deleteGuideStepByMetadataIdAndVersionAndStepId(metadataId, version, stepId) } returns Unit

            service.deleteGuideStep(svc, metadataId, version, stepId)

            coVerify(exactly = 0) { guideStepModuleRepository.delete(50L) }
        }
    }

    @Test
    fun `deleteGuideStep skips module when metadata not found`() = runTest {
        withRequestCache {
            val svc = mockk<MetadataService>(relaxed = true)
            val moduleMetaId = UUID.random()
            val stepId = 9L
            val module = GuideStepModule(id = 50, metadataId = metadataId, version = version, step = stepId, moduleMetadataId = moduleMetaId, moduleMetadataVersion = 1, sort = 0)
            val step = GuideStep(id = stepId, metadataId = metadataId, version = version, stepMetadataId = null, stepMetadataVersion = null, sort = 0)

            coEvery { guideStepModuleRepository.getByMetadataIdAndVersionAndStep(metadataId, version, stepId) } returns listOf(module)
            coEvery { svc.getById(moduleMetaId, 1) } returns null
            coEvery { guideStepRepository.getByMetadataIdAndVersionAndStep(metadataId, version, stepId) } returns step
            coEvery { guideStepRepository.deleteGuideStepByMetadataIdAndVersionAndStepId(metadataId, version, stepId) } returns Unit

            service.deleteGuideStep(svc, metadataId, version, stepId)

            // m?.id is null -> continue -> module delete skipped
            coVerify(exactly = 0) { guideStepModuleRepository.delete(50L) }
        }
    }

    @Test
    fun `deleteGuideStep with null step metadata id skips step metadata deletion`() = runTest {
        withRequestCache {
            val svc = mockk<MetadataService>(relaxed = true)
            val stepId = 12L
            val step = GuideStep(id = stepId, metadataId = metadataId, version = version, stepMetadataId = null, stepMetadataVersion = null, sort = 0)

            coEvery { guideStepModuleRepository.getByMetadataIdAndVersionAndStep(metadataId, version, stepId) } returns emptyList()
            coEvery { guideStepRepository.getByMetadataIdAndVersionAndStep(metadataId, version, stepId) } returns step
            coEvery { guideStepRepository.deleteGuideStepByMetadataIdAndVersionAndStepId(metadataId, version, stepId) } returns Unit

            service.deleteGuideStep(svc, metadataId, version, stepId)

            coVerify { guideStepRepository.deleteGuideStepByMetadataIdAndVersionAndStepId(metadataId, version, stepId) }
        }
    }

    @Test
    fun `deleteGuideStep with step metadata present but no matching metadata skips markDeleted`() = runTest {
        withRequestCache {
            val svc = mockk<MetadataService>(relaxed = true)
            val stepId = 12L
            val stepMetaId = UUID.random()
            val step = GuideStep(id = stepId, metadataId = metadataId, version = version, stepMetadataId = stepMetaId, stepMetadataVersion = 3, sort = 0)

            coEvery { guideStepModuleRepository.getByMetadataIdAndVersionAndStep(metadataId, version, stepId) } returns emptyList()
            coEvery { guideStepRepository.getByMetadataIdAndVersionAndStep(metadataId, version, stepId) } returns step
            coEvery { svc.getById(stepMetaId, 3) } returns null
            coEvery { guideStepRepository.deleteGuideStepByMetadataIdAndVersionAndStepId(metadataId, version, stepId) } returns Unit

            service.deleteGuideStep(svc, metadataId, version, stepId)

            coVerify { guideStepRepository.deleteGuideStepByMetadataIdAndVersionAndStepId(metadataId, version, stepId) }
        }
    }

    // ── deleteGuideStepModule ────────────────────────────────────────────────────

    @Test
    fun `deleteGuideStepModule deletes module and marks metadata deleted`() = runTest {
        withRequestCache {
            val svc = mockk<MetadataService>(relaxed = true)
            val moduleMetaId = UUID.random()
            val stepId = 5L
            val moduleId = 60L
            val module = GuideStepModule(id = moduleId, metadataId = metadataId, version = version, step = stepId, moduleMetadataId = moduleMetaId, moduleMetadataVersion = 1, sort = 0)

            coEvery { guideStepModuleRepository.getByMetadataIdAndVersionAndStepAndModule(metadataId, version, stepId, moduleId) } returns module
            coEvery { svc.getById(moduleMetaId, 1) } returns metadata(moduleMetaId)
            coEvery { svc.markDeleted(any()) } returns Unit
            coEvery { guideStepModuleRepository.delete(moduleId) } returns Unit

            service.deleteGuideStepModule(svc, metadataId, version, stepId, moduleId)

            coVerify { guideStepModuleRepository.delete(moduleId) }
        }
    }

    @Test
    fun `deleteGuideStepModule returns early when module metadata id null`() = runTest {
        withRequestCache {
            val svc = mockk<MetadataService>(relaxed = true)
            val stepId = 5L
            val moduleId = 60L
            val module = GuideStepModule(id = moduleId, metadataId = metadataId, version = version, step = stepId, moduleMetadataId = null, moduleMetadataVersion = 1, sort = 0)

            coEvery { guideStepModuleRepository.getByMetadataIdAndVersionAndStepAndModule(metadataId, version, stepId, moduleId) } returns module

            service.deleteGuideStepModule(svc, metadataId, version, stepId, moduleId)

            coVerify(exactly = 0) { guideStepModuleRepository.delete(moduleId) }
        }
    }

    @Test
    fun `deleteGuideStepModule returns early when module metadata version null`() = runTest {
        withRequestCache {
            val svc = mockk<MetadataService>(relaxed = true)
            val stepId = 5L
            val moduleId = 60L
            val module = GuideStepModule(id = moduleId, metadataId = metadataId, version = version, step = stepId, moduleMetadataId = UUID.random(), moduleMetadataVersion = null, sort = 0)

            coEvery { guideStepModuleRepository.getByMetadataIdAndVersionAndStepAndModule(metadataId, version, stepId, moduleId) } returns module

            service.deleteGuideStepModule(svc, metadataId, version, stepId, moduleId)

            coVerify(exactly = 0) { guideStepModuleRepository.delete(moduleId) }
        }
    }

    @Test
    fun `deleteGuideStepModule returns early when metadata not found`() = runTest {
        withRequestCache {
            val svc = mockk<MetadataService>(relaxed = true)
            val moduleMetaId = UUID.random()
            val stepId = 5L
            val moduleId = 60L
            val module = GuideStepModule(id = moduleId, metadataId = metadataId, version = version, step = stepId, moduleMetadataId = moduleMetaId, moduleMetadataVersion = 1, sort = 0)

            coEvery { guideStepModuleRepository.getByMetadataIdAndVersionAndStepAndModule(metadataId, version, stepId, moduleId) } returns module
            coEvery { svc.getById(moduleMetaId, 1) } returns null

            service.deleteGuideStepModule(svc, metadataId, version, stepId, moduleId)

            coVerify(exactly = 0) { guideStepModuleRepository.delete(moduleId) }
        }
    }
}
