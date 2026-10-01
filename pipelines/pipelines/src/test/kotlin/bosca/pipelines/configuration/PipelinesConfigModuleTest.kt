@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.pipelines.configuration

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.pipelines.git.PipelineGitSyncService
import bosca.pubsub.PubSubService
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the pipelines DI/config wiring not exercised elsewhere: the
 * [PipelinesRuntimeConfiguration] synthetic default-args constructor (partial-subset construction),
 * the [PipelinesConfiguration.pipelinesRuntimeConfiguration] config-present-vs-absent arms, and
 * [PipelinesModule.install]'s git-sync-listener enabled-vs-disabled branch (including the DI-resolved
 * listener construction). Construction goes through the real types — no provider reflection.
 */
class PipelinesConfigModuleTest {

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    private fun appWithYaml(yaml: String): BoscaApplication =
        BoscaApplication(ApplicationConfig.load(yaml.byteInputStream()))

    // --- PipelinesRuntimeConfiguration: partial-subset construction (default-args mask branches) ---

    @Test
    fun `runtime configuration constructed with only the service account keeps the rest at defaults`() {
        // Only the first optional arg supplied -> the default-args mask covers the remaining four.
        val config = PipelinesRuntimeConfiguration(serviceAccount = "svc")
        assertEquals("svc", config.serviceAccount)
        assertEquals(1440, config.suspendedRunMaxLifetimeMinutes)
        assertEquals(5000, config.onDemandRunMaxBlockMillis)
        assertEquals(30, config.runStateRetentionDays)
        assertEquals(90, config.runHistoryRetentionDays)
    }

    @Test
    fun `runtime configuration constructed with only a middle field defaults the others`() {
        // A different single optional arg supplied (a non-leading position) -> a distinct mask branch.
        val config = PipelinesRuntimeConfiguration(onDemandRunMaxBlockMillis = 99)
        assertEquals("sa", config.serviceAccount)
        assertEquals(1440, config.suspendedRunMaxLifetimeMinutes)
        assertEquals(99, config.onDemandRunMaxBlockMillis)
        assertEquals(30, config.runStateRetentionDays)
        assertEquals(90, config.runHistoryRetentionDays)
    }

    @Test
    fun `runtime configuration constructed with only the trailing retention fields defaults the rest`() {
        // Trailing optional args supplied, leading ones defaulted -> yet another mask branch.
        val config = PipelinesRuntimeConfiguration(
            runStateRetentionDays = 1,
            runHistoryRetentionDays = 2,
        )
        assertEquals("sa", config.serviceAccount)
        assertEquals(1440, config.suspendedRunMaxLifetimeMinutes)
        assertEquals(5000, config.onDemandRunMaxBlockMillis)
        assertEquals(1, config.runStateRetentionDays)
        assertEquals(2, config.runHistoryRetentionDays)
    }

    // --- PipelinesConfiguration.pipelinesRuntimeConfiguration: present vs absent ---

    @Test
    fun `provider reads the pipelines config block when present`() = runTest {
        val app = appWithYaml(
            """
            pipelines:
              serviceAccount: configured
              suspendedRunMaxLifetimeMinutes: 7
              onDemandRunMaxBlockMillis: 8
              runStateRetentionDays: 9
              runHistoryRetentionDays: 11
            """.trimIndent(),
        )
        val config = PipelinesConfiguration().pipelinesRuntimeConfiguration(app)
        assertEquals("configured", config.serviceAccount)
        assertEquals(7, config.suspendedRunMaxLifetimeMinutes)
        assertEquals(8, config.onDemandRunMaxBlockMillis)
        assertEquals(9, config.runStateRetentionDays)
        assertEquals(11, config.runHistoryRetentionDays)
    }

    @Test
    fun `provider falls back to defaults when the pipelines block is absent`() = runTest {
        // The `propertyOrNull("pipelines")?.getAs() ?: PipelinesRuntimeConfiguration()` null arm.
        val app = appWithYaml("server:\n  port: 8080")
        val config = PipelinesConfiguration().pipelinesRuntimeConfiguration(app)
        assertEquals(PipelinesRuntimeConfiguration(), config)
    }

    // --- PipelinesModule.install: git-sync listener enabled vs disabled ---

    /** Registers the two providers `install`'s enabled arm resolves via `provide()`. */
    private fun registerListenerDI() {
        provides<PubSubService> { mockk(relaxed = true) }
        provides<PipelineGitSyncService> { mockk(relaxed = true) }
    }

    @Test
    fun `install starts the push listener when git sync listeners are enabled`() = runTest {
        registerListenerDI()
        // git.sync.listeners absent -> gitSyncListenersEnabled() defaults to true -> the if-true arm
        // resolves both providers and constructs PipelineGitPushListener.
        val app = appWithYaml("server:\n  port: 8080")

        // The listener subscribes on its own scope; install returns without throwing.
        PipelinesModule().install(app)

        assertTrue(app.environment.config.propertyOrNull("git.sync.listeners") == null)
    }

    @Test
    fun `install starts the push listener when git sync listeners are explicitly true`() = runTest {
        registerListenerDI()
        val app = appWithYaml(
            """
            git:
              sync:
                listeners: "true"
            """.trimIndent(),
        )

        PipelinesModule().install(app)

        assertEquals(
            "true",
            app.environment.config.propertyOrNull("git.sync.listeners")?.getString(),
        )
    }

    @Test
    fun `install does nothing when git sync listeners are disabled`() = runTest {
        // No providers registered: if the if-false arm did NOT short-circuit, provide() would throw.
        // The disabled arm must skip the listener construction entirely.
        val app = appWithYaml(
            """
            git:
              sync:
                listeners: "false"
            """.trimIndent(),
        )

        PipelinesModule().install(app)

        assertEquals(
            "false",
            app.environment.config.propertyOrNull("git.sync.listeners")?.getString(),
        )
    }
}
