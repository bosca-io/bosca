package bosca.kubernetes.pipelines

import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.git.service.RepositoryWriteService
import bosca.kubernetes.service.KubernetesControllerClient
import bosca.workops.deploy.DeployTarget
import bosca.workops.service.EnvironmentService

/**
 * Wires the kubernetes release-pipeline nodes' runtime dependencies into Bosca's DI container.
 * Named distinctly (not `Configuration`) to avoid an FQN collision with
 * `core-kubernetes`'s `bosca.kubernetes.configuration.Configuration`.
 */
@Providers
class KubernetesPipelinesConfiguration {

    /**
     * The Helm deploy-target adapter, registered under `DeployTargetKind.HELM`'s name so the deploy
     * nodes resolve it via `provide<DeployTarget>(name = kind.name)`. The literal must match
     * `DeployTargetKind.HELM.name` (an enum name isn't a compile-time constant for the annotation).
     */
    /**
     * The values-artifact deploy adapter (`target: helm-values`), registered under
     * `DeployTargetKind.HELM_VALUES`'s name: applies the registry-published values artifact for the
     * target environment via a real helm upgrade — no git writes.
     */
    @Provider(name = "HELM_VALUES")
    fun helmValuesDeployTarget(
        controller: KubernetesControllerClient,
        environmentService: EnvironmentService,
        publications: bosca.workops.service.ArtifactPublicationService,
        artifacts: bosca.artifacts.service.ArtifactRepositoryService,
        blobs: bosca.artifacts.service.BlobStorageService,
        json: kotlinx.serialization.json.Json,
    ): DeployTarget = HelmValuesDeployTarget(controller, environmentService, publications, artifacts, blobs, json)

    @Provider(name = "HELM")
    fun helmDeployTarget(
        controller: KubernetesControllerClient,
        environmentService: EnvironmentService,
        repositoryWrite: RepositoryWriteService,
        artifactPublications: bosca.workops.service.ArtifactPublicationService,
        json: kotlinx.serialization.json.Json,
    ): DeployTarget = HelmDeployTarget(controller, environmentService, repositoryWrite, artifactPublications, json)
}
