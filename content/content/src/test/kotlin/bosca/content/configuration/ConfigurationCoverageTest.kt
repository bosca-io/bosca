package bosca.content.configuration

import bosca.cache.CacheManager
import bosca.content.collection.installer.BiblesCollectionInstaller
import bosca.content.collection.installer.RootCollectionInstaller
import bosca.content.collection.repository.CollectionFindRepository
import bosca.content.collection.service.CollectionJobHistoryService
import bosca.content.collection.service.CollectionService
import bosca.content.embedding.model.EmbeddingConfiguration
import bosca.content.healthcheck.ContentHealthCheckRepository
import bosca.content.metadata.repository.MetadataFindRepository
import bosca.content.metadata.service.MetadataJobHistoryService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.state.service.StateService
import bosca.content.tools.installer.ToolsPackageInstaller
import bosca.content.tools.service.TemplateAttributeToolService
import bosca.content.transition.jobs.UpdateContentJobHistoryOnCompleteListener
import bosca.content.transition.service.TransitionService
import bosca.content.transition.service.Transitioner
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.security.service.SecurityService
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import bosca.slug.service.SlugService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the [Configuration] @Provider factory methods not exercised by ConfigurationTest.
 * ConfigurationTest already covers rootCollectionPackage()/biblePackage(); this file covers the
 * remaining repository/service/queue/installer providers and every branch of
 * embeddingConfiguration().
 */
class ConfigurationCoverageTest {

    private val configuration = Configuration()

    private val cacheManager = mockk<CacheManager>(relaxed = true)

    /**
     * [CollectionFindRepository] and [MetadataFindRepository] build [bosca.cache.ServiceCache]
     * instances in their constructors, which resolve a [CacheManager] from the global
     * [ProviderRegistry]. Register a mock so the factory providers can construct them.
     */
    @OptIn(InternalDI::class)
    @BeforeTest
    fun setup() {
        mockkObject(ProviderRegistry)
        every { ProviderRegistry.get(CacheManager::class) } returns object : ObjectProvider<CacheManager> {
            override val type = CacheManager::class
            override suspend fun get() = cacheManager
        }
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    /** Builds a real [ApplicationConfig] from a YAML document so the real property lookups run. */
    private fun config(yaml: String): ApplicationConfig =
        ApplicationConfig.load(yaml.byteInputStream())

    private fun applicationWith(yaml: String): BoscaApplication {
        val application = mockk<BoscaApplication>()
        every { application.environment } returns BoscaApplication.Environment(config(yaml))
        return application
    }

    @Test
    fun `collectionFind returns a CollectionFindRepository`() {
        val repository = configuration.collectionFind(Json)
        assertNotNull(repository)
        assertTrue(repository is CollectionFindRepository)
    }

    @Test
    fun `metadataFind returns a MetadataFindRepository`() {
        val repository = configuration.metadataFind(Json)
        assertNotNull(repository)
        assertTrue(repository is MetadataFindRepository)
    }

    @Test
    fun `contentHealthCheck returns a ContentHealthCheckRepository`() {
        val repository = configuration.contentHealthCheck()
        assertNotNull(repository)
        assertTrue(repository is ContentHealthCheckRepository)
    }

    @Test
    fun `embeddingConfiguration uses defaults when no embedding config present`() {
        val application = applicationWith("server:\n  name: test\n")
        val defaults = EmbeddingConfiguration()
        val result = configuration.embeddingConfiguration(application)
        assertEquals(defaults.enabled, result.enabled)
        assertEquals(defaults.url, result.url)
        assertEquals(defaults.model, result.model)
        assertEquals(defaults.dimension, result.dimension)
        assertEquals(defaults.documentPrompt, result.documentPrompt)
        assertEquals(defaults.chunkOverlapTokens, result.chunkOverlapTokens)
        assertEquals(defaults.timeoutSeconds, result.timeoutSeconds)
    }

    @Test
    fun `embeddingConfiguration reads every provided property`() {
        val yaml = """
            embedding:
              enabled: "true"
              url: "http://custom-embed:9000"
              model: "custom/model-v2"
              dimension: "1024"
              documentPrompt: "title: none | text: {text}"
              chunkOverlapTokens: "96"
              timeoutSeconds: "45"
        """.trimIndent()
        val application = applicationWith(yaml)
        val result = configuration.embeddingConfiguration(application)
        assertEquals(true, result.enabled)
        assertEquals("http://custom-embed:9000", result.url)
        assertEquals("custom/model-v2", result.model)
        assertEquals(1024, result.dimension)
        assertEquals("title: none | text: {text}", result.documentPrompt)
        assertEquals(96, result.chunkOverlapTokens)
        assertEquals(45, result.timeoutSeconds)
    }

    @Test
    fun `embeddingConfiguration falls back to defaults for non-numeric dimension and timeout`() {
        val yaml = """
            embedding:
              dimension: "not-a-number"
              timeoutSeconds: "also-bad"
        """.trimIndent()
        val application = applicationWith(yaml)
        val defaults = EmbeddingConfiguration()
        val result = configuration.embeddingConfiguration(application)
        assertEquals(defaults.dimension, result.dimension)
        assertEquals(defaults.timeoutSeconds, result.timeoutSeconds)
    }

    @Test
    fun `embeddingConfiguration disabled false parses to false`() {
        val yaml = """
            embedding:
              enabled: "false"
        """.trimIndent()
        val application = applicationWith(yaml)
        val result = configuration.embeddingConfiguration(application)
        assertEquals(false, result.enabled)
    }

    @Test
    fun `contentJobQueue delegates to factory create with content queue name`() {
        val factory = mockk<JobQueueFactory>()
        val queue = mockk<JobQueue>()
        every { factory.create(JobQueueNames.contentQueue) } returns queue
        val result = configuration.contentJobQueue(factory)
        assertSame(queue, result)
        val expectedName = JobQueueNames.contentQueue
        verify { factory.create(expectedName) }
    }

    @Test
    fun `contentJobQueueRunner builds a JobRunner`() {
        val queue = mockk<JobQueue>()
        val lockFactory = mockk<DistributedLockFactory>()
        val errorCapture = mockk<ObjectProvider<ErrorCapture>>()
        val runner = configuration.contentJobQueueRunner(queue, lockFactory, errorCapture)
        assertNotNull(runner)
        assertTrue(runner is JobRunner)
    }

    @Test
    fun `updateContentJobHistoryOnCompleteListener wires its dependencies`() {
        val listener = configuration.updateContentJobHistoryOnCompleteListener(
            mockk<MetadataJobHistoryService>(),
            mockk<CollectionJobHistoryService>(),
            mockk<MetadataService>(),
            mockk<CollectionService>(),
            mockk<SecurityService>(),
            mockk<Transitioner>(),
        )
        assertNotNull(listener)
        assertTrue(listener is UpdateContentJobHistoryOnCompleteListener)
    }

    @Test
    fun `transitioner wires its dependencies`() {
        val transitioner = configuration.transitioner(
            mockk<MetadataService>(),
            mockk<MetadataJobHistoryService>(),
            mockk<CollectionService>(),
            mockk<CollectionJobHistoryService>(),
            mockk<TransitionService>(),
            mockk<StateService>(),
            mockk<MetadataPermissionEvaluator>(relaxed = true),
            mockk<CollectionPermissionEvaluator>(relaxed = true),
        )
        assertNotNull(transitioner)
        assertTrue(transitioner is Transitioner)
    }

    @Test
    fun `rootCollectionInstaller returns a RootCollectionInstaller`() {
        val installer = configuration.rootCollectionInstaller(mockk<CollectionService>())
        assertNotNull(installer)
        assertTrue(installer is RootCollectionInstaller)
    }

    @Test
    fun `biblesCollectionInstaller returns a BiblesCollectionInstaller`() {
        val installer = configuration.biblesCollectionInstaller(
            mockk<CollectionService>(),
            mockk<SlugService>(),
        )
        assertNotNull(installer)
        assertTrue(installer is BiblesCollectionInstaller)
    }

    @Test
    fun `toolsInstaller returns a ToolsPackageInstaller`() {
        val installer = configuration.toolsInstaller(mockk<TemplateAttributeToolService>())
        assertNotNull(installer)
        assertTrue(installer is ToolsPackageInstaller)
    }
}
