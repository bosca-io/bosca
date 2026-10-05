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
        SpecDocumentSyncListener(
            pubSubService = provide(),
            specRepository = provide(),
            connectionPool = provide(),
        )
        PipelineEnvironmentSyncListener(
            pubSubService = provide(),
            sync = PipelineEnvironmentSync(
                environmentService = provide(),
                environmentTypeService = provide(),
                projectRepositories = provide(),
                projectService = provide(),
            ),
            connectionPool = provide(),
        )
        PipelineArtifactPublicationListener(
            pubSubService = provide(),
            sync = PipelineArtifactPublicationSync(
                runService = provide(),
                projectRepositories = provide(),
                releaseService = provide(),
                versionService = provide(),
                publicationService = provide(),
            ),
        )
        DeployConfigPushListener(
            pubSubService = provide(),
            validator = DeployConfigPushValidator(
                deployConfigService = provide(),
                repositoryWrite = provide(),
                commitStatusService = provide(),
            ),
        )
    }
}
