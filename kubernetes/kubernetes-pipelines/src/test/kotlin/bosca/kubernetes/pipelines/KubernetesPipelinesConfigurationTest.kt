package bosca.kubernetes.pipelines

import bosca.workops.deploy.DeployTargetKind
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**the Configuration builds a HELM-kinded [HelmDeployTarget] for the DI registry. */
class KubernetesPipelinesConfigurationTest {

    @Test
    fun `provides a HELM deploy target`() {
        val target = KubernetesPipelinesConfiguration().helmDeployTarget(
            controller = mockk(),
            environmentService = mockk(),
            repositoryWrite = mockk(),
            artifactPublications = mockk(),
            json = kotlinx.serialization.json.Json,
        )
        assertIs<HelmDeployTarget>(target)
        assertEquals(DeployTargetKind.HELM, target.kind)
    }

    @Test
    fun `provides a HELM_VALUES deploy target`() {
        val target = KubernetesPipelinesConfiguration().helmValuesDeployTarget(
            controller = mockk(),
            environmentService = mockk(),
            publications = mockk(),
            artifacts = mockk(),
            blobs = mockk(),
            json = kotlinx.serialization.json.Json,
        )
        assertIs<HelmValuesDeployTarget>(target)
        assertEquals(DeployTargetKind.HELM_VALUES, target.kind)
    }
}
