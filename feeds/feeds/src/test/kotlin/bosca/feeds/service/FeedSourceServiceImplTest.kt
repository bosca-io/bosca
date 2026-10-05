package bosca.feeds.service

import bosca.configuration.model.Configuration
import bosca.configuration.model.ConfigurationInput
import bosca.configuration.service.ConfigurationService
import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.service.SchedulerService
import bosca.feeds.model.FeedAuth
import bosca.feeds.model.FeedAuthSecret
import bosca.feeds.model.FeedConfiguration
import bosca.feeds.model.FeedSource
import bosca.feeds.model.FeedSourceInput
import bosca.feeds.model.Ownership
import bosca.feeds.model.SourceType
import bosca.feeds.repository.FeedSourceRepository
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.source.model.Source
import bosca.source.model.SourceInput
import bosca.source.service.SourceService
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

/**
 * a feed source stores its (secret-free) [FeedConfiguration] on the content `Source` and its
 * operational projection in `feeds.feed_sources`; the auth secret is routed to `ConfigurationService`,
 * never the config. Upstream services + the repository are mocked; `transaction {}` is a pass-through.
 */
@OptIn(ExperimentalUuidApi::class)
class FeedSourceServiceImplTest {

    private val sourceService = mockk<SourceService>()
    private val repository = mockk<FeedSourceRepository>(relaxed = true)
    private val configurationService = mockk<ConfigurationService>(relaxed = true)
    private val schedulerService = mockk<SchedulerService>(relaxed = true)
    private val json = Json {
        serializersModule = SerializersModule {
            contextual(UUID::class, UUIDSerializer())
            contextual(java.time.OffsetDateTime::class, OffsetDateTimeSerializer())
        }
    }
    private lateinit var service: FeedSourceServiceImpl

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        coEvery { schedulerService.createJob(any(), any()) } returns mockk<ScheduledJob> { every { id } returns UUID.random() }
        // No canonical-URL overlap by default; the overlap tests override this for a specific URL.
        coEvery { repository.getByUrl(any()) } returns null
        service = FeedSourceServiceImpl(sourceService, repository, configurationService, schedulerService, json)
    }

    @AfterTest
    fun teardown() = unmockkAll()

    @Test
    fun `create stores secret-free config on the Source and the secret in ConfigurationService`() = runTest {
        val sourceId = UUID.random()
        val sourceSlot = slot<SourceInput>()
        val feedSlot = slot<FeedSource>()
        val configSlot = slot<ConfigurationInput>()
        coEvery { sourceService.add(capture(sourceSlot)) } answers {
            Source(
                id = sourceId,
                name = sourceSlot.captured.name,
                description = sourceSlot.captured.description,
                configuration = sourceSlot.captured.configuration,
            )
        }
        coEvery { repository.add(capture(feedSlot)) } answers { feedSlot.captured }
        coEvery { configurationService.getByKey(any()) } returns null
        coEvery { configurationService.setConfiguration(capture(configSlot)) } returns mockk()

        val config = FeedConfiguration(
            type = SourceType.RSS,
            endpoint = "https://example.com/feed.xml/",
            cronInterval = "0 */15 * * * *",
            ownership = Ownership.MANAGED,
            auth = FeedAuth.Bearer,
        )
        val result = service.create(FeedSourceInput("Example", "An example feed", config, authSecret = "tok"))

        // url normalized; written to the feed_sources record
        assertEquals("https://example.com/feed.xml", result.url)
        assertEquals(sourceId, result.sourceId)

        // the content Source.configuration carries the secret-free descriptor (no token anywhere in it)
        val stored = json.decodeFromJsonElement(FeedConfiguration.serializer(), sourceSlot.captured.configuration)
        assertEquals(SourceType.RSS, stored.type)
        assertEquals(FeedAuth.Bearer, stored.auth)
        assertEquals(false, sourceSlot.captured.configuration.toString().contains("tok"))

        // the secret went to ConfigurationService as a private entry under the per-source key
        assertEquals("feeds.source.$sourceId.auth", configSlot.captured.key)
        assertEquals(false, configSlot.captured.public)
        assertEquals("tok", json.decodeFromJsonElement(FeedAuthSecret.serializer(), configSlot.captured.value).secret)

        coVerify(exactly = 1) { sourceService.add(any()) }
        coVerify(exactly = 1) { repository.add(any()) }
    }

    @Test
    fun `create without a secret does not touch ConfigurationService`() = runTest {
        val sourceId = UUID.random()
        coEvery { sourceService.add(any()) } answers {
            val input = firstArg<SourceInput>()
            Source(id = sourceId, name = input.name, description = input.description, configuration = input.configuration)
        }
        coEvery { repository.add(any()) } answers { firstArg() }

        service.create(
            FeedSourceInput(
                "No auth", "desc",
                FeedConfiguration(type = SourceType.RSS, endpoint = "https://x.example/f", cronInterval = "0 0 * * * *"),
            )
        )

        coVerify(exactly = 0) { configurationService.setConfiguration(any()) }
        coVerify(exactly = 0) { configurationService.setValue(any(), any()) }
    }

    @Test
    fun `get returns the operational record`() = runTest {
        val sourceId = UUID.random()
        val feedSource = FeedSource(sourceId = sourceId, url = "https://a.example/atom")
        coEvery { repository.get(sourceId) } returns feedSource
        assertEquals(feedSource, service.get(sourceId))
    }

    @Test
    fun `getConfiguration decodes the typed config from the content Source`() = runTest {
        val sourceId = UUID.random()
        val config = FeedConfiguration(
            type = SourceType.ATOM,
            endpoint = "https://a.example/atom",
            cronInterval = "0 0 * * * *",
            url = "https://a.example/atom",
        )
        coEvery { repository.get(sourceId) } returns FeedSource(sourceId = sourceId, url = "https://a.example/atom")
        coEvery { sourceService.getById(sourceId) } returns Source(
            id = sourceId,
            name = "Atom",
            description = "d",
            configuration = json.encodeToJsonElement(FeedConfiguration.serializer(), config),
        )

        assertEquals(SourceType.ATOM, service.getConfiguration(sourceId)?.type)
    }

    @Test
    fun `getAuthSecret reads the secret back from ConfigurationService`() = runTest {
        val sourceId = UUID.random()
        val configId = UUID.random()
        coEvery { configurationService.getByKey("feeds.source.$sourceId.auth") } returns
            mockk<Configuration> { every { id } returns configId }
        coEvery { configurationService.getValue(configId) } returns
            json.encodeToJsonElement(FeedAuthSecret.serializer(), FeedAuthSecret("tok"))

        assertEquals("tok", service.getAuthSecret(sourceId))
    }

    @Test
    fun `get returns null when there is no feed_sources record`() = runTest {
        val id = UUID.random()
        coEvery { repository.get(id) } returns null
        assertNull(service.get(id))
    }

    @Test
    fun `update rewrites the Source configuration and normalizes the canonical url`() = runTest {
        val sourceId = UUID.random()
        val editSlot = slot<SourceInput>()
        coEvery { repository.get(sourceId) } returns FeedSource(sourceId = sourceId, url = "old")
        coEvery { sourceService.edit(eq(sourceId), capture(editSlot)) } answers {
            Source(id = sourceId, name = editSlot.captured.name, description = editSlot.captured.description, configuration = editSlot.captured.configuration)
        }
        coEvery { repository.update(any(), any(), any(), any()) } returns
            FeedSource(sourceId = sourceId, url = "https://e.example/feed", enabled = true)

        val config = FeedConfiguration(type = SourceType.RSS, endpoint = "https://e.example/feed/", cronInterval = "0 0 * * * *")
        val result = service.update(sourceId, FeedSourceInput("E", "d", config))

        assertEquals("https://e.example/feed", result.url)
        val stored = json.decodeFromJsonElement(FeedConfiguration.serializer(), editSlot.captured.configuration)
        assertEquals("https://e.example/feed", stored.url)
        coVerify(exactly = 1) { sourceService.edit(eq(sourceId), any()) }
    }

    @Test
    fun `setEnabled toggles the record`() = runTest {
        val sourceId = UUID.random()
        coEvery { repository.get(sourceId) } returns FeedSource(sourceId = sourceId, url = "x", enabled = true)
        coEvery { repository.setEnabled(sourceId, false) } returns
            FeedSource(sourceId = sourceId, url = "x", enabled = false)
        assertEquals(false, service.setEnabled(sourceId, false)?.enabled)
    }

    @Test
    fun `delete soft-deletes the record and removes the auth secret`() = runTest {
        val sourceId = UUID.random()
        coEvery { repository.get(sourceId) } returns FeedSource(sourceId = sourceId, url = "x")
        coJustRun { repository.softDelete(sourceId) }
        coEvery { configurationService.getByKey("feeds.source.$sourceId.auth") } returns
            mockk<Configuration> { every { id } returns UUID.random() }

        assertEquals(true, service.delete(sourceId))

        coVerify(exactly = 1) { repository.softDelete(sourceId) }
        coVerify(exactly = 1) { configurationService.deleteConfiguration("feeds.source.$sourceId.auth") }
    }

    @Test
    fun `delete returns false when the source does not exist`() = runTest {
        val sourceId = UUID.random()
        coEvery { repository.get(sourceId) } returns null
        assertEquals(false, service.delete(sourceId))
        coVerify(exactly = 0) { repository.softDelete(any()) }
    }

    @Test
    fun `create canonicalizes the endpoint (scheme and host case, default port, fragment, trailing slash)`() = runTest {
        val sourceId = UUID.random()
        val feedSlot = slot<FeedSource>()
        coEvery { sourceService.add(any()) } answers {
            val input = firstArg<SourceInput>()
            Source(id = sourceId, name = input.name, description = input.description, configuration = input.configuration)
        }
        coEvery { repository.add(capture(feedSlot)) } answers { feedSlot.captured }

        val config = FeedConfiguration(
            type = SourceType.RSS,
            endpoint = "HTTPS://Example.COM:443/Feed/#section",
            cronInterval = "0 0 * * * *",
        )
        val result = service.create(FeedSourceInput("C", "d", config))

        assertEquals("https://example.com/Feed", result.url)
    }

    @Test
    fun `create rejects a canonical url already claimed by another source`() = runTest {
        coEvery { repository.getByUrl("https://dup.example/feed") } returns
            FeedSource(sourceId = UUID.random(), url = "https://dup.example/feed")

        val config = FeedConfiguration(type = SourceType.RSS, endpoint = "https://dup.example/feed", cronInterval = "0 0 * * * *")
        assertFailsWith<IllegalStateException> { service.create(FeedSourceInput("Dup", "d", config)) }
        coVerify(exactly = 0) { sourceService.add(any()) }
    }

    @Test
    fun `update rejects switching to a url owned by another source`() = runTest {
        val sourceId = UUID.random()
        coEvery { repository.get(sourceId) } returns FeedSource(sourceId = sourceId, url = "https://old.example/feed")
        coEvery { repository.getByUrl("https://taken.example/feed") } returns
            FeedSource(sourceId = UUID.random(), url = "https://taken.example/feed")

        val config = FeedConfiguration(type = SourceType.RSS, endpoint = "https://taken.example/feed", cronInterval = "0 0 * * * *")
        assertFailsWith<IllegalStateException> { service.update(sourceId, FeedSourceInput("X", "d", config)) }
        coVerify(exactly = 0) { sourceService.edit(any(), any()) }
    }

    @Test
    fun `update allows a source to keep its own canonical url`() = runTest {
        val sourceId = UUID.random()
        val editSlot = slot<SourceInput>()
        coEvery { repository.get(sourceId) } returns FeedSource(sourceId = sourceId, url = "https://same.example/feed")
        coEvery { repository.getByUrl("https://same.example/feed") } returns
            FeedSource(sourceId = sourceId, url = "https://same.example/feed")
        coEvery { sourceService.edit(eq(sourceId), capture(editSlot)) } answers {
            Source(id = sourceId, name = editSlot.captured.name, description = editSlot.captured.description, configuration = editSlot.captured.configuration)
        }
        coEvery { repository.update(any(), any(), any(), any()) } returns
            FeedSource(sourceId = sourceId, url = "https://same.example/feed")

        val config = FeedConfiguration(type = SourceType.RSS, endpoint = "https://same.example/feed", cronInterval = "0 0 * * * *")
        val result = service.update(sourceId, FeedSourceInput("X", "d", config))

        assertEquals("https://same.example/feed", result.url)
    }
}
