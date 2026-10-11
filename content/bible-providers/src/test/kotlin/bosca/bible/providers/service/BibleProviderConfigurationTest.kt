@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.bible.providers.service

import bosca.bible.BibleAttribution
import bosca.bible.BibleContentUse
import bosca.bible.Chapter
import bosca.bible.Reference
import bosca.bible.providers.apibible.ApiBibleException
import bosca.bible.providers.apibible.ApiBibleProvider
import bosca.bible.providers.model.BibleChapterCache
import bosca.bible.providers.model.BibleProviderConfiguration
import bosca.bible.providers.model.BibleProviderConfigurationInput
import bosca.bible.providers.model.BibleProviderSettings
import bosca.bible.providers.model.BibleSource
import bosca.bible.providers.model.BibleSourceKind
import bosca.bible.providers.repository.BibleChapterCacheRepositoryImpl
import bosca.bible.providers.configuration.BibleProvidersMigration
import bosca.lock.DistributedLockFactory
import bosca.lock.redis.RedisDistributedLockFactory
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.redis.RedisCacheManager
import bosca.cache.redis.RedisCacheScripts
import bosca.cache.withRequestCache
import bosca.configuration.model.ConfigurationInput
import bosca.configuration.service.ConfigurationService
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.di.BibleProvidersProviderRegistrar
import bosca.di.ConfigurationProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.security.encryption.EncryptionService
import bosca.security.encryption.EncryptionServiceImpl
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.serialization.OffsetDateTime
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.test.resources.SharedPostgreSQLContainer
import bosca.test.resources.SharedValkeyContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.AfterClass
import org.junit.BeforeClass
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.toJavaUuid

class BibleProviderConfigurationTest {
    companion object {
        private val postgres = SharedPostgreSQLContainer().withDatabaseName("bible_configurations")
        private val valkey = SharedValkeyContainer()
        private val cleanupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private lateinit var pool: ConnectionPool
        private lateinit var cacheManager: CacheManager
        private lateinit var locks: DistributedLockFactory

        @BeforeClass
        @JvmStatic
        fun start() = runBlocking {
            postgres.start()
            valkey.start()
            pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(
                postgres.jdbcUrl, postgres.username, postgres.password,
            ), key = "bible-configurations-test"))
            val connections = valkey.newConnectionPool()
            locks = RedisDistributedLockFactory(connections)
            cacheManager = RedisCacheManager(connections, RedisCacheScripts(connections), cleanupScope)
            FlywayMigration(pool).migrate(listOf(CoreMigration(), BibleProvidersMigration()))
        }

        @AfterClass
        @JvmStatic
        fun stop() = runBlocking {
            cleanupScope.cancel()
            pool.close()
            valkey.stop()
            postgres.stop()
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
            contextual(OffsetDateTimeSerializer())
        }
    }
    private lateinit var configurationService: ConfigurationService
    private lateinit var service: BibleProviderService
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val metadataIds = mutableListOf<UUID>()

    @BeforeTest
    fun setup() = runBlocking {
        ProviderRegistry.clear()
        val application = BoscaApplication(ApplicationConfig.load("""
            security:
              encryption:
                key: bible-configuration-test-key
        """.trimIndent().byteInputStream()))
        provides<Json>(overrideExisting = true) { json }
        provides<CacheManager> { cacheManager }
        provides<DistributedLockFactory> { locks }
        provides<RequestCacheSerializer> { RequestCacheSerializerImpl(json) }
        provides<EncryptionService> { EncryptionServiceImpl(application) }
        ConfigurationProviderRegistrar().register()
        configurationService = ProviderRegistry.get<ConfigurationService>()
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        bosca.di.provides<bosca.content.metadata.service.MetadataService> { mockk() }
        bosca.di.provides<bosca.content.metadata.service.BibleService> { mockk() }
        bosca.di.provides<bosca.content.collection.service.CollectionService> { mockk() }
        bosca.di.provides<bosca.slug.service.SlugService> { mockk() }
        BibleProvidersProviderRegistrar().register()
        provides<BibleProvider>(singleton = true, name = "API_BIBLE", overrideExisting = true) {
            ApiBibleProvider(configurationService, json, client, server.url("/v1/"))
        }
        service = ProviderRegistry.get<BibleProviderService>()
    }

    @AfterTest
    fun cleanup() {
        request {
            for (configuration in configurationService.getAll()) {
                configurationService.deleteConfiguration(configuration.key)
            }
            for (metadataId in metadataIds) connection().useStatement("delete from metadata where id = ?") {
                it.setObject(1, metadataId.toJavaUuid())
                it.executeUpdate()
            }
            cacheManager.clearAll()
        }
        server.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        ProviderRegistry.clear()
    }

    @Test
    fun `saving encrypts credentials and returns private safe details without network access`() {
        val saved = request { service.saveConfiguration(input()) }
        assertEquals("Bible source", saved.name)
        assertEquals("API_BIBLE", saved.provider)
        assertTrue(saved.enabled)
        assertTrue(saved.hasApiKey)
        assertEquals(0, server.requestCount)
        request {
            val metadata = configurationService.getAll().single()
            assertEquals(saved.id, metadata.id)
            assertFalse(metadata.public)
            assertTrue(configurationService.getPermissions(metadata).isEmpty())
            val value = assertNotNull(configurationService.getValue(saved.id)).jsonObject
            assertEquals("saved-secret", value.getValue("apiKey").jsonPrimitive.content)
            assertFalse(json.decodeFromJsonElement(BibleProviderSettings.serializer(), value).toString().contains("saved-secret"))
            assertFalse(ciphertext(saved.id).decodeToString().contains("saved-secret"))
            assertFalse(metadata.toString().contains("saved-secret"))
            assertFalse(saved.toString().contains("saved-secret"))
            assertFalse(json.encodeToString(BibleProviderConfiguration.serializer(), saved).contains("saved-secret"))
        }
    }

    @Test
    fun `listing includes disabled configurations and excludes other platform settings`() {
        val first = request { service.saveConfiguration(input()) }
        val second = request { service.saveConfiguration(input().copy(name = "Other", enabled = false)) }
        request {
            configurationService.setConfiguration(ConfigurationInput("other-integration", "Other integration",
                JsonPrimitive("unrelated-secret"), false, emptyList()))
            assertEquals(listOf("API_BIBLE"), service.getProviders())
            assertEquals(setOf(first, second), service.getConfigurations().toSet())
        }
    }

    @Test
    fun `rename and disable preserve the saved key sources cache and permissions across requests`() {
        val saved = request { service.saveConfiguration(input()) }
        val metadata = request { configurationService.getAll().single() }
        val permissions = request {
            val groupId = connection().useStatement("select id from groups where name = 'administrators'") {
                it.executeQuery().use { result -> assertTrue(result.next()); UUID.parse(result.getString(1)) }
            }
            configurationService.setConfiguration(ConfigurationInput(metadata.key, metadata.description,
                assertNotNull(configurationService.getValue(saved.id)), false,
                listOf(PermissionInput(PermissionAction.VIEW, saved.id, groupId))))
            configurationService.getPermissions(metadata).also { assertEquals(1, it.size) }
        }
        val source = request { addSource(saved.id) }
        val cache = BibleChapterCache(source.id, "API_BIBLE", "JHN.1", Chapter(Reference("JHN.1"), null),
            null, 1, "cached-token", OffsetDateTime.now(), OffsetDateTime.now().plusDays(1))
        request { BibleChapterCacheRepositoryImpl().put(cache) }

        val changed = request { service.saveConfiguration(input().copy(
            id = saved.id, name = "Renamed", enabled = false, apiKey = null,
        )) }

        assertEquals(saved.copy(name = "Renamed", enabled = false), changed)
        request {
            assertEquals(metadata, configurationService.getByKey(metadata.key))
            assertEquals(source, ProviderRegistry.get<BibleSourceService>().getById(source.id))
            assertEquals("cached-token", BibleChapterCacheRepositoryImpl().get(source.id, "JHN.1", cache.created)?.fumsToken)
            assertEquals(permissions, configurationService.getPermissions(metadata))
        }
        server.enqueue(MockResponse.Builder().body("{\"data\":[]}").build())
        request { service.test(saved.id) }
        assertEquals("saved-secret", server.takeRequest().headers["api-key"])
    }

    @Test
    fun `locked configuration reads require a transaction and refresh previously cached credentials`() = request {
        val saved = service.saveConfiguration(input())
        assertNotNull(configurationService.getValue(saved.id))
        assertFailsWith<IllegalStateException> { configurationService.getValueForUpdate(saved.id) }
        request { service.saveConfiguration(input().copy(id = saved.id, apiKey = "rotated-secret")) }

        transaction { assertNotNull(configurationService.getValueForUpdate(saved.id)) }

        server.enqueue(MockResponse.Builder().body("{\"data\":[]}").build())
        service.test(saved.id)
        assertEquals("rotated-secret", server.takeRequest().headers["api-key"])
    }

    @Test
    fun `concurrent configuration updates wait for commit and preserve the latest credentials`() = runBlocking {
        withTimeout(30_000) {
            val saved = request { service.saveConfiguration(input()) }
            val rotated = CompletableDeferred<Unit>()
            val commit = CompletableDeferred<Unit>()
            val renameStarted = CompletableDeferred<Unit>()
            val rotation = async(Dispatchers.IO) {
                request {
                    transaction {
                        service.saveConfiguration(input().copy(id = saved.id, apiKey = "rotated-secret"))
                        rotated.complete(Unit)
                        commit.await()
                    }
                }
            }
            try {
                rotated.await()
                val rename = async(Dispatchers.IO) {
                    request {
                        assertNotNull(configurationService.getValue(saved.id))
                        renameStarted.complete(Unit)
                        service.saveConfiguration(input().copy(id = saved.id, name = "Renamed", apiKey = null))
                    }
                }
                renameStarted.await()
                delay(200)
                assertFalse(rename.isCompleted)
                commit.complete(Unit)
                rotation.await()
                assertEquals("Renamed", rename.await().name)
                request {
                    val value = assertNotNull(configurationService.getValue(saved.id)).jsonObject
                    assertEquals("rotated-secret", value.getValue("apiKey").jsonPrimitive.content)
                }
            } finally {
                commit.complete(Unit)
            }
        }
    }

    @Test
    fun `supplied key replaces encrypted credentials and is used on the next connection test`() {
        val saved = request { service.saveConfiguration(input()) }
        val before = request { ciphertext(saved.id) }
        request { service.saveConfiguration(input().copy(id = saved.id, apiKey = "replacement-secret")) }
        request { assertFalse(before.contentEquals(ciphertext(saved.id))) }
        server.enqueue(MockResponse.Builder().body("{\"data\":[]}").build())
        request { service.test(saved.id) }
        assertEquals("replacement-secret", server.takeRequest().headers["api-key"])
        assertEquals(listOf(saved), request { service.getConfigurations() })
    }

    @Test
    fun `shared provider reads each saved configuration and observes key rotation`() {
        val first = request { service.saveConfiguration(input()) }
        val second = request { service.saveConfiguration(input().copy(name = "Second", apiKey = "second-secret")) }
        val provider = request { service.getProvider(first.id) }
        assertEquals(provider, request { service.getProvider(second.id) })
        request { service.saveConfiguration(input().copy(id = first.id, apiKey = "replacement-secret")) }

        for ((configurationId, expectedKey) in listOf(first.id to "replacement-secret", second.id to "second-secret", first.id to "replacement-secret")) {
            server.enqueue(MockResponse.Builder().body("{\"data\":[]}").build())
            request { provider.test(configurationId) }
            assertEquals(expectedKey, server.takeRequest().headers["api-key"])
        }
    }

    @Test
    fun `connection test requests the catalog once without creating sources or fetching bodies`() {
        val saved = request { service.saveConfiguration(input()) }
        server.enqueue(MockResponse.Builder().body("{\"data\":[]}").build())
        request { service.test(saved.id) }
        assertEquals(1, server.requestCount)
        val http = server.takeRequest()
        assertEquals("/v1/bibles", http.url.encodedPath)
        assertEquals("true", http.url.queryParameter("include-full-details"))
        request { assertTrue(ProviderRegistry.get<BibleSourceService>().getByConfiguration(saved.id).isEmpty()) }
    }

    @Test
    fun `invalid input fails without creating a configuration`() = request {
        for (invalid in listOf(input().copy(name = " "), input().copy(provider = "unknown"),
            input().copy(apiKey = null), input().copy(apiKey = " "), input().copy(id = UUID.random()))) {
            assertFailsWith<IllegalArgumentException> { service.saveConfiguration(invalid) }
        }
        assertTrue(configurationService.getAll().isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `another integration cannot be read tested or overwritten as a Bible configuration`() = request {
        val other = configurationService.setConfiguration(ConfigurationInput("other-integration", "Other",
            JsonPrimitive("other-secret"), false, emptyList()))
        assertFailsWith<IllegalArgumentException> { service.saveConfiguration(input().copy(id = other.id)) }
        assertFailsWith<IllegalArgumentException> { service.test(other.id) }
        assertFailsWith<IllegalArgumentException> { service.test(UUID.random()) }
        assertEquals(JsonPrimitive("other-secret"), configurationService.getValue(other.id))
        assertTrue(service.getConfigurations().isEmpty())
    }

    @Test
    fun `upstream failures retain status without exposing saved credentials`() {
        val saved = request { service.saveConfiguration(input()) }
        server.enqueue(MockResponse.Builder().code(401).body("saved-secret").build())
        val failure = assertFailsWith<ApiBibleException> { request { service.test(saved.id) } }
        assertEquals(401, failure.statusCode)
        assertFalse(failure.toString().contains("saved-secret"))
        assertEquals(listOf(saved), request { service.getConfigurations() })
    }

    @Test
    fun `connection test preserves cancellation`() {
        val saved = request { service.saveConfiguration(input()) }
        val cancellation = CancellationException("Cancelled test")
        val provider = mockk<BibleProvider>()
        coEvery { provider.test(saved.id) } throws cancellation
        provides<BibleProvider>(name = "API_BIBLE", overrideExisting = true) { provider }
        val failure = assertFailsWith<CancellationException> { request { service.test(saved.id) } }
        assertEquals(cancellation.message, failure.message)
    }

    @Test
    fun `malformed saved settings fail without printing their credentials`() {
        val saved = request { service.saveConfiguration(input()) }
        request { configurationService.setValue(saved.id, buildJsonObject {
            put("name", "Bible")
            put("provider", "API_BIBLE")
            put("enabled", "wrong-type")
            put("apiKey", "saved-secret")
        }) }
        val failure = assertFailsWith<SerializationException> { request { service.getConfigurations() } }
        assertFalse(failure.toString().contains("saved-secret"))
    }

    @Test
    fun `disabled configurations can be tested but cannot supply content providers`() {
        val saved = request { service.saveConfiguration(input().copy(enabled = false)) }
        request { assertFailsWith<IllegalStateException> { service.getProvider(saved.id) } }
        server.enqueue(MockResponse.Builder().body("{\"data\":[]}").build())
        request { service.test(saved.id) }
        request { service.saveConfiguration(input().copy(id = saved.id, apiKey = null)) }
        request { assertEquals("API_BIBLE", service.getProvider(saved.id).key) }
    }

    @Test
    fun `missing saved credentials are reported safely and cannot be used for provider access`() {
        val saved = request { service.saveConfiguration(input()) }
        request { configurationService.setValue(saved.id, json.encodeToJsonElement(BibleProviderSettings.serializer(),
            BibleProviderSettings(saved.name, saved.provider, saved.enabled))) }
        request {
            assertEquals(listOf(saved.copy(hasApiKey = false)), service.getConfigurations())
            assertFailsWith<IllegalArgumentException> { service.test(saved.id) }
            assertFailsWith<IllegalArgumentException> { service.getProvider(saved.id).test(saved.id) }
            assertFailsWith<IllegalArgumentException> { service.saveConfiguration(input().copy(id = saved.id, apiKey = null)) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `generated registrar provides the configuration component with the platform service`() = request {
        val wired = ProviderRegistry.get<BibleProviderService>()
        val saved = wired.saveConfiguration(input())
        assertEquals(listOf(saved), wired.getConfigurations())
        assertEquals("API_BIBLE", wired.getProvider(saved.id).key)
    }

    @Test
    fun `service resolves the named provider and passes the saved configuration identity`() = request {
        val saved = service.saveConfiguration(input())
        val registered = mockk<BibleProvider>()
        coEvery { registered.test(saved.id) } returns Unit
        provides<BibleProvider>(name = "API_BIBLE", overrideExisting = true) { registered }

        assertEquals(registered, service.getProvider(saved.id))
        service.test(saved.id)
        coVerify(exactly = 1) { registered.test(saved.id) }
    }

    @Test
    fun `supported providers come from named DI registrations without constructing them`() = request {
        var constructed = false
        provides<BibleProvider>(name = "OTHER_PROVIDER") {
            constructed = true
            mockk<BibleProvider>()
        }

        assertEquals(listOf("API_BIBLE", "OTHER_PROVIDER"), service.getProviders())
        val saved = service.saveConfiguration(input().copy(provider = "OTHER_PROVIDER"))
        assertFalse(constructed)
        service.getProvider(saved.id)
        assertTrue(constructed)
    }

    private fun input() = BibleProviderConfigurationInput("Bible source", "API_BIBLE", true, apiKey = "saved-secret")

    private suspend fun ciphertext(id: UUID): ByteArray = connection().useStatement(
        "select value from configuration_values where configuration_id = ?",
    ) {
        it.setObject(1, id.toJavaUuid())
        it.executeQuery().use { result -> assertTrue(result.next()); result.getBytes(1) }
    }

    private suspend fun addSource(configurationId: UUID): BibleSource {
        val metadataId = UUID.random()
        metadataIds.add(metadataId)
        connection().useStatement("insert into metadata (id, name, content_type, version) values (?, 'Bible', 'bible', 1)") {
            it.setObject(1, metadataId.toJavaUuid())
            it.executeUpdate()
        }
        connection().useStatement("""
            insert into bibles (metadata_id, version, variant, system_id, name, name_local,
                description, abbreviation, abbreviation_local)
            values (?, 1, 'default', 'system', 'Bible', 'Bible', '', 'B', 'B')
        """.trimIndent()) {
            it.setObject(1, metadataId.toJavaUuid())
            it.executeUpdate()
        }
        return ProviderRegistry.get<BibleSourceService>().add(BibleSource(UUID.random(), configurationId, "edition", metadataId,
            1, "default", BibleSourceKind.TEXT, "Bible", "Bible", "B", BibleAttribution("Copyright"),
            setOf(BibleContentUse.DISPLAY)))
    }

    private fun <T> request(block: suspend () -> T): T = runBlocking {
        val manager = pool.connection()
        try {
            withContext(manager.asCoroutineContext()) { withRequestCache { block() } }
        } finally {
            withContext(NonCancellable) { manager.release() }
        }
    }
}
