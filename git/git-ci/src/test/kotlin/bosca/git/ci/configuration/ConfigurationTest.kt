package bosca.git.ci.configuration

import bosca.di.asProvider
import bosca.git.ci.service.KubernetesCiDispatcher
import bosca.git.service.PipelineAgentService
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineRunService
import bosca.kubernetes.service.KubernetesJobDispatchService
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConfigurationTest {

    private val configuration = Configuration()

    @Test
    fun `constructs the pipeline parser`() {
        assertIs<bosca.git.ci.parser.PipelineYamlParser>(configuration.pipelineYamlParser())
    }

    @Test
    fun `loads and normalizes Kubernetes CI profile configuration`() {
        val application = mockk<BoscaApplication>()
        every { application.environment.config } returns ApplicationConfig.load(
            """
            git:
              ci:
                kubernetesJobProfiles: android, gpu
            """.trimIndent().byteInputStream(),
        )

        assertEquals(
            setOf("android", "gpu"),
            configuration.kubernetesCiDispatchConfiguration(application).profiles,
        )
    }

    @Test
    fun `missing Kubernetes CI configuration preserves polling agents`() {
        val application = mockk<BoscaApplication>()
        every { application.environment.config } returns ApplicationConfig.load(
            "{}".byteInputStream(),
        )

        assertEquals(
            emptySet(),
            configuration.kubernetesCiDispatchConfiguration(application).profiles,
        )
    }

    @Test
    fun `constructs Kubernetes CI dispatcher with service boundaries`() {
        assertIs<KubernetesCiDispatcher>(
            configuration.kubernetesCiDispatcher(
                mockk<PipelineAgentService>(),
                mockk<PipelineJobService>(),
                mockk<PipelineRunService>(),
                mockk<KubernetesJobDispatchService>().asProvider(),
                KubernetesCiDispatchConfiguration(setOf("android")),
            ),
        )
    }
}
