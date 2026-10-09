package bosca.server.configuration

import bosca.analytics.livesessions.LiveSessionsService
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.Migration
import bosca.di.BoscaProviderRegistrar
import bosca.di.CoreProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.asProvider
import bosca.di.annotation.InternalDI
import bosca.installer.model.PackageInstallation
import bosca.nats.NatsConnectionPool
import bosca.redis.RedisConnectionPool
import bosca.security.service.SecurityConfigurationImpl
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.installer.DefaultGitEmailPipelinesInstaller
import bosca.server.installer.DefaultArtifactSyncPipelinesInstaller
import bosca.server.installer.DefaultSocialNotificationPipelinesInstaller
import bosca.server.installer.DefaultTransactionalEmailPipelinesInstaller
import bosca.server.installer.MaintenanceJobsInstaller
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class ConfigurationTest {

    private val configuration = Configuration()

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `application CORS configuration allows Bosca GraphQL client identity headers`() {
        val allowedHeaders = ApplicationConfig.loadFromClasspath()
            .property("cors.allowedHeaders")
            .getList()

        assertTrue(
            allowedHeaders.containsAll(
                listOf("X-Installation-ID", "X-App-ID", "X-App-Version"),
            ),
        )
    }

    @Test
    fun `application passkey origins are unchanged when no extra origin is configured`() {
        // The test environment leaves WEBAUTHN_EXTRA_ORIGIN unset, as every existing deployment does.
        val configuration = SecurityConfigurationImpl(BoscaApplication(ApplicationConfig.loadFromClasspath()))

        assertEquals(emptyList(), configuration.webauthn.extraOrigins)
        assertEquals(
            listOf("https://${configuration.domain}", "https://${configuration.adminDomain}").distinct(),
            configuration.webauthn.allowedOrigins(configuration.webauthn.rpId ?: configuration.domain, configuration.adminDomain),
        )
    }

    @Test
    fun `searchIndexPackage has correct key and name`() {
        val pkg = configuration.searchIndexPackage()

        assertEquals("search-index", pkg.key)
        assertEquals("Search Index", pkg.name)
    }

    @Test
    fun `searchIndexPackage has five versions`() {
        val pkg = configuration.searchIndexPackage()

        assertEquals(5, pkg.versions.size)
        assertEquals("1.0.0", pkg.versions[0].version)
        assertEquals("1.0.1", pkg.versions[1].version)
        assertEquals("1.0.4", pkg.versions[2].version)
        assertEquals("1.0.5", pkg.versions[3].version)
        assertEquals("1.0.6", pkg.versions[4].version)
    }

    @Test
    fun `searchIndexPackage version installers are correct`() {
        val pkg = configuration.searchIndexPackage()

        assertEquals(listOf("search-index"), pkg.versions[0].installerNames)
        assertEquals(listOf("search-index"), pkg.versions[1].installerNames)
        assertEquals(listOf("search-transform"), pkg.versions[2].installerNames)
        assertEquals(listOf("search-index"), pkg.versions[3].installerNames)
        assertEquals(listOf("search-index"), pkg.versions[4].installerNames)
    }

    @Test
    fun `maintenancePackage has correct structure`() {
        val pkg = configuration.maintenancePackage()

        assertEquals("maintenance", pkg.key)
        assertEquals("Maintenance Jobs", pkg.name)
        assertEquals(1, pkg.versions.size)
        assertEquals(MaintenanceJobsInstaller.VERSION, pkg.versions[0].version)
        assertEquals(listOf("maintenance-jobs"), pkg.versions[0].installerNames)
    }

    @Test
    fun `default artifact sync package is enabled for startup installation`() {
        val keys = ApplicationConfig.loadFromClasspath().property("packages").getAs<Set<String>>()
        val pkg = configuration.defaultArtifactSyncPipelinesPackage()

        assertTrue(pkg.key in keys)
        assertEquals(DefaultArtifactSyncPipelinesInstaller.NAME, pkg.key)
        assertEquals(listOf(DefaultArtifactSyncPipelinesInstaller.NAME), pkg.versions.single().installerNames)
        assertEquals(configuration.defaultArtifactSyncPipelinesInstaller(mockk()).version, pkg.versions.single().version)
    }

    @Test
    fun `default email pipeline packages expose their migration versions`() {
        val git = configuration.defaultGitEmailPipelinesPackage()
        val transactional = configuration.defaultTransactionalEmailPipelinesPackage()

        assertEquals(DefaultGitEmailPipelinesInstaller.VERSION, git.versions.single().version)
        assertEquals("1.2.0", git.versions.single().version)
        assertEquals(DefaultTransactionalEmailPipelinesInstaller.VERSION, transactional.versions.single().version)
        assertEquals("1.2.0", transactional.versions.single().version)
    }

    @Test
    fun `default social notification package exposes its installer version`() {
        val social = configuration.defaultSocialNotificationPipelinesPackage()

        assertEquals("default-social-notification-pipelines", social.key)
        assertEquals(DefaultSocialNotificationPipelinesInstaller.VERSION, social.versions.single().version)
        assertEquals(
            listOf(DefaultSocialNotificationPipelinesInstaller.NAME),
            social.versions.single().installerNames,
        )
    }

    @Test
    fun `mlArtifactsPackage has correct structure`() {
        val pkg = configuration.mlArtifactsPackage()

        assertEquals("artifacts-ml", pkg.key)
        assertEquals("ML Model Artifacts Provisioning", pkg.name)
        assertEquals(1, pkg.versions.size)
        assertEquals("1.0.0", pkg.versions[0].version)
        assertEquals(listOf("artifacts-ml"), pkg.versions[0].installerNames)
    }

    @Test
    fun `mlArtifactsInstaller returns non-null instance`() {
        val installer = configuration.mlArtifactsInstaller(mockk(), mockk(), mockk())
        assertEquals("1.0.0", installer.version)
    }

    @Test
    fun `bibleFactory returns non-null instance`() {
        val factory = configuration.bibleFactory()
        assertTrue(factory != null)
    }

    @Test
    fun `jobEnqueueEventChannel returns non-null instance`() {
        val channel = configuration.jobEnqueueEventChannel()
        assertTrue(channel != null)
    }

    @Test
    fun `production registrar includes live sessions as a singleton`() = runTest {
        BoscaApplication(
            ApplicationConfig.load(
                """
                pubsub:
                  type: nats
                """.trimIndent().byteInputStream(),
            ),
        )
        val nats = mockk<NatsConnectionPool>().asProvider()
        val redis = mockk<RedisConnectionPool>().asProvider()
        BoscaProviderRegistrar().register()
        ProviderRegistry.register(NatsConnectionPool::class, nats, true)
        ProviderRegistry.register(RedisConnectionPool::class, redis, true)

        val provider = ProviderRegistry.get(LiveSessionsService::class)

        assertTrue(provider.exists)
        assertSame(provider.get(), provider.get())
    }

    @Test
    fun `core registrar resolves the named migration provider`() = runTest {
        CoreProviderRegistrar().register()

        val provider = ProviderRegistry.get(Migration::class, "core-migrations")

        assertTrue(provider.exists)
        assertIs<CoreMigration>(provider.get())
    }
}
