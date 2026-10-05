package bosca.workops.configuration

import bosca.artifacts.service.ArtifactRepositoryService
import bosca.artifacts.service.BlobStorageService
import bosca.forms.service.FormSubmissionProcessor
import bosca.git.service.EnvironmentActionAuthorizer
import bosca.git.service.ProducedArtifactVerifier
import bosca.git.service.ReleaseDeployer
import bosca.git.service.RepositoryBrowseService
import bosca.pipelines.service.PipelineSecretService
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.store.pipelines.AppStorePublisher
import bosca.store.pipelines.PlayPublisher
import bosca.workops.deploy.AppStoreDeployTarget
import bosca.workops.deploy.DeployConfigService
import bosca.workops.deploy.GooglePlayDeployTarget
import bosca.workops.migration.WorkOpsMigration
import bosca.workops.service.AppBuildNumberService
import bosca.workops.service.ArtifactPublicationService
import bosca.workops.service.EnvironmentPermissionEvaluator
import bosca.workops.service.EnvironmentService
import bosca.workops.service.GitEnvironmentActionAuthorizer
import bosca.workops.service.GitReleaseDeployer
import bosca.workops.service.PortfolioPermissionEvaluator
import bosca.workops.service.PortfolioService
import bosca.workops.service.ProgramPermissionEvaluator
import bosca.workops.service.ProgramService
import bosca.workops.service.ProjectPermissionEvaluator
import bosca.workops.service.ProjectRepositoryService
import bosca.workops.service.ProjectService
import bosca.workops.service.RegistryProducedArtifactVerifier
import bosca.workops.service.ReleaseNotesService
import bosca.workops.service.ReleaseService
import bosca.workops.service.RequirementPermissionEvaluator
import bosca.workops.service.RequirementService
import bosca.workops.service.SpecPermissionEvaluator
import bosca.workops.service.SpecService
import bosca.workops.service.TaskPermissionEvaluator
import bosca.workops.service.TaskService
import bosca.workops.service.VersionService
import bosca.workops.service.WorkOpsFormSubmissionProcessor
import bosca.workops.service.WorkflowEvaluator
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame

class ConfigurationTest {

    private val configuration = Configuration()

    @Test
    fun `provider factories expose the declared adapters and singleton helpers`() {
        assertIs<WorkOpsMigration>(configuration.migration())
        val artifacts = mockk<ArtifactRepositoryService>()
        assertIs<RegistryProducedArtifactVerifier>(configuration.producedArtifactVerifier(artifacts))
        assertIs<RegistryProducedArtifactVerifier>(configuration.requiredArtifactVerifier(artifacts))
        assertIs<WorkflowEvaluator>(configuration.workflowEvaluator())
        val firstSecret = configuration.hmacSecret()
        val secondSecret = configuration.hmacSecret()
        assertEquals(32, firstSecret.bytes.size)
        assertEquals(32, secondSecret.bytes.size)
        assertNotSame(firstSecret, secondSecret)
    }

    @Test
    fun `deploy provider factories retain the complete dependency graph`() {
        val environmentService = mockk<EnvironmentService>()
        val secrets = mockk<PipelineSecretService>()
        val artifacts = mockk<ArtifactRepositoryService>()
        val blobs = mockk<BlobStorageService>()
        val playPublisher = mockk<PlayPublisher>()
        val appStorePublisher = mockk<AppStorePublisher>()
        val releaseNotes = mockk<ReleaseNotesService>()
        assertIs<GooglePlayDeployTarget>(
            configuration.googlePlayDeployTarget(
                environmentService, secrets, artifacts, blobs, playPublisher, releaseNotes,
            ),
        )
        assertIs<AppStoreDeployTarget>(
            configuration.appStoreDeployTarget(environmentService, secrets, appStorePublisher, releaseNotes),
        )

        val projectRepositories = mockk<ProjectRepositoryService>()
        val projectService = mockk<ProjectService>()
        val versionService = mockk<VersionService>()
        val releaseService = mockk<ReleaseService>()
        val publications = mockk<ArtifactPublicationService>()
        val authorizer = mockk<EnvironmentActionAuthorizer>()
        val programService = mockk<ProgramService>()
        val programPermissions = mockk<ProgramPermissionEvaluator>()
        val security = mockk<SecurityService>()
        val buildNumbers = mockk<AppBuildNumberService>()
        val browse = mockk<RepositoryBrowseService>()
        assertIs<GitReleaseDeployer>(
            configuration.releaseDeployer(
                mockk<DeployConfigService>(),
                projectRepositories,
                projectService,
                versionService,
                releaseService,
                publications,
                environmentService,
                authorizer,
                programService,
                programPermissions,
                security,
                buildNumbers,
                browse,
                secrets,
                playPublisher,
                appStorePublisher,
                releaseNotes,
            ),
        )
        assertIs<GitEnvironmentActionAuthorizer>(
            configuration.environmentActionAuthorizer(
                projectRepositories,
                projectService,
                environmentService,
                mockk<EnvironmentPermissionEvaluator>(),
            ),
        )
    }

    @Test
    fun `permission and form processor providers return their concrete implementations`() {
        val security = mockk<SecurityService>()
        val groups = mockk<GroupEvaluator>()
        val portfolioService = mockk<PortfolioService>()
        val programService = mockk<ProgramService>()
        val projectService = mockk<ProjectService>()
        val taskService = mockk<TaskService>()
        val specService = mockk<SpecService>()
        val environmentService = mockk<EnvironmentService>()
        val requirementService = mockk<RequirementService>()

        assertIs<PortfolioPermissionEvaluator>(
            configuration.portfolioPermissionEvaluator(portfolioService, security, groups),
        )
        assertIs<ProgramPermissionEvaluator>(
            configuration.programPermissionEvaluator(programService, security, groups),
        )
        assertIs<ProjectPermissionEvaluator>(
            configuration.projectPermissionEvaluator(projectService, security, groups),
        )
        assertIs<TaskPermissionEvaluator>(configuration.taskPermissionEvaluator(taskService, security, groups))
        assertIs<SpecPermissionEvaluator>(configuration.specPermissionEvaluator(specService, security, groups))
        assertIs<EnvironmentPermissionEvaluator>(
            configuration.environmentPermissionEvaluator(environmentService, security, groups),
        )
        assertIs<RequirementPermissionEvaluator>(
            configuration.requirementPermissionEvaluator(requirementService, security, groups),
        )
        assertIs<WorkOpsFormSubmissionProcessor>(
            configuration.workOpsFormSubmissionProcessor(taskService, projectService),
        )
        assertIs<FormSubmissionProcessor>(configuration.workOpsFormSubmissionProcessor(taskService, projectService))
    }
}
