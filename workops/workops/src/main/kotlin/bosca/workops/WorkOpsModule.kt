package bosca.workops

import bosca.di.provide
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule
import bosca.workops.service.DeployConfigPushListener
import bosca.workops.service.DeployConfigPushValidator
import bosca.workops.service.NotificationDeliveryService
import bosca.workops.service.PipelineEnvironmentSync
import bosca.workops.service.PipelineEnvironmentSyncListener
import bosca.workops.service.PipelineArtifactPublicationListener
import bosca.workops.service.PipelineArtifactPublicationSync
import bosca.workops.service.SpecDocumentSyncListener

class WorkOpsModule : BoscaApplicationModule {

    override suspend fun install(application: BoscaApplication) {
        provide<NotificationDeliveryService>()
        val documents = SpecDocumentSyncListener(
            pubSubService = provide(),
            specRepository = provide(),
            connectionPool = provide(),
        )
        application.onShutdown { documents.close() }
        val environments = PipelineEnvironmentSyncListener(
            pubSubService = provide(),
            sync = PipelineEnvironmentSync(
                environmentService = provide(),
                environmentTypeService = provide(),
                projectRepositories = provide(),
                projectService = provide(),
            ),
            connectionPool = provide(),
        )
        application.onShutdown { environments.close() }
        val publications = PipelineArtifactPublicationListener(
            pubSubService = provide(),
            sync = PipelineArtifactPublicationSync(
                runService = provide(),
                projectRepositories = provide(),
                releaseService = provide(),
                versionService = provide(),
                publicationService = provide(),
            ),
        )
        application.onShutdown { publications.close() }
        val deployConfigs = DeployConfigPushListener(
            pubSubService = provide(),
            validator = DeployConfigPushValidator(
                deployConfigService = provide(),
                repositoryWrite = provide(),
                commitStatusService = provide(),
            ),
        )
        application.onShutdown { deployConfigs.close() }
    }
}
