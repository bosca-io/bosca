package bosca.git.ci.configuration

import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.git.ci.parser.PipelineYamlParser
import bosca.git.ci.service.KubernetesCiDispatcher
import bosca.git.ci.service.PipelineRequirementChecker
import bosca.git.service.PipelineAgentService
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineRunService
import bosca.git.service.RepositoryService
import bosca.kubernetes.service.KubernetesJobDispatchService
import bosca.server.BoscaApplication
import kotlinx.serialization.json.Json

@Providers
class Configuration {

    @Provider
    fun pipelineYamlParser(): PipelineYamlParser = PipelineYamlParser()

    /**
     * Runner labels explicitly routed through Kubernetes. The environment-friendly scalar form is
     * comma-separated; YAML lists are accepted as well.
     */
    @Provider(singleton = true)
    fun kubernetesCiDispatchConfiguration(
        application: BoscaApplication,
    ): KubernetesCiDispatchConfiguration {
        val profiles = application.environment.config
            .propertyOrNull("git.ci.kubernetesJobProfiles")
            ?.getList()
            .orEmpty()
            .flatMap { it.split(',') }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        return KubernetesCiDispatchConfiguration(profiles)
    }

    @Provider(singleton = true)
    fun kubernetesCiDispatcher(
        agentService: PipelineAgentService,
        jobService: PipelineJobService,
        runService: PipelineRunService,
        dispatchService: ObjectProvider<KubernetesJobDispatchService>,
        configuration: KubernetesCiDispatchConfiguration,
    ): KubernetesCiDispatcher =
        KubernetesCiDispatcher(
            agentService,
            jobService,
            runService,
            dispatchService,
            configuration,
        )

    /** The requirement dispatch gate (artifacts + pipelines) — shared by the
     *  immediate post-creation check, both event listeners, and the scheduled sweep. */
    @Provider(singleton = true)
    fun pipelineRequirementChecker(
        jobService: PipelineJobService,
        runService: PipelineRunService,
        agentService: PipelineAgentService,
        pipelineService: bosca.git.service.PipelineService,
        repositoryService: RepositoryService,
        repositoryBrowseService: bosca.git.service.RepositoryBrowseService,
        json: Json,
    ): PipelineRequirementChecker =
        PipelineRequirementChecker(
            jobService, runService, agentService, pipelineService, repositoryService,
            repositoryBrowseService, json,
        )

    /** Standard entity-permission evaluator for pipeline secrets — resolution-time
     *  checks evaluate the run's initiating principal against it. */
    @Provider(singleton = true)
    fun pipelineSecretPermissionEvaluator(
        service: bosca.git.service.PipelineSecretService,
        securityService: bosca.security.service.SecurityService,
        groupEvaluator: bosca.security.service.GroupEvaluator,
    ): bosca.git.ci.security.PipelineSecretPermissionEvaluator =
        bosca.git.ci.security.PipelineSecretPermissionEvaluator(service, securityService, groupEvaluator)
}
