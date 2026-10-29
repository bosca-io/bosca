package bosca.atlas.server

import bosca.server.config.ApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class AtlasConfigurationTest {
    @Test
    fun `worker process can disable every public surface and select durable queues`() {
        val config = ApplicationConfig.load("""
            atlas:
              api: { enabled: false }
              git: { enabled: false }
              artifacts: { enabled: false }
              ui: { enabled: false }
              runner:
                enabled: true
                concurrency: 3
                queues: git, pipelines
                listeners: false
        """.trimIndent().byteInputStream())
        val roles = AtlasConfiguration.from(config)
        assertFalse(roles.apiEnabled)
        assertFalse(roles.gitEnabled)
        assertFalse(roles.artifactsEnabled)
        assertFalse(roles.uiEnabled)
        assertFalse(roles.runnerListeners)
        assertEquals(listOf("git", "pipelines"), roles.runnerQueues)
        assertEquals(3, roles.runnerConcurrency)
    }

    @Test
    fun `combined deployment is the default`() {
        assertEquals(AtlasConfiguration(), AtlasConfiguration.from(ApplicationConfig.load("{}".byteInputStream())))
    }

    @Test
    fun `invalid process roles and unknown queues are rejected`() {
        assertFailsWith<IllegalArgumentException> { AtlasConfiguration(apiEnabled = false) }
        assertFailsWith<IllegalArgumentException> { AtlasConfiguration(runnerQueues = listOf("content")) }
        assertFailsWith<IllegalArgumentException> { AtlasConfiguration(runnerQueues = listOf("git", "git")) }
        assertFailsWith<IllegalArgumentException> { AtlasConfiguration(runnerConcurrency = 0) }
    }
}
